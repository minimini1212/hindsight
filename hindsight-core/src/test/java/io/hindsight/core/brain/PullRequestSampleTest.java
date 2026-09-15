package io.hindsight.core.brain;

import io.hindsight.core.replay.OracleVerdict;
import io.hindsight.core.replay.ReplayResult;
import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;
import io.hindsight.model.Trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만들어진 PR 을 <b>사람이 읽는 모양 그대로</b> 찍어 본다.
 *
 * <p>🔴 조각조각 단언하는 검사만 있으면, 각 조각은 맞는데 «합쳐 놓으면 읽을 수 없는» 글이
 * 나올 수 있다. 이 시험은 그걸 보려고 통째로 출력한다 — 단언은 가볍고, 값은 출력에 있다.
 */
@DisplayName("만들어진 PR 을 통째로 찍어 본다")
class PullRequestSampleTest {

    @Test
    @DisplayName("N+1 을 조인으로 고쳐 갈라진 경우의 PR")
    void 갈라진_경우의_PR() {
        Instant t0 = Instant.parse("2026-09-16T10:00:00Z");
        Trigger trigger = new Trigger(Trigger.Kind.LATENCY, t0, "GET /api/orders",
                null, 3412L, "k", 1204);
        Recording recording = new Recording(1, "a1b2", t0, trigger,
                new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                List.of(new Event.HttpIn(1, "r-1", t0, "t", 3412L, "GET", "/api/orders",
                        null, null, Map.of(), null, false, 0, 200, "{\"orders\":[]}", false)),
                null, null, null, new Integrity(60, 4.2, 17, 0, 0, 1000, 0, false));

        ReplayInfo diverged = new ReplayInfo(ReplayInfo.Grade.DIVERGED, null, true,
                new ReplayInfo.Divergence("SQL_SHAPE_CHANGED",
                        new ReplayInfo.Divergence.Side("9f2a", "select * from member where id = ?", 20),
                        new ReplayInfo.Divergence.Side("1b3c",
                                "select o.*, m.* from orders o join member m on o.member_id = m.id", 1),
                        "질의 20개가 1개로 줄었다. N+1 해소와 부합한다"),
                new ReplayInfo.StateRestore(true, true, null, null), null, null);

        VerificationLoop.Result 채점 = new VerificationLoop().run(Map.of(),
                Map.of("src/main/java/a/OrderRepository.java", "interface OrderRepository {}"),
                diverged, new VerificationLoop.Runner() {
                    private boolean 붙었나 = false;
                    @Override public boolean 재생_테스트가_통과하나() { return 붙었나; }
                    @Override public boolean 기존_테스트가_전부_통과하나() { return true; }
                    @Override public boolean 패치를_적용한다() { 붙었나 = true; return true; }
                    @Override public boolean 패치를_되돌린다() { 붙었나 = false; return true; }
                });

        var draft = PullRequestComposer.compose(recording,
                new ReplayResult(diverged, List.of(
                        OracleVerdict.pass("응답 상태와 본문이 기록과 같다", "상태와 본문이 글자까지 같다"),
                        OracleVerdict.notJudged("같은 모양의 질의가 기록만큼 반복되지 않는다",
                                "재생이 낸 질의를 안 봤다"))),
                채점, null, 2);

        System.out.println();
        System.out.println("════════ 제목 ════════");
        System.out.println(draft.title());
        System.out.println("════════ 본문 ════════");
        System.out.println(draft.body());
        System.out.println("══════════════════════");
        System.out.println("자동으로 여나: " + draft.opensAutomatically());

        // 🔴 눈으로 읽다가 찾은 둘을 검사로 못 박는다. 조각조각 단언하는 검사만 있으면
        //    각 조각은 맞는데 «합쳐 놓으면 틀린» 글이 나온다.

        // ① 등급이 두 번 찍히지 않는다 (「DIVERGED · DIVERGED」였다)
        assertThat(draft.body()).doesNotContain("DIVERGED · DIVERGED");

        // ② 🔴 기록이 이벤트 17건을 버렸다는 사실이 «반드시» 보인다.
        //    등급이 DIVERGED 면 PARTIAL 경로를 안 타서 이 사실이 통째로 빠지고 있었다 —
        //    버려진 그것이 사고의 원인이었을 수 있는데 읽는 사람은 모른 채 머지한다.
        assertThat(draft.body()).contains("17건을 못 받았다");
        assertThat(draft.body()).contains("4.2초만 담겼다");
    }
}
