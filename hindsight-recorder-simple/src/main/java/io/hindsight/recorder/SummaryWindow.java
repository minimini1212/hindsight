package io.hindsight.recorder;

import io.hindsight.model.Summary;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「30초 전에 시작된 일」이 보이는 층. 본문도 결과 행도 없이 <b>모양과 횟수만</b> 담는다.
 *
 * <h2>🔴 왜 층이 둘인가</h2>
 * 「최근 60초를 다 들고 있다」는 산수가 안 맞는다. 초당 200요청 × 60초 = 12,000건이고,
 * 요청당 본문과 SQL 을 합쳐 7KB 만 잡아도 84MB 다. 성능 예산을 훌쩍 넘는다.
 * <b>실제로는 3~8초치만 담기고 나머지는 밀려난다.</b>
 *
 * <p>그런데 파일에는 「60초」라고 적힌다. 🔴 이건 이 프로젝트가 남의 코드에서 잡으려는
 * 「모름을 없음으로 접기」를 <b>도구가 자기 자신에게</b> 저지르는 것이다.
 *
 * <p>그래서 나눈다. 전문은 {@link EventBuffer}(짧게·무겁게), 모양은 여기(길게·가볍게).
 * 커넥션 누수처럼 <b>사고보다 30초 먼저 시작된 일</b>은 이쪽에만 남는다.
 *
 * <h2>여기 담기는 것은 재생에 못 쓴다</h2>
 * 본문이 없으므로 재생 입력이 될 수 없다. <b>사람이 원인을 찾는 데만</b> 쓴다.
 * 이걸 재생에 쓰려는 코드가 생기면 그 순간 「값이 없는데 있는 척」이 시작된다.
 */
public final class SummaryWindow {

    private final RecorderConfig config;
    private final Object lock = new Object();

    /** SQL 지문 → 누적. 모양이 같으면 한 줄로 접힌다. N+1 은 여기서 「한 놈이 812번」으로 보인다. */
    private final Map<String, Shape> shapes = new LinkedHashMap<>();

    private final Deque<Line> lines = new ArrayDeque<>();

    public SummaryWindow(RecorderConfig config) {
        this.config = config;
    }

    /** SQL 한 건. 본문은 안 받는다 — 받으면 이 층이 가벼운 이유가 사라진다. */
    public void addSql(String sql, long tookMs, long nowNanos) {
        Fingerprint fingerprint = fingerprintOf(sql);
        String normalized = fingerprint.normalized();
        String hash = fingerprint.hash();
        synchronized (lock) {
            expire(nowNanos);
            Shape shape = shapes.computeIfAbsent(hash, h -> new Shape(normalized));
            shape.count++;
            shape.totalMs += tookMs;
            shape.lastSeenNanos = nowNanos;
        }
    }

    /** 요청 한 줄. 헤더 «이름»도 본문도 안 담는다. */
    public void addRequest(Instant at, String method, String path, Integer status, Long ms, long nowNanos) {
        synchronized (lock) {
            expire(nowNanos);
            lines.addLast(new Line(at, method, path, status, ms, nowNanos));
        }
    }

    /**
     * 지금까지 모인 요약.
     *
     * <p>🔴 {@code connectionSamples} 는 {@code null} 이다. v0 은 커넥션 풀을 «안 본다».
     * 빈 목록으로 두면 「보았는데 없었다」가 되어, 커넥션 누수를 찾던 사람이
     * <b>누수가 없었다고 결론 낸다.</b> 안 본 것은 안 봤다고 적는다.
     */
    public Summary snapshot(long nowNanos) {
        synchronized (lock) {
            expire(nowNanos);

            List<Summary.SqlShape> sqlShapes = new ArrayList<>(shapes.size());
            shapes.forEach((hash, shape) ->
                    sqlShapes.add(new Summary.SqlShape(hash, shape.normalized, shape.count, shape.totalMs)));

            List<Summary.RequestLine> requestLines = new ArrayList<>(lines.size());
            for (Line line : lines) {
                requestLines.add(new Summary.RequestLine(line.at(), line.method(), line.path(), line.status(), line.ms()));
            }

            return new Summary(
                    config.windowSeconds(),
                    List.copyOf(sqlShapes),
                    List.copyOf(requestLines),
                    null // 🔴 안 봤다. [] 로 적으면 「보았고 없었다」가 된다
            );
        }
    }

    private void expire(long nowNanos) {
        long windowNanos = config.windowSeconds() * 1_000_000_000L;
        while (!lines.isEmpty() && nowNanos - lines.peekFirst().atNanos() > windowNanos) {
            lines.pollFirst();
        }
        shapes.entrySet().removeIf(e -> nowNanos - e.getValue().lastSeenNanos > windowNanos);
    }

    // ── 모양 만들기와 그 값을 재쓰는 자리 ───────────────────────────────────

    /** 같은 SQL 문자열에 대해 두 번 계산하지 않으려고 들고 있는 것. */
    private record Fingerprint(String normalized, String hash) {}

    /**
     * 캐시 상한.
     *
     * <p>🔴 <b>상한이 없으면 이 캐시가 앱을 죽인다.</b> 값을 문자열로 이어 붙여 SQL 을 만드는
     * 앱이 있다 — {@code "... where id = " + id} — 그러면 <b>서로 다른 SQL 문자열이 무한히</b>
     * 생기고, 캐시가 그걸 전부 들고 있게 된다. 관측 도구가 관측 대상의 힙을 먹어 치우는
     * 모양이고, 이 프로젝트가 절대 하면 안 되는 일이다.
     *
     * <p>1,000 인 이유: 파라미터를 제대로 쓰는 앱의 서로 다른 질의는 보통 수십~수백 개다.
     * 1,000 을 넘긴다는 것은 대개 <b>값이 SQL 문에 박혀 있다</b>는 신호이고, 그때는
     * 캐시가 도움이 안 되므로 그냥 «캐시를 안 쓴다».
     */
    private static final int 모양_캐시_상한 = 1000;

    private final java.util.concurrent.ConcurrentHashMap<String, Fingerprint> 모양캐시 =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * SQL 문자열 하나의 «모양»과 지문. 같은 문자열이면 계산을 건너뛴다.
     *
     * <h2>🔴 왜 캐시가 필요한가 — 재서 알았다</h2>
     * 2026-09-15 측정: SQL 이벤트 하나를 기록하는 데 <b>5.42µs</b> 가 들었는데
     * (설계 목표는 2µs), 그중 <b>3.34µs</b> 가 이 계산이었다. 정규식 세 번이 2.42µs,
     * SHA-256 이 0.22µs 다. 즉 <b>고칠 자리는 링 버퍼가 아니라 여기</b>였다.
     *
     * <p>그리고 이 도구가 겨누는 대표 버그인 N+1 은 <b>같은 SQL 이 수백 번 반복</b>되는 모양이다 —
     * 캐시가 가장 잘 듣는 자리가 하필 가장 중요한 자리다.
     *
     * <p>⚠️ 상한을 넘으면 캐시에 «넣지 않고» 계산만 한다. 비우지 않는 이유: 비우면
     * 상한 근처에서 채우고-비우고를 반복하며 <b>캐시가 없을 때보다 느려진다.</b>
     */
    private Fingerprint fingerprintOf(String sql) {
        if (sql == null) {
            return new Fingerprint("(알 수 없음)", SqlFingerprint.hash("(알 수 없음)"));
        }
        Fingerprint cached = 모양캐시.get(sql);
        if (cached != null) {
            return cached;
        }
        String normalized = normalize(sql);
        Fingerprint made = new Fingerprint(normalized, SqlFingerprint.hash(normalized));
        if (모양캐시.size() < 모양_캐시_상한) {
            모양캐시.putIfAbsent(sql, made);
        }
        return made;
    }

    /** 캐시에 든 서로 다른 SQL 문자열 수. 상한에 닿았다면 「값이 SQL 에 박혀 있다」는 신호다. */
    int cachedShapeCount() {
        return 모양캐시.size();
    }

    /**
     * 값이 다른 같은 질의를 한 모양으로 접는다.
     *
     * <p>🔴 값을 지우는 것이 요점이다. {@code where id = 7} 과 {@code where id = 8} 은
     * <b>같은 코드가 낸 같은 질의</b>이고, 이걸 다른 것으로 세면 N+1 이 「서로 다른 질의 200개」로
     * 보여서 안 잡힌다. 실제로 2026-09-11 실측에서 「총 횟수」보다 「모양별 반복」이
     * 훨씬 나은 신호라는 것이 나왔다.
     */
    static String normalize(String sql) {
        if (sql == null) {
            return "(알 수 없음)";
        }
        return sql
                .replaceAll("'[^']*'", "?")      // 문자열 값
                .replaceAll("\\b\\d+\\b", "?")   // 숫자 값
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static final class Shape {
        final String normalized;
        int count;
        long totalMs;
        long lastSeenNanos;

        Shape(String normalized) {
            this.normalized = normalized;
        }
    }

    private record Line(Instant at, String method, String path, Integer status, Long ms, long atNanos) {}
}
