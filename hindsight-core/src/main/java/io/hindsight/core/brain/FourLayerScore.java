package io.hindsight.core.brain;

import java.util.ArrayList;
import java.util.List;

/**
 * 과적합을 막는 네 겹의 결과.
 *
 * <h2>화이트리스트는 채점표를 «지킬» 뿐, 채점을 «옳게» 만들지 않는다</h2>
 * 패치가 테스트를 못 고치게 막아 놔도, 테스트 자체가 버그를 못 살리면 아무 의미가 없다.
 * 그 구멍을 막는 것이 이 넷이다.
 *
 * <table>
 *   <tr><th>겹</th><th>무엇을 확인하나</th><th>무엇을 막나</th></tr>
 *   <tr><td>㉠ <b>기준선 실패</b></td><td>생성된 테스트가 <b>패치 전 코드에서 정말 실패</b>하나</td>
 *       <td>애초에 버그를 못 살린 테스트로 「고쳤다」고 선언하는 것</td></tr>
 *   <tr><td>㉡ 검증 통과</td><td>패치 후 통과하나</td><td>—</td></tr>
 *   <tr><td>㉢ 🔴 <b>앱의 기존 테스트 전체</b></td><td>원래 있던 테스트가 다 통과하나</td>
 *       <td>하나 고치고 마흔 개 깨뜨리는 패치</td></tr>
 *   <tr><td>㉣ 되돌리기</td><td>패치를 되돌리면 <b>다시 실패</b>하나</td>
 *       <td>패치와 무관하게 통과하던 헛된 테스트</td></tr>
 * </table>
 *
 * <h2>🔴 ㉢ 을 고리 «밖»에 두면 안 된다</h2>
 * 처음 설계는 앱의 기존 테스트를 「PR 이후 CI 에서 돌린다」로 뒀다. 그러면
 * <b>프로젝트의 진짜 테스트를 한 번도 안 돌린 패치로 PR 이 자동 생성된다.</b>
 * CI 가 빨간불을 켜는 것은 그 PR 이 이미 올라간 «뒤»다.
 *
 * <h2>🔴 ㉣ 이 왜 필요한가</h2>
 * ㉠~㉢ 이 다 통과해도, 그 테스트가 <b>패치와 아무 상관 없이</b> 통과하던 것일 수 있다.
 * 패치를 되돌렸는데도 여전히 통과하면, 그 패치가 무엇을 고쳤는지 아무도 모른다.
 */
public record FourLayerScore(
        LayerOutcome 기준선_실패,
        LayerOutcome 검증_통과,
        LayerOutcome 기존_테스트,
        LayerOutcome 되돌리기,
        List<String> notes
) {

    public FourLayerScore {
        notes = List.copyOf(notes);
    }

    /** 🔴 네 겹이 «전부» 통과했나. {@code NOT_RUN} 은 통과가 아니다. */
    public boolean allPassed() {
        return 기준선_실패.passed() && 검증_통과.passed()
                && 기존_테스트.passed() && 되돌리기.passed();
    }

    /** 안 돌린 겹의 이름. 비어 있지 않으면 «확인되지 않은» 것이 있다는 뜻이다. */
    public List<String> notRunLayers() {
        List<String> names = new ArrayList<>();
        if (기준선_실패 == LayerOutcome.NOT_RUN) {
            names.add("㉠ 기준선 실패");
        }
        if (검증_통과 == LayerOutcome.NOT_RUN) {
            names.add("㉡ 검증 통과");
        }
        if (기존_테스트 == LayerOutcome.NOT_RUN) {
            names.add("㉢ 앱의 기존 테스트");
        }
        if (되돌리기 == LayerOutcome.NOT_RUN) {
            names.add("㉣ 되돌리기");
        }
        return List.copyOf(names);
    }

    /**
     * 사람이 읽는 표. PR 본문에 그대로 나간다.
     *
     * <p>🔴 <b>안 돌린 겹을 «빼지» 않는다.</b> 통과한 것만 보여 주면 읽는 사람은
     * 그게 전부라고 믿는다 — 안 보이는 것은 「확인됐다」로 읽힌다.
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("㉠ 기준선 실패      ").append(기준선_실패.korean()).append('\n');
        text.append("㉡ 검증 통과        ").append(검증_통과.korean()).append('\n');
        text.append("㉢ 앱의 기존 테스트  ").append(기존_테스트.korean()).append('\n');
        text.append("㉣ 되돌리기         ").append(되돌리기.korean()).append('\n');
        if (!notes.isEmpty()) {
            notes.forEach(note -> text.append("  · ").append(note).append('\n'));
        }
        return text.toString();
    }

    static FourLayerScore of(LayerOutcome 기준선, LayerOutcome 검증,
                             LayerOutcome 기존, LayerOutcome 되돌리기, List<String> notes) {
        return new FourLayerScore(기준선, 검증, 기존, 되돌리기, notes);
    }
}
