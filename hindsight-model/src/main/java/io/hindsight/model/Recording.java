package io.hindsight.model;

import java.time.Instant;
import java.util.List;

/**
 * 사고 순간 앞뒤의 경계 값을 묶은 기록 한 건. 파일 하나가 이것 하나다.
 *
 * <h2>「모름」과 「없음」은 다른 사실이다</h2>
 * 이 프로젝트 전체를 관통하는 규칙이고, 이 자료 구조가 그 규칙을 표현하는 자리다.
 * <ul>
 *   <li>{@code null} — 안 봤다, 못 봤다, 이 단계에서는 안 잡는다</li>
 *   <li>빈 목록 / {@code 0} — 보았고 없었다</li>
 * </ul>
 * 이 둘을 같게 다루는 코드({@code Optional.orElse(List.of())} 같은 것)는
 * 이 도구가 잡으려는 바로 그 결함을 도구 자신이 저지르는 것이다.
 *
 * <h2>읽는 쪽과 쓰는 쪽이 두 벌이다</h2>
 * 기록하는 쪽(v0 은 recorder-simple, v1 은 agent)과 읽는 쪽(core)이 따로 있다.
 * 형식이 갈라지면 조용히 깨지고, 깨진 걸 알아채는 시점은 정작 필요한 사고가 났을 때다.
 * 그래서 왕복 테스트가 필수다 — 설계 §11.
 *
 * @param schemaVersion 이 파일의 구조가 몇 번째 판인지. 🔴 모르는 값이면 재생을 거부한다.
 *                      기본값으로 「대충」 읽으면 그 잘못된 재생 결과가 LLM 진단의 근거가 된다.
 * @param summary       요약 층. 전문({@code events})은 몇 초치뿐이라 그보다 앞선 일은 여기 있다.
 * @param jfr           {@code null} 가능 — JFR 을 안 켰거나 못 읽었다는 뜻이다.
 */
public record Recording(
        int schemaVersion,
        String id,
        Instant capturedAt,
        Trigger trigger,
        AppInfo app,
        List<Event> events,
        Summary summary,
        JfrSummary jfr,
        ReplayInfo replay,
        Integrity integrity
) {
    /**
     * 지금 코드가 쓰는 판 번호.
     *
     * <p>필드를 더하기만 하는 변경은 번호를 올리지 않는다. 필드를 지우거나 뜻을 바꾸면 올린다.
     * 읽는 쪽은 자기가 아는 번호보다 큰 파일을 만나면 <b>거부한다</b>.
     */
    /**
     * 🔴 <b>2 (2026-09-16).</b> 1 → 2 로 올린 이유: 질의 «모양»을 만드는 규칙이 바뀌었다 —
     * {@code in (?,?,?)} 를 {@code in (?)} 로 접는다.
     *
     * <p>{@code Summary.SqlShape.sqlHash} 는 <b>파일에 저장되는 값</b>이다. 규칙이 바뀌면
     * 1판 파일에 적힌 해시를 <b>지금 코드는 절대 만들어 내지 못한다.</b> 판 번호를 안 올리면
     * 그 사실이 아무 데도 안 남고, 「같은 해시인데 왜 안 맞지」가 영영 안 풀린다.
     *
     * <p>⚠️ 1판 파일은 <b>여전히 읽힌다</b>(낮은 판은 거절하지 않는다). 다만 그 파일의
     * 질의 해시는 지금 규칙의 해시와 <b>비교하면 안 된다.</b>
     */
    public static final int CURRENT_SCHEMA_VERSION = 2;
}
