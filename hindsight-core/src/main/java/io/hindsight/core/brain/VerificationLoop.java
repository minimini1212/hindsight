package io.hindsight.core.brain;

import io.hindsight.core.guard.PatchGuard;
import io.hindsight.core.guard.PatchVerdict;
import io.hindsight.model.ReplayInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 패치 하나를 <b>네 겹으로 채점</b>하고, 그 결과를 「무엇을 해도 되는가」로 바꾼다.
 *
 * <h2>🔴 순서가 규칙이다</h2>
 * <pre>
 *   0. 경로 검사      건드리면 안 되는 자리인가        ← 실패하면 «아무것도 안 돌린다»
 *   ㉠ 기준선 실패     패치 «전»에 정말 실패하나         ← 실패하면 «LLM 을 부르지 않는다»
 *   ㉡ 검증 통과       패치 «후»에 통과하나
 *   ㉢ 기존 테스트     원래 있던 테스트가 다 통과하나     ← 🔴 고리 «안»에서
 *   ㉣ 되돌리기       되돌리면 «다시 실패»하나
 * </pre>
 *
 * <h2>🔴 ㉠ 이 맨 앞인 이유</h2>
 * 생성된 테스트가 패치 전에도 통과하면, <b>패치 전에도 초록불이고 패치 후에도 초록불</b>이다.
 * 그런데 도구는 「통과했다」며 <b>아무것도 안 고친 패치로 PR 을 올린다.</b>
 * 채점기가 있다고 믿는 구조에서 이건 치명적이라, 여기서 멈추고 LLM 을 아예 부르지 않는다.
 *
 * <h2>🔴 안 돌린 겹을 「통과」로 치지 않는다</h2>
 * 앞 겹에서 끝나 버리면 뒤 겹은 {@link LayerOutcome#NOT_RUN} 으로 남는다.
 * {@code true} 로 채우면 <b>확인한 적 없는 것을 확인했다고 적는 것</b>이다.
 *
 * <h2>이 클래스는 무엇을 «안» 하나</h2>
 * 🔴 <b>LLM 을 부르지 않고, 파일도 안 쓴다.</b> 테스트를 어떻게 돌리는지도 모른다 —
 * 전부 {@link Runner} 로 받는다. 그래서 이 판단은 <b>JVM 을 계측 아래 띄우지 않고</b>
 * 전수로 확인된다. 판단과 입출력을 섞으면 그 순간 시험할 수 없는 코드가 된다.
 */
public final class VerificationLoop {

    /** 겹마다 「실제로 돌리는」 일. 앱마다 다르므로 밖에서 받는다. */
    public interface Runner {

        /** 생성된 재생 테스트를 «지금 코드»에 대고 돌린다. */
        boolean 재생_테스트가_통과하나();

        /** 앱이 원래 갖고 있던 테스트 전체를 돌린다. */
        boolean 기존_테스트가_전부_통과하나();

        /** 패치를 적용한다. 돌려주는 값은 「적용됐나」. */
        boolean 패치를_적용한다();

        /** 패치를 되돌린다. 돌려주는 값은 「되돌렸나」. */
        boolean 패치를_되돌린다();
    }

    private final PatchGuard guard;

    public VerificationLoop() {
        this(new PatchGuard());
    }

    public VerificationLoop(PatchGuard guard) {
        this.guard = guard;
    }

    /**
     * 채점 한 판의 결과.
     *
     * @param pathVerdict 경로 판정. {@code ALLOW} 가 아니면 채점을 «시작도 안 했다»
     * @param score       네 겹
     * @param confidence  그래서 무엇을 해도 되는가
     * @param symptomCovers 「증상만 덮는」 모양이 보였나
     */
    public record Result(
            PatchVerdict pathVerdict,
            FourLayerScore score,
            Confidence confidence,
            List<SymptomCover.Finding> symptomCovers
    ) {

        public Result {
            symptomCovers = List.copyOf(symptomCovers);
        }

        /** 사람이 읽는 전체 요약. PR 본문에 그대로 나간다. */
        public String describe() {
            StringBuilder text = new StringBuilder();
            text.append("확신도: ").append(confidence.korean()).append(" — ")
                    .append(confidence.action()).append('\n');
            text.append("경로: ").append(pathVerdict.summary()).append('\n');
            text.append(score.describe());
            if (!symptomCovers.isEmpty()) {
                text.append("⚠️ 증상만 덮는 모양이 보인다:\n");
                symptomCovers.forEach(f -> text.append("  · ").append(f.path())
                        .append("  ").append(f.pattern()).append(" — ").append(f.why()).append('\n'));
            }
            List<String> notRun = score.notRunLayers();
            if (!notRun.isEmpty()) {
                // 🔴 안 돌린 겹을 «항상» 보여 준다. 안 보이면 「확인됐다」로 읽힌다.
                text.append("🔴 안 돌린 겹: ").append(String.join(", ", notRun)).append('\n');
            }
            return text.toString();
        }
    }

    /**
     * @param before  경로 → 패치 «전» 내용. 「증상만 덮는」 모양을 <b>새로 넣었는지</b> 보려면 필요하다
     * @param after   경로 → 패치 «후» 내용
     * @param replay  이 기록의 재생 등급. 🔴 {@code null} 이면 「안 봤다」이고, 확신도가 내려간다
     */
    public Result run(Map<String, String> before, Map<String, String> after,
                      ReplayInfo replay, Runner runner) {

        List<String> notes = new ArrayList<>();

        // 0. 🔴 경로부터. 여기서 걸리면 아무것도 «돌리지 않는다» — 돌리는 것 자체가
        //    패치를 적용해 본다는 뜻이고, 그건 건드리면 안 되는 자리를 건드리는 것이다.
        PatchVerdict pathVerdict = guard.judge(after == null ? List.of() : List.copyOf(after.keySet()));
        if (pathVerdict.level() != PatchVerdict.Level.ALLOW) {
            notes.add("경로 검사에서 멈췄다. 네 겹을 하나도 돌리지 않았다");
            return new Result(pathVerdict,
                    FourLayerScore.of(LayerOutcome.NOT_RUN, LayerOutcome.NOT_RUN,
                            LayerOutcome.NOT_RUN, LayerOutcome.NOT_RUN, notes),
                    Confidence.LOW, List.of());
        }

        List<SymptomCover.Finding> covers = SymptomCover.scanAdded(before, after);

        // ㉠ 기준선 실패 — 패치 «전»에 정말 실패하나.
        boolean 기준선에서_통과해버림 = runner.재생_테스트가_통과하나();
        if (기준선에서_통과해버림) {
            // 🔴 여기서 멈춘다. 이 테스트는 버그를 못 살린 테스트이고,
            //    그걸로 「고쳤다」를 선언하면 아무것도 안 고친 패치가 PR 로 나간다.
            notes.add("🔴 생성된 테스트가 «패치 전»에도 통과한다. 버그를 못 살린 테스트다 — LLM 을 부르지 않는다");
            return new Result(pathVerdict,
                    FourLayerScore.of(LayerOutcome.FAILED, LayerOutcome.NOT_RUN,
                            LayerOutcome.NOT_RUN, LayerOutcome.NOT_RUN, notes),
                    Confidence.LOW, covers);
        }
        LayerOutcome 기준선 = LayerOutcome.PASSED;

        // ㉡ 검증 통과 — 패치를 적용하고 다시 돌린다.
        if (!runner.패치를_적용한다()) {
            notes.add("패치를 적용하지 못했다");
            return new Result(pathVerdict,
                    FourLayerScore.of(기준선, LayerOutcome.FAILED,
                            LayerOutcome.NOT_RUN, LayerOutcome.NOT_RUN, notes),
                    Confidence.LOW, covers);
        }
        LayerOutcome 검증 = runner.재생_테스트가_통과하나() ? LayerOutcome.PASSED : LayerOutcome.FAILED;
        if (검증 == LayerOutcome.FAILED) {
            notes.add("패치 후에도 재생 테스트가 실패한다 — 못 고쳤다");
            runner.패치를_되돌린다();
            return new Result(pathVerdict,
                    FourLayerScore.of(기준선, 검증, LayerOutcome.NOT_RUN, LayerOutcome.NOT_RUN, notes),
                    Confidence.LOW, covers);
        }

        // ㉢ 🔴 앱의 기존 테스트 — «고리 안»에서 돌린다.
        //    밖(PR 이후 CI)에 두면 프로젝트의 진짜 테스트를 한 번도 안 돌린 패치로 PR 이 나간다.
        LayerOutcome 기존 = runner.기존_테스트가_전부_통과하나() ? LayerOutcome.PASSED : LayerOutcome.FAILED;
        if (기존 == LayerOutcome.FAILED) {
            notes.add("🔴 하나 고치고 다른 것을 깨뜨렸다. 앱의 기존 테스트가 실패한다");
            runner.패치를_되돌린다();
            return new Result(pathVerdict,
                    FourLayerScore.of(기준선, 검증, 기존, LayerOutcome.NOT_RUN, notes),
                    Confidence.LOW, covers);
        }

        // ㉣ 되돌리기 — 되돌리면 «다시 실패»해야 한다.
        LayerOutcome 되돌리기;
        if (!runner.패치를_되돌린다()) {
            // 🔴 못 되돌렸으면 「통과」가 아니라 「안 돌렸다」다. 확인할 방법이 없었다.
            되돌리기 = LayerOutcome.NOT_RUN;
            notes.add("🔴 패치를 되돌리지 못해 ㉣ 을 확인하지 못했다");
        } else if (runner.재생_테스트가_통과하나()) {
            되돌리기 = LayerOutcome.FAILED;
            notes.add("🔴 패치를 되돌렸는데도 테스트가 통과한다. 이 테스트는 패치와 «무관»하게 통과하던 것이다");
        } else {
            되돌리기 = LayerOutcome.PASSED;
            runner.패치를_적용한다(); // 통과했으니 다시 붙여 둔다
        }

        // 🔴 확신도를 «먼저» 정한다. 그래야 「왜 내려갔나」가 notes 에 들어간다.
        //    순서를 뒤집으면 FourLayerScore 가 List.copyOf 로 목록을 굳혀 버려서,
        //    확신도를 정하며 적은 이유가 통째로 사라진다 — 보고서에 「중간」만 뜨고
        //    왜 중간인지가 없는 상태가 된다.
        boolean 전부통과 = 기준선.passed() && 검증.passed() && 기존.passed() && 되돌리기.passed();
        Confidence confidence = confidenceOf(전부통과, replay, covers, notes);

        return new Result(pathVerdict,
                FourLayerScore.of(기준선, 검증, 기존, 되돌리기, notes), confidence, covers);
    }

    /**
     * 네 겹 + 재생 등급 + 패치 모양 → 무엇을 해도 되는가.
     *
     * <p>🔴 <b>「높음」은 셋이 «전부» 맞아야 한다</b> — 네 겹 전부 통과 ·
     * 재생 등급이 {@code VERIFIED_DETERMINISTIC} · 증상 덮기 모양이 없음.
     * 하나라도 어긋나면 사람에게 간다.
     */
    private static Confidence confidenceOf(boolean 네겹_전부_통과, ReplayInfo replay,
                                           List<SymptomCover.Finding> covers, List<String> notes) {
        if (!네겹_전부_통과) {
            return Confidence.LOW;
        }
        if (replay == null) {
            // 🔴 재생 등급을 «안 봤다». 「괜찮았겠지」로 올리지 않는다.
            notes.add("재생 등급을 안 봤다. 자동으로 올리지 않는다");
            return Confidence.MEDIUM;
        }
        if (replay.grade() == ReplayInfo.Grade.DIVERGED) {
            notes.add("재생이 기록에 없는 경계를 건드렸다. 채점할 수 없으니 근거를 붙여 사람에게 넘긴다");
            return Confidence.MEDIUM;
        }
        if (replay.grade() != ReplayInfo.Grade.VERIFIED_DETERMINISTIC) {
            notes.add("재생 등급이 " + replay.grade() + " 다. 진단에 경고를 붙여 사람에게 넘긴다");
            return Confidence.MEDIUM;
        }
        if (!covers.isEmpty()) {
            notes.add("증상만 덮는 모양이 패치에 새로 들어갔다. 통과했더라도 사람이 본다");
            return Confidence.MEDIUM;
        }
        return Confidence.HIGH;
    }
}
