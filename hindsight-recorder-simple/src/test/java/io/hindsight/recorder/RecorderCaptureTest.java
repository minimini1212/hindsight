package io.hindsight.recorder;

import io.hindsight.core.store.RecordingCodec;
import io.hindsight.model.Recording;
import io.hindsight.model.Trigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("사고가 났을 때 기록을 떨구는 일")
class RecorderCaptureTest {

    @TempDir
    Path storeDir;

    private Recorder recorder;

    @AfterEach
    void 정리() {
        if (recorder != null) {
            recorder.close();
        }
    }

    private Recorder recorderWith(RecorderConfig.Builder builder) {
        recorder = new Recorder(builder.storeDir(storeDir).appName("demo-app").build());
        return recorder;
    }

    private static Trigger.ExceptionInfo boom() {
        return new Trigger.ExceptionInfo("java.lang.NullPointerException", "x 가 null 이다",
                List.of("io.hindsight.demo.order.OrderService.findAll(OrderService.java:88)"));
    }

    @Nested
    @DisplayName("🔴 기록기가 쓴 파일이 core 로 다시 읽힌다 (왕복)")
    class RoundTrip {

        @Test
        @DisplayName("쓴 파일을 core 가 읽으면 같은 값이 나온다")
        void 쓴_파일이_다시_읽힌다() {
            Recorder r = recorderWith(RecorderConfig.builder());
            r.recordSql("select * from orders where id = ?", List.of(7), 1, 3);

            Optional<Path> written = r.capture(Trigger.Kind.EXCEPTION, "GET /api/orders",
                    boom(), 120L, "OrderService.findAll:88");

            assertThat(written).isPresent();
            Recording read = new RecordingCodec().read(written.get());

            assertThat(read.schemaVersion()).isEqualTo(Recording.CURRENT_SCHEMA_VERSION);
            assertThat(read.trigger().kind()).isEqualTo(Trigger.Kind.EXCEPTION);
            assertThat(read.trigger().entryPoint()).isEqualTo("GET /api/orders");
            assertThat(read.app().name()).isEqualTo("demo-app");
            assertThat(read.app().agentVersion()).isEqualTo(Recorder.VERSION);
        }

        @Test
        @DisplayName("🔴 안 본 것은 null 로 남는다 — jfr · replay · gitCommit")
        void 안_본_것은_null_로_남는다() {
            Recorder r = recorderWith(RecorderConfig.builder());

            Path file = r.capture(Trigger.Kind.LATENCY, "GET /slow", null, 5000L, null).orElseThrow();
            Recording read = new RecordingCodec().read(file);

            // JFR 은 v2, 재생은 아직 안 했다. 빈 객체가 아니라 null 이어야 한다 —
            // 빈 객체는 「보았고 없었다」로 읽힌다.
            assertThat(read.jfr()).isNull();
            assertThat(read.replay()).isNull();
            assertThat(read.app().gitCommit()).isNull();
        }

        @Test
        @DisplayName("🔴 커넥션 표본은 null 이다 — [] 로 적으면 누수가 「없었다」로 읽힌다")
        void 커넥션_표본은_null() {
            Recorder r = recorderWith(RecorderConfig.builder());

            Path file = r.capture(Trigger.Kind.LATENCY, "GET /slow", null, 5000L, null).orElseThrow();
            Recording read = new RecordingCodec().read(file);

            assertThat(read.summary().connectionSamples()).isNull();
        }
    }

    @Nested
    @DisplayName("🔴 담으려던 시간과 담긴 시간을 둘 다 적는다")
    class Integrity {

        @Test
        @DisplayName("설정값은 그대로, 실제 담긴 시간은 따로 적힌다")
        void 두_시간이_따로_적힌다() {
            Recorder r = recorderWith(RecorderConfig.builder().windowSeconds(60));
            r.recordSql("select 1", List.of(), 1, 1);

            Path file = r.capture(Trigger.Kind.LATENCY, "GET /x", null, 4000L, null).orElseThrow();
            Recording read = new RecordingCodec().read(file);

            assertThat(read.integrity().windowRequestedSeconds()).isEqualTo(60);
            // 방금 만든 기록이라 실제로 담긴 시간은 60초와 한참 다르다.
            assertThat(read.integrity().windowActualSeconds()).isLessThan(60.0);
            assertThat(read.integrity().windowFellShort()).isTrue();
        }

        @Test
        @DisplayName("계측이 꺼졌는지가 파일에 적힌다")
        void 꺼졌는지가_적힌다() {
            Recorder r = recorderWith(RecorderConfig.builder());

            Path file = r.capture(Trigger.Kind.LATENCY, "GET /x", null, 4000L, null).orElseThrow();
            Recording read = new RecordingCodec().read(file);

            assertThat(read.integrity().instrumentationDisabled()).isFalse();
            assertThat(read.integrity().agentErrors()).isZero();
        }
    }

    @Nested
    @DisplayName("🔴 포획 폭주를 막는다")
    class Limits {

        @Test
        @DisplayName("같은 사고가 또 나면 파일을 더 만들지 않고 «몇 번째인지»를 센다")
        void 같은_사고는_한_번만_파일이_된다() {
            Recorder r = recorderWith(RecorderConfig.builder());

            Optional<Path> first = r.capture(Trigger.Kind.EXCEPTION, "GET /api/orders",
                    boom(), 10L, "OrderService.findAll:88");
            Optional<Path> second = r.capture(Trigger.Kind.EXCEPTION, "GET /api/orders",
                    boom(), 10L, "OrderService.findAll:88");

            assertThat(first).isPresent();
            assertThat(second).isEmpty();
            // 막았어도 «몇 번 났는지»는 들고 있어야 한다.
            assertThat(r.timesSeen(Trigger.Kind.EXCEPTION, "GET /api/orders", "OrderService.findAll:88"))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("🔴 난 자리가 다르면 다른 사고다 — 예외 종류로만 묶지 않는다")
        void 난_자리가_다르면_다른_사고다() {
            Recorder r = recorderWith(RecorderConfig.builder());

            Optional<Path> a = r.capture(Trigger.Kind.EXCEPTION, "GET /api/orders", boom(), 10L, "A.m:1");
            Optional<Path> b = r.capture(Trigger.Kind.EXCEPTION, "GET /api/orders", boom(), 10L, "B.m:2");

            assertThat(a).isPresent();
            assertThat(b).isPresent();
        }

        @Test
        @DisplayName("시간당 상한에 걸리면 더 만들지 않는다")
        void 시간당_상한에_걸린다() {
            Recorder r = recorderWith(RecorderConfig.builder().capturesPerHour(2));

            assertThat(r.capture(Trigger.Kind.EXCEPTION, "GET /a", boom(), 1L, "A:1")).isPresent();
            assertThat(r.capture(Trigger.Kind.EXCEPTION, "GET /b", boom(), 1L, "B:1")).isPresent();
            assertThat(r.capture(Trigger.Kind.EXCEPTION, "GET /c", boom(), 1L, "C:1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("🔴 기록기의 실패가 앱으로 새지 않는다")
    class NeverBreakTheApp {

        @Test
        @DisplayName("쓸 수 없는 폴더를 줘도 예외가 밖으로 안 나온다")
        void 쓰기_실패가_예외로_안_나온다() {
            // 파일을 폴더 자리에 놓으면 그 아래로는 쓸 수 없다.
            Path notADirectory = storeDir.resolve("막힌자리");
            try {
                java.nio.file.Files.writeString(notADirectory, "나는 파일이다");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            recorder = new Recorder(RecorderConfig.builder()
                    .storeDir(notADirectory.resolve("안쪽"))
                    .build());

            // 던지지 «않고» 비어 있는 결과를 돌려준다. 그리고 실패했다는 사실은 센다.
            assertThat(recorder.capture(Trigger.Kind.LATENCY, "GET /x", null, 4000L, null)).isEmpty();
            assertThat(recorder.errorCount()).isPositive();
        }

        @Test
        @DisplayName("SQL 기록 중 무엇이 잘못돼도 앱 쪽으로 예외가 안 나온다")
        void sql_기록_실패가_예외로_안_나온다() {
            Recorder r = recorderWith(RecorderConfig.builder());

            // SQL 이 null 이어도(못 알아낸 경우) 던지지 않는다.
            r.recordSql(null, null, null, 0);
            r.recordSql("select 1", List.of(), 1, 1);

            assertThat(r.disabled()).isFalse();
        }
    }
}
