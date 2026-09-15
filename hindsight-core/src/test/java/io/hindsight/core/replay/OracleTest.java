package io.hindsight.core.replay;

import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.Recording;
import io.hindsight.model.SqlShapes;
import io.hindsight.model.Summary;
import io.hindsight.model.Trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("오라클 — 「맞았다」를 무엇으로 판정하나")
class OracleTest {

    private static final Instant T0 = Instant.parse("2026-09-15T10:00:00Z");

    // ── 기록 만들기 ─────────────────────────────────────────────────────────

    private static Event.HttpIn 요청(Integer status, String body, boolean truncated) {
        return new Event.HttpIn(1, "r-1", T0, "t", 120L,
                "GET", "/api/orders", null, null, Map.of(),
                null, false, 0, status, body, truncated);
    }

    private static Event.Sql 질의(long seq, String sql) {
        return new Event.Sql(seq, "r-1", T0, "t", 1L, sql, List.of(), null, null, false, null, null);
    }

    private static Recording 기록(Trigger trigger, List<Event> events, Summary summary) {
        return new Recording(Recording.CURRENT_SCHEMA_VERSION, "t1", T0, trigger,
                new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                events, summary, null, null,
                new Integrity(60, 3.0, 0, 0, 0, 1000, 0, false));
    }

    private static Trigger 예외방아쇠(String type) {
        return new Trigger(Trigger.Kind.EXCEPTION, T0, "GET /api/orders",
                new Trigger.ExceptionInfo(type, "터졌다", List.of("A.m:1")), 120L, "k", 1);
    }

    private static Trigger 지연방아쇠() {
        return new Trigger(Trigger.Kind.LATENCY, T0, "GET /api/orders", null, 3400L, "k", 1);
    }

    /** N+1 이 심긴 기록 — 목록 1번 + 회원 조회 20번. */
    private static Recording n플러스원_기록() {
        List<Event> events = new ArrayList<>();
        events.add(요청(200, "{\"orders\":[]}", false));
        events.add(질의(2, "select * from orders"));
        for (int i = 0; i < 20; i++) {
            events.add(질의(3 + i, "select * from member where id = " + i));
        }
        SqlShapes.Shape 목록 = SqlShapes.of("select * from orders");
        SqlShapes.Shape 회원 = SqlShapes.of("select * from member where id = 1");
        Summary summary = new Summary(60,
                List.of(new Summary.SqlShape(목록.hash(), 목록.normalized(), 1, 5),
                        new Summary.SqlShape(회원.hash(), 회원.normalized(), 20, 40)),
                List.of(), null);
        return 기록(지연방아쇠(), events, summary);
    }

    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("예외가 다시 나오지 않는다")
    class 예외 {

        private final Oracle oracle = new Oracle.ExceptionGone();

        @Test
        @DisplayName("예외가 안 나오면 통과")
        void 예외가_안_나오면_통과() {
            OracleVerdict v = oracle.judge(
                    기록(예외방아쇠("java.lang.NullPointerException"), List.of(요청(500, "err", false)), null),
                    ReplayObservation.builder().responseStatus(500).noExceptionEscaped().build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.PASS);
        }

        @Test
        @DisplayName("같은 예외가 그대로 나오면 실패")
        void 같은_예외면_실패() {
            OracleVerdict v = oracle.judge(
                    기록(예외방아쇠("java.lang.NullPointerException"), List.of(요청(500, "err", false)), null),
                    ReplayObservation.builder()
                            .exceptionEscaped("java.lang.NullPointerException", "또 터졌다").build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.FAIL);
        }

        @Test
        @DisplayName("🔴 예외가 나갔는지 «안 봤으면» 통과가 아니라 「판정 못 함」이다")
        void 안_봤으면_판정_못_함() {
            OracleVerdict v = oracle.judge(
                    기록(예외방아쇠("java.lang.NullPointerException"), List.of(요청(500, "err", false)), null),
                    ReplayObservation.builder().responseStatus(500).build()); // escaped 를 안 넣었다

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.NOT_JUDGED);
            assertThat(v.isPass()).isFalse();
        }
    }

    @Nested
    @DisplayName("응답이 기록과 같다")
    class 응답 {

        private final Oracle oracle = new Oracle.ResponseMatches();

        @Test
        @DisplayName("상태와 본문이 글자까지 같으면 통과")
        void 글자까지_같으면_통과() {
            OracleVerdict v = oracle.judge(
                    기록(예외방아쇠("X"), List.of(요청(200, "{\"a\":1}", false)), null),
                    ReplayObservation.builder().responseStatus(200).responseBody("{\"a\":1}").build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.PASS);
        }

        @Test
        @DisplayName("🔴 상태만 같고 본문이 다르면 실패 — 예외를 삼키고 200 을 주는 패치를 잡는 자리")
        void 상태만_같으면_실패() {
            OracleVerdict v = oracle.judge(
                    기록(예외방아쇠("X"), List.of(요청(200, "{\"a\":1}", false)), null),
                    ReplayObservation.builder().responseStatus(200).responseBody("null").build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.FAIL);
        }

        @Test
        @DisplayName("🔴 기록의 본문이 «잘려» 있으면 판정하지 않는다 — 앞부분만 같으면 통과가 되어 버린다")
        void 잘린_본문은_판정하지_않는다() {
            OracleVerdict v = oracle.judge(
                    기록(예외방아쇠("X"), List.of(요청(200, "{\"a\":1", true)), null),
                    ReplayObservation.builder().responseStatus(200).responseBody("{\"a\":1").build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.NOT_JUDGED);
        }

        @Test
        @DisplayName("🔴 기록에 본문이 «없으면»(안 잡혔으면) 통과로 치지 않는다")
        void 기록에_본문이_없으면_판정_못_함() {
            OracleVerdict v = oracle.judge(
                    기록(예외방아쇠("X"), List.of(요청(200, null, false)), null),
                    ReplayObservation.builder().responseStatus(200).responseBody("아무거나").build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.NOT_JUDGED);
        }
    }

    @Nested
    @DisplayName("🔴 같은 모양의 질의가 더 반복되지 않는다 — N+1 을 잡는 자리")
    class 질의반복 {

        private final Oracle oracle = new Oracle.QueryRepeatNotWorse();

        @Test
        @DisplayName("🔴 반복이 «그대로»여도 실패다 — 이게 「패치 전에 실패했나」를 참으로 만드는 자리")
        void 반복이_그대로면_실패다() {
            // 기록과 «똑같이» 20번 반복 — 즉 아무것도 안 고쳤다.
            List<String> 재생질의 = new ArrayList<>();
            재생질의.add("select * from orders");
            for (int i = 0; i < 20; i++) {
                재생질의.add("select * from member where id = " + i);
            }

            OracleVerdict v = oracle.judge(n플러스원_기록(),
                    ReplayObservation.builder().executedSql(재생질의).build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.FAIL);
        }

        @Test
        @DisplayName("반복이 더 늘면 실패")
        void 반복이_더_늘면_실패() {
            List<String> 재생질의 = new ArrayList<>();
            재생질의.add("select * from orders");
            for (int i = 0; i < 25; i++) {
                재생질의.add("select * from member where id = " + i);
            }

            OracleVerdict v = oracle.judge(n플러스원_기록(),
                    ReplayObservation.builder().executedSql(재생질의).build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.FAIL);
            assertThat(v.reason()).contains("25").contains("20");
        }

        @Test
        @DisplayName("반복이 줄면 통과")
        void 반복이_줄면_통과() {
            OracleVerdict v = oracle.judge(n플러스원_기록(),
                    ReplayObservation.builder()
                            .executedSql(List.of("select * from orders", "select * from member where id = 1"))
                            .build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.PASS);
        }

        @Test
        @DisplayName("🔴 값만 다른 질의는 «한 모양»으로 센다 — 안 그러면 N+1 이 「서로 다른 질의 20개」로 보인다")
        void 값만_다르면_한_모양으로_센다() {
            List<String> 재생질의 = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                재생질의.add("select * from member where id = " + i);
            }

            OracleVerdict v = oracle.judge(n플러스원_기록(),
                    ReplayObservation.builder().executedSql(재생질의).build());

            // 30개가 서로 다른 질의로 세였다면 「한 모양이 1번씩」이라 통과했을 것이다.
            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.FAIL);
            assertThat(v.reason()).contains("30");
        }

        @Test
        @DisplayName("🔴 재생이 낸 질의를 «안 봤으면» 통과가 아니다")
        void 질의를_안_봤으면_판정_못_함() {
            OracleVerdict v = oracle.judge(n플러스원_기록(),
                    ReplayObservation.builder().responseStatus(200).build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.NOT_JUDGED);
        }
    }

    @Nested
    @DisplayName("방아쇠에 따라 무엇으로 판정할지 고른다")
    class 오라클고르기 {

        @Test
        @DisplayName("🔴 예외 방아쇠에도 «응답 대조»가 낀다 — 예외만 보면 삼키는 패치가 통과한다")
        void 예외에도_응답_대조가_낀다() {
            List<Oracle> oracles = Oracle.forRecording(
                    기록(예외방아쇠("X"), List.of(요청(500, "err", false)), null));

            assertThat(oracles).hasAtLeastOneElementOfType(Oracle.ResponseMatches.class);
            assertThat(oracles).hasAtLeastOneElementOfType(Oracle.ExceptionGone.class);
        }

        @Test
        @DisplayName("🔴 지연 방아쇠는 «횟수»로 판정한다 — 재생에서는 시간이 사라진다")
        void 지연은_횟수로_판정한다() {
            List<Oracle> oracles = Oracle.forRecording(n플러스원_기록());

            assertThat(oracles).hasAtLeastOneElementOfType(Oracle.QueryRepeatNotWorse.class);
            // 시간을 보는 오라클은 «없다». 있으면 안 된다.
            assertThat(oracles).allSatisfy(o ->
                    assertThat(o.name()).doesNotContain("시간").doesNotContain("ms"));
        }
    }

    @Nested
    @DisplayName("🔴 남의 질의를 기준으로 삼지 않는다 — 2026-09-15 실측으로 잡은 결함")
    class 남의질의 {

        private final Oracle oracle = new Oracle.QueryRepeatNotWorse();

        /** 요청 «밖»의 질의(스키마 생성 · seed · 다른 요청)가 섞인 기록. */
        private Recording 남의질의가_섞인_기록() {
            List<Event> events = new ArrayList<>();
            events.add(요청(200, "{}", false));                       // corrId = r-1
            // 이 요청이 낸 것: 목록 1 + 회원 3 = N+1 이 3번 반복
            events.add(질의(2, "select * from orders"));
            for (int i = 0; i < 3; i++) {
                events.add(질의(3 + i, "select * from member where id = " + i));
            }
            // 🔴 요청 «밖»의 것 — seed 가 같은 모양을 10번 냈다. corrId 가 없다.
            for (int i = 0; i < 10; i++) {
                events.add(new Event.Sql(20 + i, null, T0, "t", 1L,
                        "insert into orders (product) values ('물건" + i + "')",
                        List.of(), null, null, false, null, null));
            }
            SqlShapes.Shape seed = SqlShapes.of("insert into orders (product) values ('x')");
            Summary summary = new Summary(60,
                    List.of(new Summary.SqlShape(seed.hash(), seed.normalized(), 10, 20)),
                    List.of(), null);
            return 기록(지연방아쇠(), events, summary);
        }

        @Test
        @DisplayName("🔴 요청 밖의 질의가 문턱을 올려서 «버그를 못 고쳤는데 통과»하면 안 된다")
        void 남의_질의가_문턱이_되면_안_된다() {
            // 재생도 똑같이 N+1 을 3번 냈다 — 즉 «안 고쳐졌다».
            List<String> 재생질의 = new ArrayList<>();
            재생질의.add("select * from orders");
            for (int i = 100; i < 103; i++) {
                재생질의.add("select * from member where id = " + i);
            }

            OracleVerdict v = oracle.judge(남의질의가_섞인_기록(),
                    ReplayObservation.builder().executedSql(재생질의).build());

            // 남의 질의(10번)를 기준으로 삼으면 3 < 10 이라 «통과»가 나온다.
            // 이 요청의 것(3번)을 기준으로 삼아야 «그대로»라는 것이 보인다.
            assertThat(v.outcome()).isNotEqualTo(OracleVerdict.Outcome.PASS);
        }

        @Test
        @DisplayName("이 요청이 낸 질의만 세면 반복이 늘었을 때 실패로 나온다")
        void 이_요청의_것만_센다() {
            List<String> 재생질의 = new ArrayList<>();
            재생질의.add("select * from orders");
            for (int i = 0; i < 7; i++) {          // 3번이었던 것이 7번으로 늘었다
                재생질의.add("select * from member where id = " + i);
            }

            OracleVerdict v = oracle.judge(남의질의가_섞인_기록(),
                    ReplayObservation.builder().executedSql(재생질의).build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.FAIL);
            assertThat(v.reason()).contains("7").contains("3");
        }

        @Test
        @DisplayName("🔴 어느 질의가 이 요청 것인지 «가려낼 수 없으면» 통과가 아니라 「판정 못 함」")
        void 가려낼_수_없으면_판정_못_함() {
            // 진입점에 상관 식별자가 없는 기록 — 어느 질의가 그 요청 것인지 알 수 없다.
            Event.HttpIn 식별자없는요청 = new Event.HttpIn(1, null, T0, "t", 120L,
                    "GET", "/api/orders", null, null, Map.of(),
                    null, false, 0, 200, "{}", false);
            Recording 기록 = 기록(지연방아쇠(), List.of(식별자없는요청, 질의(2, "select 1")), null);

            OracleVerdict v = oracle.judge(기록,
                    ReplayObservation.builder().executedSql(List.of("select 1")).build());

            assertThat(v.outcome()).isEqualTo(OracleVerdict.Outcome.NOT_JUDGED);
        }
    }
}
