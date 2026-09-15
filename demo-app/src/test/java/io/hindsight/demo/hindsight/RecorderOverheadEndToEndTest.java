package io.hindsight.demo.hindsight;

import io.hindsight.demo.member.Member;
import io.hindsight.demo.member.MemberRepository;
import io.hindsight.demo.order.Order;
import io.hindsight.demo.order.OrderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔬 실험 ⑪ — <b>기록기를 붙인 앱과 뗀 앱을 «같은 기계에서» 견준다.</b>
 *
 * <p>설계 §10 이 요구한 것이 정확히 이것이다 — *"에이전트 붙임/뗌을 비교해 숫자를 남긴다"*.
 *
 * <h2>🔴 이 시험이 미리 인정하고 시작하는 것 — 잴 수 없을지도 모른다</h2>
 * 목표는 «요청당 50µs 이하»인데, 스프링 요청 하나는 톰캣·JSON·JPA 를 지나며
 * <b>밀리초 단위</b>로 흔들린다. 즉 <b>재려는 값이 잡음보다 작을 수 있다.</b>
 *
 * <p>그래서 이 시험은 「몇 µs 인가」에 답하지 못할 수 있고, 그때의 정직한 결론은
 * 「차이가 없다」가 아니라 <b>「이 방법으로는 못 잰다」</b>이다. 🔴 그 둘을 섞어 적는 것이
 * 이 프로젝트가 잡으려는 결함 그 자체다 — 「모름」을 「없음」으로 접는 것.
 *
 * <p>µs 단위의 진짜 값은 기록기 모듈의 실험 ⑩ 이 따로 잰다.
 */
@DisplayName("🔬 실험 ⑪ — 기록기 붙임/뗌 비교")
// 🔴 순서를 «고정»한다. JUnit 은 중첩 클래스의 순서를 보장하지 않아서, 처음 돌렸을 때
//    ②가 먼저 돌고 ①이 나중에 돌았다 — 그래서 견줄 값이 없어 비교가 통째로 건너뛰어졌다.
//
// 🔴 그리고 «어느 쪽을 먼저 돌리나»가 결과를 바꾼다. 먼저 도는 쪽이 JVM 을 덥히는
//    값을 치르기 때문이다. 그래서 «뗀 쪽»을 먼저 돌린다 — 그러면 붙인 쪽이 더 더운
//    JVM 에서 돌아 «유리한» 조건이 된다. 그 유리한 조건에서도 붙인 쪽이 느리게 나오면,
//    그 차이는 적어도 덥히기로는 설명되지 않는다.
@org.junit.jupiter.api.TestClassOrder(org.junit.jupiter.api.ClassOrderer.OrderAnnotation.class)
class RecorderOverheadEndToEndTest {

    /** 한 판에 보내는 요청 수와 판 수. 잡음을 줄이려면 판을 늘리는 쪽이 낫다. */
    private static final int 덥히기 = 200;
    private static final int 판 = 30;
    private static final int 판당요청 = 50;

    /** 두 쪽의 결과를 견주려고 모아 둔다. */
    private static Double 뗀쪽_p50;
    private static Double 뗀쪽_p99;

    // ────────────────────────────────────────────────────────────────────────

    abstract static class 공통 {

        @Autowired
        TestRestTemplate rest;

        @Autowired
        MemberRepository memberRepository;

        @Autowired
        OrderRepository orderRepository;

        @BeforeEach
        void 심는다() {
            orderRepository.deleteAll();
            memberRepository.deleteAll();
            Member member = memberRepository.save(new Member("홍길동"));
            for (int i = 0; i < 20; i++) {
                orderRepository.save(new Order(member, "물건" + i));
            }
        }

        /** 요청 하나당 걸린 시간의 분위수(µs). */
        double[] 잰다() {
            for (int i = 0; i < 덥히기; i++) {
                rest.getForEntity("/api/orders", String.class);
            }
            long[] perRequest = new long[판];
            for (int round = 0; round < 판; round++) {
                long startedAt = System.nanoTime();
                for (int i = 0; i < 판당요청; i++) {
                    rest.getForEntity("/api/orders", String.class);
                }
                perRequest[round] = (System.nanoTime() - startedAt) / 판당요청;
            }
            Arrays.sort(perRequest);
            return new double[]{
                    perRequest[판 / 2] / 1000.0,
                    perRequest[Math.min(판 - 1, (int) (판 * 0.99))] / 1000.0,
                    perRequest[0] / 1000.0
            };
        }
    }

    @Nested
    @org.junit.jupiter.api.Order(1)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "hindsight.recorder.enabled=false")
    @DisplayName("① 기록기를 «뗀» 앱")
    class 기록기_없이 extends 공통 {

        @Test
        @DisplayName("요청 하나에 얼마나 걸리나")
        void 기준선을_잰다() {
            double[] r = 잰다();
            뗀쪽_p50 = r[0];
            뗀쪽_p99 = r[1];

            System.out.println();
            System.out.println("── 실험 ⑪-① 기록기 «없이» ──");
            System.out.printf("  요청 하나   p50 %8.1fµs   p99 %8.1fµs   min %8.1fµs   (%d판 × %d요청)%n",
                    r[0], r[1], r[2], 판, 판당요청);

            assertThat(r[0]).isPositive();
        }
    }

    @Nested
    @org.junit.jupiter.api.Order(2)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "hindsight.recorder.enabled=true")
    @DisplayName("② 기록기를 «붙인» 앱")
    class 기록기_붙여서 extends 공통 {

        @DynamicPropertySource
        static void 기록기_설정(DynamicPropertyRegistry registry) {
            Path store = Path.of(System.getProperty("java.io.tmpdir"),
                    "hindsight-overhead-" + System.nanoTime());
            registry.add("HINDSIGHT_STORE_DIR", store::toString);
            // 🔴 지연 방아쇠를 아주 크게 둔다. 안 그러면 재는 도중에 «사고»가 나서
            //    파일 쓰기(실험 ⑩-④ 기준 12ms)가 섞여 들어가고, 그러면 평상시 비용이 아니라
            //    「사고가 섞인 값」을 재게 된다. 그 값은 실험 ⑩-④ 가 따로 잰다.
            registry.add("HINDSIGHT_LATENCY_TRIGGER_MS", () -> "600000");
            registry.add("HINDSIGHT_APP_NAME", () -> "demo-app");
        }

        @Test
        @DisplayName("🔴 뗀 쪽과 견준다 — 차이가 잡음보다 작으면 「못 잰다」고 적는다")
        void 붙인_쪽을_재고_견준다() {
            double[] r = 잰다();

            System.out.println();
            System.out.println("── 실험 ⑪-② 기록기 «붙여서» ──");
            System.out.printf("  요청 하나   p50 %8.1fµs   p99 %8.1fµs   min %8.1fµs   (%d판 × %d요청)%n",
                    r[0], r[1], r[2], 판, 판당요청);

            if (뗀쪽_p50 == null) {
                System.out.println("  ⚠️ 뗀 쪽을 못 쟀다. 견주지 않는다 — 한쪽만으로 차이를 «지어내지» 않는다.");
                return;
            }

            double Δp50 = r[0] - 뗀쪽_p50;
            double Δp99 = r[1] - 뗀쪽_p99;
            double 잡음 = Math.abs(뗀쪽_p99 - 뗀쪽_p50);

            System.out.println();
            System.out.println("── 실험 ⑪ 견주기 (목표: 요청당 50µs 이하) ──");
            System.out.printf("  뗀 쪽   p50 %8.1fµs   p99 %8.1fµs%n", 뗀쪽_p50, 뗀쪽_p99);
            System.out.printf("  붙인 쪽 p50 %8.1fµs   p99 %8.1fµs%n", r[0], r[1]);
            System.out.printf("  차이    Δp50 %+8.1fµs  Δp99 %+8.1fµs%n", Δp50, Δp99);
            System.out.printf("  잡음    기준선의 p99−p50 = %.1fµs%n", 잡음);

            if (Math.abs(Δp50) < 잡음) {
                System.out.println("  🔴 차이가 잡음보다 «작다». 이 방법으로는 못 잰다 —");
                System.out.println("     「차이가 없다」가 아니라 「이 자로는 안 보인다」가 정직한 결론이다.");
                System.out.println("     µs 단위의 진짜 값은 실험 ⑩(기록기 모듈)이 따로 잰다.");
            } else {
                System.out.printf("  차이가 잡음보다 크다. Δp50 %.1fµs 를 그대로 읽는다.%n", Δp50);
            }

            assertThat(r[0]).isPositive();
        }
    }
}
