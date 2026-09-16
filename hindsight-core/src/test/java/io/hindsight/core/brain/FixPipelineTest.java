package io.hindsight.core.brain;

import io.hindsight.core.guard.PatchVerdict;
import io.hindsight.core.replay.ReplayResult;
import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;
import io.hindsight.model.Trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>고리에서 틀리면 남의 저장소에 잘못된 PR 이 올라간다.</b>
 *
 * <p>그래서 이 고리의 판단은 네트워크도 저장소도 없이 전수로 돈다 —
 * 진단도 수선도 전부 밖에서 받기 때문이다.
 */
@DisplayName("고치는 고리 — 예산 안에서 진단·적용·채점을 반복한다")
class FixPipelineTest {

    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");
    private static final String 좋은경로 = "src/main/java/a/OrderService.java";

    private static Recording 기록() {
        return new Recording(Recording.CURRENT_SCHEMA_VERSION, "a1b2", T0,
                new Trigger(Trigger.Kind.LATENCY, T0, "GET /api/orders", null, 3400L, "k", 1),
                new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                List.of(new Event.HttpIn(1, "r-1", T0, "t", 3400L, "GET", "/api/orders",
                        null, null, Map.of(), null, false, 0, 200, "{}", false)),
                null, null, null,
                new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false));
    }

    private static final ReplayInfo 확인된_재생 = new ReplayInfo(
            ReplayInfo.Grade.VERIFIED_DETERMINISTIC, List.of(), true, null,
            new ReplayInfo.StateRestore(true, true, null, null), null, null);

    /** 정해 둔 답을 차례로 돌려주는 가짜 진단기. 무엇을 «들고 왔는지»도 적어 둔다. */
    private static final class 가짜진단 implements Diagnosis {
        final Deque<결과> 답들 = new ArrayDeque<>();
        final List<Diagnosis.소스맥락> 받은맥락 = new ArrayList<>();

        @Override
        public 결과 진단한다(Recording recording, 소스맥락 맥락) {
            받은맥락.add(맥락);
            return 답들.isEmpty()
                    ? 결과.답이_쓸모없다("더 줄 답이 없다", 10, 10, 0L)
                    : 답들.removeFirst();
        }
    }

    private static Diagnosis.결과 패치를_준다(String 경로) {
        return new Diagnosis.결과(true, "원인", Map.of(경로, "class OrderService {}"),
                100, 50, 0L, null);
    }

    /** 정해 둔 결과를 내는 가짜 수선공. */
    private static final class 가짜수선공 implements FixPipeline.수선공 {
        final Deque<VerificationLoop.Runner> 러너들 = new ArrayDeque<>();
        final List<Map<String, String>> 받은패치 = new ArrayList<>();
        PatchVerdict 판정 = PatchVerdict.allow(List.of("전부 화이트리스트 안이다"));

        /** 🔴 앞에서 이만큼은 «경로 때문에 거절»한다. Deque 는 null 을 못 담아서 따로 센다. */
        int 앞에서_거절할_횟수 = 0;

        @Override
        public VerificationLoop.Runner 준비한다(Map<String, String> 패치) {
            받은패치.add(패치);
            if (앞에서_거절할_횟수 > 0) {
                앞에서_거절할_횟수--;
                return null;
            }
            return 러너들.isEmpty() ? null : 러너들.removeFirst();
        }

        @Override
        public PatchVerdict 마지막_경로판정() {
            return 판정;
        }
    }

    /** 네 겹이 어떻게 나올지 정해 주는 러너. */
    private static VerificationLoop.Runner 러너(boolean 기준선실패, boolean 검증통과,
                                             boolean 기존통과, boolean 되돌리기됨) {
        return new VerificationLoop.Runner() {
            private boolean 붙었나 = false;
            private int 몇번째 = 0;

            @Override public boolean 재생_테스트가_통과하나() {
                몇번째++;
                if (몇번째 == 1) {
                    return !기준선실패;          // ㉠ 여기서 통과하면 「버그를 못 살린 테스트」
                }
                return 붙었나 ? 검증통과 : false;  // ㉡ 붙였을 때 / ㉣ 뗐을 때
            }
            @Override public boolean 기존_테스트가_전부_통과하나() { return 기존통과; }
            @Override public boolean 패치를_적용한다() { 붙었나 = true; return true; }
            @Override public boolean 패치를_되돌린다() { 붙었나 = false; return 되돌리기됨; }
        };
    }

    private static final Map<String, String> 원래내용 = Map.of(좋은경로, "class OrderService {}");

    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("한 번에 고쳤을 때")
    class 한번에 {

        @Test
        @DisplayName("네 겹을 다 통과하면 PR 초안이 나온다")
        void 통과하면_초안() {
            가짜진단 진단 = new 가짜진단();
            진단.답들.add(패치를_준다(좋은경로));
            가짜수선공 수선 = new 가짜수선공();
            수선.러너들.add(러너(true, true, true, true));

            var r = new FixPipeline(진단).돌린다(기록(), Diagnosis.소스맥락.없음(),
                    new AttemptBudget(3, 100), 수선, 원래내용,
                    new ReplayResult(확인된_재생, List.of()), null);

            assertThat(r.고쳤나()).isTrue();
            assertThat(r.초안()).isNotNull();
            assertThat(r.시도들()).hasSize(1);
            assertThat(수선.받은패치).hasSize(1);
        }

        @Test
        @DisplayName("🔴 첫 시도에 됐으면 LLM 을 «한 번만» 부른다")
        void 한번만_부른다() {
            가짜진단 진단 = new 가짜진단();
            진단.답들.add(패치를_준다(좋은경로));
            가짜수선공 수선 = new 가짜수선공();
            수선.러너들.add(러너(true, true, true, true));
            AttemptBudget 예산 = new AttemptBudget(3, 100);

            new FixPipeline(진단).돌린다(기록(), Diagnosis.소스맥락.없음(), 예산, 수선,
                    원래내용, new ReplayResult(확인된_재생, List.of()), null);

            assertThat(예산.쓴_시도()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("🔴 실패했을 때 — «왜» 실패했는지를 다음 시도에 들려 보낸다")
    class 다시_시도 {

        @Test
        @DisplayName("🔴 안 들려 보내면 같은 답이 세 번 온다 — 그래서 반드시 들려 보낸다")
        void 이유를_들려_보낸다() {
            가짜진단 진단 = new 가짜진단();
            진단.답들.add(패치를_준다(좋은경로));   // 1차: 기존 테스트를 깨뜨린다
            진단.답들.add(패치를_준다(좋은경로));   // 2차: 성공
            가짜수선공 수선 = new 가짜수선공();
            수선.러너들.add(러너(true, true, false, true));  // ㉢ 실패
            수선.러너들.add(러너(true, true, true, true));

            var r = new FixPipeline(진단).돌린다(기록(), Diagnosis.소스맥락.없음(),
                    new AttemptBudget(3, 100), 수선, 원래내용,
                    new ReplayResult(확인된_재생, List.of()), null);

            assertThat(r.고쳤나()).isTrue();
            assertThat(진단.받은맥락).hasSize(2);
            assertThat(진단.받은맥락.get(0).파일들())
                    .as("첫 시도에는 알려 줄 «지난번»이 없다")
                    .doesNotContainKey("__지난_시도가_실패한_이유.txt");
            assertThat(진단.받은맥락.get(1).파일들().get("__지난_시도가_실패한_이유.txt"))
                    .as("🔴 어디서 걸렸는지가 그대로 들어가야 다음 시도가 «다른» 시도가 된다")
                    .contains("기존 테스트");
        }

        @Test
        @DisplayName("🔴 경로에 걸리면 «적용도 안 하고», 채점은 「안 돌렸다」로 남는다")
        void 경로에_걸리면() {
            가짜진단 진단 = new 가짜진단();
            진단.답들.add(new Diagnosis.결과(true, "원인",
                    Map.of("src/test/java/a/ATest.java", "class ATest {}"), 100, 50, 0L, null));
            진단.답들.add(패치를_준다(좋은경로));
            가짜수선공 수선 = new 가짜수선공();
            수선.판정 = PatchVerdict.reject(List.of("테스트는 읽기 전용이다"),
                    List.of("src/test/java/a/ATest.java"));
            수선.앞에서_거절할_횟수 = 1;   // 🔴 첫 시도는 경로 때문에 거절된다
            수선.러너들.add(러너(true, true, true, true));

            var r = new FixPipeline(진단).돌린다(기록(), Diagnosis.소스맥락.없음(),
                    new AttemptBudget(3, 100), 수선, 원래내용,
                    new ReplayResult(확인된_재생, List.of()), null);

            var 첫시도 = r.시도들().getFirst();
            assertThat(첫시도.채점())
                    .as("🔴 적용도 안 했으면 채점은 «안 돌린» 것이다. 「다 실패」가 아니다")
                    .isNull();
            assertThat(첫시도.왜멈췄나()).contains("경로");
            assertThat(진단.받은맥락.get(1).파일들().get("__지난_시도가_실패한_이유.txt"))
                    .contains("경로");
        }

        @Test
        @DisplayName("패치를 아예 못 받으면 형식을 알려 주고 다시 묻는다")
        void 패치를_못_받으면() {
            가짜진단 진단 = new 가짜진단();
            진단.답들.add(new Diagnosis.결과(true, "모르겠다", Map.of(), 100, 50, 0L, "형식이 안 맞았다"));
            진단.답들.add(패치를_준다(좋은경로));
            가짜수선공 수선 = new 가짜수선공();
            수선.러너들.add(러너(true, true, true, true));

            var r = new FixPipeline(진단).돌린다(기록(), Diagnosis.소스맥락.없음(),
                    new AttemptBudget(3, 100), 수선, 원래내용,
                    new ReplayResult(확인된_재생, List.of()), null);

            assertThat(r.고쳤나()).isTrue();
            assertThat(진단.받은맥락.get(1).파일들().get("__지난_시도가_실패한_이유.txt"))
                    .contains("형식");
        }
    }

    @Nested
    @DisplayName("🔴 멈출 때 — «왜» 멈췄는지를 구별한다")
    class 멈춤 {

        @Test
        @DisplayName("🔴 예산이 끝난 것을 「못 고쳤다」로 적지 않는다")
        void 예산이_끝나면() {
            가짜진단 진단 = new 가짜진단();
            for (int i = 0; i < 5; i++) {
                진단.답들.add(패치를_준다(좋은경로));
            }
            가짜수선공 수선 = new 가짜수선공();
            for (int i = 0; i < 5; i++) {
                수선.러너들.add(러너(true, true, false, true));  // 계속 ㉢ 실패
            }

            var r = new FixPipeline(진단).돌린다(기록(), Diagnosis.소스맥락.없음(),
                    new AttemptBudget(2, 100), 수선, 원래내용,
                    new ReplayResult(확인된_재생, List.of()), null);

            assertThat(r.고쳤나()).isFalse();
            assertThat(r.왜())
                    .as("🔴 「못 고쳤다」와 「여기까지만 해 보기로 했다」는 다른 사실이다")
                    .contains("여기까지만");
            assertThat(r.시도들()).hasSize(3); // 시도 2번 + 예산이 끝났다는 기록
        }

        @Test
        @DisplayName("🔴 LLM 을 못 부르면 «거기서» 끝난다 — 다시 물어도 같은 결과다")
        void 못_부르면() {
            가짜진단 진단 = new 가짜진단();
            진단.답들.add(Diagnosis.결과.못불렀다("LLM_API_KEY 가 없다"));
            가짜수선공 수선 = new 가짜수선공();
            AttemptBudget 예산 = new AttemptBudget(3, 100);

            var r = new FixPipeline(진단).돌린다(기록(), Diagnosis.소스맥락.없음(), 예산, 수선,
                    원래내용, new ReplayResult(확인된_재생, List.of()), null);

            assertThat(r.고쳤나()).isFalse();
            assertThat(r.왜()).contains("LLM_API_KEY");
            assertThat(예산.쓴_시도())
                    .as("🔴 못 불렀으면 «돈을 안 썼다». 예산을 깎으면 안 된다")
                    .isZero();
            assertThat(수선.받은패치).isEmpty();
        }

        @Test
        @DisplayName("🔴 예산이 0 이면 LLM 을 «한 번도» 안 부른다")
        void 예산이_없으면() {
            가짜진단 진단 = new 가짜진단();
            진단.답들.add(패치를_준다(좋은경로));
            AttemptBudget 예산 = new AttemptBudget(1, 100);
            예산.한번_썼다(0);   // 이미 다 썼다

            var r = new FixPipeline(진단).돌린다(기록(), Diagnosis.소스맥락.없음(), 예산,
                    new 가짜수선공(), 원래내용, new ReplayResult(확인된_재생, List.of()), null);

            assertThat(r.고쳤나()).isFalse();
            assertThat(진단.받은맥락).isEmpty();
            assertThat(r.시도들()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("🔴 PR 은 «만들기만» 한다")
    class PR은_만들기만 {

        @Test
        @DisplayName("올리는 것은 여기서 안 한다 — 만든 것과 올린 것을 갈라 둔다")
        void 안_올린다() {
            가짜진단 진단 = new 가짜진단();
            진단.답들.add(패치를_준다(좋은경로));
            가짜수선공 수선 = new 가짜수선공();
            수선.러너들.add(러너(true, true, true, true));

            var r = new FixPipeline(진단).돌린다(기록(), Diagnosis.소스맥락.없음(),
                    new AttemptBudget(3, 100), 수선, 원래내용,
                    new ReplayResult(확인된_재생, List.of()), null);

            // 🔴 초안은 나오지만 «올렸다»는 상태가 이 결과에 없다.
            //    그건 ship 이 하는 일이고, 토큰이 있어야 한다.
            assertThat(r.초안()).isNotNull();
            assertThat(r.초안().branchName()).isEqualTo("hindsight/a1b2");
        }

        @Test
        @DisplayName("🔴 몇 번 만에 나온 패치인지가 PR 에 적힌다")
        void 시도_횟수가_적힌다() {
            가짜진단 진단 = new 가짜진단();
            진단.답들.add(패치를_준다(좋은경로));
            진단.답들.add(패치를_준다(좋은경로));
            가짜수선공 수선 = new 가짜수선공();
            수선.러너들.add(러너(true, true, false, true));
            수선.러너들.add(러너(true, true, true, true));

            var r = new FixPipeline(진단).돌린다(기록(), Diagnosis.소스맥락.없음(),
                    new AttemptBudget(3, 100), 수선, 원래내용,
                    new ReplayResult(확인된_재생, List.of()), null);

            assertThat(r.초안().body())
                    .as("세 번 만에 나온 패치와 한 번에 나온 패치는 다른 사실이다")
                    .contains("2");
        }
    }
}
