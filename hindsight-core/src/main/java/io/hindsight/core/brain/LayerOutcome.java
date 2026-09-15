package io.hindsight.core.brain;

/**
 * 채점 한 겹의 결과.
 *
 * <h2>🔴 왜 셋인가 — 「안 돌렸다」가 「통과」가 되면 안 된다</h2>
 * 가장 흔한 실수는 겹을 «건너뛰고» 통과로 치는 것이다. 앱의 기존 테스트를 못 돌렸을 때
 * {@code true} 로 두면, <b>프로젝트의 진짜 테스트를 한 번도 안 돌린 패치로 PR 이 올라간다.</b>
 *
 * <p>「모름」을 「없음」으로 접지 않는다는 이 프로젝트의 규율이 여기서는
 * <b>「안 돌렸다」를 「통과」로 접지 않는다</b>로 나타난다.
 */
public enum LayerOutcome {

    /** 돌렸고, 기대대로였다. */
    PASSED,

    /** 돌렸고, 기대와 달랐다. */
    FAILED,

    /**
     * 🔴 <b>안 돌렸다.</b> 「통과」가 아니다.
     *
     * <p>앞 겹에서 이미 끝나서 돌릴 필요가 없었거나, 돌릴 수 없는 환경이었거나,
     * 시간이 없었거나 — 이유가 무엇이든 <b>확인되지 않은 것</b>이다.
     */
    NOT_RUN;

    public boolean passed() {
        return this == PASSED;
    }

    /** 사람이 읽는 말. 보고서와 PR 본문에 그대로 나간다. */
    public String korean() {
        return switch (this) {
            case PASSED -> "통과";
            case FAILED -> "실패";
            case NOT_RUN -> "안 돌렸다";
        };
    }
}
