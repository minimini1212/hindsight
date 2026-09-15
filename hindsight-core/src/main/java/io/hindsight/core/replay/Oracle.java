package io.hindsight.core.replay;

import io.hindsight.model.Event;
import io.hindsight.model.Recording;
import io.hindsight.model.SqlShapes;
import io.hindsight.model.Summary;
import io.hindsight.model.Trigger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 재생 결과가 「맞았다」인지 「틀렸다」인지를 판정하는 기준.
 *
 * <h2>🔴 처음 설계에 이게 통째로 없었고, 그게 가장 큰 구멍이었다</h2>
 * 테스트는 <b>입력</b>과 <b>오라클</b>로 이루어진다. 입력을 아무리 완벽하게 재생해도
 * 「맞았다」를 무엇으로 판정할지가 없으면 <b>그건 테스트가 아니라 재실행</b>이다.
 *
 * <h2>방아쇠 종류마다 판정 기준이 다르다</h2>
 * <table>
 *   <tr><th>방아쇠</th><th>무엇으로 판정하나</th><th>왜</th></tr>
 *   <tr><td>예외</td><td>그 예외 타입이 안 나감 <b>＋</b> 응답 상태·본문이 기록과 같음</td>
 *       <td>🔴 「안 던진다」만으로는 {@code catch (Exception e) { return null; }} 로 통과된다</td></tr>
 *   <tr><td>지연·N+1</td><td>🔴 <b>같은 모양의 질의가 N번 이상 반복되면 실패</b></td>
 *       <td>🔴 시간으로 재면 안 된다 — 재생에서는 180ms 가 0ms 가 된다</td></tr>
 *   <tr><td>값이 틀림</td><td>응답 본문이 기록과 같음</td><td>—</td></tr>
 * </table>
 *
 * <h2>🔴 지연 버그를 「시간」이 아니라 「횟수」로 잡는 이유</h2>
 * N+1 은 <b>예외를 안 던진다.</b> 응답도 정상이다. 느려질 뿐이다. 그런데 재생 환경은
 * 진짜 운영 DB 가 아니라서 <b>180ms 짜리 지연이 0ms 가 된다</b> — 시간으로 재면 재생에서는
 * 버그가 사라진다. 실행 «횟수»는 그렇지 않고, 게다가 <b>완전히 결정론적</b>이다.
 *
 * <p>그리고 총 횟수가 아니라 <b>모양별 반복</b>을 본다. 총 횟수는 데이터가 늘면 같이 늘어서
 * 문턱이 자의적이 되는데, 「같은 질의가 여러 번」은 <b>데이터 양과 무관하게 결함</b>이다.
 *
 * <h2>⚠️ 이 테스트는 특성화 테스트다</h2>
 * 「원래 이랬다」를 굳히는 것이지 「이래야 한다」를 정하는 게 아니다.
 * 사양이 바뀌면 이 테스트는 <b>틀린 게 아니라 낡은 것</b>이다.
 */
public sealed interface Oracle {

    /** 사람이 읽는 이름. PR 본문과 보고서에 그대로 나간다. */
    String name();

    OracleVerdict judge(Recording recording, ReplayObservation observation);

    // ────────────────────────────────────────────────────────────────────────

    /**
     * 기록된 예외가 진입점 밖으로 <b>다시 나오지 않아야</b> 한다.
     *
     * <p>🔴 이것만으로는 부족해서 {@link ResponseMatches} 와 «함께» 쓴다.
     * 예외를 삼키고 {@code null} 을 돌려주는 패치가 이 기준 하나는 통과하기 때문이다.
     */
    record ExceptionGone() implements Oracle {

        @Override
        public String name() {
            return "기록된 예외가 다시 나오지 않는다";
        }

        @Override
        public OracleVerdict judge(Recording recording, ReplayObservation observation) {
            Trigger trigger = recording.trigger();
            if (trigger == null || trigger.exception() == null) {
                return OracleVerdict.notJudged(name(), "기록에 예외가 없다. 이 오라클로는 판정할 수 없다");
            }
            String expected = trigger.exception().type();

            if (observation.escaped() == null) {
                // 🔴 안 봤다. 「안 나왔다」로 치면 아무것도 확인하지 않고 통과시키는 것이다.
                return OracleVerdict.notJudged(name(), "예외가 나갔는지를 «안 봤다»");
            }
            return switch (observation.escaped()) {
                case ReplayObservation.EscapedException.None ignored ->
                        OracleVerdict.pass(name(), expected + " 가 더 이상 나오지 않는다");
                case ReplayObservation.EscapedException.Thrown thrown -> expected.equals(thrown.type())
                        ? OracleVerdict.fail(name(), "같은 예외가 그대로 나온다: " + thrown.type())
                        : OracleVerdict.fail(name(),
                                "다른 예외가 나온다. 기록은 " + expected + ", 재생은 " + thrown.type());
            };
        }
    }

    /**
     * 응답 상태와 본문이 기록과 <b>같아야</b> 한다.
     *
     * <p>🔴 상태 코드만 보면 안 된다. 예외를 삼키고 200 을 돌려주는 처리기 하나로 통과된다.
     * 본문이 있어야 「값이 맞나」를 물을 수 있다.
     */
    record ResponseMatches() implements Oracle {

        @Override
        public String name() {
            return "응답 상태와 본문이 기록과 같다";
        }

        @Override
        public OracleVerdict judge(Recording recording, ReplayObservation observation) {
            Event.HttpIn recorded = entryPointOf(recording);
            if (recorded == null) {
                return OracleVerdict.notJudged(name(), "기록에 들어온 요청이 없다");
            }
            if (observation.responseStatus() == null) {
                return OracleVerdict.notJudged(name(), "재생이 응답을 못 받았다");
            }
            if (!observation.responseStatus().equals(recorded.responseStatus())) {
                return OracleVerdict.fail(name(),
                        "상태가 다르다. 기록 " + recorded.responseStatus() + ", 재생 " + observation.responseStatus());
            }

            if (recorded.responseBody() == null) {
                // 🔴 기록 쪽에 본문이 없다. 「같다」고 칠 근거가 없다 — 상태만 맞은 것이다.
                return OracleVerdict.notJudged(name(),
                        "상태는 같지만 기록에 응답 본문이 «없다»(안 잡혔다). 본문은 판정하지 못했다");
            }
            if (observation.responseBody() == null) {
                return OracleVerdict.notJudged(name(), "상태는 같지만 재생의 응답 본문을 «안 봤다»");
            }
            if (recorded.responseBodyTruncated()) {
                // 잘린 본문끼리 비교하면 「앞부분만 같으면 통과」가 된다. 그건 판정이 아니다.
                return OracleVerdict.notJudged(name(),
                        "기록의 응답 본문이 «잘려» 있다. 잘린 것끼리 견주면 판정이 아니다");
            }
            return recorded.responseBody().equals(observation.responseBody())
                    ? OracleVerdict.pass(name(), "상태와 본문이 글자까지 같다")
                    : OracleVerdict.fail(name(), "상태는 같은데 응답 본문이 다르다");
        }
    }

    /**
     * 🔴 <b>같은 모양의 질의가 문턱 이상 반복되면 실패.</b> N+1 을 잡는 자리다.
     *
     * <p>문턱은 <b>기록에서 가져온다</b> — 기록 당시 가장 많이 반복된 모양의 횟수다.
     * 고정된 숫자를 쓰지 않는 이유: 어떤 앱은 3번이 정상이고 어떤 앱은 50번이 정상이라,
     * 고정값은 <b>거짓 경보 아니면 놓침</b> 중 하나가 된다. 기록이 「원래 이랬다」를 들고 있으므로
     * 그걸 기준으로 삼는다. 이게 특성화 테스트의 뜻이다.
     */
    record QueryRepeatNotWorse() implements Oracle {

        @Override
        public String name() {
            return "같은 모양의 질의가 기록보다 더 반복되지 않는다";
        }

        @Override
        public OracleVerdict judge(Recording recording, ReplayObservation observation) {
            if (observation.executedSql() == null) {
                return OracleVerdict.notJudged(name(), "재생이 낸 질의를 «안 봤다»");
            }
            int recordedWorst = worstRecordedRepeat(recording);
            if (recordedWorst < 0) {
                return OracleVerdict.notJudged(name(), "기록에 질의 모양이 «없다». 견줄 기준이 없다");
            }

            Map<String, Integer> replayed = countByShape(observation.executedSql());
            int replayedWorst = replayed.values().stream().mapToInt(Integer::intValue).max().orElse(0);

            if (replayedWorst > recordedWorst) {
                String worstShape = replayed.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey).orElse("?");
                return OracleVerdict.fail(name(),
                        "한 모양이 " + replayedWorst + "번 반복된다. 기록의 최대는 " + recordedWorst + "번이었다: " + worstShape);
            }
            return OracleVerdict.pass(name(),
                    "가장 많이 반복된 모양이 " + replayedWorst + "번. 기록의 " + recordedWorst + "번 이하다");
        }

        /** 기록에서 «한 모양»이 최대 몇 번 나왔나. 요약층이 없으면 전문층에서 센다. */
        private static int worstRecordedRepeat(Recording recording) {
            Summary summary = recording.summary();
            if (summary != null && summary.sqlShapes() != null && !summary.sqlShapes().isEmpty()) {
                return summary.sqlShapes().stream()
                        .mapToInt(Summary.SqlShape::count).max().orElse(-1);
            }
            if (recording.events() == null) {
                return -1;
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Event event : recording.events()) {
                if (event instanceof Event.Sql sql) {
                    counts.merge(SqlShapes.of(sql.sql()).hash(), 1, Integer::sum);
                }
            }
            return counts.isEmpty() ? -1 : counts.values().stream().mapToInt(Integer::intValue).max().orElse(-1);
        }

        private static Map<String, Integer> countByShape(List<String> sqls) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String sql : sqls) {
                counts.merge(SqlShapes.of(sql).normalized(), 1, Integer::sum);
            }
            return counts;
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    /** 기록의 진입점이 된 요청. 없으면 {@code null}. */
    static Event.HttpIn entryPointOf(Recording recording) {
        if (recording.events() == null) {
            return null;
        }
        return recording.events().stream()
                .filter(Event.HttpIn.class::isInstance)
                .map(Event.HttpIn.class::cast)
                .findFirst()
                .orElse(null);
    }

    /**
     * 이 기록을 무엇으로 판정할지 고른다.
     *
     * <p>🔴 방아쇠가 무엇이든 <b>응답 대조는 항상 낀다.</b> 예외 오라클만 쓰면
     * 예외를 삼키는 패치가 통과하기 때문이다.
     */
    static List<Oracle> forRecording(Recording recording) {
        Trigger trigger = recording.trigger();
        Trigger.Kind kind = trigger == null ? null : trigger.kind();

        if (kind == Trigger.Kind.EXCEPTION) {
            return List.of(new ExceptionGone(), new ResponseMatches(), new QueryRepeatNotWorse());
        }
        if (kind == Trigger.Kind.LATENCY) {
            // 🔴 지연은 시간이 아니라 «횟수»로 잡는다. 재생에서는 시간이 사라진다.
            return List.of(new QueryRepeatNotWorse(), new ResponseMatches());
        }
        return List.of(new ResponseMatches());
    }
}
