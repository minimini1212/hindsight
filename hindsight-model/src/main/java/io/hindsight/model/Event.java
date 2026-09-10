package io.hindsight.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 프로세스 밖과 값이 오간 한 건.
 *
 * <p>앱 <b>안에서</b> 일어난 일은 여기 없다. 메서드 호출도, 내부 상태 변화도 안 잡는다.
 * 잡는 것은 경계뿐이고, 그래서 재생이 성립한다 — 경계만 되돌려주면 안쪽은 알아서 다시 일어난다.
 *
 * <p>{@code sealed} 로 닫아 둔 이유: 재생기와 오라클이 종류별로 갈라져 처리하는데,
 * 새 종류가 생겼을 때 처리를 빠뜨리면 컴파일러가 잡아 준다. 열려 있으면 조용히 무시된다.
 */
public sealed interface Event {

    /** 에이전트가 매기는 전역 순번. 재생은 이 순서로 값을 되돌려준다. */
    long seq();

    /**
     * 어느 요청에 속한 이벤트인지.
     *
     * <p>🔴 {@code thread} 로 대신하지 않는다. 가상 스레드는 요청마다 생겼다 사라지고
     * 식별자가 재사용되므로, 스레드로 묶으면 남의 요청 SQL 이 섞인다.
     *
     * <p>{@code null} 이면 어느 요청에도 안 붙는 이벤트다(스케줄러, 시작 시 초기화 등).
     * 🔴 버리지 않고 그대로 담는다 — 원인이 거기 있을 수 있다.
     */
    String corrId();

    Instant at();

    /** 진단할 때 사람이 읽는 용도. 묶는 근거로 쓰지 않는다({@link #corrId()} 참조). */
    String thread();

    /** {@code null} 가능 — 아직 안 끝났거나 재지 않은 이벤트. 0 과 다른 사실이다. */
    Long durationMs();

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 들어온 요청과 그 응답.
     *
     * @param handler            어느 메서드가 처리했나. {@code null} 가능 — 라우팅 전에 터졌다는 뜻이다.
     * @param headers            🔴 {@code Authorization}·{@code Cookie} 는 여기 없다. 기록 전에 버린다.
     * @param bodyBytes          자르기 <b>전</b>의 크기. {@code body} 길이와 다르면 잘린 것이다.
     * @param responseBody       🔴 반드시 남긴다. 상태 코드만 검사하는 테스트는 예외를 삼키는
     *                           {@code @ExceptionHandler} 하나로 통과된다 — 설계 §7-1.
     */
    record HttpIn(
            long seq, String corrId, Instant at, String thread, Long durationMs,
            String method, String path, Map<String, String> query, String handler,
            Map<String, String> headers,
            String body, boolean bodyTruncated, Integer bodyBytes,
            Integer responseStatus, String responseBody, boolean responseBodyTruncated
    ) implements Event {}

    /**
     * 나간 요청. 재생할 때 진짜로 안 보내고 여기 적힌 응답을 돌려준다. (v1)
     */
    record HttpOut(
            long seq, String corrId, Instant at, String thread, Long durationMs,
            String method, String url, Map<String, String> requestHeaders,
            String requestBody, boolean requestBodyTruncated,
            Integer responseStatus, Map<String, String> responseHeaders,
            String responseBody, boolean responseBodyTruncated
    ) implements Event {}

    /**
     * DB 질의.
     *
     * <h2>같은 질의가 반복될 때</h2>
     * N+1 은 이 도구가 겨누는 대표 버그인데, 그 상황에서 SQL 201 건의 결과를 전부 담으면
     * 버퍼가 터진다. 그래서 처음 몇 건만 전문으로 남기고 나머지는 {@code repeatOf} 로 접는다.
     * 🔴 이건 손실이 아니라 신호다 — 「같은 질의 200회」 자체가 진단에 필요한 그 사실이다.
     *
     * @param params     자리표시자에 들어간 값. 🔴 가명화 대상이다.
     *                   원소가 {@code null} 이면 SQL NULL 이었다는 뜻이다.
     * @param rowCount   실제 행 수. {@code rows} 가 잘렸어도 이 값은 진짜 개수다.
     * @param rows       각 행은 컬럼이름→값. 🔴 <b>키가 있고 값이 {@code null} 이면 SQL NULL</b>,
     *                   <b>키가 아예 없으면 그 컬럼을 안 담았다</b>는 뜻이다. 둘은 다른 사실이다.
     * @param repeatOf   앞선 같은 모양 질의의 {@code seq}. {@code null} 이면 접힌 게 아니다.
     * @param repeatCount 그 모양이 몇 번 나왔나. {@code repeatOf} 가 있을 때만 의미가 있다.
     */
    record Sql(
            long seq, String corrId, Instant at, String thread, Long durationMs,
            String sql, List<Object> params,
            Integer rowCount, List<Map<String, Object>> rows, boolean rowsTruncated,
            Long repeatOf, Integer repeatCount
    ) implements Event {}

    /**
     * 시각을 물어본 것. (v1)
     *
     * <p>여기 없는 호출은 「그런 호출이 없었다」가 아니라 <b>「우리가 못 잡았다」</b>일 수 있다.
     * 그 사실이 재생 등급을 {@link ReplayInfo.Grade#PARTIAL} 로 만든다.
     */
    record Clock(
            long seq, String corrId, Instant at, String thread, Long durationMs,
            String source, String value
    ) implements Event {}

    /** 난수를 뽑은 것. (v1) {@link Clock} 과 같은 「못 잡았을 수 있다」가 적용된다. */
    record Rand(
            long seq, String corrId, Instant at, String thread, Long durationMs,
            String source, String value
    ) implements Event {}
}
