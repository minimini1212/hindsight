package io.hindsight.recorder;

import io.hindsight.model.Event;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 경계에서 오간 값을 들고 있는 링 버퍼. 계속 덮어쓰다가 사고 때만 꺼낸다.
 *
 * <h2>흐름</h2>
 * <pre>
 *   [앱의 요청 스레드]            [우리 스레드 하나]
 *
 *   record(event)
 *      │
 *      │ offer()  ← 🔴 절대 기다리지 않는다
 *      ▼
 *   ┌──────────────┐   가득 차면 그 자리에서 버리고 «센다»
 *   │  받는 큐      │   (droppedEvents++) — 앱을 세우느니 기록을 잃는다
 *   │  4,096칸      │
 *   └──────┬───────┘
 *          │ take()
 *          ▼
 *   ┌───────────────────────────────────────────┐
 *   │  링                                        │
 *   │  ← 오래된 것                  새 것 →      │
 *   │  [ ][ ][ ][ ][ ][ ][ ][ ][ ][ ][ ][ ][ ]  │
 *   └───────────────────────────────────────────┘
 *      앞에서 밀어낸다: 시간이 넘거나(기본 60초)
 *                      바이트가 넘으면(기본 32MB)
 *                      🔴 «둘 중 먼저 오는 쪽»이 이긴다
 *      밀어낸 건수와 바이트를 «센다» (evictedEvents · evictedBytes)
 * </pre>
 *
 * <h2>🔴 왜 앱의 스레드에서 링을 직접 안 건드리나</h2>
 * 링을 건드리려면 잠금이 필요하다. 요청 스레드가 그 잠금을 기다리면, 우리 도구가
 * <b>앱의 응답 시간을 늘린다.</b> 관측이 관측 대상의 동작을 바꾸는 것이고, 이 프로젝트가
 * 절대 하면 안 되는 일이다. 그래서 요청 스레드는 큐에 <b>넣기만</b> 하고 바로 돌아간다.
 *
 * <h2>🔴 큐가 차면 «버리고 센다». 기다리지 않는다</h2>
 * 장애가 나면 이벤트가 초당 수천 건 쏟아진다. 그때 {@code put()} 으로 기다리면
 * 요청 스레드가 전부 우리 큐 앞에 줄을 선다 — <b>기록기가 장애의 원인이 된다.</b>
 * 버린 건수는 {@code droppedEvents} 로 파일에 적힌다. 🔴 「못 받았다」를 안 적으면
 * 읽는 사람은 그 일이 «일어나지 않았다»고 읽는다.
 *
 * <h2>🔴 밀려난 것(evicted)과 못 받은 것(dropped)은 다른 사실이다</h2>
 * 앞은 「받았다가 오래돼서 밀어냈다」, 뒤는 「받지도 못했다」. 합쳐서 하나로 적지 않는다.
 */
public final class EventBuffer {

    /**
     * 받는 큐의 칸 수.
     *
     * <p>4,096 인 이유: 설계가 잡은 최악의 유입은 초당 200요청이고 요청당 이벤트가
     * 스무 개쯤이니 초당 4,000건이다. 즉 이 큐는 <b>대략 1초치 여유</b>다.
     * 이보다 크게 잡으면 장애 때 큐 자체가 메모리를 먹고, 작게 잡으면 평상시에도 버린다.
     */
    static final int INTAKE_SLOTS = 4096;

    private final RecorderConfig config;

    private final BlockingQueue<Slot> intake = new ArrayBlockingQueue<>(INTAKE_SLOTS);
    private final Deque<Slot> ring = new ArrayDeque<>();
    private final Object ringLock = new Object();

    private final AtomicLong droppedEvents = new AtomicLong();
    private final AtomicLong evictedEvents = new AtomicLong();
    private final AtomicLong evictedBytes = new AtomicLong();

    /**
     * 큐가 받아 준 개수와 링까지 들어간 개수.
     *
     * <p>🔴 <b>옮기는 일은 오직 «한» 스레드만 한다.</b> 사고 순간에 급하다고 요청 스레드가
     * 같이 옮기게 만들면, 두 스레드가 큐에서 번갈아 꺼내면서 <b>링에 들어가는 순서가 섞인다</b> —
     * {@code select 1 · select 3 · select 2} 처럼. 재생은 이벤트를 «순서대로» 흘려 넣는 것이라
     * 순서가 섞이면 재생 자체가 다른 일이 된다.
     *
     * <p>⚠️ 처음엔 그렇게 만들었다가 이 셈으로 바꿨다. 순서가 섞이는 것은 «가끔»만 일어나서
     * 스무 번 중 열아홉 번은 통과했다 — 검사에 걸린 것이 운이 좋았다.
     */
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong appended = new AtomicLong();
    private long ringBytes; // ringLock 이 지킨다

    private final Thread drainer;
    private volatile boolean running = true;

    public EventBuffer(RecorderConfig config) {
        this(config, true);
    }

    /**
     * 옮기는 스레드를 안 띄우고 만든다. <b>테스트 전용이고, 이유가 있다.</b>
     *
     * <p>🔴 「큐가 차면 버리고 센다」를 시험하려면 큐가 실제로 차야 하는데, 옮기는 스레드가
     * 돌고 있으면 <b>얼마나 밀어 넣어야 차는지가 그날 기계 상태에 달린다.</b> 그렇게 쓴
     * 테스트는 어떤 날은 통과하고 어떤 날은 실패한다. 그리고 가끔 실패하는 테스트는
     * 사람이 「또 그거네」 하고 넘기게 만들어서, <b>진짜 고장도 같이 넘어간다.</b>
     * 없는 것보다 나쁘다.
     *
     * <p>그래서 그 한 가지 성질만 시험할 때는 스레드를 안 띄우고, 큐가 차는 것을
     * 시간이 아니라 <b>칸 수로</b> 확정한다.
     */
    EventBuffer(RecorderConfig config, boolean startDrainer) {
        this.config = config;
        this.drainer = new Thread(this::drainLoop, "hindsight-recorder-drain");
        // 🔴 데몬이다. 앱이 끝나려는데 우리 스레드가 붙잡고 있으면 안 된다 —
        //    관측 도구가 앱의 종료를 막는 것도 「앱을 망가뜨리는」 축에 든다.
        this.drainer.setDaemon(true);
        if (startDrainer) {
            this.drainer.start();
        }
    }

    /**
     * 이벤트 하나를 넣는다. <b>앱의 스레드가 부르는 자리다 — 절대 기다리지 않는다.</b>
     *
     * @return 받았으면 {@code true}, 큐가 차서 버렸으면 {@code false}
     */
    public boolean record(Event event) {
        Slot slot = new Slot(event, estimateBytes(event), System.nanoTime());
        if (intake.offer(slot)) {
            accepted.incrementAndGet();
            return true;
        }
        droppedEvents.incrementAndGet();
        return false;
    }

    /**
     * 큐에 남은 것을 링까지 밀어 넣고 기다린다. 사고가 나서 «꺼내기 직전»에 부른다.
     *
     * <p>🔴 이게 없으면 <b>사고를 일으킨 바로 그 이벤트들이 기록에서 빠진다.</b>
     * 예외가 터진 순간의 SQL 은 방금 큐에 들어갔을 가능성이 가장 높은데, 그게 아직
     * 링에 없으면 기록에 안 담긴다 — 가장 필요한 것이 없는 기록이 된다.
     *
     * @return 제한 시간 안에 큐가 비었으면 {@code true}
     */
    public boolean awaitDrained(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (fullyDrained()) {
                return true;
            }
            Thread.onSpinWait();
        }
        return fullyDrained();
    }

    /**
     * 받은 것이 전부 링까지 갔나.
     *
     * <h2>🔴 「큐가 비었나」로 물으면 틀린다</h2>
     * 옮기는 스레드가 큐에서 꺼낸 «직후», 아직 링에 넣기 «전»인 순간이 있다. 그때
     * {@code intake.isEmpty()} 는 참인데 그 이벤트는 아직 링에 없다. 그 상태로 기록을 뜨면
     * <b>마지막 이벤트가 빠진다</b> — 그리고 마지막 이벤트가 바로 사고를 일으킨 그것이다.
     *
     * <p>그래서 큐를 보지 않고 «받은 개수»와 «넣은 개수»를 맞춰 본다.
     */
    private boolean fullyDrained() {
        return appended.get() >= accepted.get();
    }

    /** 지금 링에 담긴 이벤트를 순서대로. 꺼내도 링은 그대로 둔다. */
    public List<Event> snapshot() {
        synchronized (ringLock) {
            evictExpired(System.nanoTime());
            List<Event> events = new ArrayList<>(ring.size());
            for (Slot slot : ring) {
                events.add(slot.event());
            }
            return List.copyOf(events);
        }
    }

    /**
     * 링에 «실제로» 담긴 시간. 🔴 설정값(담으려던 시간)과 거의 항상 다르다.
     *
     * <p>비어 있으면 0. 이건 「0초치가 담겼다」는 사실이고 「모른다」가 아니다.
     */
    public double actualWindowSeconds() {
        synchronized (ringLock) {
            if (ring.size() < 2) {
                return 0.0;
            }
            long span = ring.peekLast().atNanos() - ring.peekFirst().atNanos();
            return span / 1_000_000_000.0;
        }
    }

    public long droppedEvents() { return droppedEvents.get(); }
    public long evictedEvents() { return evictedEvents.get(); }
    public long evictedBytes() { return evictedBytes.get(); }

    public long bufferBytes() {
        synchronized (ringLock) {
            return ringBytes;
        }
    }

    /** 지금 링에 든 이벤트 수. 「어림한 바이트」와 짝지어 봐야 어림이 맞는지 알 수 있다. */
    public int bufferedEventCount() {
        synchronized (ringLock) {
            return ring.size();
        }
    }

    /** 테스트와 앱 종료용. 도는 중에는 부르지 않는다. */
    public void close() {
        running = false;
        drainer.interrupt();
    }

    // ── 안쪽 ────────────────────────────────────────────────────────────────

    private void drainLoop() {
        while (running) {
            try {
                Slot slot = intake.poll(200, TimeUnit.MILLISECONDS);
                if (slot != null) {
                    append(slot);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // 🔴 우리 스레드가 죽으면 그 뒤로 기록이 통째로 안 쌓이는데 «아무 오류도 안 난다».
                //    조용히 멈추는 것이 가장 나쁜 고장이라, 무엇이 와도 돌던 자리를 지킨다.
                //    셈은 Recorder 쪽 agentErrors 가 맡는다.
            }
        }
    }

    /** 🔴 옮기는 스레드 «하나»만 부른다. 부르는 곳이 둘이 되면 순서가 섞인다. */
    private void append(Slot slot) {
        synchronized (ringLock) {
            ring.addLast(slot);
            ringBytes += slot.bytes();
            evictExpired(slot.atNanos());
            evictOverflow();
        }
        appended.incrementAndGet();
    }

    /** 시간 상한. 담으려던 시간보다 오래된 것을 앞에서 밀어낸다. */
    private void evictExpired(long nowNanos) {
        long windowNanos = config.windowSeconds() * 1_000_000_000L;
        while (!ring.isEmpty() && nowNanos - ring.peekFirst().atNanos() > windowNanos) {
            evictOldest();
        }
    }

    /** 바이트 상한. 🔴 시간보다 이쪽이 먼저 걸리는 경우가 훨씬 흔하다. */
    private void evictOverflow() {
        while (ringBytes > config.bufferMaxBytes() && !ring.isEmpty()) {
            evictOldest();
        }
    }

    private void evictOldest() {
        Slot gone = ring.pollFirst();
        if (gone == null) {
            return;
        }
        ringBytes -= gone.bytes();
        evictedEvents.incrementAndGet();
        evictedBytes.addAndGet(gone.bytes());
    }

    /**
     * 이벤트 하나가 차지하는 바이트를 «어림»한다.
     *
     * <p>🔴 정확히 재지 않는 이유: 정확히 재려면 직렬화해 봐야 하는데, 그건 링에 넣을 때마다
     * JSON 을 한 번씩 만드는 것이라 기록기가 앱보다 비싸진다. 상한은 「메모리를 얼마나
     * 쓰는가」를 막으려는 것이므로 자릿수만 맞으면 된다.
     *
     * <p>문자 하나를 2바이트로 보고, 레코드 자체의 부담을 256바이트로 얹는다.
     */
    static long estimateBytes(Event event) {
        long base = 256;
        return switch (event) {
            case Event.HttpIn e -> base
                    + text(e.path()) + text(e.body()) + text(e.responseBody())
                    + mapText(e.headers()) + mapText(e.query());
            case Event.HttpOut e -> base
                    + text(e.url()) + text(e.requestBody()) + text(e.responseBody())
                    + mapText(e.requestHeaders()) + mapText(e.responseHeaders());
            case Event.Sql e -> base
                    + text(e.sql())
                    + (e.params() == null ? 0 : e.params().size() * 32L)
                    + (e.rows() == null ? 0 : e.rows().size() * 128L);
            case Event.Clock e -> base + text(e.value());
            case Event.Rand e -> base + text(e.value());
        };
    }

    private static long text(String s) {
        return s == null ? 0 : s.length() * 2L;
    }

    private static long mapText(java.util.Map<String, String> map) {
        if (map == null) {
            return 0;
        }
        long total = 0;
        for (var entry : map.entrySet()) {
            total += text(entry.getKey()) + text(entry.getValue());
        }
        return total;
    }

    /**
     * 링에 들어가는 한 칸.
     *
     * <p>{@code atNanos} 가 {@link Event#at()} 이 아니라 {@link System#nanoTime()} 인 이유:
     * 벽시계는 <b>뒤로 갈 수 있다</b>(NTP 보정, 서머타임). 뒤로 간 시계로 「오래된 것」을
     * 판단하면 방금 넣은 것이 밀려나거나, 오래된 것이 영영 안 밀려난다.
     */
    record Slot(Event event, long bytes, long atNanos) {}
}
