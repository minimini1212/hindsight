package io.hindsight.demo.hindsight;

import io.hindsight.core.replay.GeneratedTest;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * 🔴 <b>생성된 테스트를 «진짜로» 컴파일해서 돌린다.</b>
 *
 * <h2>왜 이게 필요한가</h2>
 * 여기까지 이 프로젝트는 생성된 소스가 <b>컴파일되는 것</b>까지만 확인했다.
 * 그런데 채점의 첫 겹은 <b>「패치 전 코드에서 정말 실패하는가」</b>이고,
 * 그건 <b>돌려 봐야</b> 알 수 있다. 안 돌려 보고 「실패했을 것이다」로 넘어가면,
 * 버그를 못 살린 테스트로 「고쳤다」를 선언하게 된다.
 *
 * <h2>🔴 디스크의 파일을 «읽지» 않는다</h2>
 * 소스를 파일로 떨궈 두고 그걸 컴파일하면, LLM 이 그 파일을 고쳐서 통과시킬 수 있다.
 * 그래서 <b>메모리 안에서 컴파일한다</b> — 디스크에는 클래스 파일만 잠깐 생긴다.
 *
 * <h2>실행 흐름</h2>
 * <pre>
 *   ① 기록 → 소스        (ReplayTestGenerator)
 *   ② 소스 → 클래스       메모리에서 javac. 클래스패스는 «지금 JVM 의 것»을 그대로 준다
 *   ③ 클래스 → 실행       JUnit Platform Launcher 로 돌린다
 *   ④ 통과했나 / 왜 실패했나
 * </pre>
 */
final class GeneratedTestRunner {

    private GeneratedTestRunner() {}

    /**
     * @param 돌았나   테스트가 «실행»은 됐나. 🔴 컴파일이 안 되면 거짓이고,
     *                그건 「실패」와 다른 사실이다
     * @param 통과했나 실행됐고 통과했나
     * @param 왜       실패했다면 그 이유
     */
    record 결과(boolean 돌았나, boolean 통과했나, String 왜) {

        /** 🔴 「패치 전에 실패했다」가 참이려면, «돌았고» «실패»해야 한다. */
        boolean 제대로_실패했나() {
            return 돌았나 && !통과했나;
        }
    }

    static 결과 컴파일해서_돌린다(GeneratedTest generated) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            // 🔴 JRE 로 돌면 컴파일러가 없다. 「통과」도 「실패」도 아니고 «못 돌렸다»다.
            return new 결과(false, false, "javac 가 없어서 돌리지 못했다");
        }

        Path 클래스자리;
        try {
            클래스자리 = Files.createTempDirectory("hindsight-generated-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        try (var fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(클래스자리.toFile()));
            // 🔴 클래스패스를 «명시»로 준다. Gradle 테스트 JVM 의 기본값을 그대로 믿을 수 없다.
            fileManager.setLocation(StandardLocation.CLASS_PATH, 지금_클래스패스());

            var diagnostics = new javax.tools.DiagnosticCollector<JavaFileObject>();
            boolean 컴파일됨 = compiler.getTask(null, fileManager, diagnostics,
                    List.of("-proc:none"), null,
                    List.of(new 메모리소스(generated.className(), generated.source()))).call();

            if (!컴파일됨) {
                String 오류 = diagnostics.getDiagnostics().stream()
                        .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                        .map(d -> d.getMessage(null))
                        .findFirst().orElse("이유를 모르겠다");
                return new 결과(false, false, "컴파일되지 않았다: " + 오류);
            }

            return 돌린다(generated.className(), 클래스자리);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static 결과 돌린다(String className, Path 클래스자리) {
        // 🔴 부모를 지금 클래스로더로 둔다. 그래야 생성된 테스트가 기반 클래스와
        //    «같은» 클래스를 본다 — 따로 읽으면 타입이 둘이 되어 ClassCastException 이 난다.
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{클래스자리.toUri().toURL()},
                GeneratedTestRunner.class.getClassLoader())) {

            Class<?> testClass = loader.loadClass(className);

            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            LauncherDiscoveryRequest 요청 = request().selectors(selectClass(testClass)).build();
            Launcher launcher = LauncherFactory.create();
            launcher.execute(요청, listener);

            TestExecutionSummary summary = listener.getSummary();
            if (summary.getTestsFoundCount() == 0) {
                // 🔴 「테스트가 없다」를 「통과」로 치지 않는다. 아무것도 안 돌린 것이다.
                return new 결과(false, false, "생성된 클래스에 돌릴 테스트가 없다");
            }
            boolean 통과 = summary.getTestsFailedCount() == 0;
            String 왜 = 통과 ? null : summary.getFailures().stream()
                    .map(f -> f.getException() == null ? "?" : f.getException().getMessage())
                    .findFirst().orElse("이유를 모르겠다");
            return new 결과(true, 통과, 왜);

        } catch (Exception e) {
            return new 결과(false, false, "돌리지 못했다: " + e);
        }
    }

    private static List<File> 지금_클래스패스() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(File::new)
                .toList();
    }

    /** 메모리 안의 자바 소스 하나. 🔴 파일로 안 떨군다 — 떨구면 고칠 수 있게 된다. */
    private static final class 메모리소스 extends SimpleJavaFileObject {
        private final String code;

        private 메모리소스(String className, String code) {
            super(URI.create("string:///" + className + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
