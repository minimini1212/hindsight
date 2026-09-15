package io.hindsight.core.cli;

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

                기록 폴더는 HINDSIGHT_STORE_DIR 환경변수로 정한다 (기본: recordings)
                """;
    }
}
