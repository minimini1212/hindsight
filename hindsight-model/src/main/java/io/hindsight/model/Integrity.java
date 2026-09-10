package io.hindsight.model;

/**
 * 이 기록이 온전한가 — 무엇을 못 담았나.
 *
 * <p>🔴 <b>이 자료 구조가 이 프로젝트의 존재 이유를 도구 자신에게 적용하는 자리다.</b>
 * 못 담은 것을 못 담았다고 말하지 않으면, 읽는 사람에게 「없었다」와 「못 담았다」가
 * 똑같아 보인다. 그건 이 도구가 남의 코드에서 잡으려는 바로 그 결함이다.
 *
 * @param windowRequestedSeconds 담으려고 한 시간. 설정값.
 * @param windowActualSeconds    🔴 <b>실제로 담긴 시간.</b> 이 둘은 거의 항상 다르다.
 *                               트래픽이 많으면 바이트 상한이 먼저 걸려 60초가 4초가 된다.
 *                               이때 「60초」만 적혀 있으면, 읽는 사람은 30초 전에 시작된
 *                               커넥션 누수가 <b>일어나지 않았다고</b> 결론 낸다.
 * @param droppedEvents          큐가 차서 <b>아예 못 받은</b> 건수.
 * @param evictedEvents          🔴 받았다가 <b>버퍼가 넘쳐 밀어낸</b> 건수.
 *                               {@code droppedEvents} 와 합치지 않는다 — 다른 사실이다.
 * @param agentErrors            에이전트 내부에서 삼킨 예외 건수. 0 이 아니면 계측이 온전치 않다.
 * @param instrumentationDisabled 자기 무력화가 걸렸나. 걸린 뒤의 구간은 <b>기록이 아예 없다.</b>
 */
public record Integrity(
        int windowRequestedSeconds,
        double windowActualSeconds,
        long droppedEvents,
        long evictedEvents,
        long evictedBytes,
        long bufferBytes,
        long agentErrors,
        boolean instrumentationDisabled
) {
    /**
     * 담으려던 시간보다 실제로 담긴 시간이 눈에 띄게 짧은가.
     *
     * <p>참이면 {@code hs show} 가 <b>경고를 띄우고</b> 요약 층을 함께 보라고 안내한다.
     * 조용히 넘어가면 사용자는 짧은 창을 긴 창으로 착각한 채 결론을 내린다.
     */
    public boolean windowFellShort() {
        return windowActualSeconds < windowRequestedSeconds * 0.9;
    }

    /**
     * 이 기록에 빠진 것이 있나.
     *
     * <p>재생이 실패하면 <b>가장 먼저</b> 이 값을 본다. 원인이 코드가 아니라
     * 「애초에 안 담겼다」인 경우가 많고, 그걸 모르면 없는 버그를 쫓게 된다.
     *
     * <p>🔴 이름이 {@code isIncomplete} 가 아닌 이유: 이 모듈에는 Jackson 애너테이션을
     * 못 붙이는데(그 라이브러리가 남의 JVM 으로 딸려 들어가므로), {@code isXxx} 로 지으면
     * Jackson 이 자동으로 JSON 필드로 만들어 버린다. 그러면 읽을 때 그런 구성 요소가 없어
     * 왕복이 깨진다. 2026-09-10 실제로 이걸로 테스트 셋이 깨졌다.
     * <b>이 모듈의 파생 메서드는 {@code get}·{@code is} 로 시작하지 않는다.</b>
     */
    public boolean hasGaps() {
        return droppedEvents > 0 || evictedEvents > 0 || agentErrors > 0 || instrumentationDisabled;
    }
}
