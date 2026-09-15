package io.hindsight.core.cli;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("기록을 사람이 읽는 글로")
class CliRendererTest {

    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");

    private static final Integrity 온전함 = new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false);
    /** 🔴 담으려던 60초 중 4.2초만 담긴 기록 — 이 화면이 가장 크게 경고해야 하는 자리. */
    private static final Integrity 짧게담김 = new Integrity(60, 4.2, 0, 0, 0, 1000, 0, false);

    private static Recording 기록(String id, Integrity integrity, ReplayInfo replay, Summary summary) {
        return new Recording(1, id, T0,
                new Trigger(Trigger.Kind.LATENCY, T0, "GET /api/orders", null, 3412L, "k", 1),
                new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                List.of(new Event.HttpIn(1, "r-1", T0, "t", 3412L, "GET", "/api/orders",
                        null, null, Map.of(), null, false, 0, 200, "{\"orders\":[]}", false)),
                summary, null, replay, integrity);
    }

    private static Summary 요약(int 회원조회_횟수) {
        SqlShapes.Shape 목록 = SqlShapes.of("select * from orders");
        SqlShapes.Shape 회원 = SqlShapes.of("select * from member where id = 1");
        return new Summary(60,
                List.of(new Summary.SqlShape(목록.hash(), 목록.normalized(), 1, 5),
                        new Summary.SqlShape(회원.hash(), 회원.normalized(), 회원조회_횟수, 40)),
                List.of(), null);
    }

    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("🔴 담긴 시간이 짧으면 «눈에 띄게» 경고한다")
    class 짧게_담김 {

        @Test
        @DisplayName("목록에서 빨간 표시와 함께 몇 초인지 보인다")
        void 목록에_표시된다() {
            String 화면 = CliRenderer.renderList(List.of(기록("a1b2", 짧게담김, null, null)));

            assertThat(화면).contains("🔴");
            assertThat(화면).contains("4.2/60초");
        }

        @Test
        @DisplayName("🔴 목록 아래에 「그 앞에서 시작된 일은 없다」고 못 박는다")
        void 목록_아래에_경고가_붙는다() {
            String 화면 = CliRenderer.renderList(List.of(기록("a1b2", 짧게담김, null, null)));

            assertThat(화면).contains("훨씬 짧게");
            assertThat(화면).contains("그 앞에서 시작된 일은 그 기록에 없다");
        }

        @Test
        @DisplayName("🔴 자세히 보기에서는 「없었다고 결론 내리면 안 된다」까지 적는다")
        void 자세히에서_결론을_막는다() {
            String 화면 = CliRenderer.renderShow(기록("a1b2", 짧게담김, null, null));

            assertThat(화면).contains("4.2초만 담겼다");
            assertThat(화면).contains("「없었다」고 결론 내리면 안 된다");
        }

        @Test
        @DisplayName("온전한 기록에는 경고가 안 붙는다 — 늘 켜진 경고등은 꺼진 것과 같다")
        void 온전하면_경고가_없다() {
            String 화면 = CliRenderer.renderList(List.of(기록("a1b2", 온전함, null, null)));

            assertThat(화면).contains("✅");
            assertThat(화면).doesNotContain("훨씬 짧게");
        }
    }

    @Nested
    @DisplayName("🔴 「안 봤다」와 「보았고 없었다」를 다르게 적는다")
    class 모름 {

        @Test
        @DisplayName("재생한 적이 없으면 「재생이 실패했다」가 아니라 「없다」고 적는다")
        void 재생한_적이_없다() {
            String 화면 = CliRenderer.renderShow(기록("a1b2", 온전함, null, null));

            assertThat(화면).contains("아직 재생한 적이 «없다»");
            assertThat(화면).doesNotContain("FAILED");
        }

        @Test
        @DisplayName("🔴 되돌리기의 null 을 「아니오」로 적지 않는다")
        void 되돌리기의_null() {
            ReplayInfo replay = new ReplayInfo(ReplayInfo.Grade.VERIFIED_DETERMINISTIC, List.of(),
                    true, null, new ReplayInfo.StateRestore(true, true, null, null), null, null);

            String 화면 = CliRenderer.renderShow(기록("a1b2", 온전함, replay, null));

            assertThat(화면).contains("캐시 안 봤다");
            assertThat(화면).contains("외부 안 봤다");
        }

        @Test
        @DisplayName("🔴 질의 모양을 «안 봤을» 때와 「보았고 없었다」를 다르게 적는다")
        void 질의_모양의_모름() {
            assertThat(CliRenderer.renderShow(기록("a1b2", 온전함, null, null)))
                    .contains("── 질의 모양 (많이 반복된 순) ──\n  «안 봤다»");

            Summary 비어있음 = new Summary(60, List.of(), List.of(), null);
            assertThat(CliRenderer.renderShow(기록("a1b2", 온전함, null, 비어있음)))
                    .contains("보았고 하나도 없었다");
        }

        @Test
        @DisplayName("🔴 커넥션 풀을 안 봤다고 말한다 — 누수를 찾는 사람이 여기서 오해한다")
        void 커넥션_풀은_안_봤다() {
            String 화면 = CliRenderer.renderShow(기록("a1b2", 온전함, null, 요약(20)));

            assertThat(화면).contains("커넥션 풀은 «안 봤다»");
        }

        @Test
        @DisplayName("응답 본문을 못 잡았으면 「비었다」가 아니라 「못 잡았다」고 적는다")
        void 응답_본문을_못_잡음() {
            Recording 본문없음 = new Recording(1, "a1b2", T0,
                    new Trigger(Trigger.Kind.LATENCY, T0, "GET /x", null, 10L, "k", 1),
                    new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                    List.of(new Event.HttpIn(1, "r-1", T0, "t", 10L, "GET", "/x",
                            null, null, Map.of(), null, false, 0, 200, null, false)),
                    null, null, null, 온전함);

            assertThat(CliRenderer.renderShow(본문없음)).contains("응답 본문을 «못 잡았다»");
        }
    }

    @Nested
    @DisplayName("N+1 이 눈에 띈다")
    class 질의_반복 {

        @Test
        @DisplayName("🔴 한 모양이 10번 이상 반복되면 빨간 표시가 붙는다")
        void 많이_반복되면_빨간_표시() {
            String 화면 = CliRenderer.renderShow(기록("a1b2", 온전함, null, 요약(20)));

            assertThat(화면).contains("🔴   20번");
            assertThat(화면).contains("select * from member where id = ?");
        }

        @Test
        @DisplayName("많이 반복된 것이 «위»로 온다 — 스무 줄 아래에 있으면 못 본다")
        void 많이_반복된_것이_위로() {
            String 화면 = CliRenderer.renderShow(기록("a1b2", 온전함, null, 요약(20)));
            int 회원 = 화면.indexOf("member where id");
            int 목록 = 화면.indexOf("from orders");

            assertThat(회원).isLessThan(목록);
        }

        @Test
        @DisplayName("몇 번 안 되면 표시가 안 붙는다")
        void 적게_반복되면_표시가_없다() {
            String 화면 = CliRenderer.renderShow(기록("a1b2", 온전함, null, 요약(2)));

            assertThat(화면).doesNotContain("🔴    2번");
        }
    }

    @Nested
    @DisplayName("기록이 망가져 있을 때")
    class 망가진_기록 {

        @Test
        @DisplayName("방아쇠가 없으면 그렇게 말한다")
        void 방아쇠가_없다() {
            Recording 방아쇠없음 = new Recording(1, "a1b2", T0, null,
                    new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                    List.of(), null, null, null, 온전함);

            assertThat(CliRenderer.renderShow(방아쇠없음)).contains("방아쇠가 «없다»");
        }

        @Test
        @DisplayName("🔴 온전함이 안 적혀 있으면 「괜찮다」로 치지 않는다")
        void 온전함이_없으면_모른다고_한다() {
            Recording 온전함없음 = new Recording(1, "a1b2", T0, null,
                    new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                    List.of(), null, null, null, null);

            assertThat(CliRenderer.renderShow(온전함없음)).contains("«안 적혀 있다»");
            assertThat(CliRenderer.renderList(List.of(온전함없음))).contains("🔴 모름");
        }

        @Test
        @DisplayName("기록이 없으면 그렇게 말한다")
        void 기록이_없다() {
            assertThat(CliRenderer.renderList(List.of())).isEqualTo("기록이 없다.\n");
        }
    }

    @Nested
    @DisplayName("🔴 두 층이 다른 숫자를 말할 때 «틀린 것처럼» 보이지 않게 한다")
    class 두_층 {

        @Test
        @DisplayName("전문층보다 요약층 횟수가 많으면 「둘 다 맞다」고 설명한다")
        void 둘_다_맞다고_설명한다() {
            // 전문층에는 SQL 이 0건인데 요약층에는 20번이 세여 있다 — 밀려난 것이다.
            String 화면 = CliRenderer.renderShow(기록("a1b2", 온전함, null, 요약(20)));

            assertThat(화면).contains("둘 다 맞다");
            assertThat(화면).contains("재생에 쓸 수 있는 것은 «전문»뿐이다");
        }

        @Test
        @DisplayName("두 층이 어긋나지 않으면 설명이 안 붙는다 — 늘 뜨는 설명은 안 읽힌다")
        void 안_어긋나면_설명이_없다() {
            Summary 한건 = new Summary(60,
                    List.of(new Summary.SqlShape("h", "select 1", 1, 1)), List.of(), null);
            Recording 전문에도_하나 = new Recording(1, "a1b2", T0,
                    new Trigger(Trigger.Kind.LATENCY, T0, "GET /x", null, 10L, "k", 1),
                    new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                    List.of(new Event.Sql(1, "r-1", T0, "t", 1L, "select 1",
                            List.of(), null, null, false, null, null)),
                    한건, null, null, 온전함);

            assertThat(CliRenderer.renderShow(전문에도_하나)).doesNotContain("둘 다 맞다");
        }
    }
}
