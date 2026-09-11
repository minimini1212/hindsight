package io.hindsight.model;

import java.time.Instant;
import java.util.List;

/**
 * 이 기록을 얼마나 믿을 수 있나.
 *
 * <p>🔴 이 등급은 <b>주장이 아니라 실측</b>이다. 「어떤 계측이 걸렸나」로 미리 매기면
 * 캐시가 따뜻했는지, 정적 변수에 뭐가 쌓였는지를 알 수 없어 틀린 확신을 준다.
 * 그래서 <b>재생 중에도 같은 계측을 켜고</b> 재생이 만든 경계 호출을 기록과 대조한 결과를 적는다.
 *
 * @param missing        무엇을 못 잡았는지 이름. 🔴 빈 목록(「보았고 다 잡았다」)과
 *                       {@code null}(「대조를 아직 안 했다」)은 다른 사실이다.
 * @param baselineFailed 🔴 {@link #baselineFailed()} 설명 참조. {@code null} 이면 아직 안 돌려봤다.
 * @param stateRestore   🔴 재생 «전»에 상태를 어디까지 되돌렸나. {@code null} 이면 안 봤다.
 *                       되돌리지 않고 돌린 재생이 원본과 다른 것은 패치 탓이 아니다.
 * @param staleAfter     이 시각이 지나면 「낡았다」고 표시한다. 막지는 않는다.
 */
public record ReplayInfo(
        Grade grade,
        List<String> missing,
        Boolean baselineFailed,
        Divergence diverged,
        StateRestore stateRestore,
        Instant staleAfter,
        String notes
) {
    public enum Grade {
        /**
         * 재생이 만든 경계 호출이 기록과 <b>전부 맞았고 순서도 같았다</b>.
         *
         * <p>이름에 {@code VERIFIED} 가 붙은 이유: 이건 관찰이지 약속이 아니다.
         * 「결정론적이다」가 아니라 「이번에 대조해 보니 같았다」는 뜻이다.
         */
        VERIFIED_DETERMINISTIC,

        /**
         * 일부를 못 잡았다. {@link #missing()} 에 이름이 있다.
         *
         * <p>🔴 이걸 {@link #VERIFIED_DETERMINISTIC} 으로 접지 않는다.
         * 시각 호출을 못 잡은 기록은 <b>시각 호출이 없었던 기록과 다르다.</b>
         */
        PARTIAL,

        /**
         * 재생 중 <b>기록에 없는 경계</b>를 건드렸다.
         *
         * <p>실패도 통과도 아니다. 이 도구가 겨누는 대표 버그가 바로 이 상태를 만든다 —
         * N+1 을 조인으로 고치면 그 새 질의는 기록에 없다. 이걸 실패로 처리하면
         * <b>채점기가 얕은 패치를 구조적 수정보다 높게 친다.</b> 정확히 거꾸로다.
         *
         * <p>그래서 「무엇이 무엇으로 바뀌었나」를 붙여 사람에게 넘긴다. 설계 §6-2.
         */
        DIVERGED,

        /**
         * 재생 자체가 안 된다. 🔴 LLM 을 부르지 않는다.
         *
         * <p>생성된 테스트가 <b>패치 전 코드에서 실패하지 않는 경우</b>도 여기다 —
         * 그런 테스트는 채점을 못 한다.
         */
        FAILED
    }

    /**
     * 패치가 질의·호출의 모양을 바꿔서 기록으로는 채점할 수 없게 된 상황.
     *
     * @param verdict 진단이 말한 방향과 맞는지에 대한 판단.
     *                「질의 201 → 1」은 N+1 해소와 부합한다. 🔴 이건 <b>통과 판정이 아니다.</b>
     *                사람에게 넘길 때 붙이는 근거일 뿐이다.
     */
    public record Divergence(String kind, Side before, Side after, String verdict) {
        public record Side(String sqlHash, String normalized, Integer count) {}
    }

    /**
     * 🔴 재생을 돌리기 «전»에 상태를 어디까지 되돌렸나.
     *
     * <h2>왜 이게 필요한가</h2>
     * 재생은 요청을 다시 보내는 게 아니라 <b>기록 시점 상태로 되돌린 뒤</b> 다시 보내는 것이다.
     * 되돌리지 않으면 같은 요청이 다른 답을 낸다 — 그리고 그건 패치가 아니라 우리가 만든 차이다.
     * 그걸 「안 고쳐졌다」로 읽으면 아무 잘못 없는 패치가 벌을 받는다.
     *
     * <h2>🔴 {@code identityCounters} 가 왜 따로 있나</h2>
     * 행을 <b>같은 내용·같은 개수로</b> 복원해도, DB 가 들고 있는 「다음 id 는 몇 번」이라는
     * 숫자는 안 돌아간다. 그러면 행에 붙는 id 가 어긋나고, 기록에 담긴 {@code memberId: 1} 이
     * 가리키는 것이 사라져서 <b>재생이 예외로 죽는다.</b> 행을 되돌리는 것은 상태를 되돌리는 것이 아니다.
     *
     * <p>세 값은 서로 다른 사실이다. {@code true} 되돌렸다 / {@code false} 보았고 못 되돌렸다 /
     * {@code null} 🔴 <b>안 봤다.</b> 데이터 계약 §7-3.
     *
     * @param caches   앱 안에 쌓인 캐시. v0 범위 밖이라 보통 {@code null} 이다
     * @param external 외부 시스템. v1 부터
     */
    public record StateRestore(Boolean rows, Boolean identityCounters, Boolean caches, Boolean external) {

        /**
         * 채점을 걸어도 되는 최소 복원선.
         *
         * <p>🔴 {@code caches}·{@code external} 을 여기 넣지 않는 이유: v0 은 그 둘을 되돌릴
         * 수단이 아예 없다. 넣으면 모든 기록이 영원히 이 선을 못 넘어서, 조건이 검사가 아니라
         * 장식이 된다. 대신 못 되돌린 것은 {@code missing} 에 이름으로 남는다.
         *
         * <p>{@code null} 을 {@code true} 로 치지 않는다 — 「안 봤다」는 「되돌렸다」가 아니다.
         */
        public boolean restoredEnoughToGrade() {
            return Boolean.TRUE.equals(rows) && Boolean.TRUE.equals(identityCounters);
        }
    }

    /**
     * 이 기록으로 얻은 패치를 사람 손 없이 PR 로 올려도 되나.
     *
     * <p>🔴 자동 PR 은 <b>등급이 실측으로 확인됐고 기준선이 정말 실패했을 때만</b> 열린다.
     * 이 조건을 코드 한 곳에 모아 두는 이유는, 조건이 흩어지면 언젠가 한 곳이 느슨해지기 때문이다.
     * 느슨해진 그 한 곳이 잘못된 패치를 운영 저장소로 밀어 넣는다.
     */
    public boolean allowsAutoPullRequest() {
        return grade == Grade.VERIFIED_DETERMINISTIC
                && Boolean.TRUE.equals(baselineFailed)
                // 🔴 되돌리지 않은 자리에서 나온 「같았다」는 우연이다. 규율을 글로만 적으면
                //    언젠가 안 지켜지므로 여기서 막는다. stateRestore 가 null 이면 «안 봤다» 이고,
                //    안 본 것을 「괜찮았다」로 치는 것이 이 프로젝트가 겨누는 결함 그 자체다.
                && stateRestore != null && stateRestore.restoredEnoughToGrade();
    }
}
