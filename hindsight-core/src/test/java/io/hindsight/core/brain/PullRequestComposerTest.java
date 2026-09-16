package io.hindsight.core.brain;

import io.hindsight.core.replay.GeneratedTest;
import io.hindsight.core.replay.OracleVerdict;
import io.hindsight.core.replay.ReplayObservation;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("채점 결과를 사람이 읽을 PR 로")
class PullRequestComposerTest {

    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");

    private static Recording 기록(Trigger trigger) {
        return new Recording(Recording.CURRENT_SCHEMA_VERSION, "a1b2", T0, trigger,
                new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                List.of(new Event.HttpIn(1, "r-1", T0, "t", 3400L, "GET", "/api/orders",
                        null, null, Map.of(), null, false, 0, 200, "{}", false)),
                null, null, null,
                new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false));
    }

    private static Trigger 지연방아쇠(int dedupCount) {
        return new Trigger(Trigger.Kind.LATENCY, T0, "GET /api/orders", null, 3400L, "k", dedupCount);
    }

    private static Trigger 예외방아쇠() {
        return new Trigger(Trigger.Kind.EXCEPTION, T0, "GET /api/orders",
                new Trigger.ExceptionInfo("java.lang.NullPointerException", "x", List.of("A.m:1")),
                120L, "k", 1);
    }

    private static final ReplayInfo 확인된_재생 = new ReplayInfo(
            ReplayInfo.Grade.VERIFIED_DETERMINISTIC, List.of(), true, null,
            new ReplayInfo.StateRestore(true, true, null, null), null, null);

    private static ReplayResult 재생결과(ReplayInfo replay, OracleVerdict... verdicts) {
        return new ReplayResult(replay, List.of(verdicts));
    }

    /** 네 겹을 원하는 결과로 만들어 주는 대본. */
    private static VerificationLoop.Result 채점(Confidence expected, ReplayInfo replay,
                                             Map<String, String> after) {
        return new VerificationLoop().run(Map.of(), after, replay, new VerificationLoop.Runner() {
            private boolean 붙었나 = false;

            @Override public boolean 재생_테스트가_통과하나() { return 붙었나; }
            @Override public boolean 기존_테스트가_전부_통과하나() { return true; }
            @Override public boolean 패치를_적용한다() { 붙었나 = true; return true; }
            @Override public boolean 패치를_되돌린다() { 붙었나 = false; return true; }
        });
    }

    private static final Map<String, String> 정직한_패치 =
            Map.of("src/main/java/a/OrderService.java", "class OrderService {}");

    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("🔴 제목이 확신도를 숨기지 않는다")
    class 제목 {

        @Test
        @DisplayName("높음이면 「고쳤다」라고 적는다")
        void 높음이면_고쳤다() {
            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            assertThat(draft.confidence()).isEqualTo(Confidence.HIGH);
            assertThat(draft.title()).contains("고쳤다");
            assertThat(draft.opensAutomatically()).isTrue();
        }

        @Test
        @DisplayName("🔴 중간이면 제목에 «초안»이라고 적는다 — 목록에서는 본문을 안 읽는다")
        void 중간이면_초안이라고_적는다() {
            ReplayInfo partial = new ReplayInfo(ReplayInfo.Grade.PARTIAL, List.of("STATE"),
                    true, null, null, null, null);

            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(partial), 채점(Confidence.MEDIUM, partial, 정직한_패치), null, 1);

            assertThat(draft.confidence()).isEqualTo(Confidence.MEDIUM);
            assertThat(draft.title()).contains("초안");
            assertThat(draft.title()).doesNotContain("고쳤다");
            assertThat(draft.opensAutomatically()).isFalse();
        }

        @Test
        @DisplayName("🔴 낮음이면 「자동으로 못 고쳤다」라고 적는다")
        void 낮음이면_못_고쳤다고_적는다() {
            // 테스트를 건드리는 패치 → 경로에서 거절 → 확신도 낮음
            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)), null,
                    채점(Confidence.LOW, 확인된_재생, Map.of("src/test/java/ATest.java", "")), null, 1);

            assertThat(draft.confidence()).isEqualTo(Confidence.LOW);
            assertThat(draft.title()).contains("사람 필요");
            assertThat(draft.opensAutomatically()).isFalse();
        }
    }

    @Nested
    @DisplayName("🔴 「확인하지 못한 것」이 본문에 «항상» 있다")
    class 못한_것 {

        @Test
        @DisplayName("안 돌린 겹이 본문에 적힌다")
        void 안_돌린_겹이_적힌다() {
            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)), null,
                    채점(Confidence.LOW, 확인된_재생, Map.of("src/test/java/ATest.java", "")), null, 1);

            assertThat(draft.body()).contains("무엇을 확인하지");
            assertThat(draft.body()).contains("안 돌렸다");
            assertThat(draft.body()).contains("확인되지 않음");
        }

        @Test
        @DisplayName("생성된 테스트가 단언 못 한 자리가 본문에 적힌다")
        void 단언_못_한_자리가_적힌다() {
            GeneratedTest generated = new GeneratedTest("XTest", "XTest.java", "class XTest {}",
                    List.of("응답 상태가 200 이다"),
                    List.of("응답 본문 — 기록에 없다(안 잡혔다)"));

            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), generated, 1);

            assertThat(draft.body()).contains("응답 본문 — 기록에 없다");
        }

        @Test
        @DisplayName("🔴 판정 못 한 오라클이 본문에 적힌다 — 통과한 것만 보여 주면 그게 전부라고 믿는다")
        void 판정_못_한_오라클이_적힌다() {
            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생,
                            OracleVerdict.pass("응답 대조", "같다"),
                            OracleVerdict.notJudged("질의 반복", "재생이 낸 질의를 안 봤다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            assertThat(draft.body()).contains("판정하지 못한 오라클");
            assertThat(draft.body()).contains("질의 반복");
        }

        @Test
        @DisplayName("🔴 확인 못 한 것이 «없어 보여도» 비우지 않는다 — 재생이 원래 증명하지 않는 것을 적는다")
        void 없어_보여도_비우지_않는다() {
            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            // 네 겹 전부 통과 · missing 비어 있음 · 판정 못 한 것 없음 — 그래도 비지 않는다.
            assertThat(draft.body()).contains("재생은 이 상황 하나만 본다");
            assertThat(draft.body()).contains("앱 안에 쌓인 상태");
        }
    }

    @Nested
    @DisplayName("본문이 담는 것")
    class 본문 {

        @Test
        @DisplayName("무엇이 있었나 — 진입점과 방아쇠")
        void 무엇이_있었나() {
            var draft = PullRequestComposer.compose(기록(예외방아쇠()),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            assertThat(draft.body()).contains("GET /api/orders");
            assertThat(draft.body()).contains("java.lang.NullPointerException");
        }

        @Test
        @DisplayName("🔴 「한 번 났다」와 「1,204번 중 하나다」를 다르게 적는다")
        void 몇_번_났는지_적는다() {
            var draft = PullRequestComposer.compose(기록(지연방아쇠(1204)),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            assertThat(draft.body()).contains("1204번");
        }

        @Test
        @DisplayName("네 겹의 결과가 본문에 그대로 들어간다")
        void 네_겹이_들어간다() {
            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            assertThat(draft.body()).contains("㉠ 기준선 실패");
            assertThat(draft.body()).contains("㉢ 앱의 기존 테스트");
        }

        @Test
        @DisplayName("🔴 시도 횟수가 1 이 아니면 적는다 — 세 번 만에 나온 패치는 다른 사실이다")
        void 시도_횟수를_적는다() {
            var 한번 = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);
            var 세번 = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 3);

            assertThat(한번.body()).doesNotContain("시도 횟수");
            assertThat(세번.body()).contains("시도 횟수: **3번**");
        }

        @Test
        @DisplayName("🔴 DIVERGED 면 「무엇이 무엇으로 바뀌었나」를 붙인다")
        void 갈라짐을_붙인다() {
            ReplayInfo diverged = new ReplayInfo(ReplayInfo.Grade.DIVERGED, null, true,
                    new ReplayInfo.Divergence("SQL_SHAPE_CHANGED",
                            new ReplayInfo.Divergence.Side("9f2a", "select * from member where id = ?", 20),
                            new ReplayInfo.Divergence.Side("1b3c", "select ... join member", 1),
                            "질의 20개가 1개로 줄었다. N+1 해소와 부합한다"),
                    null, null, null);

            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(diverged), 채점(Confidence.MEDIUM, diverged, 정직한_패치), null, 1);

            assertThat(draft.body()).contains("질의 모양이 바뀌었다");
            assertThat(draft.body()).contains("20번");
            assertThat(draft.body()).contains("1번");
            assertThat(draft.body()).contains("N+1 해소와 부합한다");
        }

        @Test
        @DisplayName("🔴 배포하지 않는다는 것이 본문에 적혀 있다")
        void 배포하지_않는다고_적혀_있다() {
            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            assertThat(draft.body()).contains("배포하지 않는다");
            assertThat(draft.body()).contains("경계 «안쪽»만 보기 때문이다");
        }

        @Test
        @DisplayName("증상 덮기가 보이면 본문에 그렇게 적힌다")
        void 증상_덮기가_적힌다() {
            Map<String, String> 덮는_패치 = Map.of("src/main/java/a/C.java",
                    "class C { @ExceptionHandler void 삼킨다() {} }");

            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.MEDIUM, 확인된_재생, 덮는_패치), null, 1);

            assertThat(draft.body()).contains("증상만 덮는 모양");
            assertThat(draft.body()).contains("@ExceptionHandler");
        }
    }

    @Nested
    @DisplayName("브랜치 이름")
    class 브랜치 {

        @Test
        @DisplayName("기록 번호로 만든다 — 같은 기록이면 같은 이름")
        void 기록_번호로_만든다() {
            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            assertThat(draft.branchName()).isEqualTo("hindsight/a1b2");
        }
    }

    @Nested
    @DisplayName("🔴 나가기 직전에 한 번 더 훑는다")
    class 유출검사 {

        /**
         * 🔴 <b>진입점 경로</b>에 개인정보가 박힌 기록. 이 자리는 본문에 그대로 실린다.
         *
         * <p>⚠️ 처음에는 예외 «메시지»에 이메일을 넣어 시험했는데, 시험이 알려 줬다 —
         * <b>메시지는 본문에 아예 안 실린다</b>(타입만 실린다). 즉 그 경로로는 안 샌다.
         * 실제로 새는 자리는 <b>진입점 · 질의문 · 생성된 테스트가 못 단언한 것</b>처럼
         * 글에 그대로 옮겨 적는 곳이다.
         */
        private Recording 개인정보가_박힌_기록() {
            return new Recording(Recording.CURRENT_SCHEMA_VERSION, "leak1", T0,
                    new Trigger(Trigger.Kind.LATENCY, T0,
                            "GET /api/users/hong.gildong@example.com/orders?phone=010-1234-5678",
                            null, 3400L, "k", 1),
                    new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                    List.of(), null, null, null,
                    new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false));
        }

        @Test
        @DisplayName("🔴 모양이 걸리면 확신도가 높아도 «자동으로 안 연다»")
        void 걸리면_자동으로_안_연다() {
            var draft = PullRequestComposer.compose(개인정보가_박힌_기록(),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            assertThat(draft.confidence())
                    .as("채점 자체는 통과했다 — 그래서 이 검사가 없으면 그대로 올라간다")
                    .isEqualTo(Confidence.HIGH);
            assertThat(draft.opensAutomatically())
                    .as("🔴 GitHub 에 올라가면 못 되돌린다. 지워도 알림 메일과 색인에 남는다")
                    .isFalse();
            assertThat(draft.body())
                    .contains("자동으로 열리지 않고")
                    .as("🔴 막기만 하고 안 가리면, 사람이 손으로 열 때 그대로 나간다")
                    .contains("본문에서도 가렸다");
        }

        @Test
        @DisplayName("🔴 무엇이 걸렸는지 적되, 값은 «가려서» 적는다")
        void 값을_다시_적지_않는다() {
            var draft = PullRequestComposer.compose(개인정보가_박힌_기록(),
                    재생결과(확인된_재생), 채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            assertThat(draft.body()).contains("이메일").contains("휴대폰 번호");
            assertThat(draft.body())
                    .as("🔴 걸렸다고 본문에 원본을 또 적으면 새는 자리를 하나 더 만드는 것이다")
                    .doesNotContain("hong.gildong@example.com");
        }

        @Test
        @DisplayName("🔴 깨끗할 때도 「훑었다」를 적는다 — 아무 말이 없으면 «안 훑은 것»과 같다")
        void 깨끗해도_적는다() {
            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생, OracleVerdict.pass("응답", "같다")),
                    채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            assertThat(draft.body()).contains("나가기 전 한 번 더 훑었다").contains("안 걸렸다");
            assertThat(draft.opensAutomatically()).isTrue();
        }

        @Test
        @DisplayName("🔴 «못 찾는 것»이 항상 같이 나간다 — 없으면 「훑었으니 안전하다」로 읽힌다")
        void 못_찾는_것도_적는다() {
            var draft = PullRequestComposer.compose(기록(지연방아쇠(1)),
                    재생결과(확인된_재생), 채점(Confidence.HIGH, 확인된_재생, 정직한_패치), null, 1);

            assertThat(draft.body()).contains("모양으로는 못 찾는 것").contains("이름");
        }
    }

    @Nested
    @DisplayName("본문 길이 — GitHub 이 거절하기 «전»에 자른다")
    class 길이 {

        @Test
        @DisplayName("상한 아래면 손대지 않는다")
        void 짧으면_그대로() {
            String 짧은것 = "가".repeat(100);

            assertThat(PullRequestComposer.길이를_자른다(짧은것)).isEqualTo(짧은것);
        }

        @Test
        @DisplayName("🔴 자를 때 «잘랐다는 사실과 원래 길이»를 적는다 — 조용히 짧아지면 그게 전부인 줄 안다")
        void 자르면_말한다() {
            String 긴것 = "가".repeat(PullRequestComposer.본문_길이_상한 + 5_000);

            String 잘린것 = PullRequestComposer.길이를_자른다(긴것);

            assertThat(잘린것).contains("여기서 잘렸다");
            assertThat(잘린것).contains(String.valueOf(긴것.length()));
            assertThat(잘린것).contains("잘린 부분에 무엇이 있었는지는 이 PR 로 알 수 없다");
        }

        @Test
        @DisplayName("자른 뒤에도 GitHub 상한(65,536자) 안이다")
        void 자른_뒤에도_상한_안() {
            String 긴것 = "가".repeat(200_000);

            assertThat(PullRequestComposer.길이를_자른다(긴것).length())
                    .as("설명을 붙이느라 다시 상한을 넘으면 자른 의미가 없다")
                    .isLessThan(65_536);
        }
    }
}
