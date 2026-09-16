package io.hindsight.demo.hindsight;

import io.hindsight.core.brain.AttemptBudget;
import io.hindsight.core.brain.Diagnosis;
import io.hindsight.core.brain.FixPipeline;
import io.hindsight.core.brain.HttpDiagnosis;
import io.hindsight.core.brain.JdkHttpTransport;
import io.hindsight.core.brain.LlmConfig;
import io.hindsight.core.cli.DotEnv;
import io.hindsight.core.store.RecordingCodec;
import io.hindsight.model.Recording;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 🔴 <b>고리를 «진짜로» 한 바퀴 돌린다 — 진짜 LLM · 진짜 파일 · 진짜 빌드.</b>
 *
 * <pre>
 *   ./gradlew :demo-app:fix -Pid=a1b2c3d4
 * </pre>
 *
 * <h2>여기까지 무엇이 빠져 있었나</h2>
 * 조각은 각각 진짜로 돌았다 — LLM 호출도, 파일 쓰기도, 바깥 Gradle 호출도.
 * 🔴 <b>그런데 셋을 «한 번에 이어서» 돌린 적이 없었다.</b> 그래서
 * 「한 판이 몇 분인가」도 「진짜로 고쳐지는가」도 몰랐다.
 *
 * <h2>⚠️ 이 명령은 «사람의 작업 트리»를 고친다</h2>
 * LLM 이 낸 패치를 진짜로 파일에 쓴다. 🔴 끝나면 <b>반드시 되돌린다</b> —
 * 고쳐진 채로 두면 다음 사람이 「내가 안 한 변경」을 보게 된다.
 *
 * <p>고친 내용은 <b>버리지 않는다</b> — 되돌리기 «전»에 파일로 남기고 어디에 뒀는지 알려 준다.
 * 🔴 <b>「되돌렸다」와 「버렸다」는 다른 사실이다.</b>
 *
 * <h2>🔴 PR 을 올리지 않는다</h2>
 * 글까지만 만든다. 올리는 것은 토큰이 있어야 하고, 그건 사람이 넣는다.
 */
public final class FixRunner {

    private FixRunner() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        String id = args.length > 0 ? args[0] : null;
        if (id == null || id.isBlank()) {
            out.println("기록 번호가 필요하다.  예: ./gradlew :demo-app:fix -Pid=a1b2c3d4");
            System.exit(2);
            return;
        }

        Path 뿌리 = 저장소뿌리();
        Path store = 기록폴더();
        Recording 기록;
        try {
            기록 = 찾는다(store, id);
        } catch (Exception e) {
            out.println("기록을 못 읽었다: " + e.getMessage());
            System.exit(1);
            return;
        }
        if (기록 == null) {
            out.println("그 번호의 기록이 없다: " + id + "  (폴더: " + store.toAbsolutePath() + ")");
            System.exit(1);
            return;
        }

        DotEnv env = DotEnv.찾아_읽는다();
        LlmConfig config = LlmConfig.from(env.조회());

        // 🔴 패치 뿌리는 «모듈» 이다. 화이트리스트가 src/main/java/ 이므로 경로도 그 기준이어야 한다.
        Path 모듈뿌리 = 뿌리.resolve("demo-app");
        Diagnosis.소스맥락 맥락 = 소스를_모은다(모듈뿌리);
        Map<String, String> 원래내용 = new LinkedHashMap<>(맥락.파일들());

        out.println();
        out.println("── 고쳐 본다 ──");
        out.println("  기록   " + 기록.id());
        out.println("  설정   " + config.describe());
        out.println("  소스   " + 맥락.파일들().size() + "개");
        out.println();
        out.println("⚠️ 이 명령은 작업 트리를 «진짜로» 고친다. 끝나면 되돌린다.");
        out.println("   되돌리기 «전»에 고친 내용을 파일로 남긴다 — 「되돌렸다」와 「버렸다」는 다르다.");
        out.println();

        RealFixer 수선 = new RealFixer(모듈뿌리, 뿌리, new RealFixer.진짜명령());
        long 시작 = System.nanoTime();
        FixPipeline.결과 결과;
        try {
            결과 = new FixPipeline(new HttpDiagnosis(config, new JdkHttpTransport()))
                    .돌린다(기록, 맥락,
                            new AttemptBudget(config.maxAttempts(), config.maxCents()),
                            수선, 원래내용, null, null);
        } finally {
            // 🔴 무슨 일이 있어도 되돌린다. 안 그러면 시험 한 번이 저장소를 바꿔 놓는다.
            고친것을_남긴다(뿌리, 수선, out);
            수선.되돌린다();
        }
        long 걸린초 = (System.nanoTime() - 시작) / 1_000_000_000;

        out.println();
        out.println("── 결과 ──");
        out.println(결과.describe());
        out.println("  걸린 시간: " + 걸린초 + "초");

        if (결과.초안() != null) {
            out.println();
            out.println("── PR 초안 ──");
            out.println("제목: " + 결과.초안().title());
            out.println("브랜치: " + 결과.초안().branchName());
            out.println("자동으로 여나: " + (결과.초안().opensAutomatically() ? "예" : "🔴 아니오"));
            out.println();
            out.println("🔴 이 명령은 PR 을 «안 올린다». 올리려면 GITHUB_TOKEN 이 있어야 한다.");
        }
        out.println();
        out.println("✅ 작업 트리를 되돌렸다. `git status` 로 확인할 것.");
        System.exit(결과.고쳤나() ? 0 : 1);
    }

    /**
     * 🔴 되돌리기 «전»에 고친 내용을 파일로 남긴다.
     *
     * <p>안 남기면 LLM 이 낸 패치가 <b>아무 데도 안 남는다</b> — 되돌리는 순간 사라진다.
     * 그러면 왜 통과했는지/왜 실패했는지를 나중에 볼 수가 없다.
     */
    private static void 고친것을_남긴다(Path 빌드뿌리, RealFixer 수선, PrintStream out) {
        // 🔴 «수선공이 들고 있는 패치»에서 읽는다. 되돌리기가 비우는 목록에서 읽으면
        //    이미 비어 있어서 아무것도 안 남는다 — 2026-09-16 에 실제로 그랬다.
        var 패치 = 수선.지금패치();
        if (패치.isEmpty()) {
            return;
        }
        Path 보관 = 빌드뿌리.resolve("build").resolve("hindsight-patch");
        try {
            Files.createDirectories(보관);
            for (var e : 패치.entrySet()) {
                Files.writeString(보관.resolve(e.getKey().replace('/', '_')),
                        e.getValue(), StandardCharsets.UTF_8);
            }
            out.println("  🗂 LLM 이 낸 패치를 남겼다: " + 보관.toAbsolutePath());
            var 출력 = 수선.마지막출력();
            if (!출력.isBlank()) {
                Files.writeString(보관.resolve("_마지막_빌드_출력.txt"), 출력, StandardCharsets.UTF_8);
                out.println("  🗂 마지막 빌드 출력도 남겼다 — 「테스트 실패」와 「빌드 실패」를 가르려면 필요하다");
            }
        } catch (IOException e) {
            // 🔴 못 남겼다고 되돌리기를 건너뛰지 않는다. 되돌리는 쪽이 더 중요하다.
            out.println("  ⚠️ 고친 내용을 못 남겼다: " + e.getMessage());
        }
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    /** {@code demo-app/} 안에서 돌든 뿌리에서 돌든 저장소 뿌리를 찾는다. */
    private static Path 저장소뿌리() {
        Path 여기 = Path.of("").toAbsolutePath();
        for (Path p = 여기; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("settings.gradle.kts"))) {
                return p;
            }
        }
        return 여기;
    }

    private static Path 기록폴더() {
        String dir = System.getenv("HINDSIGHT_STORE_DIR");
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("HINDSIGHT_STORE_DIR");
        }
        return Path.of(dir == null || dir.isBlank() ? "recordings" : dir);
    }

    private static Recording 찾는다(Path store, String id) throws IOException {
        RecordingCodec codec = new RecordingCodec();
        try (Stream<Path> files = Files.list(store)) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .map(codec::read)
                    .filter(r -> id.equals(r.id()))
                    .findFirst().orElse(null);
        }
    }

    /**
     * 🔴 고칠 수 있는 파일을 모은다. <b>경로는 «모듈» 뿌리 기준</b>이어야 한다 —
     * 화이트리스트가 {@code src/main/java/} 이고, {@code PatchApplier} 도 그 기준으로 찾는다.
     * ⚠️ 여기서 저장소 뿌리를 쓰면 경로가 {@code demo-app/src/main/java/…} 가 되어
     * <b>화이트리스트를 벗어나고, 모든 패치가 거절된다.</b>
     */
    private static Diagnosis.소스맥락 소스를_모은다(Path 모듈뿌리) {
        Path 소스 = 모듈뿌리.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(소스)) {
            return Diagnosis.소스맥락.없음();
        }
        Map<String, String> 파일들 = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(소스)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    파일들.put(모듈뿌리.relativize(p).toString().replace('\\', '/'),
                            Files.readString(p, StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // 한 파일을 못 읽었다고 나머지를 안 보내지 않는다
                }
            });
        } catch (IOException e) {
            return Diagnosis.소스맥락.없음();
        }
        return new Diagnosis.소스맥락(파일들);
    }
}
