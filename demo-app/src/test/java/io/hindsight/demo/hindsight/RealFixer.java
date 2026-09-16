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

    /**
     * 🔴 <b>패치 경로의 기준.</b> 멀티모듈에서는 «모듈» 뿌리다 —
     * 화이트리스트가 {@code src/main/java/} 이므로 경로도 그 기준이어야 한다.
     */
    private final Path 패치뿌리;

    /** 🔴 <b>빌드를 부르는 자리.</b> 멀티모듈에서는 «저장소» 뿌리다. 위와 다를 수 있다. */
    private final Path 빌드뿌리;

    private final PatchApplier 적용기;
    private final 명령 명령기;

    /**
     * 🔴 지금 판의 패치. 두 가지에 쓴다.
     * <ol>
     *   <li>㉣ 이 통과한 뒤 «다시 붙일» 때</li>
     *   <li>🔴 되돌린 «뒤»에도 「LLM 이 뭘 냈는지」를 남길 때</li>
     * </ol>
     *
     * <p>⚠️ 2026-09-16 에 여기를 «되돌리기가 비우는 목록»에서 읽으려다 실패했다 —
     * 되돌리고 나면 목록이 비어서 <b>패치가 아무 데도 안 남았다.</b>
     * 「되돌렸다」와 「버렸다」는 다른 사실인데, 그때는 버린 것이 됐다.
     */
    private Map<String, String> 지금패치 = Map.of();

    /** 마지막 바깥 명령의 출력. 🔴 「테스트가 실패했다」와 「빌드를 못 돌렸다」를 가르는 데 쓴다. */
    private String 마지막출력 = "";

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

    RealFixer(Path 패치뿌리, 명령 명령기) {
        this(패치뿌리, 패치뿌리, 명령기);
    }

    /**
     * @param 패치뿌리 패치 경로의 기준. 화이트리스트가 {@code src/main/java/} 이므로
     *                 멀티모듈에서는 <b>모듈 뿌리</b>여야 한다
     * @param 빌드뿌리 {@code gradlew} 가 있는 곳. 🔴 위와 다를 수 있고,
     *                 <b>그 둘을 하나로 뭉치면 멀티모듈에서 경로가 화이트리스트를 벗어난다</b>
     */
    RealFixer(Path 패치뿌리, Path 빌드뿌리, 명령 명령기) {
        this.패치뿌리 = 패치뿌리;
        this.빌드뿌리 = 빌드뿌리;
        this.적용기 = new PatchApplier(패치뿌리);
        this.명령기 = 명령기;
    }

    /**
     * 🔴 <b>여기서 «적용하지 않는다». 경로만 본다.</b>
     *
     * <h2>왜 — 재서 알았다</h2>
     * 처음에는 여기서 바로 파일을 썼다. 그랬더니 네 겹의 ㉠(기준선 실패)이 <b>실패</b>했다:
     * <i>「생성된 테스트가 «패치 전»에도 통과한다」</i>.
     *
     * <p>당연했다 — {@code VerificationLoop} 은 <b>㉠ 을 «맨 처음»에</b> 재는데,
     * 그때 이미 패치가 디스크에 있었다. 즉 <b>「패치 전」이 「패치 후」였다.</b>
     *
     * <p>🔴 그러면 고리가 「버그를 못 살린 테스트다」라며 <b>LLM 을 부르지도 않고 끝낸다</b> —
     * 실은 패치가 멀쩡했는데도.
     *
     * <p>지금은 <b>경로 검사만</b> 하고, 쓰기는 {@code 패치를_적용한다()} 에서 한다.
     * 그게 {@code VerificationLoop} 이 요구하는 순서다.
     *
     * @return 🔴 경로에 걸리면 {@code null}. 그때는 <b>아무것도 안 썼다</b>
     */
    @Override
    public VerificationLoop.Runner 준비한다(Map<String, String> 패치) {
        // 🔴 «지난 시도»가 붙여 놓은 것이 남아 있으면 먼저 뗀다.
        //
        // ⚠️ 2026-09-16 에 여기가 없어서 크게 헛돌았다. 네 겹은 ㉠ 에서 일찍 끝날 수 있고
        //    (「패치 전에도 통과한다」), 그때는 «되돌리는 코드가 안 불린다».
        //    그러면 다음 시도의 준비 단계가 «이미 패치된 파일»을 「원래 내용」으로 읽고,
        //    그 뒤로는 되돌려도 패치된 상태로 돌아간다 — 기준선이 기준선이 아니게 된다.
        //    🔴 그 상태에서는 ㉠ 이 «영원히» 「패치 전에도 통과한다」로 나온다.
        되돌린다();

        // 🔴 «쓰지 않고» 판정만 받는다. 실제 파일 검사(존재 · 심볼릭 링크)도 여기서 같이 한다.
        PatchVerdict 판정 = 적용기.judgeOnly(패치);
        마지막판정 = 판정;
        if (판정.level() != PatchVerdict.Level.ALLOW) {
            원래내용.clear();
            지금패치 = Map.of();
            return null;
        }
        // 🔴 쓰기 «전»에 원래 내용을 들고 있는다. 안 그러면 되돌릴 수 없다.
        원래내용.clear();
        패치.keySet().forEach(경로 -> 원래내용.put(경로, 읽는다(패치뿌리.resolve(경로))));
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
            Path 파일 = 패치뿌리.resolve(경로);
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
        // 🔴 «비우지 않는다». 네 겹의 ㉣ 은 되돌려 본 뒤 통과하면 패치를 «다시 붙이고»,
        //    그 뒤에 바깥이 한 번 더 되돌린다. 여기서 비우면 그 마지막 되돌리기가
        //    아무것도 못 되돌려서, 고리가 «성공했을 때» 작업 트리가 고쳐진 채로 남는다.
        //    ⚠️ 2026-09-16 에 실제로 그랬다 — 실패 경로만 보고 성공 경로를 놓쳤다.
        //    비우는 것은 준비한다() 가 다음 판을 시작할 때 한다.
    }

    /** 무엇을 되돌릴 것인지. 보고에 그대로 나간다. */
    List<String> 건드린_경로() {
        return List.copyOf(원래내용.keySet());
    }

    /**
     * 🔴 <b>LLM 이 낸 패치. 되돌린 «뒤»에도 남아 있다.</b>
     *
     * <p>안 남기면 되돌리는 순간 사라지고, 왜 통과했는지/왜 실패했는지를 나중에 못 본다.
     */
    Map<String, String> 지금패치() {
        return 지금패치;
    }

    /** 마지막 바깥 명령의 출력. 🔴 실패했을 때 «무엇 때문에»를 사람이 읽어야 한다. */
    String 마지막출력() {
        return 마지막출력;
    }

    // ────────────────────────────────────────────────────────────────────────

    private final class Runner implements VerificationLoop.Runner {

        /**
         * 🔴 지금 패치가 «붙어 있나». 네 겹이 붙였다 뗐다 하므로 여기서 들고 있어야 한다.
         * ⚠️ <b>{@code false} 로 시작한다</b> — 준비 단계에서는 «안 쓴다».
         */
        private boolean 붙어있나 = false;

        @Override
        public boolean 재생_테스트가_통과하나() {
            // 🔴 새 프로세스로 돌린다. 지금 JVM 은 «패치 전» 클래스를 들고 있다.
            // 🔴 «한 방향만» 보는 시험을 쓴다. BaselineFailsTest 는 「버그가 있으면 실패,
            //    고치면 통과」를 둘 다 단언해서, 패치가 붙으면 앞쪽 단언이 반드시 깨진다 —
            //    그러면 ㉡ 이 영영 통과할 수 없다. 2026-09-16 에 실제로 그렇게 두 번 실패했다.
            var r = 명령기.돌린다(List.of(gradlew(), ":demo-app:replayCheck",
                    "--console=plain"), 빌드뿌리);
            마지막출력 = r.출력();
            return r.ok();
        }

        @Override
        public boolean 기존_테스트가_전부_통과하나() {
            var r = 명령기.돌린다(List.of(gradlew(), ":demo-app:test",
                    "--tests", "io.hindsight.demo.order.*", "--console=plain"), 빌드뿌리);
            마지막출력 = r.출력();
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
                // 🔴 «절대 경로»로 부른다. 2026-09-16 에 "gradlew.bat" 만 줬더니
                //    'gradlew.bat'은(는) 내부 또는 외부 명령이 아닙니다 가 났다 —
                //    PATH 에 없고, 작업 디렉터리를 줘도 cmd 는 그걸 PATH 로 안 본다.
                //
                // ⚠️ 그때 그 실패가 «테스트 실패»로 읽혔다. 세 번 시도해서 세 번 다
                //    「패치 후에도 실패한다」가 나왔는데, 실은 한 번도 «안 돌았다».
                //    그래서 지금은 바깥 명령의 출력을 남긴다 — 그게 이걸 찾아 줬다.
                실제.set(0, "cmd");
                실제.add(1, "/c");
                실제.add(2, 작업디렉터리.resolve("gradlew.bat").toAbsolutePath().toString());
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
