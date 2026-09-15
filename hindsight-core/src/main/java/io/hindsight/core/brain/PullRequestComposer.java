package io.hindsight.core.brain;

import io.hindsight.core.replay.GeneratedTest;
import io.hindsight.core.replay.OracleVerdict;
import io.hindsight.core.replay.ReplayResult;
import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;
import io.hindsight.model.Trigger;

import java.util.List;

/**
 * 채점 결과를 <b>사람이 읽고 판단할 수 있는 PR</b> 로 바꾼다.
 *
 * <h2>🔴 증명한 것보다 크게 주장하지 않는다</h2>
 * 이게 이 클래스의 전부다. 「고쳤습니다」라고 적기는 쉽고, 그 한 줄이 사람의 주의를 끈다.
 * 그런데 <b>재생이 증명하는 것은 「이 상황이 고쳐졌다」 하나뿐</b>이다 —
 * 무엇이 더 깨졌는지도, 다른 상황에서도 되는지도 증명하지 않는다.
 *
 * <p>그래서 본문은 <b>항상 셋을 같이</b> 담는다.
 *
 * <table>
 *   <tr><td>무엇이 있었나</td><td>포획된 상황 · 방아쇠 · 진입점</td></tr>
 *   <tr><td>무엇을 확인했나</td><td>생성된 테스트와 <b>그 오라클</b> · 네 겹 · 재생 등급 · 시도 횟수</td></tr>
 *   <tr><td>🔴 무엇을 확인하지 «못했나»</td><td>단언 못 한 자리 · 안 돌린 겹 · 못 잡은 것</td></tr>
 * </table>
 *
 * <h2>🔴 제목이 확신도를 숨기지 않는다</h2>
 * 확신도가 「중간」인데 제목이 「고쳤습니다」면, <b>본문을 안 읽는 사람에게는 거짓말</b>이다.
 * 그리고 PR 제목은 목록에서 «본문 없이» 읽히는 자리다.
 *
 * <h2>이 클래스가 «안» 하는 것</h2>
 * 🔴 <b>PR 을 열지 않는다. 배포는 말할 것도 없다.</b> 글자만 만든다 —
 * 그래서 이 판단은 네트워크 없이 전수로 확인된다.
 */
public final class PullRequestComposer {

    private PullRequestComposer() {}

    /**
     * @param attempts LLM 에게 몇 번 시켰나. 🔴 <b>1 이 아니면 본문에 적는다</b> —
     *                 세 번 만에 나온 패치와 한 번에 나온 패치는 다른 사실이다
     */
    public static PullRequestDraft compose(Recording recording,
                                           ReplayResult replayResult,
                                           VerificationLoop.Result verification,
                                           GeneratedTest generatedTest,
                                           int attempts) {

        Confidence confidence = verification.confidence();
        String branchName = "hindsight/" + (recording.id() == null ? "unknown" : recording.id());

        StringBuilder body = new StringBuilder();

        // ── 무엇이 있었나 ───────────────────────────────────────────────────
        body.append("## 무엇이 있었나\n\n");
        Trigger trigger = recording.trigger();
        if (trigger == null) {
            body.append("🔴 이 기록에는 방아쇠가 «없다». 무엇이 이 기록을 만들었는지 모른다.\n\n");
        } else {
            body.append("`").append(trigger.entryPoint()).append("` 에서 ")
                    .append(설명(trigger)).append(".\n\n");
            if (trigger.dedupCount() > 1) {
                // 🔴 「한 번 났다」와 「1,204번 중 하나다」는 완전히 다른 사실이다.
                body.append("⚠️ 같은 사고가 **").append(trigger.dedupCount())
                        .append("번** 났다. 이 기록은 그중 첫 번째다.\n\n");
            }
        }

        // ── 무엇을 확인했나 ─────────────────────────────────────────────────
        body.append("## 무엇을 확인했나\n\n");

        if (generatedTest != null) {
            body.append("### 생성된 테스트가 검사하는 것\n\n");
            if (generatedTest.oracles().isEmpty()) {
                body.append("🔴 **아무것도 검사하지 못했다.** 기록에 단언할 값이 없었다.\n\n");
            } else {
                generatedTest.oracles().forEach(o -> body.append("- ✅ ").append(o).append('\n'));
                body.append('\n');
            }
        }

        body.append("### 과적합을 막는 네 겹\n\n```\n")
                .append(verification.score().describe())
                .append("```\n\n");

        if (replayResult != null) {
            body.append("### 재생\n\n");
            // summary() 가 이미 등급으로 시작한다. 앞에 또 붙이면 「DIVERGED · DIVERGED」가 된다.
            body.append(replayResult.summary()).append("\n\n");
            replayResult.verdicts().forEach(v ->
                    body.append("- ").append(표시(v.outcome())).append(' ')
                            .append(v.oracle()).append(" — ").append(v.reason()).append('\n'));
            body.append('\n');
            appendDivergence(body, replayResult.replay());
        }

        if (attempts != 1) {
            body.append("시도 횟수: **").append(attempts).append("번**");
            if (attempts > 1) {
                body.append(" — 한 번에 안 나왔다는 뜻이다");
            }
            body.append("\n\n");
        }

        // ── 🔴 무엇을 확인하지 못했나 ───────────────────────────────────────
        body.append("## 🔴 무엇을 확인하지 «못했나»\n\n");
        List<String> 못한것 = 못한것을_모은다(recording, verification, replayResult, generatedTest);
        못한것.forEach(line -> body.append("- ⬜ ").append(line).append('\n'));
        body.append('\n');

        if (!verification.symptomCovers().isEmpty()) {
            body.append("⚠️ **증상만 덮는 모양이 패치에 새로 들어갔다.** 통과했더라도 사람이 본다.\n\n");
            verification.symptomCovers().forEach(f ->
                    body.append("- `").append(f.path()).append("` 의 `").append(f.pattern())
                            .append("` — ").append(f.why()).append('\n'));
            body.append('\n');
        }

        // ── 🔴 배포는 하지 않는다 ───────────────────────────────────────────
        body.append("---\n\n");
        body.append("🔴 **이 도구는 배포하지 않는다.** 재생이 증명하는 것은 「이 상황이 고쳐졌다」 하나뿐이고,\n");
        body.append("무엇이 더 깨졌는지는 증명하지 않는다 — 재생은 경계 «안쪽»만 보기 때문이다.\n");
        body.append("나머지는 CI 와 사람이 정한다.\n");

        return new PullRequestDraft(branchName, 제목(recording, confidence, trigger), body.toString(),
                confidence, confidence.allowsAutomaticPullRequest());
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    /**
     * 🔴 제목이 확신도를 <b>숨기지 않는다.</b>
     *
     * <p>PR 제목은 목록에서 «본문 없이» 읽히는 자리다. 확신도가 「중간」인데 제목이
     * 「고쳤습니다」면, 본문을 안 읽는 사람에게는 <b>거짓말</b>이 된다.
     */
    private static String 제목(Recording recording, Confidence confidence, Trigger trigger) {
        String where = trigger == null || trigger.entryPoint() == null
                ? "기록 " + recording.id() : trigger.entryPoint();

        return switch (confidence) {
            case HIGH -> "fix(hindsight): " + where + " 의 사고를 고쳤다 — 재생으로 확인함";
            case MEDIUM -> "🟡 [초안] " + where + " — 진단과 재현 테스트. 패치는 사람이 본다";
            case LOW -> "🔴 [사람 필요] " + where + " — 자동으로 못 고쳤다. 기록과 테스트만 남긴다";
        };
    }

    private static String 설명(Trigger trigger) {
        if (trigger.exception() != null) {
            return "`" + trigger.exception().type() + "` 이 진입점 밖으로 나갔다";
        }
        if (trigger.latencyMs() != null) {
            return trigger.latencyMs() + "ms 가 걸렸다";
        }
        return trigger.kind() + " 방아쇠가 걸렸다";
    }

    private static String 표시(OracleVerdict.Outcome outcome) {
        return switch (outcome) {
            case PASS -> "✅";
            case FAIL -> "🔴";
            case DIVERGED -> "🔀";
            case NOT_JUDGED -> "⬜";
        };
    }

    private static void appendDivergence(StringBuilder body, ReplayInfo replay) {
        ReplayInfo.Divergence diverged = replay.diverged();
        if (diverged == null) {
            return;
        }
        // 🔴 여기가 이 PR 에서 가장 값나가는 부분이 될 수 있다.
        //    「채점할 수 없다」가 아니라 「무엇이 무엇으로 바뀌었는지」를 보여 주는 자리다.
        body.append("### 🔀 재생으로는 채점할 수 없다 — 질의 모양이 바뀌었다\n\n");
        body.append("제대로 된 수정일수록 여기로 온다. N+1 을 조인으로 고치면 그 새 질의는 «기록에 없다».\n\n");
        body.append("```\n");
        if (diverged.before() != null) {
            body.append("before  ").append(diverged.before().normalized())
                    .append("   ").append(diverged.before().count()).append("번\n");
        }
        if (diverged.after() != null) {
            body.append("after   ").append(diverged.after().normalized())
                    .append("   ").append(diverged.after().count()).append("번\n");
        }
        body.append("```\n\n");
        if (diverged.verdict() != null) {
            body.append(diverged.verdict()).append("\n\n");
        }
    }

    /**
     * 🔴 <b>이 목록은 비지 않는다.</b>
     *
     * <p>확인 못 한 것이 하나도 없어 보이면, 그때는 <b>「재생이 증명하지 않는 것」</b>을 적는다.
     * 아무 말이 없으면 읽는 사람은 「전부 확인됐다」로 읽는다 — 그리고 그건 사실이 아니다.
     */
    /**
     * 기록이 「못 담았다」고 스스로 적은 것을 PR 에 옮긴다.
     *
     * <p>🔴 <b>재생 등급과 다른 축이다.</b> 등급이 {@code DIVERGED} 면 {@code PARTIAL} 경로를
     * 안 타서, 「이벤트 17건을 버렸다」 같은 사실이 <b>통째로 빠진다.</b> 버려진 그것이
     * 사고의 원인이었을 수 있으므로 읽는 사람은 그걸 알아야 한다.
     */
    private static void 기록의_구멍을_적는다(Recording recording, List<String> lines) {
        io.hindsight.model.Integrity integrity = recording.integrity();
        if (integrity == null) {
            lines.add("기록이 「무엇을 못 담았나」를 **안 적었다**. 온전한지 알 수 없다");
            return;
        }
        if (integrity.droppedEvents() > 0) {
            lines.add("기록할 때 큐가 차서 **" + integrity.droppedEvents()
                    + "건을 못 받았다**. 그중에 원인이 있었을 수 있다");
        }
        if (integrity.evictedEvents() > 0) {
            lines.add("버퍼가 넘쳐 **" + integrity.evictedEvents() + "건이 밀려났다**");
        }
        if (integrity.instrumentationDisabled()) {
            lines.add("기록 중 **계측이 스스로 꺼졌다**");
        }
        if (integrity.windowFellShort()) {
            lines.add("담으려던 " + integrity.windowRequestedSeconds() + "초 중 실제로는 **"
                    + String.format("%.1f", integrity.windowActualSeconds())
                    + "초만 담겼다** — 그 앞에서 시작된 일은 이 기록에 없다");
        }
    }

    private static List<String> 못한것을_모은다(Recording recording,
                                        VerificationLoop.Result verification,
                                        ReplayResult replayResult,
                                        GeneratedTest generatedTest) {
        List<String> lines = new java.util.ArrayList<>();

        verification.score().notRunLayers().forEach(layer ->
                lines.add("**" + layer + "** 을 안 돌렸다. 「통과」가 아니라 «확인되지 않음»이다"));

        // 🔴 기록 «자체»의 구멍을 반드시 적는다. 재생 등급과는 «다른 축»이다 —
        //    등급이 DIVERGED 면 PARTIAL 경로를 안 타서 이 사실이 통째로 빠진다.
        기록의_구멍을_적는다(recording, lines);

        if (generatedTest != null) {
            generatedTest.notAsserted().forEach(n ->
                    lines.add("생성된 테스트가 단언하지 못한 것: " + n));
        }

        if (replayResult != null) {
            ReplayInfo replay = replayResult.replay();
            if (replay.missing() == null) {
                lines.add("재생이 무엇을 못 잡았는지 **모른다** (`missing` 이 `null` 이다)");
            } else {
                replay.missing().forEach(m -> lines.add("재생이 못 잡은 것: " + m));
            }
            replayResult.verdicts().stream()
                    .filter(v -> v.outcome() == OracleVerdict.Outcome.NOT_JUDGED)
                    .forEach(v -> lines.add("판정하지 못한 오라클: " + v.oracle() + " — " + v.reason()));
        } else {
            lines.add("재생을 **안 했다**. 이 패치가 그 상황을 고쳤는지 확인된 바가 없다");
        }

        if (lines.isEmpty()) {
            // 🔴 비어 있으면 「전부 확인됐다」로 읽힌다. 재생이 원래 증명하지 않는 것을 적는다.
            lines.add("**재생은 이 상황 하나만 본다.** 다른 상황에서도 되는지, 무엇이 더 깨졌는지는 "
                    + "증명하지 않는다 — 경계 «안쪽»만 보기 때문이다");
            lines.add("**앱 안에 쌓인 상태**(캐시 · 정적 변수 · 세션)는 되돌리지 않았다");
        }
        return List.copyOf(lines);
    }
}
