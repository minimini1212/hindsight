package io.hindsight.recorder;

import io.hindsight.core.store.RecordingStore;
import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.Recording;
import io.hindsight.model.Trigger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * v0 기록기의 중심. 이벤트를 받아 링에 넣고, 사고가 나면 파일로 떨군다.
 *
 * <h2>🔴 여기서 나간 예외는 앱으로 새면 안 된다</h2>
 * 이 코드는 관측 대상 앱과 <b>같은 JVM, 같은 요청 스레드</b>에서 돈다. 여기서 예외가
 * 새어 나가면 그 요청이 500 으로 끝난다 — <b>지켜보려던 앱을 우리가 망가뜨리는 것</b>이고,
 * 그건 기록기가 없는 것보다 나쁘다. 그래서 바깥에서 부르는 메서드는 전부
 * {@code try { … } catch (Throwable) { … }} 로 감싸고 <b>삼키면서 센다</b>.
 *
 * <h2>🔴 계속 실패하면 스스로 끈다</h2>
 * 삼키기만 하면 고장 난 기록기가 영원히 헛돌면서 앱만 느리게 만든다. 연속 실패가
 * 상한(기본 50)에 닿으면 계측을 <b>자기 손으로 끄고</b>, 껐다는 사실을 기록에 적는다.
 * 🔴 「꺼졌다」를 적지 않으면 그 뒤의 빈 기록이 「아무 일도 없었다」로 읽힌다.
 *
 * <h2>흐름</h2>
 * <pre>
 *   요청 들어옴 ─ Correlation.begin()
 *      │
 *      ├─ 앱이 SQL 을 낸다 ── recordSql() ──┐
 *      │                                     ├─▶ EventBuffer (전문 · 짧게)
 *      └─ 요청 끝 ─ recordHttp() ────────────┘   SummaryWindow (모양 · 길게)
 *              │
 *              └─ 예외가 났나? 너무 느렸나?
 *                    └─ 예 ─▶ capture()
 *                               ├─ 큐를 마저 비운다 (안 하면 «사고 순간»이 빠진다)
 *                               ├─ 링을 그대로 뜬다
 *                               ├─ 「담으려던 시간」과 「담긴 시간」을 «둘 다» 적는다
 *                               └─ RecordingStore.write() ← 가명화가 그 안에 묶여 있다
 * </pre>
 */
public final class Recorder {

    /** 기록기 자신의 판 번호. 기록 파일의 {@code app.agentVersion} 에 적힌다. */
    public static final String VERSION = "0.1.0-recorder-simple";

    private final RecorderConfig config;
    private final EventBuffer buffer;
    private final SummaryWindow summary;
    private final CaptureLimiter limiter;
    private final RecordingStore store;

    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong consecutiveErrors = new AtomicLong();
    private final AtomicBoolean disabled = new AtomicBoolean(false);

    private final String hostname;

    public Recorder(RecorderConfig config) {
        this(config, new RecordingStore(config.storeDir(), config.storeMaxBytes(), config.pseudonymKey()));
    }

    Recorder(RecorderConfig config, RecordingStore store) {
        this.config = config;
        this.buffer = new EventBuffer(config);
        this.summary = new SummaryWindow(config);
        this.limiter = new CaptureLimiter(config);
        this.store = store;
        this.hostname = resolveHostname();
    }

    public RecorderConfig config() {
        return config;
    }

    /** 계측이 스스로 꺼졌나. 꺼졌으면 기록이 비는데 «이유가 있다». */
    public boolean disabled() {
        return disabled.get();
    }

    public long errorCount() {
        return errors.get();
    }

    // ── 이벤트 받기 ─────────────────────────────────────────────────────────

    /**
     * SQL 한 건. {@code DataSource} 껍데기가 부른다.
     *
     * <p>🔴 무엇이 와도 예외를 밖으로 내보내지 않는다. 여기서 던지면 앱의 DB 호출이 깨진다.
     */
    public void recordSql(String sql, List<Object> params, Integer rowCount, long tookMs) {
        guarded(() -> {
            long now = System.nanoTime();
            buffer.record(new Event.Sql(
                    seq.incrementAndGet(), Correlation.current(), Instant.now(),
                    Thread.currentThread().getName(), tookMs,
                    sql, params,
                    rowCount,
                    // 🔴 결과 «행»은 v0 에서 안 잡는다. null 이 그 사실이다.
                    //    [] 로 적으면 「질의했는데 아무것도 안 나왔다」가 되어,
                    //    재생이 빈 결과를 정답으로 삼는다.
                    null, false,
                    null, null));
            summary.addSql(sql, tookMs, now);
        });
    }

    /** 들어온 요청 하나와 그 응답. {@code Filter} 가 부른다. */
    public void recordHttp(Event.HttpIn event) {
        guarded(() -> {
            long now = System.nanoTime();
            buffer.record(event);
            summary.addRequest(event.at(), event.method(), event.path(),
                    event.responseStatus(), event.durationMs(), now);
        });
    }

    public long nextSeq() {
        return seq.incrementAndGet();
    }

    // ── 사고 ────────────────────────────────────────────────────────────────

    /**
     * 사고가 났다. 파일을 만들지 말지는 {@link CaptureLimiter} 가 정한다.
     *
     * @return 파일을 만들었으면 그 경로. 막혔으면 비어 있다
     */
    public Optional<Path> capture(Trigger.Kind kind, String entryPoint,
                                  Trigger.ExceptionInfo exception, Long latencyMs,
                                  String stackSignature) {
        if (disabled.get()) {
            return Optional.empty();
        }
        try {
            String dedupKey = Trigger.dedupKeyOf(entryPoint, kind, stackSignature);
            CaptureLimiter.Verdict verdict = limiter.admit(dedupKey, System.nanoTime());
            if (!verdict.capture()) {
                return Optional.empty();
            }

            // 🔴 큐를 먼저 비운다. 사고를 «일으킨» 이벤트가 아직 큐에 있을 가능성이
            //    가장 높은데, 그게 링에 없으면 가장 필요한 것이 빠진 기록이 된다.
            //    200ms 는 넉넉하다 — 4,096칸을 옮기는 데 그만큼 걸리지 않는다.
            boolean drained = buffer.awaitDrained(Duration.ofMillis(200));

            Instant now = Instant.now();
            Recording recording = new Recording(
                    Recording.CURRENT_SCHEMA_VERSION,
                    newId(),
                    now,
                    new Trigger(kind, now, entryPoint, exception, latencyMs, dedupKey, verdict.dedupCount()),
                    appInfo(),
                    buffer.snapshot(),
                    summary.snapshot(System.nanoTime()),
                    // 🔴 아래 둘은 null 이다 — v0 은 JFR 을 안 읽고, 재생도 아직 안 했다.
                    //    「안 봤다」를 빈 객체로 적으면 「보았고 없었다」가 된다.
                    null,
                    null,
                    integrity(drained));

            return Optional.of(store.write(recording));
        } catch (Throwable t) {
            // 사고를 기록하려다 실패한 것이다. 앱은 이미 아픈 상태이므로 더 건드리지 않는다.
            noteError();
            return Optional.empty();
        }
    }

    /** 이 사고가 지금까지 몇 번 났나. 막힌 사고도 센다. */
    public int timesSeen(Trigger.Kind kind, String entryPoint, String stackSignature) {
        return limiter.countFor(Trigger.dedupKeyOf(entryPoint, kind, stackSignature));
    }

    // ── 온전함 ──────────────────────────────────────────────────────────────

    /**
     * 이 기록이 얼마나 온전한가. 🔴 <b>담으려던 시간과 담긴 시간을 둘 다 적는다.</b>
     *
     * <p>설정에 60초라고 써 있다고 60초가 담기지 않는다. 트래픽이 많으면 바이트 상한이
     * 먼저 걸려 실제로는 4초치만 남는다. 이때 파일에 「60초」만 적혀 있으면, 읽는 사람은
     * 30초 전에 시작된 커넥션 누수가 <b>일어나지 않았다고</b> 결론 낸다.
     */
    private Integrity integrity(boolean drained) {
        long dropped = buffer.droppedEvents();
        if (!drained) {
            // 큐를 다 못 비웠다. 그 안의 것은 이 기록에 «안 들어갔다» — 못 받은 것과
            // 같은 종류의 사실이므로 같이 센다. 조용히 넘기면 기록이 완전해 보인다.
            dropped += 1;
        }
        return new Integrity(
                config.windowSeconds(),
                buffer.actualWindowSeconds(),
                dropped,
                buffer.evictedEvents(),
                buffer.evictedBytes(),
                buffer.bufferBytes(),
                errors.get(),
                disabled.get());
    }

    private AppInfo appInfo() {
        return new AppInfo(
                config.appName(),
                // 🔴 git 커밋은 v0 이 «안 알아본다». null 이 그 사실이다.
                //    빈 문자열로 적으면 「알아봤는데 없었다」가 된다.
                null,
                null,
                System.getProperty("java.version"),
                VERSION,
                hostname);
    }

    // ── 안전장치 ────────────────────────────────────────────────────────────

    /**
     * 기록기 코드를 도는 유일한 통로. 예외를 삼키고, 재진입을 막고, 실패를 센다.
     *
     * <p>🔴 이 셋이 한 자리에 있는 이유: 부르는 쪽마다 기억하게 만들면 언젠가 한 자리가
     * 빠지고, 그 자리가 앱을 죽인다.
     */
    private void guarded(Runnable work) {
        if (disabled.get() || ReentryGuard.inside()) {
            return;
        }
        try {
            ReentryGuard.run(work);
            consecutiveErrors.set(0);
        } catch (Throwable t) {
            noteError();
        }
    }

    private void noteError() {
        errors.incrementAndGet();
        if (consecutiveErrors.incrementAndGet() >= config.recorderErrorLimit() && disabled.compareAndSet(false, true)) {
            // 🔴 끄는 것이 죽는 것보다 낫다. 그리고 껐다는 사실은 기록에 남는다
            //    (integrity.instrumentationDisabled). 조용히 꺼지면 그 뒤의 빈 기록이
            //    「아무 일도 없었다」로 읽힌다.
            buffer.close();
        }
    }

    /** 앱 종료·테스트용. */
    public void close() {
        buffer.close();
    }

    // ── 재는 데만 쓰는 구멍 ─────────────────────────────────────────────────
    //
    // 🔴 아래 넷은 «패키지 안에서만» 보인다. 밖으로 열지 않는 이유는, 버퍼의 속사정이
    //    공개 API 가 되면 나중에 버퍼를 바꿀 때 쓰는 쪽이 같이 깨지기 때문이다.
    //    그렇다고 안 열면 「기록기를 붙이면 얼마나 드나」를 잴 방법이 없고,
    //    잴 수 없는 것은 결국 «안 재게 된다».

    boolean awaitDrainedForTest(java.time.Duration timeout) {
        return buffer.awaitDrained(timeout);
    }

    long bufferBytesForTest() {
        return buffer.bufferBytes();
    }

    int bufferedEventCountForTest() {
        return buffer.bufferedEventCount();
    }

    long droppedEventsForTest() {
        return buffer.droppedEvents();
    }

    private static String newId() {
        return Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFL);
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // 🔴 못 알아낸 것이다. "unknown" 같은 그럴듯한 문자열로 채우지 않는다.
            return null;
        }
    }
}
