package io.hindsight.model;

import java.time.Instant;
import java.util.List;

/**
 * 무엇이 이 기록을 만들었나.
 *
 * @param entryPoint  어느 진입점에서 났나. {@code "GET /api/orders"} 같은 모양.
 * @param exception   {@link Kind#EXCEPTION} 일 때만 채워진다.
 * @param latencyMs   {@link Kind#LATENCY} 일 때만 채워진다.
 * @param dedupKey    같은 사고를 한 건으로 묶는 열쇠. {@link #dedupKeyOf} 참조.
 * @param dedupCount  이 열쇠로 접힌 건수. 1 이면 안 접힌 것이다.
 */
public record Trigger(
        Kind kind,
        Instant at,
        String entryPoint,
        ExceptionInfo exception,
        Long latencyMs,
        String dedupKey,
        int dedupCount
) {
    public enum Kind {
        /** 진입점 밖으로 예외가 나갔다. (v0) */
        EXCEPTION,
        /** 진입점 소요시간이 임계값을 넘었다. (v0) 🔴 N+1 은 예외를 안 던지므로 여기로 잡힌다. */
        LATENCY,
        /** 사람이 {@code hs capture} 로 시켰다. (v1) */
        MANUAL,
        /** 커넥션 풀 고갈, 스레드 부족 등. (v2) */
        RESOURCE,
        /** GC 후에도 회수가 안 되는 추세. (v2) */
        MEMORY
    }

    /**
     * @param stack 최대 50 줄. 잘렸는지는 길이로 판단하지 말고 이 목록을 그대로 쓴다.
     */
    public record ExceptionInfo(String type, String message, List<String> stack) {}

    /**
     * 묶는 열쇠를 만든다.
     *
     * <p>🔴 예외 종류만으로 묶으면 안 된다. <b>지연 방아쇠에는 예외 종류가 없어서</b>
     * 모든 지연 사고가 한 건으로 접히거나, 반대로 하나도 안 접혀 디스크를 채운다.
     * 장애는 한 번 나면 초당 수백 번 나므로 후자는 앱을 두 번 죽인다.
     *
     * @param stackSignature 스택의 윗부분 몇 줄을 요약한 것. 없으면 {@code null}.
     */
    public static String dedupKeyOf(String entryPoint, Kind kind, String stackSignature) {
        return entryPoint + "|" + kind + "|" + (stackSignature == null ? "-" : stackSignature);
    }
}
