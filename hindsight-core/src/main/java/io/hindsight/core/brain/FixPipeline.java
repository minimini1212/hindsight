package io.hindsight.core.brain;

import io.hindsight.core.guard.PatchVerdict;
import io.hindsight.core.replay.GeneratedTest;
import io.hindsight.core.replay.ReplayResult;
import io.hindsight.model.Recording;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 🔴 <b>기록 하나에서 PR 초안까지, 한 줄로 잇는 자리.</b>
 *
 * <pre>
 *   예산 확인 → 진단(LLM) → 경로 검사 → 적용 → 네 겹 채점 → PR 글
 *        ↑                                          │
 *        └──────────── 실패하면 «왜 실패했는지»를 들고 다시 ────┘
 * </pre>
 *
 * <h2>🔴 「왜 실패했는지」를 다시 알려 주는 것이 이 고리의 핵심이다</h2>
 * 그냥 세 번 물으면 <b>같은 답이 세 번 온다.</b> LLM 은 자기가 방금 뭘 틀렸는지 모른다 —
 * 우리가 말해 줘야 다음 시도가 «다른» 시도가 된다.
 *
 * <h2>🔴 이 클래스는 파일도 안 쓰고 테스트도 안 돌린다</h2>
 * 전부 {@link 수선공} 과 {@link Diagnosis} 로 받는다. 그래서 <b>고리의 판단</b>을
 * 네트워크도 저장소도 없이 전수로 시험한다 — 그리고 이 고리에서 틀리면
 * <b>남의 저장소에 잘못된 PR 이 올라간다.</b>
 *
 * <h2>⚠️ 여기서 PR 을 «올리지» 않는다</h2>
 * 만드는 것까지다. 올리는 것은 {@code core.ship} 이 하고, 그건 토큰이 있어야 한다.
 * 만드는 것과 올리는 것을 한 클래스에 두면 <b>「만들었는데 안 올렸다」를 시험할 수 없다.</b>
 */
public final class FixPipeline {

    /**
     * 패치 하나를 <b>실제로 적용해 보고 채점할 준비</b>를 해 주는 쪽.
     *
     * <p>🔴 <b>앱마다 다르다.</b> 파일을 쓰고, 다시 빌드하고, 테스트를 돌리는 방법은
     * 프로젝트마다 다르다. 그래서 밖에서 받는다.
     */
    public interface 수선공 {

        /**
         * @param 패치 경로 → 파일 «전체» 내용
         * @return 이 패치를 적용/되돌리고 테스트를 돌릴 수 있는 것.
         *         🔴 <b>경로 검사에 걸리면 {@code null}</b> — 그때는 아무것도 돌리지 않는다
         */
        VerificationLoop.Runner 준비한다(Map<String, String> 패치);

        /** 마지막 {@link #준비한다} 의 경로 판정. 🔴 왜 거절됐는지가 보고에 남아야 한다. */
        PatchVerdict 마지막_경로판정();
    }

    /**
     * 시도 한 번의 기록.
     *
     * @param 몇번째   1부터
     * @param 진단     LLM 이 뭐라고 했나
     * @param 채점     네 겹. 🔴 «안 돌렸으면» {@code null} — 「다 실패」가 아니다
     * @param 왜멈췄나 이 시도가 여기서 끝난 이유
     */
    public record 시도(int 몇번째, Diagnosis.결과 진단,
                     VerificationLoop.Result 채점, String 왜멈췄나) {

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("시도 ").append(몇번째).append(": ");
            sb.append(진단 == null ? "(진단 없음)" : 진단.describe());
            if (채점 != null) {
                sb.append(System.lineSeparator()).append("  ")
                        .append(채점.score().describe().replace(System.lineSeparator(),
                                System.lineSeparator() + "  ").trim());
            }
            if (왜멈췄나 != null) {
                sb.append(System.lineSeparator()).append("  → ").append(왜멈췄나);
            }
            return sb.toString();
        }
    }

    /**
     * @param 고쳤나   🔴 네 겹을 «전부» 통과했나
     * @param 초안     고쳤으면 PR 글. 못 고쳤으면 {@code null}
     * @param 시도들   한 번도 안 불렀어도 «비어 있지 않다» — 왜 안 불렀는지가 들어 있다
     * @param 왜       못 고쳤으면 그 이유. 🔴 「예산이 끝났다」와 「고치지 못했다」를 구별한다
     */
    public record 결과(boolean 고쳤나, PullRequestDraft 초안, List<시도> 시도들, String 왜) {

        public 결과 {
            시도들 = List.copyOf(시도들);
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(고쳤나 ? "✅ 고쳤다" : "🔴 못 고쳤다");
            if (왜 != null) {
                sb.append(" — ").append(왜);
            }
            sb.append(System.lineSeparator());
            시도들.forEach(a -> sb.append(a.describe()).append(System.lineSeparator()));
            return sb.toString();
        }
    }

    private final Diagnosis 진단기;
    private final VerificationLoop 채점기;

    public FixPipeline(Diagnosis 진단기) {
        this(진단기, new VerificationLoop());
    }

    public FixPipeline(Diagnosis 진단기, VerificationLoop 채점기) {
        this.진단기 = 진단기;
        this.채점기 = 채점기;
    }

    /**
     * @param 예산       🔴 이 판에서만 쓴다. 기록마다 새로 만든다
     * @param 원래내용   패치 «전» 파일 내용. 「증상만 덮는」 모양을 <b>새로 넣었는지</b> 보려면 필요하다
     * @param replay     이 기록의 재생 결과. {@code null} 이면 「안 봤다」이고 확신도가 내려간다
     */
    public 결과 돌린다(Recording recording,
                   Diagnosis.소스맥락 소스맥락,
                   AttemptBudget 예산,
                   수선공 수선공,
                   Map<String, String> 원래내용,
                   ReplayResult replay,
                   GeneratedTest 생성된테스트) {

        List<시도> 시도들 = new ArrayList<>();
        String 지난번_왜_안됐나 = null;

        for (int n = 1; ; n++) {
            // 🔴 예산부터. 「부르고 나서 세는」 구조면 상한이 상한이 아니다.
            //    ⚠️ 다음 호출 비용은 모른다 — 그래서 «모름»을 넘긴다. 예산이 그걸
            //    「상한만큼 쓸 수 있다」로 볼지 정한다.
            var 남은것 = 예산.한번_더_되나(0);
            if (!남은것.되나()) {
                시도들.add(new 시도(n, null, null, 남은것.왜()));
                return new 결과(false, null, 시도들,
                        // 🔴 「예산이 끝났다」를 「못 고쳤다」로 적지 않는다.
                        "여기까지만 해 보기로 했다 (" + 예산.describe() + ")");
            }

            Diagnosis.결과 진단 = 진단기.진단한다(recording, 맥락에_이유를_더한다(소스맥락, 지난번_왜_안됐나));

            if (!진단.불렀나()) {
                // 못 불렀으면 돈도 안 썼다. 다시 물어도 같은 결과이므로 여기서 끝낸다.
                시도들.add(new 시도(n, 진단, null, "LLM 을 못 불렀다"));
                return new 결과(false, null, 시도들, 진단.왜());
            }
            예산.한번_썼다(진단.예산에_적을_센트());

            if (!진단.패치를_받았나()) {
                지난번_왜_안됐나 = "지난번에는 패치 블록을 하나도 못 받았다. 형식(```path:…)을 지켜라.";
                시도들.add(new 시도(n, 진단, null, 지난번_왜_안됐나));
                continue;
            }

            VerificationLoop.Runner runner = 수선공.준비한다(진단.패치());
            if (runner == null) {
                PatchVerdict 판정 = 수선공.마지막_경로판정();
                지난번_왜_안됐나 = "지난번 패치는 «경로 때문에» 거절됐다: "
                        + (판정 == null ? "알 수 없음" : 판정.summary())
                        + " 고칠 수 있는 경로 안에서만 고쳐라.";
                // 🔴 적용도 안 했으므로 채점은 «안 돌린» 것이다. null 이 그 사실이다.
                시도들.add(new 시도(n, 진단, null, 지난번_왜_안됐나));
                continue;
            }

            VerificationLoop.Result 채점 = 채점기.run(원래내용, 진단.패치(),
                    replay == null ? null : replay.replay(), runner);
            시도들.add(new 시도(n, 진단, 채점, null));

            if (채점.score().allPassed()) {
                PullRequestDraft 초안 = PullRequestComposer.compose(
                        recording, replay, 채점, 생성된테스트, n);
                return new 결과(true, 초안, 시도들, null);
            }

            지난번_왜_안됐나 = 채점에서_배운것(채점);
        }
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    /**
     * 🔴 <b>지난번에 왜 안 됐는지를 다음 시도에 «들려 보낸다».</b>
     *
     * <p>안 그러면 같은 답이 세 번 온다. LLM 은 자기가 방금 뭘 틀렸는지 모른다.
     * 그리고 그러면 「세 번 시도했다」가 실은 「한 번을 세 번 반복했다」가 된다.
     */
    static Diagnosis.소스맥락 맥락에_이유를_더한다(Diagnosis.소스맥락 맥락, String 지난번_왜_안됐나) {
        if (지난번_왜_안됐나 == null) {
            return 맥락;
        }
        var 파일들 = new java.util.LinkedHashMap<>(맥락 == null ? Map.<String, String>of() : 맥락.파일들());
        // 🔴 파일처럼 끼워 넣는다. 프롬프트가 「고칠 수 있는 파일」을 통째로 싣는 모양이라,
        //    여기에 한 자리를 더하는 것이 가장 덜 놀라운 방법이다.
        파일들.put("__지난_시도가_실패한_이유.txt", 지난번_왜_안됐나);
        return new Diagnosis.소스맥락(파일들);
    }

    /** 네 겹 중 «어디서» 걸렸는지를 사람 말로. 이게 다음 시도의 유일한 새 정보다. */
    static String 채점에서_배운것(VerificationLoop.Result 채점) {
        var s = 채점.score();
        if (s.기준선_실패() == LayerOutcome.FAILED) {
            return "지난번 재생 테스트는 «패치 전»에도 통과했다. 그 테스트로는 이 버그를 못 잡는다 "
                    + "— 기록에 남은 증상(질의 반복 같은 것)을 다시 본다.";
        }
        if (s.검증_통과() == LayerOutcome.FAILED) {
            return "지난번 패치를 적용해도 재생 테스트가 «여전히 실패»했다. 증상이 그대로다.";
        }
        if (s.기존_테스트() == LayerOutcome.FAILED) {
            return "지난번 패치는 증상은 고쳤지만 «앱의 기존 테스트»를 깨뜨렸다. "
                    + "다른 동작을 바꾸지 않는 방법을 쓴다.";
        }
        if (s.되돌리기() == LayerOutcome.FAILED) {
            return "지난번 패치를 «되돌려도» 테스트가 통과했다. 그 테스트는 패치와 무관하게 "
                    + "통과하던 것이라, 고쳤다는 증거가 되지 못한다.";
        }
        return "지난번 시도는 네 겹 중 일부를 «안 돌렸다». " + String.join(", ", s.notRunLayers());
    }
}
