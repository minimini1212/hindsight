package io.hindsight.recorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔬 실험 ⑯ — <b>여러 요청이 «동시에» 들어올 때도 비용이 그대로인가.</b>
 *
 * <h2>왜 이걸 따로 재나</h2>
 * 실험 ⑩ 은 전부 <b>한 스레드</b>로 쟀다. 그런데 진짜 앱에서 링 버퍼를 두드리는 것은
 * 톰캣 워커 수십 개다. 그리고 <b>사고가 나는 순간이 바로 그 수십 개가 동시에
 * 같은 자리를 두드리는 순간</b>이다 — 즉 지금까지 잰 조건은 정작 중요한 조건이 아니었다.
 *
 * <h2>🔴 무엇이 무너질 수 있나</h2>
 * <pre>
 *   넣는 자리에 자물쇠가 있으면  → 스레드가 늘수록 «기다리는 시간»이 늘어난다
 *     → 장애가 나서 사고가 쏟아질수록 앱이 느려진다
 *       → 🔴 관측 도구가 장애를 «키운다»
 * </pre>
 *
 * <h2>이 시험이 «증명하지 않는» 것</h2>
 * ⚠️ 여기서 재는 것은 <b>기록기에 넣는 비용</b>이지 스프링 요청 전체가 아니다.
 * 요청 전체의 잡음(수 ms)이 재려는 값(수 µs)보다 세 자릿수 크기 때문에,
 * 요청 안에서 재면 <b>아무것도 안 보인다.</b> 그게 「요청당 추가 시간을 못 쟀다」의 이유다.
 */
@DisplayName("🔬 실험 ⑯ — 동시에 두드려도 비용이 그대로인가")
class ConcurrentOverheadTest {

    /** 🔴 한 스레드일 때 대비 이 배수를 넘으면 「자릿수가 바뀌었다」로 본다. */
    private static final double 허용_배수 = 12.0;

    @TempDir
    Path storeDir;

    private Recorder recorder;

    @AfterEach
    void 정리() {
        if (recorder != null) {
            recorder.close();
        }
    }

    private Recorder 기록기() {
        recorder = new Recorder(RecorderConfig.builder()
                .storeDir(storeDir).appName("bench").build());
        return recorder;
    }

    @Test
    @DisplayName("🔴 스레드를 1 → 8 로 늘려도 이벤트 하나당 비용의 «자릿수»가 안 바뀐다")
    void 동시에_두드려도_자릿수가_안_바뀐다() throws InterruptedException {
        Recorder r = 기록기();

        double 한스레드 = 스레드당_평균_나노(r, 1, 20_000);
        double 여덟스레드 = 스레드당_평균_나노(r, 8, 20_000);

        System.out.println();
        System.out.println("── 실험 ⑯ 동시 기록 비용 ──");
        System.out.printf("  스레드 1개: %.2fµs/건%n", 한스레드 / 1000);
        System.out.printf("  스레드 8개: %.2fµs/건%n", 여덟스레드 / 1000);
        System.out.printf("  배수      : %.1f배%n", 여덟스레드 / 한스레드);
        System.out.println("  버린 이벤트: " + r.droppedEventsForTest()
                + "건 (큐가 차서 «못 받은» 것 — 버리는 것이 설계의 «의도»다)");

        assertThat(여덟스레드 / 한스레드)
                .as("🔴 스레드가 늘수록 기다리는 시간이 늘면, 장애가 클수록 앱이 느려진다")
                .isLessThan(허용_배수);
    }

    @Test
    @DisplayName("🔴 동시에 두드려도 «막히지» 않는다 — 넣는 쪽은 절대 기다리지 않는다")
    void 아무도_막히지_않는다() throws InterruptedException {
        Recorder r = 기록기();
        int 스레드수 = 16;
        int 건수 = 5_000;

        AtomicLong 가장오래걸린_나노 = new AtomicLong();
        CountDownLatch 출발 = new CountDownLatch(1);
        CountDownLatch 도착 = new CountDownLatch(스레드수);

        for (int t = 0; t < 스레드수; t++) {
            new Thread(() -> {
                try {
                    출발.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < 건수; i++) {
                    long 시작 = System.nanoTime();
                    r.recordSql("select o from Order o where o.id = ?", List.of(i), 1, 3);
                    long 걸린 = System.nanoTime() - 시작;
                    가장오래걸린_나노.accumulateAndGet(걸린, Math::max);
                }
                도착.countDown();
            }, "두드리기-" + t).start();
        }

        출발.countDown();
        assertThat(도착.await(60, TimeUnit.SECONDS))
                .as("🔴 60초 안에 안 끝나면 어딘가에서 막힌 것이다")
                .isTrue();

        double 최악밀리 = 가장오래걸린_나노.get() / 1_000_000.0;
        System.out.println();
        System.out.println("── 실험 ⑯-② 한 건이 가장 오래 걸린 시간 ──");
        System.out.printf("  스레드 %d개 · 각 %d건 → 최악 %.2fms%n", 스레드수, 건수, 최악밀리);
        System.out.println("  버린 이벤트: " + r.droppedEventsForTest() + "건");

        // 🔴 「평균」이 아니라 «최악»을 본다. 요청 하나가 멈추는 것이 사용자가 겪는 일이다.
        //    ⚠️ 이 그물은 넉넉하다 — GC 한 번에 수십 ms 가 나가므로, 여기서 잡으려는 것은
        //    「잠깐 느렸다」가 아니라 「누가 자물쇠를 붙잡고 안 놨다」다.
        assertThat(최악밀리)
                .as("넣는 쪽이 기다리는 설계였으면 여기서 초 단위가 나온다")
                .isLessThan(1_000.0);
    }

    /** 스레드 여럿이 같이 두드렸을 때 이벤트 하나당 평균 나노초. */
    private static double 스레드당_평균_나노(Recorder r, int 스레드수, int 스레드당_건수)
            throws InterruptedException {
        // 워밍업 — JIT 가 덜 데워진 채로 재면 첫 번째 쪽이 손해를 본다.
        for (int i = 0; i < 20_000; i++) {
            r.recordSql("warmup", List.of(i), 1, 1);
        }

        CountDownLatch 출발 = new CountDownLatch(1);
        CountDownLatch 도착 = new CountDownLatch(스레드수);
        AtomicLong 합계나노 = new AtomicLong();

        for (int t = 0; t < 스레드수; t++) {
            new Thread(() -> {
                try {
                    출발.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long 시작 = System.nanoTime();
                for (int i = 0; i < 스레드당_건수; i++) {
                    r.recordSql("select o from Order o where o.id = ?", List.of(i), 1, 3);
                }
                합계나노.addAndGet(System.nanoTime() - 시작);
                도착.countDown();
            }).start();
        }

        출발.countDown();
        도착.await(60, TimeUnit.SECONDS);

        // 🔴 «스레드가 쓴 시간의 합»을 «전체 건수»로 나눈다. 벽시계로 나누면
        //    스레드가 늘수록 저절로 작아져서 「빨라졌다」는 착시가 생긴다.
        return (double) 합계나노.get() / ((long) 스레드수 * 스레드당_건수);
    }
}
