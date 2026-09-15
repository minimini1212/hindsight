package io.hindsight.core.replay;

import java.util.List;

/**
 * 재생을 한 번 돌려 보고 <b>실제로 관찰한 것</b>.
 *
 * <h2>🔴 「안 봤다」와 「보았고 없었다」를 필드마다 갈라 둔다</h2>
 * 이 자료 구조가 이 프로젝트에서 가장 틀리기 쉬운 자리다. 재생이 응답을 못 받았는데
 * 그걸 「응답 본문이 빈 문자열」로 적으면, 오라클(테스트가 「맞았다/틀렸다」를 판정하는 기준)이
 * <b>본문이 다르다고 «판정»한다.</b> 실제로는 판정할 자료가 없었던 것인데 말이다.
 * 그 순간 아무 잘못 없는 패치가 벌을 받는다.
 *
 * <p>그래서 규칙은 하나다. <b>{@code null} 은 「안 봤다」이고, 빈 값은 「보았고 없었다」다.</b>
 *
 * <table>
 *   <tr><th>필드</th><th>{@code null} 일 때</th><th>값이 있을 때</th></tr>
 *   <tr><td>{@code responseStatus}</td><td>응답을 못 받았다</td><td>그 상태 코드</td></tr>
 *   <tr><td>{@code responseBody}</td><td>본문을 «안 봤다»</td><td>{@code ""} 이면 「보았고 비어 있었다」</td></tr>
 *   <tr><td>{@code escaped}</td><td>예외가 나갔는지 «안 봤다»</td><td>{@link EscapedException.None} 이면 「보았고 안 나갔다」</td></tr>
 *   <tr><td>{@code executedSql}</td><td>질의를 «안 봤다»</td><td>{@code []} 이면 「보았고 한 건도 없었다」</td></tr>
 * </table>
 *
 * @param replayFailed 재생 자체가 성립하지 않았나. 🔴 이게 참이면 <b>LLM 을 부르지 않는다</b> —
 *                     재생이 안 되는 상태에서 나온 판정은 패치에 대한 판정이 아니다
 * @param failureReason 왜 성립하지 않았나. {@code replayFailed} 가 참일 때만 뜻이 있다
 */
public record ReplayObservation(
        Integer responseStatus,
        String responseBody,
        EscapedException escaped,
        List<String> executedSql,
        boolean replayFailed,
        String failureReason
) {

    /**
     * 진입점 밖으로 나간 예외.
     *
     * <p>🔴 {@code String} 하나로 두면 {@code null} 이 「안 나갔다」인지 「안 봤다」인지
     * 구별이 안 된다. 그 둘은 <b>정반대의 사실</b>이다 — 앞은 「버그가 고쳐졌다」의 근거이고
     * 뒤는 「판정할 수 없다」의 근거다.
     */
    public sealed interface EscapedException {

        /** 보았고, 아무것도 안 나갔다. */
        record None() implements EscapedException {}

        /** 보았고, 이게 나갔다. */
        record Thrown(String type, String message) implements EscapedException {}
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 재생이 아예 성립하지 않은 경우. */
    public static ReplayObservation failed(String reason) {
        return new ReplayObservation(null, null, null, null, true, reason);
    }

    public static final class Builder {
        private Integer responseStatus;
        private String responseBody;
        private EscapedException escaped;
        private List<String> executedSql;

        public Builder responseStatus(Integer v) { this.responseStatus = v; return this; }
        public Builder responseBody(String v) { this.responseBody = v; return this; }
        public Builder noExceptionEscaped() { this.escaped = new EscapedException.None(); return this; }
        public Builder exceptionEscaped(String type, String message) {
            this.escaped = new EscapedException.Thrown(type, message);
            return this;
        }
        public Builder executedSql(List<String> v) { this.executedSql = v == null ? null : List.copyOf(v); return this; }

        public ReplayObservation build() {
            return new ReplayObservation(responseStatus, responseBody, escaped, executedSql, false, null);
        }
    }
}
