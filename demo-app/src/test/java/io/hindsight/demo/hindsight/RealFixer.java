package io.hindsight.demo.hindsight;

import io.hindsight.core.brain.FixPipeline;
import io.hindsight.core.brain.VerificationLoop;
import io.hindsight.core.guard.PatchApplier;
import io.hindsight.core.guard.PatchVerdict;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🔴 <b>진짜로 파일을 쓰고, 진짜로 다시 빌드하고, 진짜로 테스트를 돌리는 수선공.</b>
 *
 * <h2>왜 «다시 빌드»가 필요한가 — 이게 이 클래스의 존재 이유다</h2>
 * 지금까지의 실험은 전부 <b>설정 한 줄</b>로 「패치」를 흉내 냈다. 진짜 패치는
 * <b>소스 파일을 고치는 것</b>이고, 고친 소스는 <b>다시 컴파일해야</b> 돈다.
 *
 * <p>🔴 그런데 <b>돌고 있는 JVM 안에서는 그게 안 된다.</b> 이미 로딩된 클래스를
 * 갈아 끼울 수 없다. 그래서 <b>바깥 프로세스로 Gradle 을 부른다.</b>
 *
 * <pre>
 *   패치를 파일로 쓴다 (PatchApplier — 경로 검사가 그 «안»에 있다)
 *     → ./gradlew :demo-app:test 를 «새 프로세스»로 부른다
 *       → 그 프로세스가 컴파일하고 테스트를 돌린다
 * </pre>
 *
 * <h2>⚠️ 느리다. 그리고 그게 정직한 값이다</h2>
 * 한 번 돌 때마다 컴파일 + 스프링 기동이 들어간다. 시도 세 번이면 그게 세 번이다.
 * 🔴 <b>「빠르게 하려고 JVM 안에서 흉내 내는」 길은 이미 걸어 봤고, 그건
 * 「고쳤다」를 증명하지 못한다</b> — 컴파일이 안 되는 패치도 통과해 버린다.
 *
 * <h2>🔴 반드시 되돌린다</h2>
 * 이 클래스는 <b>사람의 작업 트리를 고친다.</b> 되돌리지 않으면 시험 한 번이
 * 저장소를 바꿔 놓는다. {@link #되돌린다()} 를 {@code finally} 에서 부른다.
 */
final class RealFixer implements FixPipeline.수선공 {

    private final Path 저장소뿌리;
    private final PatchApplier 적용기;
    private final 명령 명령기;

    /** 🔴 지금 판의 패치. ㉣ 이 통과한 뒤 «다시 붙일» 때 필요하다. */
    private Map<String, String> 지금패치 = Map.of();

    /** 되돌리기용. 경로 → 패치 «전» 내용. 🔴 없던 파일이면 {@code null} 이 「없었다」다. */
    private final Map<String, String> 원래내용 = new LinkedHashMap<>();

    private PatchVerdict 마지막판정;

    /** 바깥 프로세스를 부르는 쪽. 🔴 시험에서 갈아 끼울 수 있게 밖으로 뺀다. */
    interface 명령 {
        결과 돌린다(List<String> 명령줄, Path 작업디렉터리);

        record 결과(int 종료코드, String 출력) {
            boolean ok() {
                return 종료코드 == 0;
            }
        }
    }

    RealFixer(Path 저장소뿌리, 명령 명령기) {
        this.저장소뿌리 = 저장소뿌리;
        this.적용기 = new PatchApplier(저장소뿌리);
        this.명령기 = 명령기;
    }

    @Override
    public VerificationLoop.Runner 준비한다(Map<String, String> 패치) {
        // 🔴 쓰기 «전»에 원래 내용을 들고 있는다. 안 그러면 되돌릴 수 없다.
        원래내용.clear();
        패치.keySet().forEach(경로 -> 원래내용.put(경로, 읽는다(저장소뿌리.resolve(경로))));

        PatchApplier.Result 결과 = 적용기.apply(패치);
        마지막판정 = 결과.verdict();
        if (!결과.applied()) {
            // 🔴 경로에 걸렸다. «아무것도 안 썼다» — 되돌릴 것도 없다.
            원래내용.clear();
            return null;
        }
        지금패치 = Map.copyOf(패치);
        return new Runner();
    }

    @Override
    public PatchVerdict 마지막_경로판정() {
        return 마지막판정;
    }

    /**
     * 🔴 <b>패치를 되돌린다. {@code finally} 에서 반드시 부른다.</b>
     *
     * <p>이 클래스는 사람의 작업 트리를 고친다. 안 되돌리면 시험 한 번이 저장소를 바꾼다.
     */
    void 되돌린다() {
        원래내용.forEach((경로, 내용) -> {
            Path 파일 = 저장소뿌리.resolve(경로);
            try {
                if (내용 == null) {
                    // 🔴 원래 «없던» 파일이었다. 빈 파일로 남기지 않는다.
                    Files.deleteIfExists(파일);
                } else {
                    Files.writeString(파일, 내용, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("되돌리지 못했다: " + 파일, e);
            }
        });
        원래내용.clear();
    }

    /** 무엇을 되돌릴 것인지. 보고에 그대로 나간다. */
    List<String> 건드린_경로() {
        return List.copyOf(원래내용.keySet());
    }

    // ────────────────────────────────────────────────────────────────────────

    private final class Runner implements VerificationLoop.Runner {

        /** 🔴 지금 패치가 «붙어 있나». 네 겹이 붙였다 뗐다 하므로 여기서 들고 있어야 한다. */
        private boolean 붙어있나 = true;

        @Override
        public boolean 재생_테스트가_통과하나() {
            // 🔴 새 프로세스로 돌린다. 지금 JVM 은 «패치 전» 클래스를 들고 있다.
            var r = 명령기.돌린다(List.of(gradlew(), ":demo-app:test",
                    "--tests", "*BaselineFailsTest*", "--console=plain"), 저장소뿌리);
            return r.ok();
        }

        @Override
        public boolean 기존_테스트가_전부_통과하나() {
            var r = 명령기.돌린다(List.of(gradlew(), ":demo-app:test",
                    "--tests", "io.hindsight.demo.order.*", "--console=plain"), 저장소뿌리);
            return r.ok();
        }

        /**
         * 🔴 <b>다시 붙일 수 있어야 한다.</b> 네 겹의 ㉣ 은 「되돌리면 다시 실패하나」를 본 뒤,
         * 통과하면 패치를 <b>다시 붙여 둔다</b> — 그래야 그 뒤에 PR 이 그 상태를 커밋한다.
         *
         * <p>⚠️ 이 클래스를 쓰다가 그 자리를 빠뜨린 채로 쓸 뻔했다. 그러면 ㉣ 이 통과한
         * 「고친 상태」가 <b>되돌려진 채로</b> 남고, PR 이 «패치 전» 코드를 올린다.
         */
        @Override
        public boolean 패치를_적용한다() {
            if (붙어있나) {
                return true;
            }
            PatchApplier.Result 결과 = 적용기.apply(지금패치);
            마지막판정 = 결과.verdict();
            붙어있나 = 결과.applied();
            return 붙어있나;
        }

        @Override
        public boolean 패치를_되돌린다() {
            RealFixer.this.되돌린다();
            붙어있나 = false;
            return true;
        }
    }

    private static String gradlew() {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? "cmd" : "./gradlew";
    }

    private static String 읽는다(Path 파일) {
        try {
            return Files.isRegularFile(파일)
                    ? Files.readString(파일, StandardCharsets.UTF_8)
                    : null; // 🔴 「없었다」. 빈 문자열이 아니다
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 진짜로 바깥 프로세스를 부르는 구현. */
    static final class 진짜명령 implements 명령 {

        @Override
        public 결과 돌린다(List<String> 명령줄, Path 작업디렉터리) {
            List<String> 실제 = new ArrayList<>(명령줄);
            if ("cmd".equals(실제.getFirst())) {
                실제.set(0, "cmd");
                실제.add(1, "/c");
                실제.add(2, "gradlew.bat");
            }
            try {
                Process p = new ProcessBuilder(실제)
                        .directory(작업디렉터리.toFile())
                        .redirectErrorStream(true)
                        .start();
                String 출력 = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int 코드 = p.waitFor();
                return new 결과(코드, 출력);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new 결과(-1, "기다리다 중단됐다");
            } catch (IOException e) {
                return new 결과(-1, e.toString());
            }
        }
    }
}
