package io.hindsight.core.brain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("한 기록에 얼마나 매달릴 것인가 — 시도와 돈, 둘 다 끊는다")
class AttemptBudgetTest {

    @Nested
    @DisplayName("시도 횟수")
    class 시도 {

        @Test
        @DisplayName("기본은 세 번이고, 세 번을 쓰면 끝난다")
        void 세번이면_끝() {
            AttemptBudget 예산 = new AttemptBudget();

            for (int i = 0; i < AttemptBudget.기본_시도_횟수; i++) {
                assertThat(예산.한번_더_되나(1).되나()).as("%d번째".formatted(i + 1)).isTrue();
                예산.한번_썼다(1);
            }

            var 결과 = 예산.한번_더_되나(1);
            assertThat(결과.되나()).isFalse();
            assertThat(결과.남은_시도()).isZero();
        }

        @Test
        @DisplayName("🔴 「예산을 다 썼다」가 「못 고쳤다」로 읽히지 않는다")
        void 예산이_끝난_것과_못_고친_것은_다르다() {
            AttemptBudget 예산 = new AttemptBudget(1, 100);
            예산.한번_썼다(1);

            assertThat(예산.한번_더_되나(1).왜())
                    .as("🔴 이유가 없으면 PR 에 「세 번 실패」라고만 적히고, "
                            + "정말 세 번 시켰는지 알 수 없게 된다")
                    .contains("여기까지만 해 보기로 했다");
        }

        @Test
        @DisplayName("🔴 부른 «뒤»에 센다 — 터진 호출이 시도를 깎으면 한 번도 못 불러 보고 끝난다")
        void 부른_뒤에_센다() {
            AttemptBudget 예산 = new AttemptBudget(3, 100);

            // 「되나」를 세 번 물어봐도 시도는 안 준다. 실제로 쓴 것만 깎인다.
            예산.한번_더_되나(1);
            예산.한번_더_되나(1);
            예산.한번_더_되나(1);

            assertThat(예산.쓴_시도()).isZero();
            assertThat(예산.한번_더_되나(1).남은_시도()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("비용")
    class 비용 {

        @Test
        @DisplayName("상한을 넘기는 호출은 «하기 전에» 막는다")
        void 넘길_것_같으면_안_한다() {
            AttemptBudget 예산 = new AttemptBudget(10, 100);
            예산.한번_썼다(80);

            assertThat(예산.한번_더_되나(10).되나()).as("80+10=90 은 상한 안").isTrue();
            assertThat(예산.한번_더_되나(30).되나()).as("80+30=110 은 상한 밖").isFalse();
            assertThat(예산.한번_더_되나(30).왜()).contains("비용 상한을 넘는다");
        }

        @Test
        @DisplayName("🔴 「얼마 들지 모른다」를 0 으로 치지 않는다 — 그러면 상한이 없는 것과 같다")
        void 모르면_거절한다() {
            AttemptBudget 예산 = new AttemptBudget(10, 100);

            var 결과 = 예산.한번_더_되나(-1);

            assertThat(결과.되나()).isFalse();
            assertThat(결과.왜()).contains("모른다");
        }

        @Test
        @DisplayName("🔴 실제로 든 값을 모르면 «상한만큼» 썼다고 친다 — 0 으로 적으면 예산이 안 준다")
        void 모르는_비용은_상한만큼_친다() {
            AttemptBudget 예산 = new AttemptBudget(10, 100);

            예산.한번_썼다(-1);

            assertThat(예산.쓴_센트()).isEqualTo(100);
            assertThat(예산.한번_더_되나(1).되나())
                    .as("모르는 호출 한 번으로 예산이 끝난다. 🔴 시끄러운 쪽이 맞다")
                    .isFalse();
        }

        @Test
        @DisplayName("🔴 상한 0 은 「공짜만」이지 「무제한」이 아니다")
        void 상한_0_은_무제한이_아니다() {
            AttemptBudget 예산 = new AttemptBudget(10, 0);

            assertThat(예산.한번_더_되나(0).되나()).as("공짜면 된다").isTrue();
            assertThat(예산.한번_더_되나(1).되나()).as("1센트라도 들면 안 된다").isFalse();
        }
    }

    @Nested
    @DisplayName("잘못된 설정은 조용히 넘어가지 않는다")
    class 설정 {

        @Test
        @DisplayName("시도 0회는 거절한다 — 「아무것도 안 한다」를 설정으로 만들 수 없다")
        void 시도_0은_거절() {
            assertThatThrownBy(() -> new AttemptBudget(0, 100))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("음수 비용 상한은 거절한다")
        void 음수_상한은_거절() {
            assertThatThrownBy(() -> new AttemptBudget(3, -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("쓴 만큼이 한 줄로 나온다 — PR 본문에 그대로 들어간다")
    void 요약() {
        AttemptBudget 예산 = new AttemptBudget(3, 100);
        예산.한번_썼다(35);
        예산.한번_썼다(20);

        assertThat(예산.describe()).isEqualTo("시도 2/3 · 비용 55/100센트");
    }
}
