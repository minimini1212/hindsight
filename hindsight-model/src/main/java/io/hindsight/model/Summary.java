package io.hindsight.model;

import java.time.Instant;
import java.util.List;

/**
 * 요약 층 — 전문({@link Recording#events()})보다 <b>훨씬 앞선 시간</b>까지 담는다.
 *
 * <h2>왜 층을 나누나</h2>
 * 「최근 60초를 다 들고 있다」는 산수가 안 맞는다. 초당 200요청 × 60초 = 12,000건이고
 * 요청당 7KB 만 잡아도 84MB 다. 실제로는 몇 초치만 담기고 앞은 밀려난다.
 *
 * <p>그런데 원인은 결과보다 먼저 일어난다. 커넥션이 안 반납되고 있었다면 그건 30초 전 일이다.
 * 몇 초치 전문만으로는 <b>그 30초 전이 안 보인다.</b>
 *
 * <p>그래서 값을 뺀 요약을 따로 오래 들고 있는다. 여기서는 재생을 못 하지만
 * <b>사람이 원인을 찾을 수는 있다</b> — 「같은 질의가 812번」, 「빌린 커넥션이 계속 늘었다」.
 *
 * @param windowSeconds 이 요약이 담는 시간. 전문 층의 시간과 다르다.
 */
public record Summary(
        int windowSeconds,
        List<SqlShape> sqlShapes,
        List<RequestLine> requestLines,
        List<PoolSample> connectionSamples
) {
    /**
     * 같은 모양의 질의를 하나로 묶은 것.
     *
     * <p>🔴 재생 중 <b>기록에 없는 질의</b>를 만났을 때 이 지문과 대조한다.
     * 가까운 것이 있으면 「패치가 질의 모양을 바꿨다」로 판정한다 — 설계 §6-2 의 {@code DIVERGED}.
     * 즉 이 목록은 요약일 뿐 아니라 <b>채점에 직접 쓰인다.</b>
     *
     * @param normalized 자리표시자와 공백을 정규화한 SQL. 값이 달라도 모양이 같으면 한 줄이다.
     */
    public record SqlShape(String sqlHash, String normalized, int count, long totalMs) {}

    /** 요청 한 줄. 본문은 없다 — 그게 요약 층인 이유다. */
    public record RequestLine(Instant at, String method, String path, Integer status, Long ms) {}

    /**
     * 커넥션 풀을 들여다본 한 순간.
     *
     * <p>{@code leased} 가 계속 늘고 {@code idle} 이 0 으로 가는 그림이 누수다.
     * 한 순간만 봐서는 못 알아보고 <b>여러 순간을 이어 봐야</b> 보인다 — 요약 층이 오래 남는 이유.
     */
    public record PoolSample(Instant at, int leased, int idle) {}
}
