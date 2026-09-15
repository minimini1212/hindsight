package io.hindsight.recorder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("상한값 읽기")
class RecorderConfigTest {

    private static java.util.function.Function<String, String> env(Map<String, String> values) {
        return values::get;
    }

    @Nested
    @DisplayName("값이 없을 때")
    class Defaults {

        @Test
        @DisplayName("환경변수가 하나도 없으면 데이터 계약의 기본값이 나온다")
        void 환경변수가_하나도_없으면_기본값() {
            RecorderConfig config = RecorderConfig.fromEnvironment(env(Map.of()));

            assertThat(config.windowSeconds()).isEqualTo(60);
            assertThat(config.bufferMaxBytes()).isEqualTo(32L * 1024 * 1024);
            assertThat(config.bodyMaxBytes()).isEqualTo(64 * 1024);
            assertThat(config.sqlRowsMax()).isEqualTo(100);
            assertThat(config.capturesPerHour()).isEqualTo(20);
            assertThat(config.latencyTriggerMs()).isEqualTo(3000);
        }

        @Test
        @DisplayName("🔴 어디서 온 값인지 같이 들고 있다 — 「30(기본값)」과 「30(환경변수)」은 다른 사실이다")
        void 값의_출처를_들고_있다() {
            RecorderConfig config = RecorderConfig.fromEnvironment(
                    env(Map.of("HINDSIGHT_WINDOW_SECONDS", "5")));

            assertThat(config.sources().get("HINDSIGHT_WINDOW_SECONDS")).isEqualTo("환경변수");
            assertThat(config.sources().get("HINDSIGHT_BODY_MAX_BYTES")).isEqualTo("기본값");
        }
    }

    @Nested
    @DisplayName("값이 이상할 때")
    class Invalid {

        @Test
        @DisplayName("🔴 숫자가 아니면 «멈춘다». 기본값으로 조용히 되돌아가지 않는다")
        void 숫자가_아니면_멈춘다() {
            assertThatThrownBy(() -> RecorderConfig.fromEnvironment(
                    env(Map.of("HINDSIGHT_BODY_MAX_BYTES", "64kb"))))
                    .isInstanceOf(InvalidRecorderConfigException.class)
                    .hasMessageContaining("HINDSIGHT_BODY_MAX_BYTES")
                    .hasMessageContaining("64kb");
        }

        @Test
        @DisplayName("0 이나 음수도 멈춘다 — 상한이 0 이면 아무것도 기록되지 않는데 조용하다")
        void 영이나_음수도_멈춘다() {
            assertThatThrownBy(() -> RecorderConfig.fromEnvironment(
                    env(Map.of("HINDSIGHT_WINDOW_SECONDS", "0"))))
                    .isInstanceOf(InvalidRecorderConfigException.class);
        }
    }

    @Nested
    @DisplayName("가명화 키")
    class PseudonymKey {

        @Test
        @DisplayName("🔴 없으면 null 이다 — 빈 문자열로 바꿔 돌려주지 않는다")
        void 없으면_null() {
            RecorderConfig config = RecorderConfig.fromEnvironment(env(Map.of()));

            assertThat(config.pseudonymKey()).isNull();
            assertThat(config.sources().get("HINDSIGHT_PSEUDONYM_KEY")).isEqualTo("없음");
        }

        @Test
        @DisplayName("🔴 요약에 키 값 자체는 절대 안 찍힌다. 있는지 없는지만")
        void 요약에_키가_안_찍힌다() {
            RecorderConfig config = RecorderConfig.fromEnvironment(
                    env(Map.of("HINDSIGHT_PSEUDONYM_KEY", "진짜-비밀-키-1234")));

            assertThat(config.describe())
                    .anyMatch(line -> line.contains("HINDSIGHT_PSEUDONYM_KEY") && line.contains("(설정됨)"));
            assertThat(String.join("\n", config.describe())).doesNotContain("진짜-비밀-키-1234");
        }
    }
}
