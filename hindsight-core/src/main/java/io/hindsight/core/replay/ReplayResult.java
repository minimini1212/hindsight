package io.hindsight.core.replay;

import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;

import java.util.List;

/**
 * 재생 한 번의 결론 — <b>등급</b>과 <b>판정</b>을 같이 들고 있다.
 *
 * <p>둘은 다른 축이다. 등급은 「이 재생을 믿을 수 있나」, 판정은 「패치가 버그를 고쳤나」.
 * 🔴 <b>믿을 수 없는 재생에서 나온 「통과」는 통과가 아니므로</b>, 다음 단계로 넘어갈지는
 * 반드시 둘을 같이 보고 정한다.
 */
public record ReplayResult(ReplayInfo replay, List<OracleVerdict> verdicts) {

    public ReplayResult {
        verdicts = List.copyOf(verdicts);
    }

    /** 기록 하나와 관찰 하나로 등급과 판정을 전부 낸다. 🔴 순수 함수다. */
    public static ReplayResult of(Recording recording,
                                  ReplayObservation observation,
                                  ReplayInfo.StateRestore stateRestore,
                                  Boolean baselineFailed) {
        ReplayInfo info = ReplayGrader.grade(recording, observation, stateRestore, baselineFailed);
        List<OracleVerdict> verdicts = Oracle.forRecording(recording).stream()
                .map(oracle -> oracle.judge(recording, observation))
                .toList();
        return new ReplayResult(info, verdicts);
    }

    /**
     * 🔴 <b>사람 손 없이 PR 을 올려도 되나.</b>
     *
     * <p>세 가지가 «전부» 참이어야 한다.
     * <ol>
     *   <li>등급이 {@code VERIFIED_DETERMINISTIC} 이고, 패치 전에 정말 실패했고,
     *       되돌리기가 충분했다 ({@link ReplayInfo#allowsAutoPullRequest()})</li>
     *   <li>오라클이 <b>하나라도</b> 있었다 — 🔴 오라클이 0개면 아무것도 검사하지 않은 것이고,
     *       그건 「전부 통과」가 아니라 「테스트가 아니다」</li>
     *   <li>모든 오라클이 {@code PASS} 다 — 🔴 {@code NOT_JUDGED} 는 통과가 «아니다»</li>
     * </ol>
     */
    public boolean allowsAutoPullRequest() {
        if (replay == null || !replay.allowsAutoPullRequest()) {
            return false;
        }
        if (verdicts.isEmpty()) {
            return false;
        }
        return verdicts.stream().allMatch(OracleVerdict::isPass);
    }

    /** 사람이 읽는 한 줄 요약. PR 본문과 명령줄 출력에 그대로 쓴다. */
    public String summary() {
        String grade = replay == null ? "등급 없음" : replay.grade().name();
        long passed = verdicts.stream().filter(OracleVerdict::isPass).count();
        long notJudged = verdicts.stream()
                .filter(v -> v.outcome() == OracleVerdict.Outcome.NOT_JUDGED).count();

        StringBuilder line = new StringBuilder();
        line.append(grade).append(" · 오라클 ").append(passed).append('/').append(verdicts.size()).append(" 통과");
        if (notJudged > 0) {
            // 🔴 「판정 못 함」을 조용히 빼지 않는다. 안 보이면 통과로 읽힌다.
            line.append(" (판정 못 한 것 ").append(notJudged).append("개)");
        }
        if (replay != null && replay.missing() != null && !replay.missing().isEmpty()) {
            line.append(" · 못 잡은 것: ").append(String.join(", ", replay.missing()));
        }
        return line.toString();
    }
}
