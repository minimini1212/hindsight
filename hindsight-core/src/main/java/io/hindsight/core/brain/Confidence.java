package io.hindsight.core.brain;

/**
 * 채점 결과가 「무엇을 해도 되는가」로 바뀐 것.
 *
 * <h2>🔴 「모르겠다」고 말할 줄 아는 것이 이 도구의 신뢰를 만든다</h2>
 * 모든 결과를 PR 로 바꾸는 도구는 한 번 틀리는 순간 아무도 안 쓴다.
 * 반대로 <b>애매할 때 애매하다고 말하는 도구</b>는 틀려도 신뢰가 남는다.
 */
public enum Confidence {

    /** 네 겹 전부 통과 + 재생 등급이 확인됨 + 증상 덮기 없음 → PR 자동 생성. */
    HIGH("높음", "PR 을 자동으로 만든다"),

    /**
     * 🔴 <b>막지도 통과시키지도 않는다.</b> 재현 테스트와 진단은 주되, 패치는 «초안»으로 붙인다.
     *
     * <p>재생 등급이 {@code PARTIAL} 이거나, 기록에 없는 경계를 건드렸거나({@code DIVERGED}),
     * 설정 파일을 고쳤거나, 증상만 덮는 모양이 보일 때.
     */
    MEDIUM("중간", "재현 테스트와 진단만 주고, 패치는 초안으로 붙인다"),

    /** 채점이 어디선가 멈췄다. 기록과 테스트만 남기고 사람을 부른다. */
    LOW("낮음", "기록과 테스트만 남기고 사람을 부른다");

    private final String korean;
    private final String action;

    Confidence(String korean, String action) {
        this.korean = korean;
        this.action = action;
    }

    public String korean() {
        return korean;
    }

    public String action() {
        return action;
    }

    /** 🔴 자동으로 PR 을 만들어도 되는 것은 «높음» 하나뿐이다. */
    public boolean allowsAutomaticPullRequest() {
        return this == HIGH;
    }
}
