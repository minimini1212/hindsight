package io.hindsight.recorder;

import io.hindsight.core.replay.ReplayObservation;
import io.hindsight.model.Event;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 재생하는 동안 <b>무엇이 경계 밖으로 나갔는지</b>를 본다.
 *
 * <h2>🔴 재생 중에도 «같은» 계측을 켠다</h2>
 * 처음 설계는 「어떤 계측이 걸렸나」로 등급을 <b>미리</b> 매겼다. 그런데 캐시가 따뜻했는지,
 * 정적 변수에 무엇이 쌓였는지는 계측 «목록»으로 알 수 없다 —
 * <b>{@code DETERMINISTIC} 이라고 적힌 기록이 실제로는 갈라질 수 있다.</b>
 *
 * <p>그래서 주장 대신 <b>관찰</b>로 바꿨다. 재생할 때도 기록기를 그대로 켜 두고,
 * 재생이 만든 경계 호출을 기록과 대조한다. 이 한 번의 변경이
 * 「기록에 없는 질의를 만났나」와 「이 재생을 믿을 수 있나」를 <b>같은 장치 하나로</b> 푼다.
 *
 * <h2>쓰는 법</h2>
 * <pre>
 *   ReplayProbe probe = ReplayProbe.open(recorder);   // 여기서부터 본다
 *   ... 재생한다 ...
 *   ReplayObservation observed = probe.observed(status, body, 나간예외);
 * </pre>
 *
 * <p>🔴 {@code open} 과 재생 사이에 다른 요청이 끼면 그 요청의 질의까지 섞인다.
 * v0 는 재생을 한 번에 하나씩만 돌리므로 문제가 없고, 여러 개를 동시에 돌리게 되면
 * 상관 식별자로 걸러야 한다 — <b>그때까지는 동시에 돌리지 않는다.</b>
 */
public final class ReplayProbe {

    /** 큐에 남은 것을 링까지 밀어 넣기를 기다리는 시간. 재생 하나는 이보다 훨씬 빨리 끝난다. */
    private static final Duration 기다리는_시간 = Duration.ofMillis(500);

    private final Recorder recorder;
    private final long mark;

    private ReplayProbe(Recorder recorder, long mark) {
        this.recorder = recorder;
        this.mark = mark;
    }

    /** 지금부터 본다. 재생을 시작하기 «직전»에 부른다. */
    public static ReplayProbe open(Recorder recorder) {
        return new ReplayProbe(recorder, recorder.currentSeq());
    }

    /**
     * 본 것을 넘긴다.
     *
     * @param responseStatus 응답 상태. 🔴 못 받았으면 {@code null} — 「응답이 없었다」가 아니라
     *                       「못 받았다」이고, 오라클이 그 둘을 다르게 다룬다
     * @param responseBody   응답 본문. 🔴 «안 봤으면» {@code null}. 빈 문자열은 「보았고 비었다」다
     * @param escaped        진입점 밖으로 나간 예외. {@code null} 이면 <b>안 나갔다</b>
     *                       (이 메서드를 부른 시점에 우리는 «보았으므로» 「안 봤다」가 아니다)
     */
    public ReplayObservation observed(Integer responseStatus, String responseBody, Throwable escaped) {
        List<String> executedSql = new ArrayList<>();
        for (Event event : recorder.eventsSince(mark, 기다리는_시간)) {
            if (event instanceof Event.Sql sql) {
                executedSql.add(sql.sql());
            }
        }

        ReplayObservation.Builder builder = ReplayObservation.builder()
                .responseStatus(responseStatus)
                .responseBody(responseBody)
                // 🔴 질의는 «보았다». 한 건도 없었으면 [] 이고, 그건 「안 봤다」와 다른 사실이다.
                .executedSql(executedSql);

        if (escaped == null) {
            builder.noExceptionEscaped();
        } else {
            Throwable cause = rootCauseOf(escaped);
            builder.exceptionEscaped(cause.getClass().getName(), cause.getMessage());
        }
        return builder.build();
    }

    /**
     * 껍데기 예외를 벗기고 진짜 원인을 찾는다.
     *
     * <p>🔴 기록할 때와 <b>같은 방식으로</b> 벗겨야 한다. 한쪽만 벗기면 기록에는
     * {@code IllegalStateException} 이 적혀 있는데 재생은 {@code ServletException} 을 봐서,
     * <b>고쳐졌는데도 「다른 예외가 나온다」로 판정된다.</b>
     */
    private static Throwable rootCauseOf(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 10; depth++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                return current;
            }
            current = cause;
        }
        return current;
    }
}
