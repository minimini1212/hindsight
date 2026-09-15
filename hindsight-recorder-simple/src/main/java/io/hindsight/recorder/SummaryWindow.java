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
        String normalized = normalize(sql);
        String hash = SqlFingerprint.hash(normalized);
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
