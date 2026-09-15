package io.hindsight.recorder;

import io.hindsight.model.Summary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("요약층 — 모양으로 접기")
class SummaryWindowTest {

    private final SummaryWindow window = new SummaryWindow(RecorderConfig.builder().build());

    @Nested
    @DisplayName("값이 다른 같은 질의를 한 모양으로 접는다")
    class Folding {

        @Test
        @DisplayName("🔴 값만 다른 질의 200개가 «한 줄»로 접힌다 — N+1 이 이렇게 보인다")
        void 값만_다른_질의는_한_줄로_접힌다() {
            for (int i = 1; i <= 200; i++) {
                window.addSql("select * from member where id = " + i, 1, System.nanoTime());
            }

            Summary summary = window.snapshot(System.nanoTime());

            assertThat(summary.sqlShapes()).hasSize(1);
            assertThat(summary.sqlShapes().get(0).count()).isEqualTo(200);
            assertThat(summary.sqlShapes().get(0).normalized()).isEqualTo("select * from member where id = ?");
        }

        @Test
        @DisplayName("진짜로 다른 질의는 따로 세인다")
        void 진짜_다른_질의는_따로_세인다() {
            window.addSql("select * from member where id = 1", 1, System.nanoTime());
            window.addSql("select * from orders where id = 1", 1, System.nanoTime());

            assertThat(window.snapshot(System.nanoTime()).sqlShapes()).hasSize(2);
        }

        @Test
        @DisplayName("문자열 값도 접는다")
        void 문자열_값도_접는다() {
            window.addSql("select * from member where name = '홍길동'", 1, System.nanoTime());
            window.addSql("select * from member where name = '김철수'", 1, System.nanoTime());

            assertThat(window.snapshot(System.nanoTime()).sqlShapes()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("🔴 모양 캐시가 앱의 힙을 먹지 않는다")
    class CacheBound {

        @Test
        @DisplayName("같은 SQL 문자열은 한 칸만 쓴다")
        void 같은_문자열은_한_칸() {
            for (int i = 0; i < 5_000; i++) {
                window.addSql("select * from member where id = ?", 1, System.nanoTime());
            }

            assertThat(window.cachedShapeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("🔴 값이 SQL 문에 박힌 앱을 만나도 캐시가 무한히 안 자란다")
        void 값이_박혀도_캐시가_안_자란다() {
            // 값을 문자열로 이어 붙이는 앱을 흉내 낸다 — 서로 다른 SQL «문자열»이 계속 생긴다.
            for (int i = 0; i < 5_000; i++) {
                window.addSql("select * from member where id = " + i, 1, System.nanoTime());
            }

            // 상한(1,000)에서 멈춘다. 멈춰도 접기는 그대로 된다 — 아래에서 확인.
            assertThat(window.cachedShapeCount()).isLessThanOrEqualTo(1000);

            // 🔴 캐시가 상한에 닿아도 «기능»은 안 변한다. 5,000건이 여전히 한 모양으로 접힌다.
            Summary summary = window.snapshot(System.nanoTime());
            assertThat(summary.sqlShapes()).hasSize(1);
            assertThat(summary.sqlShapes().get(0).count()).isEqualTo(5_000);
        }
    }

    @Test
    @DisplayName("🔴 커넥션 표본은 null 이다 — v0 은 풀을 «안 본다»")
    void 커넥션_표본은_null() {
        assertThat(window.snapshot(System.nanoTime()).connectionSamples()).isNull();
    }
}
