package io.hindsight.core.brain;

import io.hindsight.model.ReplayInfo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("🔴 과적합을 막는 네 겹")
class VerificationLoopTest {

    private static final ReplayInfo 확인된_재생 = new ReplayInfo(
            ReplayInfo.Grade.VERIFIED_DETERMINISTIC, List.of(), true, null,
            new ReplayInfo.StateRestore(true, true, null, null),
            Instant.parse("2026-10-15T00:00:00Z"), null);

    private static final Map<String, String> 정직한_패치 =
            Map.of("src/main/java/a/OrderService.java", "class OrderService { /* join fetch */ }");

    private final VerificationLoop loop = new VerificationLoop();

    /**
     * 「테스트를 돌린다」를 흉내 낸다. 무엇이 통과할지 «대본»으로 정해 둔다.
     *
     * <p>🔴 진짜 앱을 띄우지 않는 이유: 이 판단이 옳은지는 «순서와 조합»의 문제이고,
     * 그건 앱 없이 전수로 확인할 수 있다. 앱을 띄우면 조합마다 몇 초씩 걸려서
     * <b>결국 몇 가지만 확인하고 넘어가게 된다.</b>
     */
    private static final class 대본 implements VerificationLoop.Runner {
        boolean 기준선에서_통과 = false;   // 보통은 실패해야 정상이다
        boolean 패치후_통과 = true;
        boolean 기존테스트_통과 = true;
        boolean 되돌린뒤_통과 = false;     // 보통은 다시 실패해야 정상이다
        boolean 적용됨 = true;
        boolean 되돌려짐 = true;

        final List<String> 부른것 = new ArrayList<>();
        private boolean 패치가_붙어있나 = false;

        @Override
        public boolean 재생_테스트가_통과하나() {
            부른것.add("재생테스트");
            if (!패치가_붙어있나) {
                return 부른것.stream().filter(x -> x.equals("되돌리기")).count() > 0
                        ? 되돌린뒤_통과 : 기준선에서_통과;
            }
            return 패치후_통과;
        }

        @Override
        public boolean 기존_테스트가_전부_통과하나() {
            부른것.add("기존테스트");
            return 기존테스트_통과;
        }

        @Override
        public boolean 패치를_적용한다() {
            부른것.add("적용");
            패치가_붙어있나 = 적용됨;
            return 적용됨;
        }

        @Override
        public boolean 패치를_되돌린다() {
            부른것.add("되돌리기");
            if (되돌려짐) {
                패치가_붙어있나 = false;
            }
            return 되돌려짐;
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("🔴 ㉠ 기준선 실패 — 여기서 걸리면 LLM 을 부르지 않는다")
    class 기준선 {

        @Test
        @DisplayName("🔴 생성된 테스트가 패치 «전»에도 통과하면 거기서 멈춘다")
        void 패치_전에_통과하면_멈춘다() {
            대본 대본 = new 대본();
            대본.기준선에서_통과 = true;   // 버그를 못 살린 테스트다

            var result = loop.run(Map.of(), 정직한_패치, 확인된_재생, 대본);

            assertThat(result.score().기준선_실패()).isEqualTo(LayerOutcome.FAILED);
            assertThat(result.confidence()).isEqualTo(Confidence.LOW);
            // 🔴 뒤 겹은 «안 돌렸다». 통과로 채우지 않는다.
            assertThat(result.score().검증_통과()).isEqualTo(LayerOutcome.NOT_RUN);
            assertThat(result.score().기존_테스트()).isEqualTo(LayerOutcome.NOT_RUN);
            // 패치를 적용해 보지도 않는다.
            assertThat(대본.부른것).doesNotContain("적용");
        }

        @Test
        @DisplayName("멈춘 이유를 「버그를 못 살린 테스트」라고 말한다")
        void 이유를_말한다() {
            대본 대본 = new 대본();
            대본.기준선에서_통과 = true;

            var result = loop.run(Map.of(), 정직한_패치, 확인된_재생, 대본);

            assertThat(String.join(" ", result.score().notes())).contains("버그를 못 살린 테스트");
        }
    }

    @Nested
    @DisplayName("🔴 ㉢ 앱의 기존 테스트 — 고리 «안»에서 돌린다")
    class 기존_테스트 {

        @Test
        @DisplayName("🔴 하나 고치고 다른 것을 깨뜨리면 확신도가 내려간다")
        void 다른_것을_깨뜨리면_내려간다() {
            대본 대본 = new 대본();
            대본.기존테스트_통과 = false;

            var result = loop.run(Map.of(), 정직한_패치, 확인된_재생, 대본);

            assertThat(result.score().기존_테스트()).isEqualTo(LayerOutcome.FAILED);
            assertThat(result.confidence()).isEqualTo(Confidence.LOW);
            assertThat(result.confidence().allowsAutomaticPullRequest()).isFalse();
        }

        @Test
        @DisplayName("🔴 고리 «안»에서 돌린다 — PR 이후 CI 로 미루지 않는다")
        void 고리_안에서_돌린다() {
            대본 대본 = new 대본();

            loop.run(Map.of(), 정직한_패치, 확인된_재생, 대본);

            // 🔴 「기존테스트」를 부른 적이 있어야 한다. 안 부르면 프로젝트의 진짜 테스트를
            //    한 번도 안 돌린 패치로 PR 이 나간다.
            assertThat(대본.부른것).contains("기존테스트");
        }
    }

    @Nested
    @DisplayName("🔴 ㉣ 되돌리기 — 패치와 «무관»하게 통과하던 테스트를 잡는다")
    class 되돌리기 {

        @Test
        @DisplayName("🔴 되돌렸는데도 통과하면 실패다 — 그 패치가 무엇을 고쳤는지 아무도 모른다")
        void 되돌려도_통과하면_실패() {
            대본 대본 = new 대본();
            대본.되돌린뒤_통과 = true;

            var result = loop.run(Map.of(), 정직한_패치, 확인된_재생, 대본);

            assertThat(result.score().되돌리기()).isEqualTo(LayerOutcome.FAILED);
            assertThat(result.score().allPassed()).isFalse();
            assertThat(String.join(" ", result.score().notes())).contains("무관");
        }

        @Test
        @DisplayName("🔴 못 되돌렸으면 「통과」가 아니라 「안 돌렸다」다")
        void 못_되돌리면_안_돌린_것() {
            대본 대본 = new 대본();
            대본.되돌려짐 = false;

            var result = loop.run(Map.of(), 정직한_패치, 확인된_재생, 대본);

            assertThat(result.score().되돌리기()).isEqualTo(LayerOutcome.NOT_RUN);
            assertThat(result.score().allPassed()).isFalse();
            assertThat(result.score().notRunLayers()).anyMatch(n -> n.contains("㉣"));
        }
    }

    @Nested
    @DisplayName("경로 검사에서 멈추면 아무것도 안 돌린다")
    class 경로 {

        @Test
        @DisplayName("🔴 테스트를 건드리는 패치는 «돌려 보지도» 않는다")
        void 테스트를_건드리면_안_돌린다() {
            대본 대본 = new 대본();

            var result = loop.run(Map.of(),
                    Map.of("src/test/java/a/ATest.java", ""), 확인된_재생, 대본);

            assertThat(result.pathVerdict().level()).isEqualTo(
                    io.hindsight.core.guard.PatchVerdict.Level.REJECT);
            // 🔴 돌리는 것 자체가 패치를 적용해 본다는 뜻이다.
            assertThat(대본.부른것).isEmpty();
            assertThat(result.score().notRunLayers()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("🔴 확신도 — 「높음」은 셋이 «전부» 맞아야 한다")
    class 확신도 {

        @Test
        @DisplayName("네 겹 전부 통과 + 재생 확인됨 + 증상 덮기 없음 → 높음")
        void 전부_맞으면_높음() {
            var result = loop.run(Map.of(), 정직한_패치, 확인된_재생, new 대본());

            assertThat(result.score().allPassed()).isTrue();
            assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(result.confidence().allowsAutomaticPullRequest()).isTrue();
        }

        @Test
        @DisplayName("🔴 재생 등급을 «안 봤으면» 높음이 안 된다")
        void 재생을_안_봤으면_중간() {
            var result = loop.run(Map.of(), 정직한_패치, null, new 대본());

            assertThat(result.score().allPassed()).isTrue();
            assertThat(result.confidence()).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        @DisplayName("재생이 PARTIAL 이면 중간 — 네 겹이 다 통과해도")
        void PARTIAL_이면_중간() {
            ReplayInfo partial = new ReplayInfo(ReplayInfo.Grade.PARTIAL, List.of("STATE"),
                    true, null, null, null, null);

            var result = loop.run(Map.of(), 정직한_패치, partial, new 대본());

            assertThat(result.score().allPassed()).isTrue();
            assertThat(result.confidence()).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        @DisplayName("🔴 DIVERGED 면 중간 — 채점 불가이지 실패가 아니다")
        void DIVERGED_면_중간() {
            ReplayInfo diverged = new ReplayInfo(ReplayInfo.Grade.DIVERGED, null,
                    true, null, null, null, null);

            var result = loop.run(Map.of(), 정직한_패치, diverged, new 대본());

            assertThat(result.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(String.join(" ", result.score().notes())).contains("사람에게 넘긴다");
        }

        @Test
        @DisplayName("🔴 증상만 덮는 모양이 «새로» 들어가면 통과해도 중간으로 내린다")
        void 증상_덮기가_있으면_중간() {
            Map<String, String> 덮는_패치 = Map.of("src/main/java/a/OrderController.java",
                    "class OrderController { @ExceptionHandler void 삼킨다() {} }");

            var result = loop.run(Map.of(), 덮는_패치, 확인된_재생, new 대본());

            assertThat(result.score().allPassed()).isTrue();
            assertThat(result.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(result.symptomCovers()).isNotEmpty();
        }

        @Test
        @DisplayName("🔴 원래 있던 catch 까지 세지 않는다 — 그러면 그 파일을 건드리는 모든 패치가 영원히 중간이 된다")
        void 원래_있던_것은_안_센다() {
            String 원래 = "class A { void m() { try {} catch (Exception e) {} } }";
            String 고친것 = "class A { void m() { try { 제대로(); } catch (Exception e) {} } }";

            var result = loop.run(
                    Map.of("src/main/java/a/A.java", 원래),
                    Map.of("src/main/java/a/A.java", 고친것),
                    확인된_재생, new 대본());

            assertThat(result.symptomCovers()).isEmpty();
            assertThat(result.confidence()).isEqualTo(Confidence.HIGH);
        }
    }

    @Nested
    @DisplayName("🔴 안 돌린 겹을 요약에서 «숨기지» 않는다")
    class 요약 {

        @Test
        @DisplayName("안 돌린 겹이 있으면 요약에 그렇게 적힌다")
        void 안_돌린_겹이_보인다() {
            대본 대본 = new 대본();
            대본.기준선에서_통과 = true;

            var result = loop.run(Map.of(), 정직한_패치, 확인된_재생, 대본);

            assertThat(result.describe()).contains("안 돌린 겹");
            assertThat(result.describe()).contains("안 돌렸다");
        }

        @Test
        @DisplayName("무엇을 해도 되는지가 요약 첫 줄에 있다")
        void 행동이_첫_줄에_있다() {
            var result = loop.run(Map.of(), 정직한_패치, 확인된_재생, new 대본());

            assertThat(result.describe()).startsWith("확신도: 높음");
            assertThat(result.describe()).contains("PR 을 자동으로 만든다");
        }
    }
}
