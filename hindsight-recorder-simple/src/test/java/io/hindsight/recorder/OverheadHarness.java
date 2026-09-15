package io.hindsight.recorder;

import java.util.Arrays;
import java.util.function.LongConsumer;

/**
 * 시간을 재는 자. <b>재는 방법 자체가 틀리면 나온 숫자는 숫자가 아니다.</b>
 *
 * <h2>🔴 이 하네스가 «일부러» 하는 네 가지</h2>
 * <ol>
 *   <li><b>덥힌다(warmup).</b> JVM 은 처음 몇천 번을 해석해서 돌리다가 기계어로 바꾼다.
 *       덥히지 않고 재면 <b>실제보다 10~100배 느린 숫자</b>가 나오고, 그걸 그대로 적으면
 *       「기록기가 느리다」는 틀린 결론이 남는다</li>
 *   <li><b>평균이 아니라 «분위수»를 본다.</b> 평균은 GC 한 번에 통째로 끌려간다.
 *       설계가 요구하는 것도 p99 다 — 🔴 「p99 가 5% 느려진다」는 잴 수 없는 말이라
 *       <b>절대값(마이크로초)으로</b> 적는다</li>
 *   <li><b>결과를 «쓴다».</b> 계산해 놓고 안 쓰면 JIT 이 그 계산을 통째로 지운다.
 *       그러면 「0ns 만에 끝났다」가 나오는데, 그건 빠른 게 아니라 <b>안 한 것</b>이다</li>
 *   <li><b>여러 판을 돌려 «가장 좋은 판»을 쓴다.</b> 이 기계는 다른 일도 하고 있다.
 *       느린 판은 우리 코드가 아니라 남의 프로세스를 잰 것이다</li>
 * </ol>
 *
 * <h2>⚠️ 이건 JMH 가 아니다</h2>
 * 제대로 하려면 JMH(자바 표준 벤치마크 도구)를 쓴다. 안 쓴 이유는 이 프로젝트가
 * <b>절대 성능을 겨루는 게 아니라 「붙였을 때와 뗐을 때의 차이」만</b> 필요하기 때문이다.
 * 같은 기계·같은 JVM 에서 두 쪽을 같은 방식으로 재면 그 차이는 살아남는다.
 * 🔴 <b>그래서 여기 나온 숫자는 「우리 기계에서 잰 차이」이지 「이 코드의 성능」이 아니다.</b>
 */
final class OverheadHarness {

    private OverheadHarness() {}

    /** 한 번 재고 나온 것. 단위는 전부 나노초. */
    record Result(String name, long rounds, long opsPerRound, long p50, long p99, long min) {

        double p50Micros() {
            return p50 / 1000.0;
        }

        double p99Micros() {
            return p99 / 1000.0;
        }

        @Override
        public String toString() {
            return String.format("%-28s p50 %8.3fµs   p99 %8.3fµs   min %8.3fµs   (%d판 × %d회)",
                    name, p50 / 1000.0, p99 / 1000.0, min / 1000.0, rounds, opsPerRound);
        }
    }

    /**
     * @param name       무엇을 쟀나
     * @param warmupOps  덥히는 횟수. 🔴 여기를 줄이면 숫자가 통째로 틀린다
     * @param rounds     판 수
     * @param opsPerRound 한 판에서 돌리는 횟수
     * @param work       잴 것. 인자는 회차 번호이고, <b>결과를 반드시 쓰는</b> 일을 해야 한다
     */
    static Result measure(String name, int warmupOps, int rounds, int opsPerRound, LongConsumer work) {
        // ① 덥힌다. 이 구간의 시간은 «버린다» — 해석 실행이라 실제와 상관이 없다.
        for (int i = 0; i < warmupOps; i++) {
            work.accept(i);
        }

        // ② 판마다 「한 번당 몇 나노초」를 구한다.
        long[] perOp = new long[rounds];
        for (int round = 0; round < rounds; round++) {
            long startedAt = System.nanoTime();
            for (int i = 0; i < opsPerRound; i++) {
                work.accept(i);
            }
            perOp[round] = (System.nanoTime() - startedAt) / opsPerRound;
        }

        Arrays.sort(perOp);
        return new Result(
                name,
                rounds,
                opsPerRound,
                perOp[(int) (rounds * 0.50)],
                perOp[Math.min(rounds - 1, (int) (rounds * 0.99))],
                perOp[0]);
    }

    /**
     * 두 결과의 차이 — 「붙였을 때 얼마나 더 드나」.
     *
     * <p>🔴 음수가 나올 수 있고, 그건 <b>측정 잡음이 차이보다 크다</b>는 뜻이다.
     * 0 으로 바꿔 적지 않는다 — 그러면 「차이가 없다」로 읽히는데,
     * 진짜 뜻은 「이 방법으로는 못 잰다」이다.
     */
    static String delta(String label, Result without, Result with) {
        double p50 = (with.p50() - without.p50()) / 1000.0;
        double p99 = (with.p99() - without.p99()) / 1000.0;
        return String.format("%-28s Δp50 %+8.3fµs   Δp99 %+8.3fµs", label, p50, p99);
    }

    /**
     * 지금 쓰고 있는 힙을 «어림»한다. GC 를 부르고 잠깐 기다린 뒤 잰다.
     *
     * <p>⚠️ {@code System.gc()} 는 <b>부탁이지 명령이 아니다.</b> JVM 이 무시할 수 있고,
     * 무시해도 우리는 알 수 없다. 그래서 이 숫자는 «자릿수»로만 읽는다 —
     * 「32MB 상한이 말이 되나」에는 답하지만 「정확히 몇 바이트인가」에는 답하지 않는다.
     */
    static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
