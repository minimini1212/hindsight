package io.hindsight.core.replay;

import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.Recording;
import io.hindsight.model.SqlShapes;
import io.hindsight.model.Summary;
import io.hindsight.model.Trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

@DisplayName("기록을 실패하는 JUnit 테스트로 바꾼다")
class ReplayTestGeneratorTest {

    private static final Instant T0 = Instant.parse("2026-09-15T10:00:00Z");

    /**
     * 🔴 «구체» 기반 클래스. 생성된 소스가 진짜로 컴파일되려면 추상 메서드가 채워진 클래스를
     * 상속해야 한다. 앱이 제공하는 것을 {@link StubReplayTestBase} 가 대신한다.
     */
    private static final String 구체기반클래스 = StubReplayTestBase.class.getName();

    private static Event.HttpIn 요청(Integer status, String body, boolean truncated, String requestBody) {
        return new Event.HttpIn(1, "r-1", T0, "t", 3400L,
                "GET", "/api/orders", null, null, Map.of(),
                requestBody, false, 0, status, body, truncated);
    }

    private static Event.Sql 질의(long seq, String sql) {
        return new Event.Sql(seq, "r-1", T0, "t", 1L, sql, List.of(), null, null, false, null, null);
    }

    private static Recording 기록(Trigger trigger, List<Event> events) {
        return new Recording(Recording.CURRENT_SCHEMA_VERSION, "a1b2", T0, trigger,
                new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                events, null, null, null,
                new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false));
    }

    private static Trigger 지연방아쇠() {
        return new Trigger(Trigger.Kind.LATENCY, T0, "GET /api/orders", null, 3400L, "k", 1);
    }

    /** N+1 이 심긴 기록 — 목록 1번 + 회원 20번. 응답 본문에 «따옴표»가 들어 있다. */
    private static Recording n플러스원_기록() {
        List<Event> events = new ArrayList<>();
        events.add(요청(200, "{\"orders\":[{\"id\":1}]}", false, null));
        events.add(질의(2, "select * from orders"));
        for (int i = 0; i < 20; i++) {
            events.add(질의(3 + i, "select * from member where id = " + i));
        }
        return 기록(지연방아쇠(), events);
    }

    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("무엇을 단언하나")
    class 단언 {

        @Test
        @DisplayName("🔴 질의 반복은 «미만»으로 단언한다 — 「이하」면 그대로여도 통과해서 패치 전에 안 실패한다")
        void 반복은_미만으로_단언한다() {
            GeneratedTest generated = ReplayTestGenerator.generateForShapeOnly(n플러스원_기록());

            assertThat(generated.source()).contains("worstQueryRepeat()");
            assertThat(generated.source()).contains("isLessThan(20)");
            assertThat(generated.oracles()).anyMatch(o -> o.contains("20번보다 줄었다"));
        }

        @Test
        @DisplayName("응답 상태와 본문을 둘 다 단언한다 — 상태만 보면 예외를 삼키는 패치가 통과한다")
        void 상태와_본문을_둘_다_단언한다() {
            GeneratedTest generated = ReplayTestGenerator.generateForShapeOnly(n플러스원_기록());

            assertThat(generated.source()).contains("assertThat(관찰.status()).isEqualTo(200)");
            assertThat(generated.source()).contains("assertThat(관찰.body())");
        }

        @Test
        @DisplayName("되돌리기가 모자라면 그 실패가 패치 탓이 아님을 테스트가 먼저 말한다")
        void 되돌리기를_먼저_단언한다() {
            GeneratedTest generated = ReplayTestGenerator.generateForShapeOnly(n플러스원_기록());

            assertThat(generated.source()).contains("되돌린다()");
            assertThat(generated.source()).contains("되돌리기가 모자라면");
        }

        @Test
        @DisplayName("예외 방아쇠면 그 예외가 다시 안 나오는지를 단언한다")
        void 예외를_단언한다() {
            Trigger 예외 = new Trigger(Trigger.Kind.EXCEPTION, T0, "GET /api/orders",
                    new Trigger.ExceptionInfo("java.lang.NullPointerException", "x", List.of("A.m:1")),
                    120L, "k", 1);
            GeneratedTest generated = ReplayTestGenerator.generateForShapeOnly(기록(예외, List.of(요청(500, "{\"error\":\"x\"}", false, null))));

            assertThat(generated.source()).contains("java.lang.NullPointerException");
            assertThat(generated.className()).contains("Replay");
            assertThat(generated.source()).contains("NullPointerException 로 죽지 않는다");
        }
    }

    @Nested
    @DisplayName("🔴 기록에 «없는» 것은 단언하지 않는다 — 그러면 버그와 무관하게 실패한다")
    class 없는_것은_단언하지_않는다 {

        @Test
        @DisplayName("응답 본문이 기록에 없으면 본문을 단언하지 않고, 「못 했다」로 남긴다")
        void 본문이_없으면_단언하지_않는다() {
            GeneratedTest generated = ReplayTestGenerator.generateForShapeOnly(기록(지연방아쇠(), List.of(요청(200, null, false, null), 질의(2, "select 1"), 질의(3, "select 1"))));

            assertThat(generated.source()).doesNotContain("assertThat(관찰.body())");
            assertThat(generated.notAsserted()).anyMatch(n -> n.contains("응답 본문"));
        }

        @Test
        @DisplayName("🔴 본문이 «잘려» 있으면 단언하지 않는다 — 잘린 것끼리 견주면 앞부분만 같아도 통과다")
        void 잘린_본문은_단언하지_않는다() {
            GeneratedTest generated = ReplayTestGenerator.generateForShapeOnly(기록(지연방아쇠(), List.of(요청(200, "{\"orders\":[", true, null))));

            assertThat(generated.source()).doesNotContain("assertThat(관찰.body())");
            assertThat(generated.notAsserted()).anyMatch(n -> n.contains("잘려"));
        }

        @Test
        @DisplayName("요청 본문이 기록에 없으면 «빈 문자열»로 바꾸지 않는다")
        void 요청_본문이_없으면_null_로_보낸다() {
            GeneratedTest generated = ReplayTestGenerator.generateForShapeOnly(n플러스원_기록());

            assertThat(generated.source()).contains("null);   // 🔴 기록에 요청 본문이 «없다»");
            assertThat(generated.notAsserted()).anyMatch(n -> n.contains("요청 본문"));
        }

        @Test
        @DisplayName("반복 결함이 «없는» 기록이면 반복을 단언하지 않는다")
        void 반복이_없으면_단언하지_않는다() {
            GeneratedTest generated = ReplayTestGenerator.generateForShapeOnly(기록(지연방아쇠(), List.of(요청(200, "{}", false, null), 질의(2, "select * from orders"))));

            assertThat(generated.source()).doesNotContain("worstQueryRepeat()");
            assertThat(generated.notAsserted()).anyMatch(n -> n.contains("반복 결함이 «없다»"));
        }

        @Test
        @DisplayName("🔴 「검사하지 못한 것」이 요약에 «항상» 보인다 — 안 보이면 「다 확인했다」로 읽힌다")
        void 못한_것이_요약에_보인다() {
            GeneratedTest generated = ReplayTestGenerator.generateForShapeOnly(n플러스원_기록());

            assertThat(generated.describe()).contains("검사하지 «못한» 것");
            assertThat(generated.source()).contains("검사하지 «못한» 것");
        }
    }

    @Nested
    @DisplayName("🔴 만들어진 소스가 «진짜 자바»인지 — 컴파일해서 본다")
    class 컴파일 {

        @Test
        @DisplayName("따옴표가 든 JSON 본문이 있어도 컴파일된다")
        void 따옴표가_있어도_컴파일된다() throws IOException {
            // 🔴 JSON 본문에는 따옴표가 «반드시» 들어 있다. 이스케이프를 빠뜨리면 거의 항상 깨진다.
            assertThat(컴파일한다(ReplayTestGenerator.generate(n플러스원_기록(), 구체기반클래스))).isEmpty();
        }

        @Test
        @DisplayName("🔴 줄바꿈·역슬래시·제어 문자가 든 본문이 있어도 컴파일된다")
        void 고약한_본문도_컴파일된다() throws IOException {
            String 고약한본문 = "{\n  \"path\": \"C:\\\\temp\\\\x\",\n  \"tab\": \"a\tb\",\n  \"bell\": \"\u0007\"\n}";
            GeneratedTest generated = ReplayTestGenerator.generate(
                    기록(지연방아쇠(), List.of(요청(200, 고약한본문, false, 고약한본문),
                            질의(2, "select 1"), 질의(3, "select 1"))),
                    구체기반클래스);

            assertThat(컴파일한다(generated)).isEmpty();
        }

        /** @return 컴파일 오류 메시지. 비어 있으면 컴파일된 것이다. */
        private List<String> 컴파일한다(GeneratedTest generated) throws IOException {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            // JRE 로 돌면 컴파일러가 없다. 그때는 «확인 못 했다»이지 «통과»가 아니다.
            assumeThat(compiler).as("javac 가 없어서 이 검사를 못 한다").isNotNull();

            Path work = Files.createTempDirectory("hindsight-gen-");
            try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(work.toFile()));
                // 🔴 클래스패스를 «명시»로 준다. 기본값(java.class.path)은 Gradle 테스트 JVM 에서
                //    비어 있거나 매니페스트 jar 하나뿐이라, 기반 클래스를 못 찾는다.
                fileManager.setLocation(StandardLocation.CLASS_PATH,
                        java.util.Arrays.stream(System.getProperty("java.class.path")
                                        .split(java.io.File.pathSeparator))
                                .map(java.io.File::new).toList());

                var diagnostics = new javax.tools.DiagnosticCollector<JavaFileObject>();
                // 🔴 이 시험은 «문법»만 본다. 생성된 소스가 기대는 기반 클래스는 아직 없으므로
                //    「못 찾겠다」류 오류는 빼고, 진짜 문법 오류만 센다.
                compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null,
                        List.of(new InMemorySource(generated.className(), generated.source()))).call();

                List<String> 문법오류 = new ArrayList<>();
                diagnostics.getDiagnostics().forEach(d -> {
                    String message = d.getMessage(null);
                    boolean 못찾는것 = message.contains("package")
                            || message.contains("symbol")
                            || message.contains("cannot find");
                    if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR && !못찾는것) {
                        문법오류.add(message);
                    }
                });
                return 문법오류;
            }
        }
    }

    @Nested
    @DisplayName("🔴 매번 새로 만들어지고, 같은 기록이면 «같은» 소스가 나온다")
    class 결정론 {

        @Test
        @DisplayName("두 번 만들어도 글자까지 같다")
        void 두_번_만들어도_같다() {
            Recording recording = n플러스원_기록();

            assertThat(ReplayTestGenerator.generateForShapeOnly(recording).source())
                    .isEqualTo(ReplayTestGenerator.generateForShapeOnly(recording).source());
        }

        @Test
        @DisplayName("같은 기록이면 클래스 이름도 같다 — 이름이 매번 바뀌면 「전에 본 그 테스트」인지 알 수 없다")
        void 이름도_같다() {
            Recording recording = n플러스원_기록();

            assertThat(ReplayTestGenerator.generateForShapeOnly(recording).className())
                    .isEqualTo(ReplayTestGenerator.generateForShapeOnly(recording).className());
        }

        @Test
        @DisplayName("🔴 생성물이 「고쳐도 소용없다」고 스스로 말한다")
        void 고쳐도_소용없다고_적혀_있다() {
            String source = ReplayTestGenerator.generateForShapeOnly(n플러스원_기록()).source();

            assertThat(source).contains("채점할 때마다 새로 만들어진다");
            assertThat(source).contains("고쳐도 다음 채점에 반영되지 않는다");
        }
    }

    @Nested
    @DisplayName("재생할 수 없는 기록")
    class 재생할_수_없는_기록 {

        @Test
        @DisplayName("🔴 들어온 요청이 없으면 «통과하는» 테스트를 만들지 않는다")
        void 요청이_없으면_실패하는_테스트를_만든다() {
            GeneratedTest generated = ReplayTestGenerator.generateForShapeOnly(기록(지연방아쇠(), List.of(질의(1, "select 1"))));

            // 빈 테스트를 만들어 통과시키느니, 만들 수 없다고 «말하는» 테스트를 만든다.
            assertThat(generated.source()).contains("Assertions.fail");
            assertThat(generated.source()).contains("기록이 잘못된 것이지 코드가 잘못된 것이 아니다");
            assertThat(generated.notAsserted()).anyMatch(n -> n.contains("전부"));
        }
    }

    /** 메모리 안의 자바 소스 하나. 파일로 안 떨구고 컴파일한다. */
    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String code;

        private InMemorySource(String className, String code) {
            super(URI.create("string:///" + className + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    @Nested
    @DisplayName("🔴 생성물이 «실재하는» 계약을 가리킨다")
    class 계약 {

        @Test
        @DisplayName("기반 클래스가 진짜로 있다 — 허공을 가리키는 소스를 만들지 않는다")
        void 기반_클래스가_실재한다() {
            String source = ReplayTestGenerator.generateForShapeOnly(n플러스원_기록()).source();

            assertThat(source).contains("io.hindsight.core.replay.ReplayTestBase");
            // 🔴 이름만 맞추는 게 아니라 «그 타입이 실제로 있는지»를 본다.
            assertThat(ReplayTestBase.class.getName())
                    .isEqualTo("io.hindsight.core.replay.ReplayTestBase");
        }

        @Test
        @DisplayName("생성물이 부르는 메서드가 계약에 «전부» 있다")
        void 부르는_메서드가_계약에_있다() {
            String source = ReplayTestGenerator.generateForShapeOnly(n플러스원_기록()).source();
            List<String> 계약메서드 = java.util.Arrays.stream(ReplayTestBase.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName).toList();

            if (source.contains("되돌린다()")) {
                assertThat(계약메서드).contains("되돌린다");
            }
            if (source.contains("재생한다(")) {
                assertThat(계약메서드).contains("재생한다");
            }
            // 관찰에서 읽는 것들도 계약에 있어야 한다.
            List<String> 관찰필드 = java.util.Arrays.stream(
                            ReplayTestBase.Observed.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName).toList();
            assertThat(관찰필드).contains("status", "body", "escapedTypeOrNull", "worstQueryRepeat");
        }
    }
}
