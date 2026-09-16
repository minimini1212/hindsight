package io.hindsight.core.cli;

import io.hindsight.core.replay.GeneratedTest;
import io.hindsight.core.replay.ReplayTestGenerator;
import io.hindsight.core.store.RecordingCodec;
import io.hindsight.model.Recording;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code hs} — 기록을 사람이 들여다보는 명령어.
 *
 * <pre>
 *   hs list              기록 목록. 최근 것이 위
 *   hs show &lt;번호&gt;       기록 하나를 자세히
 *   hs test &lt;번호&gt;       그 기록을 「실패하는 JUnit 테스트」로 찍어 낸다
 *   hs replay &lt;번호&gt;     🔴 재생하는 «방법»을 알려 준다. 재생 자체는 앱이 한다
 * </pre>
 *
 * <h2>🔴 왜 picocli 를 안 쓰나 — 재 보고 정했다</h2>
 * 설계는 명령줄에 picocli 를 쓴다고 적어 뒀다. 그런데 넣어 보니 <b>빌드가 막혔다.</b>
 *
 * <pre>
 *   core 에 picocli 를 넣는다
 *     → recorder-simple 이 core 를 의존한다
 *       → demo-app 이 recorder-simple 을 의존한다
 *         → 🔴 picocli 가 «관측 대상 앱»의 클래스패스로 들어간다
 * </pre>
 *
 * <p>2026-09-15 에 만들어 둔 {@code checkRecorderDependencies} 가 그 자리에서 울렸고,
 * demo-app 의 의존성 목록에 picocli 가 실제로 나타나는 것도 확인했다.
 *
 * <p>🔴 <b>그래서 의존성을 안 쓴다.</b> 명령이 둘이고 옵션이 거의 없어서
 * 직접 읽는 편이 싸다. 라이브러리를 넣으려면 {@code core.store} 를 따로 모듈로
 * 떼어내야 하는데, <b>둘짜리 명령어가 그 값을 치를 이유가 없다.</b>
 * 🧭 잰 값과 버린 길: {@code docs/reports/2026-09-16/hs-cli.md}
 */
public final class HindsightCli {

    private final Path storeDir;
    private final PrintStream out;
    private final RecordingCodec codec = new RecordingCodec();

    public HindsightCli(Path storeDir, PrintStream out) {
        this.storeDir = storeDir;
        this.out = out;
    }

    public static void main(String[] args) {
        // 🔴 기록 폴더는 설정에서 읽는다. 여기에 기본값을 또 적으면 기록기 쪽 기본값과
        //    갈라지고, 그러면 「기록이 없다」가 나오는데 이유를 아무도 모른다.
        String dir = System.getenv("HINDSIGHT_STORE_DIR");
        PrintStream stdout = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        int code = new HindsightCli(Path.of(dir == null || dir.isBlank() ? "recordings" : dir), stdout)
                .run(args);
        System.exit(code);
    }

    /** @return 종료 코드. 0 이 아니면 뭔가 잘못된 것이다 */
    public int run(String[] args) {
        if (args.length == 0) {
            out.print(usage());
            return 2;
        }
        return switch (args[0]) {
            case "list" -> list();
            case "show" -> args.length < 2 ? 번호가_없다() : show(args[1]);
            case "test" -> args.length < 2 ? 번호가_없다() : test(args[1], 기반클래스(args));
            case "replay" -> args.length < 2 ? 번호가_없다() : replay(args[1]);
            case "help", "--help", "-h" -> {
                out.print(usage());
                yield 0;
            }
            default -> {
                out.println("모르는 명령이다: " + args[0]);
                out.print(usage());
                yield 2;
            }
        };
    }

    private int list() {
        List<Recording> recordings = readAll();
        if (recordings == null) {
            return 1;
        }
        out.print(CliRenderer.renderList(recordings));
        return 0;
    }

    private int show(String id) {
        List<Recording> recordings = readAll();
        if (recordings == null) {
            return 1;
        }
        Recording found = recordings.stream()
                .filter(r -> id.equals(r.id()))
                .findFirst()
                .orElse(null);
        if (found == null) {
            // 🔴 「없다」와 「폴더를 못 읽었다」를 다르게 말한다.
            out.println("그 번호의 기록이 없다: " + id);
            out.println("`hs list` 로 있는 번호를 볼 수 있다.");
            return 1;
        }
        out.print(CliRenderer.renderShow(found));
        return 0;
    }

    /**
     * 🔴 <b>기록을 「실패하는 테스트」로 바꿔서 «화면에» 찍는다. 파일로 쓰지 않는다.</b>
     *
     * <p>파일로 떨구면 LLM 이 그 파일을 고쳐서 통과시킬 수 있다. 채점기를 고칠 수 있으면
     * 채점이 아니므로, 여기서도 같은 규율을 따른다 — 고리 «안»에서는 메모리에서
     * 컴파일해서 돌리고, 사람이 볼 때는 화면으로 본다.
     *
     * <p>⚠️ 그래서 이 명령은 <b>재생을 «돌리지» 않는다.</b> 재생은 관측 대상 앱의 JVM 안에서
     * 일어나야 하고(DB 를 되돌리고 요청을 다시 보낸다), 명령줄에는 그 앱이 없다.
     * 이 명령이 하는 것은 <b>「무엇을 단언할 것인가」를 사람이 읽게 하는 것</b>이다.
     */
    private int test(String id, String baseClassName) {
        List<Recording> recordings = readAll();
        if (recordings == null) {
            return 1;
        }
        Recording found = recordings.stream().filter(r -> id.equals(r.id())).findFirst().orElse(null);
        if (found == null) {
            out.println("그 번호의 기록이 없다: " + id);
            out.println("`hs list` 로 있는 번호를 볼 수 있다.");
            return 1;
        }

        GeneratedTest generated;
        try {
            generated = ReplayTestGenerator.generate(found, baseClassName);
        } catch (RuntimeException e) {
            // 🔴 「만들지 못했다」를 빈 출력으로 넘기지 않는다. 빈 출력은 「단언할 게 없다」로 읽힌다.
            out.println("이 기록으로는 테스트를 만들지 못했다: " + e.getMessage());
            return 1;
        }

        out.println(generated.describe());
        out.println();
        out.println("── " + generated.fileName() + " ──");
        out.println(generated.source());
        out.println();
        out.println("🔴 이 소스는 «파일로 안 쓴다». 디스크에 있으면 고쳐서 통과시킬 수 있기 때문이다.");
        out.println("   붙여 넣을 자리: 관측 대상 앱의 src/test 아래, 기반 클래스 " + baseClassName);
        if (기본_기반클래스.equals(baseClassName)) {
            // 🔴 「만들었다」로 끝내면 받는 사람은 이게 도는 줄 안다. 안 돈다.
            out.println();
            out.println("⚠️ 기반 클래스가 기본값(" + 기본_기반클래스 + ")이다. 이건 «추상 클래스»라");
            out.println("   이대로 붙여 넣으면 컴파일이 안 된다. 앱이 되돌리기와 요청 보내기를 채운");
            out.println("   클래스를 만들고 `--base <그 클래스>` 로 다시 뽑아야 한다.");
        }
        return 0;
    }

    /** 기본 기반 클래스. 🔴 <b>추상 클래스라 이대로는 «안 돈다»</b> — 그래서 경고를 붙인다. */
    static final String 기본_기반클래스 = "io.hindsight.core.replay.ReplayTestBase";

    /**
     * 생성된 테스트가 상속할 기반 클래스.
     *
     * <p>🔴 되돌리기와 요청 보내기는 <b>앱마다 다르다.</b> 기본값인 {@code ReplayTestBase} 는
     * 추상 클래스라, 그대로 붙여 넣으면 <b>컴파일이 안 된다.</b> 2026-09-15 에 실제로
     * 그 일이 있었고, javac 의 오류 메시지가 「추상 메서드를 안 덮었다」로 나와서
     * 진짜 원인이 가려졌다. 그래서 이 명령은 <b>기본값을 쓸 때 경고를 찍는다.</b>
     */
    private String 기반클래스(String[] args) {
        for (int i = 2; i < args.length - 1; i++) {
            if ("--base".equals(args[i])) {
                return args[i + 1];
            }
        }
        return 기본_기반클래스;
    }

    /**
     * 🔴 <b>재생을 «여기서» 돌리지 않는다. 돌릴 수가 없다.</b>
     *
     * <h2>왜 못 하나</h2>
     * 재생은 <b>DB 를 되돌리고 요청을 다시 보내는 일</b>이다. 둘 다 <b>관측 대상 앱만</b>
     * 할 수 있다 — 어떤 DB 를 쓰는지, 어느 포트로 받는지는 앱이 안다.
     * <b>명령줄에는 그 앱이 없다.</b>
     *
     * <p>⚠️ <b>그렇다고 「재생했다」인 척하지 않는다.</b> 이 명령이 하는 일은
     * <b>무엇을 재생할 것이고 무엇이 확인 안 될지를 미리 말해 주는 것</b>이고,
     * 실제로 돌리는 명령을 그대로 찍어 준다.
     */
    private int replay(String id) {
        List<Recording> recordings = readAll();
        if (recordings == null) {
            return 1;
        }
        Recording found = recordings.stream().filter(r -> id.equals(r.id())).findFirst().orElse(null);
        if (found == null) {
            out.println("그 번호의 기록이 없다: " + id);
            out.println("`hs list` 로 있는 번호를 볼 수 있다.");
            return 1;
        }

        out.println(CliRenderer.renderReplayPlan(found));
        out.println("── 이렇게 돌린다 ──");
        out.println("  ./gradlew :demo-app:replay -Pid=" + id);
        out.println();
        out.println("🔴 `hs` 가 직접 못 돌리는 이유: 재생은 DB 를 되돌리고 요청을 다시 보내는");
        out.println("   일이고, 둘 다 «관측 대상 앱»만 할 수 있다. 명령줄에는 그 앱이 없다.");
        return 0;
    }

    private int 번호가_없다() {
        out.println("기록 번호가 필요하다.  예: hs show a1b2");
        return 2;
    }

    /** @return 못 읽으면 {@code null}. 🔴 빈 목록으로 돌려주면 「기록이 없다」와 구별이 안 된다 */
    private List<Recording> readAll() {
        if (!Files.isDirectory(storeDir)) {
            out.println("기록 폴더가 없다: " + storeDir.toAbsolutePath());
            out.println("HINDSIGHT_STORE_DIR 환경변수로 폴더를 지정할 수 있다.");
            return null;
        }
        List<Recording> recordings = new ArrayList<>();
        try (Stream<Path> files = Files.list(storeDir)) {
            List<Path> jsons = files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong(HindsightCli::lastModified).reversed())
                    .toList();
            for (Path json : jsons) {
                try {
                    recordings.add(codec.read(json));
                } catch (RuntimeException e) {
                    // 🔴 파일 하나가 깨졌다고 나머지를 안 보여 주지 않는다. 대신 «조용히
                    //    빼지도» 않는다 — 안 보이면 그 파일이 없는 것으로 읽힌다.
                    out.println("⚠️ 못 읽은 기록이 있다: " + json.getFileName() + " — " + e.getMessage());
                }
            }
        } catch (IOException e) {
            out.println("기록 폴더를 읽지 못했다: " + e.getMessage());
            return null;
        }
        return recordings;
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    static String usage() {
        return """
                hs — 기록을 들여다본다

                  hs list           기록 목록 (최근 것이 위)
                  hs show <번호>    기록 하나를 자세히
                  hs test <번호>    그 기록을 「실패하는 JUnit 테스트」로 찍어 낸다
                                    --base <클래스>  기반 클래스 (앱마다 다르다)
                  hs replay <번호>  재생하는 «방법»을 알려 준다 (재생 자체는 앱이 한다)

                기록 폴더는 HINDSIGHT_STORE_DIR 환경변수로 정한다 (기본: recordings)

                🔴 `hs test` 는 소스를 «화면에» 찍는다. 파일로 안 쓴다 —
                   디스크에 있으면 고쳐서 통과시킬 수 있기 때문이다.
                """;
    }
}
