package io.hindsight.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사고가 쏟아질 때 파일도, <b>메모리도</b> 같이 쏟아지지 않는지 본다.
 *
 * <h2>🔴 이 시험이 생긴 이유</h2>
 * 묶음 장부가 <b>무한히 자라고 있었다</b>(2026-09-16 에 찾음). 묶음 열쇠는 진입점을 그대로
 * 들고 있고, 진입점은 URI 다 — {@code GET /api/orders/1}, {@code /2}, {@code /3} … 이
 * 전부 다른 열쇠다. <b>경로에 식별자가 들어가는 API 가 하나만 있어도 끝없이 늘어난다.</b>
 *
 * <p>⚠️ 이건 남의 JVM 안에서 자라는 메모리다. <b>관측 도구가 앱을 죽이는 모양</b>이고,
 * 이 프로젝트에서 가장 굳은 규율을 정면으로 어긴다.
 */
@DisplayName("사고 묶기와 상한 — 파일도 메모리도 안 쏟아진다")
class CaptureLimiterTest {

    private static CaptureLimiter 제한기() {
        return new CaptureLimiter(RecorderConfig.fromEnvironment(k -> null));
    }

    private static long 분(long n) {
        return Duration.ofMinutes(n).toNanos();
    }

    @Nested
    @DisplayName("묶기 — 같은 사고는 한 번만 파일이 된다")
    class 묶기 {

        @Test
        @DisplayName("첫 번째는 만들고 두 번째부터는 «세기만» 한다")
        void 두번째부터는_안_만든다() {
            CaptureLimiter 제한 = 제한기();

            assertThat(제한.admit("k", 분(0)).capture()).isTrue();
            var 두번째 = 제한.admit("k", 분(1));

            assertThat(두번째.capture()).isFalse();
            assertThat(두번째.dedupCount())
                    .as("🔴 「이게 2번째」와 「한 번 났다」는 완전히 다른 사실이다")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("서로 다른 사고는 따로 센다")
        void 다른_사고는_따로() {
            CaptureLimiter 제한 = 제한기();

            제한.admit("a", 분(0));
            제한.admit("a", 분(0));
            제한.admit("b", 분(0));

            assertThat(제한.countFor("a")).isEqualTo(2);
            assertThat(제한.countFor("b")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("🔴 장부가 «안 자란다»")
    class 장부 {

        @Test
        @DisplayName("🔴 서로 다른 사고 5만 건이 와도 장부가 상한 안에 머문다")
        void 오만건이_와도_안_자란다() {
            CaptureLimiter 제한 = 제한기();

            for (int i = 0; i < 50_000; i++) {
                // 🔴 이게 진짜로 일어나는 모양이다 — 경로에 주문 번호가 들어가는 API 하나면 된다.
                제한.admit("GET /api/orders/" + i, 분(i));
            }

            assertThat(제한.장부_크기())
                    .as("고치기 전에는 여기가 50,000 이었다 — 남의 JVM 안에서 자라던 메모리다")
                    .isLessThanOrEqualTo(CaptureLimiter.장부_최대);
        }

        @Test
        @DisplayName("🔴 시간이 안 흘러도 «개수»로 막는다 — 한꺼번에 쏟아지는 것이 진짜 위험이다")
        void 같은_순간에_쏟아져도_막는다() {
            CaptureLimiter 제한 = 제한기();

            // 시간이 전혀 안 흐른다. 시간으로만 막는 설계였으면 여기서 전부 남는다.
            for (int i = 0; i < 30_000; i++) {
                제한.admit("k" + i, 분(0));
            }

            assertThat(제한.장부_크기()).isLessThanOrEqualTo(CaptureLimiter.장부_최대);
        }

        @Test
        @DisplayName("🔴 잊은 것을 «센다» — 조용히 지우면 「1,204번 중 하나」가 「한 번 났다」로 읽힌다")
        void 잊은_것을_센다() {
            CaptureLimiter 제한 = 제한기();

            assertThat(제한.잊은_묶음_수()).as("아직 아무것도 안 잊었다").isZero();

            for (int i = 0; i < 20_000; i++) {
                제한.admit("k" + i, 분(0));
            }

            assertThat(제한.잊은_묶음_수())
                    .as("잊었으면 잊었다고 말해야 한다. 0 이면 「아무것도 안 잊었다」는 뜻이 된다")
                    .isPositive();
        }

        @Test
        @DisplayName("오래 안 본 묶음은 시간이 지나면 잊는다")
        void 오래된_것은_시간으로_잊는다() {
            CaptureLimiter 제한 = 제한기();
            제한.admit("옛날것", 분(0));

            long 한참뒤 = CaptureLimiter.묶음을_기억하는_시간.toNanos() + 분(10);
            제한.admit("새것", 한참뒤);

            assertThat(제한.countFor("옛날것")).isZero();
            assertThat(제한.countFor("새것")).isEqualTo(1);
            assertThat(제한.잊은_묶음_수()).isEqualTo(1);
        }

        @Test
        @DisplayName("🔴 기억하는 시간이 시간당 상한 창보다 «길다» — 짧으면 같은 사고가 다시 파일이 된다")
        void 기억이_상한창보다_길다() {
            assertThat(CaptureLimiter.묶음을_기억하는_시간)
                    .isGreaterThan(Duration.ofHours(1));
        }
    }

    @Nested
    @DisplayName("⚠️ 청소는 «가끔»만 한다 — 이 코드는 앱의 요청 스레드에서 돈다")
    class 청소_비용 {

        @Test
        @DisplayName("1분이 안 지났고 넘치지도 않았으면 «안» 훑는다")
        void 매번_훑지_않는다() {
            CaptureLimiter 제한 = 제한기();
            제한.admit("옛날것", 분(0));

            // 기억 시간이 지났지만, 청소 간격이 아직 안 됐다 → 아직 안 잊는다.
            long 기억시간뒤_같은_순간 = CaptureLimiter.묶음을_기억하는_시간.toNanos();
            제한.admit("두번째", 기억시간뒤_같은_순간);
            long 청소직후 = 기억시간뒤_같은_순간 + 1;
            제한.admit("세번째", 청소직후);

            assertThat(제한.장부_크기())
                    .as("청소 간격 안이면 훑지 않는다 — 열쇠 1만 개를 사고마다 훑으면 앱이 느려진다")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("🔴 넘쳤으면 간격을 기다리지 않는다 — 메모리는 미룰 수 없다")
        void 넘치면_바로_청소한다() {
            CaptureLimiter 제한 = 제한기();

            // 전부 «같은 순간»이라 청소 간격은 한 번도 안 찬다. 그래도 막혀야 한다.
            for (int i = 0; i <= CaptureLimiter.장부_최대 + 500; i++) {
                제한.admit("k" + i, 분(0));
            }

            assertThat(제한.장부_크기()).isLessThanOrEqualTo(CaptureLimiter.장부_최대);
        }
    }
}
