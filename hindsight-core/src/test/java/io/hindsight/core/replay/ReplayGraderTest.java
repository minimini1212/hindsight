package io.hindsight.core.replay;

import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;
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

@DisplayName("재생 등급 — 이 재생을 얼마나 믿을 수 있나")
class ReplayGraderTest {

    private static final Instant T0 = Instant.parse("2026-09-15T10:00:00Z");

    private static final ReplayInfo.StateRestore 충분히_되돌림 =
            new ReplayInfo.StateRestore(true, true, null, null);

    /** 🔴 데이터 계약이 「가장 위험한 기록」이라고 부르는 모양 — 행만 되돌린 것. */
    private static final ReplayInfo.StateRestore 행만_되돌림 =
            new ReplayInfo.StateRestore(true, false, null, null);

    private static Integrity 구멍없는_기록() {
        // 담으려던 60초 중 57초가 담겼다 — windowFellShort() 가 거짓이 되는 값.
        return new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false);
    }

    private static Recording 기록(Integrity integrity, List<Event> events, Summary summary) {
        return new Recording(Recording.CURRENT_SCHEMA_VERSION, "t1", T0,
                new Trigger(Trigger.Kind.LATENCY, T0, "GET /api/orders", null, 3400L, "k", 1),
                new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                events, summary, null, null, integrity);
    }

    private static Event.Sql 질의(long seq, String sql) {
        return new Event.Sql(seq, "r-1", T0, "t", 1L, sql, List.of(), null, null, false, null, null);
    }

    /** N+1 이 심긴 기록 — 목록 1번 + 회원 조회 20번. */
    private static Recording n플러스원_기록(Integrity integrity) {
        List<Event> events = new ArrayList<>();
        events.add(new Event.HttpIn(1, "r-1", T0, "t", 3400L, "GET", "/api/orders",
                null, null, Map.of(), null, false, 0, 200, "{\"orders\":[]}", false));
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
        return 기록(integrity, events, summary);
    }

    /**
     * 🔴 «고쳐진» 재생 — 모양은 그대로인데 반복이 사라졌다(캐시를 붙였다고 치자).
     *
     * <p>모양이 그대로라 갈라짐이 아니고, 반복이 줄었으므로 반복 오라클이 통과한다.
     * 조인으로 고치면 «새 모양»이 생겨서 DIVERGED 가 되는데, 그건 따로 검사한다.
     */
    private static List<String> 고쳐진_질의() {
        return List.of("select * from orders", "select * from member where id = 1");
    }

    /** 기록과 «똑같은» 재생 — 즉 아무것도 안 고친 것. */
    private static List<String> 기록과_같은_질의() {
        List<String> sqls = new ArrayList<>();
        sqls.add("select * from orders");
        for (int i = 0; i < 20; i++) {
            sqls.add("select * from member where id = " + i);
        }
        return sqls;
    }

    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("① 재생이 아예 안 돌았을 때")
    class 실패 {

        @Test
        @DisplayName("FAILED 이고, 무엇을 못 잡았는지는 «모른다»(null)")
        void 재생이_안_돌면_FAILED() {
            ReplayInfo info = ReplayGrader.grade(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.failed("앱이 안 떴다"), 충분히_되돌림, true);

            assertThat(info.grade()).isEqualTo(ReplayInfo.Grade.FAILED);
            // 🔴 [] 가 아니라 null 이다. 「보았고 없었다」가 아니라 「못 봤다」이므로.
            assertThat(info.missing()).isNull();
            assertThat(info.notes()).contains("앱이 안 떴다");
            assertThat(info.allowsAutoPullRequest()).isFalse();
        }
    }

    @Nested
    @DisplayName("🔴 ② 기록에 없는 질의를 만났을 때 — 제대로 고칠수록 여기 온다")
    class 갈라짐 {

        @Test
        @DisplayName("N+1 을 조인으로 고치면 DIVERGED 다 — 실패가 «아니다»")
        void 조인으로_고치면_DIVERGED() {
            ReplayInfo info = ReplayGrader.grade(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}")
                            .executedSql(List.of("select * from orders o join member m on o.member_id = m.id"))
                            .build(),
                    충분히_되돌림, true);

            assertThat(info.grade()).isEqualTo(ReplayInfo.Grade.DIVERGED);
            assertThat(info.diverged()).isNotNull();
            assertThat(info.diverged().kind()).isEqualTo("SQL_SHAPE_CHANGED");
        }

        @Test
        @DisplayName("🔴 「무엇이 무엇으로 바뀌었나」를 붙여 준다 — 이게 가장 좋은 PR 설명이 된다")
        void 무엇이_바뀌었는지를_붙여_준다() {
            ReplayInfo info = ReplayGrader.grade(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}")
                            .executedSql(List.of("select * from orders o join member m on o.member_id = m.id"))
                            .build(),
                    충분히_되돌림, true);

            ReplayInfo.Divergence d = info.diverged();
            assertThat(d.before().count()).isEqualTo(20);  // 회원 조회가 20번이었다
            assertThat(d.after().count()).isEqualTo(1);    // 이제 1번이다
            assertThat(d.verdict()).contains("20").contains("1").contains("N+1");
        }

        @Test
        @DisplayName("🔴 DIVERGED 는 자동 PR 로 안 간다 — 통과가 아니기 때문")
        void DIVERGED_는_자동_PR_이_안_된다() {
            ReplayInfo info = ReplayGrader.grade(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.builder()
                            .executedSql(List.of("select something totally new")).build(),
                    충분히_되돌림, true);

            assertThat(info.allowsAutoPullRequest()).isFalse();
        }

        @Test
        @DisplayName("🔴 질의를 «안 봤으면» 갈라졌다고도 말하지 않는다")
        void 질의를_안_봤으면_갈라짐이_아니다() {
            ReplayInfo info = ReplayGrader.grade(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.builder().responseStatus(200).build(),
                    충분히_되돌림, true);

            assertThat(info.grade()).isNotEqualTo(ReplayInfo.Grade.DIVERGED);
            assertThat(info.diverged()).isNull();
        }
    }

    @Nested
    @DisplayName("🔴 ③ 되돌리기가 모자랐을 때 — 그 차이는 패치 탓이 아니다")
    class 복원 {

        @Test
        @DisplayName("행만 되돌린 재생은 PARTIAL 이고 missing 에 STATE 가 있다")
        void 행만_되돌리면_PARTIAL() {
            ReplayInfo info = ReplayGrader.grade(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}")
                            .executedSql(기록과_같은_질의()).build(),
                    행만_되돌림, true);

            assertThat(info.grade()).isEqualTo(ReplayInfo.Grade.PARTIAL);
            assertThat(info.missing()).contains("STATE");
            assertThat(info.notes()).contains("부분 복원은 무복원보다 위험하다");
        }

        @Test
        @DisplayName("🔴 되돌렸는지 «안 봤으면»(null) 「되돌렸다」로 치지 않는다")
        void 안_봤으면_되돌린_것이_아니다() {
            ReplayInfo info = ReplayGrader.grade(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}")
                            .executedSql(기록과_같은_질의()).build(),
                    null, true);

            assertThat(info.grade()).isEqualTo(ReplayInfo.Grade.PARTIAL);
            assertThat(info.missing()).contains("STATE");
        }
    }

    @Nested
    @DisplayName("🔴 ④ 기록 자체에 구멍이 있었을 때")
    class 기록의_구멍 {

        @Test
        @DisplayName("큐가 차서 버린 이벤트가 있으면 PARTIAL — 버린 그것이 원인이었을 수 있다")
        void 버린_이벤트가_있으면_PARTIAL() {
            Integrity 구멍 = new Integrity(60, 57.0, 17, 0, 0, 1000, 0, false);

            ReplayInfo info = ReplayGrader.grade(n플러스원_기록(구멍),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}")
                            .executedSql(기록과_같은_질의()).build(),
                    충분히_되돌림, true);

            assertThat(info.grade()).isEqualTo(ReplayInfo.Grade.PARTIAL);
            assertThat(info.missing()).contains("EVENTS");
            assertThat(info.notes()).contains("17");
        }

        @Test
        @DisplayName("🔴 담으려던 시간보다 훨씬 짧게 담겼으면 PARTIAL")
        void 짧게_담겼으면_PARTIAL() {
            Integrity 짧음 = new Integrity(60, 4.2, 0, 0, 0, 1000, 0, false);

            ReplayInfo info = ReplayGrader.grade(n플러스원_기록(짧음),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}")
                            .executedSql(기록과_같은_질의()).build(),
                    충분히_되돌림, true);

            assertThat(info.missing()).contains("WINDOW");
            assertThat(info.notes()).contains("4.2");
        }

        @Test
        @DisplayName("계측이 스스로 꺼진 기록도 PARTIAL")
        void 계측이_꺼졌으면_PARTIAL() {
            Integrity 꺼짐 = new Integrity(60, 57.0, 0, 0, 0, 1000, 50, true);

            ReplayInfo info = ReplayGrader.grade(n플러스원_기록(꺼짐),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}")
                            .executedSql(기록과_같은_질의()).build(),
                    충분히_되돌림, true);

            assertThat(info.missing()).contains("INSTRUMENTATION");
        }
    }

    @Nested
    @DisplayName("⑤ 전부 맞았을 때")
    class 전부_맞음 {

        private ReplayInfo 온전한_재생(Boolean baselineFailed) {
            return ReplayGrader.grade(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}")
                            .executedSql(기록과_같은_질의()).build(),
                    충분히_되돌림, baselineFailed);
        }

        @Test
        @DisplayName("VERIFIED_DETERMINISTIC 이고 missing 은 «빈 목록»이다 (보았고 없었다)")
        void 전부_맞으면_VERIFIED() {
            ReplayInfo info = 온전한_재생(true);

            assertThat(info.grade()).isEqualTo(ReplayInfo.Grade.VERIFIED_DETERMINISTIC);
            assertThat(info.missing()).isEmpty();   // null 이 아니라 []
            assertThat(info.staleAfter()).isEqualTo(T0.plusSeconds(30L * 24 * 60 * 60));
        }

        @Test
        @DisplayName("🔴 패치 전에 실패했는지 «안 확인했으면» 자동 PR 이 안 된다")
        void 기준선을_안_봤으면_자동_PR_이_안_된다() {
            assertThat(온전한_재생(null).allowsAutoPullRequest()).isFalse();
            assertThat(온전한_재생(false).allowsAutoPullRequest()).isFalse();
            assertThat(온전한_재생(true).allowsAutoPullRequest()).isTrue();
        }
    }

    @Nested
    @DisplayName("🔴 등급과 판정을 «같이» 봐야 자동 PR 이 된다")
    class 자동PR {

        @Test
        @DisplayName("오라클이 하나라도 「판정 못 함」이면 자동 PR 이 안 된다")
        void 판정_못_한_것이_있으면_안_된다() {
            // 질의는 안 봤지만 응답은 맞은 상태 — 질의 오라클이 NOT_JUDGED 가 된다.
            ReplayResult result = ReplayResult.of(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}").build(),
                    충분히_되돌림, true);

            assertThat(result.replay().grade()).isEqualTo(ReplayInfo.Grade.VERIFIED_DETERMINISTIC);
            assertThat(result.verdicts())
                    .anyMatch(v -> v.outcome() == OracleVerdict.Outcome.NOT_JUDGED);
            // 🔴 등급은 최고인데도 자동 PR 은 안 된다.
            assertThat(result.allowsAutoPullRequest()).isFalse();
        }

        @Test
        @DisplayName("등급도 최고이고 오라클도 전부 통과하면 자동 PR 이 된다")
        void 둘_다_좋으면_자동_PR() {
            ReplayResult result = ReplayResult.of(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}")
                            .executedSql(고쳐진_질의()).build(),
                    충분히_되돌림, true);

            assertThat(result.allowsAutoPullRequest()).isTrue();
        }

        @Test
        @DisplayName("🔴 아무것도 안 고친 재생은 자동 PR 이 «안» 된다 — 반복이 그대로라서")
        void 안_고친_재생은_자동_PR_이_안_된다() {
            ReplayResult result = ReplayResult.of(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}")
                            .executedSql(기록과_같은_질의()).build(),
                    충분히_되돌림, true);

            assertThat(result.allowsAutoPullRequest()).isFalse();
            assertThat(result.verdicts())
                    .anyMatch(v -> v.outcome() == OracleVerdict.Outcome.FAIL);
        }

        @Test
        @DisplayName("🔴 요약 줄이 「판정 못 한 것」을 숨기지 않는다 — 안 보이면 통과로 읽힌다")
        void 요약이_판정_못_함을_숨기지_않는다() {
            ReplayResult result = ReplayResult.of(n플러스원_기록(구멍없는_기록()),
                    ReplayObservation.builder()
                            .responseStatus(200).responseBody("{\"orders\":[]}").build(),
                    충분히_되돌림, true);

            assertThat(result.summary()).contains("판정 못 한 것");
        }
    }
}
