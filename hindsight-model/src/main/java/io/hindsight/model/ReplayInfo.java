package io.hindsight.model;

import java.time.Instant;
import java.util.List;

/**
 * 이 기록을 얼마나 믿을 수 있나.
 *
 * <p>🔴 이 등급은 <b>주장이 아니라 실측</b>이다. 「어떤 계측이 걸렸나」로 미리 매기면
 * 캐시가 따뜻했는지, 정적 변수에 뭐가 쌓였는지를 알 수 없어 틀린 확신을 준다.
 * 그래서 <b>재생 중에도 같은 계측을 켜고</b> 재생이 만든 경계 호출을 기록과 대조한 결과를 적는다.
 *
 * @param missing        무엇을 못 잡았는지 이름. 🔴 빈 목록(「보았고 다 잡았다」)과
 *                       {@code null}(「대조를 아직 안 했다」)은 다른 사실이다.
 * @param baselineFailed 🔴 {@link #baselineFailed()} 설명 참조. {@code null} 이면 아직 안 돌려봤다.
 * @param staleAfter     이 시각이 지나면 「낡았다」고 표시한다. 막지는 않는다.
 */
public record ReplayInfo(
        Grade grade,
        List<String> missing,
        Boolean baselineFailed,
        Divergence diverged,
        Instant staleAfter,
        String notes
) {
    public enum Grade {
        /**
         * 재생이 만든 경계 호출이 기록과 <b>전부 맞았고 순서도 같았다</b>.
         *
         * <p>이름에 {@code VERIFIED} 가 붙은 이유: 이건 관찰이지 약속이 아니다.
         * 「결정론적이다」가 아니라 「이번에 대조해 보니 같았다」는 뜻이다.
         */
        VERIFIED_DETERMINISTIC,

        /**
         * 일부를 못 잡았다. {@link #missing()} 에 이름이 있다.
         *
         * <p>🔴 이걸 {@link #VERIFIED_DETERMINISTIC} 으로 접지 않는다.
         * 시각 호출을 못 잡은 기록은 <b>시각 호출이 없었던 기록과 다르다.</b>
         */
        PARTIAL,

        /**
         * 재생 중 <b>기록에 없는 경계</b>를 건드렸다.
         *
         * <p>실패도 통과도 아니다. 이 도구가 겨누는 대표 버그가 바로 이 상태를 만든다 —
         * N+1 을 조인으로 고치면 그 새 질의는 기록에 없다. 이걸 실패로 처리하면
         * <b>채점기가 얕은 패치를 구조적 수정보다 높게 친다.</b> 정확히 거꾸로다.
         *
         * <p>그래서 「무엇이 무엇으로 바뀌었나」를 붙여 사람에게 넘긴다. 설계 §6-2.
         */
        DIVERGED,

        /**
         * 재생 자체가 안 된다. 🔴 LLM 을 부르지 않는다.
         *
         * <p>생성된 테스트가 <b>패치 전 코드에서 실패하지 않는 경우</b>도 여기다 —
         * 그런 테스트는 채점을 못 한다.
         */
        FAILED
    }

    /**
     * 패치가 질의·호출의 모양을 바꿔서 기록으로는 채점할 수 없게 된 상황.
     *
     * @param verdict 진단이 말한 방향과 맞는지에 대한 판단.
     *                「질의 201 → 1」은 N+1 해소와 부합한다. 🔴 이건 <b>통과 판정이 아니다.</b>
     *                사람에게 넘길 때 붙이는 근거일 뿐이다.
     */
    public record Divergence(String kind, Side before, Side after, String verdict) {
        public record Side(String sqlHash, String normalized, Integer count) {}
    }

    /**
     * 이 기록으로 얻은 패치를 사람 손 없이 PR 로 올려도 되나.
     *
     * <p>🔴 자동 PR 은 <b>등급이 실측으로 확인됐고 기준선이 정말 실패했을 때만</b> 열린다.
     * 이 조건을 코드 한 곳에 모아 두는 이유는, 조건이 흩어지면 언젠가 한 곳이 느슨해지기 때문이다.
     * 느슨해진 그 한 곳이 잘못된 패치를 운영 저장소로 밀어 넣는다.
     */
    public boolean allowsAutoPullRequest() {
        return grade == Grade.VERIFIED_DETERMINISTIC && Boolean.TRUE.equals(baselineFailed);
    }
}
