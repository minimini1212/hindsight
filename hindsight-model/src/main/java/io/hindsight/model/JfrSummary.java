package io.hindsight.model;

import java.util.List;

/**
 * JVM 이 스스로 남긴 지표에서 뽑은 요약. (v2)
 *
 * <p>JFR(Java Flight Recorder)은 JDK 가 공짜로 주는 실행 기록기다. GC 가 언제 얼마나 돌았는지,
 * 어느 스레드가 어느 락 앞에서 얼마나 기다렸는지가 이미 다 찍힌다.
 * <b>우리가 만들지 않는다.</b> 갖다 쓴다.
 *
 * <p>원본 {@code .jfr} 파일은 따로 두고 여기엔 요약만 담는다. 원본은 크고,
 * 기록 파일 하나가 완결적이어야 한다는 원칙보다 크기가 더 문제라서.
 *
 * @param dumpFile      원본 파일 이름. {@code null} 이면 JFR 을 안 켰거나 못 떴다는 뜻이다.
 * @param lockHotspots  락 대기가 몰린 자리. 🔴 경쟁 상태를 <b>재현</b>할 수는 없지만
 *                      「이 자리가 의심된다」까지는 말할 수 있다. 그 근거가 이것이다.
 */
public record JfrSummary(
        String dumpFile,
        Long gcPauseMsTotal,
        Integer gcCount,
        Long lockWaitMsTotal,
        List<String> lockHotspots,
        Long heapUsedBytesAtTrigger,
        Integer threadCount
) {}
