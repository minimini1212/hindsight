package io.hindsight.core.replay;

/**
 * 오라클(테스트가 「맞았다/틀렸다」를 판정하는 기준) 하나가 내린 판정.
 *
 * <h2>🔴 결과가 넷인 이유 — 「판정 못 함」이 「통과」가 되면 안 된다</h2>
 * 가장 흔한 실수는 판정할 자료가 없을 때 조용히 통과시키는 것이다. 그러면 도구는
 * <b>아무것도 확인하지 않고 초록불을 켠다.</b> 그리고 그 초록불을 근거로 PR 이 올라간다.
 *
 * <p>「모름」을 「없음」으로 접지 않는다는 이 프로젝트의 규율이 여기서는
 * <b>「판정 못 함」을 「통과」로 접지 않는다</b>로 나타난다.
 */
public record OracleVerdict(String oracle, Outcome outcome, String reason) {

    public enum Outcome {
        /** 기준을 만족했다. */
        PASS,

        /** 기준을 어겼다. 버그가 그대로거나 다시 생겼다. */
        FAIL,

        /**
         * 🔴 기록에 없는 경계를 건드렸다. <b>통과도 실패도 아니다.</b>
         *
         * <p>N+1 을 제대로 고치면 질의 201개가 조인 1개로 바뀌는데, 그 새 질의는 기록에 없다.
         * 이걸 실패로 치면 채점기가 <b>얕은 패치를 구조적인 수정보다 높게 친다</b> — 정확히 거꾸로다.
         */
        DIVERGED,

        /** 🔴 판정할 자료를 못 봤다. <b>통과가 아니다.</b> */
        NOT_JUDGED
    }

    public static OracleVerdict pass(String oracle, String reason) {
        return new OracleVerdict(oracle, Outcome.PASS, reason);
    }

    public static OracleVerdict fail(String oracle, String reason) {
        return new OracleVerdict(oracle, Outcome.FAIL, reason);
    }

    public static OracleVerdict diverged(String oracle, String reason) {
        return new OracleVerdict(oracle, Outcome.DIVERGED, reason);
    }

    public static OracleVerdict notJudged(String oracle, String reason) {
        return new OracleVerdict(oracle, Outcome.NOT_JUDGED, reason);
    }

    public boolean isPass() {
        return outcome == Outcome.PASS;
    }
}
