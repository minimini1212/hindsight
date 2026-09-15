package io.hindsight.core.brain;

/**
 * 사람에게 넘길 PR 한 벌. <b>여는 것은 사람이 한다.</b>
 *
 * <h2>🔴 이 도구는 배포하지 않는다</h2>
 * 재생 검증이 증명하는 것은 <b>「이 상황이 고쳐졌다」 하나뿐</b>이다.
 * 무엇이 더 깨졌는지는 증명하지 않는다 — 재생은 경계 «안쪽»만 보기 때문이다.
 * 그리고 되돌리기 비용이 비대칭이다: <b>아홉 번 맞혀도 한 번 사고 나면 아무도 안 쓴다.</b>
 *
 * <p>그래서 이 도구는 PR 을 만들고 <b>멈춘다.</b> CI 와 사람이 나머지를 정한다.
 *
 * @param opensAutomatically 🔴 <b>확신도가 「높음」일 때만 참이다.</b> 나머지는 사람이 본 뒤에 연다
 */
public record PullRequestDraft(
        String branchName,
        String title,
        String body,
        Confidence confidence,
        boolean opensAutomatically
) {

    /** 사람이 읽는 한 줄. 명령줄 출력용. */
    public String summary() {
        return "[" + confidence.korean() + "] " + title
                + (opensAutomatically ? "  → 자동으로 연다" : "  → " + confidence.action());
    }
}
