package io.hindsight.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔬 실험 ⑩-⑤ — <b>SQL 이벤트만 목표를 넘긴 이유가 어디에 있나.</b>
 *
 * <p>실험 ⑩-① 에서 HTTP 이벤트는 0.96µs 인데 SQL 이벤트는 5.4µs 로 나왔다.
 * 같은 링 버퍼에 같은 방식으로 넣는데 <b>다섯 배 넘게</b> 차이가 난다.
 * 🔴 «다른 일을 하는 자리»가 하나 있다는 뜻이고, 그게 어디인지 재서 찾는다.
 *
 * <p>고치기 «전»에 이걸 먼저 하는 이유: 안 재고 고치면 <b>엉뚱한 곳을 고치고도
 * 고쳤다고 믿는다.</b> 그리고 그 믿음은 다음에 같은 자리를 또 의심하게 만든다.
 */
@DisplayName("🔬 실험 ⑩-⑤ — SQL 이벤트의 비용이 어디서 오나")
class SqlShapeCostTest {

    private static final String SQL =
            "select o.id, o.product from orders o where o.member_id = 7 and o.status = 'PAID'";

    @Test
    @DisplayName("요약층의 「모양 만들기」가 얼마나 먹나")
    void 모양_만들기의_비용() {
        OverheadHarness.Result 정규화 = OverheadHarness.measure(
                "정규화(정규식 3개)", 20_000, 50, 10_000,
                i -> consume(SummaryWindow.normalize(SQL)));

        String normalized = SummaryWindow.normalize(SQL);
        OverheadHarness.Result 지문 = OverheadHarness.measure(
                "지문(SHA-256)", 20_000, 50, 10_000,
                i -> consume(SqlFingerprint.hash(normalized)));

        OverheadHarness.Result 둘다 = OverheadHarness.measure(
                "정규화 + 지문", 20_000, 50, 10_000,
                i -> consume(SqlFingerprint.hash(SummaryWindow.normalize(SQL))));

        System.out.println();
        System.out.println("── 실험 ⑩-⑤ SQL 이벤트의 비용이 어디서 오나 ──");
        System.out.println(정규화);
        System.out.println(지문);
        System.out.println(둘다);
        System.out.println("  ⚠️ 실험 ⑩-① 의 「SQL 이벤트 하나」와 견주어 읽는다 —");
        System.out.println("     이 둘의 합이 그 값의 대부분이면, 고칠 자리는 링 버퍼가 아니라 여기다.");
    }

    /** 🔴 결과를 안 쓰면 JIT 이 계산을 통째로 지운다. 그러면 「0ns」가 나오는데 안 한 것이다. */
    private static int sink;

    private static void consume(String s) {
        sink += s.length();
    }
}
