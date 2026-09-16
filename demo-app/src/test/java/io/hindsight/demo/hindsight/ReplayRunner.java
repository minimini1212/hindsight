package io.hindsight.demo.hindsight;

import io.hindsight.core.replay.ReplayObservation;
import io.hindsight.core.replay.ReplayResult;
import io.hindsight.core.store.RecordingCodec;
import io.hindsight.model.Event;
import io.hindsight.model.Recording;
import io.hindsight.model.SqlShapes;
import io.hindsight.recorder.Recorder;
import io.hindsight.recorder.ReplayProbe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import io.hindsight.demo.DemoApplication;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 🔴 <b>기록 하나를 명령줄에서 «진짜로» 재생한다.</b>
 *
 * <pre>
 *   ./gradlew :demo-app:replay -Pid=a1b2c3d4
 * </pre>
 *
 * <h2>왜 이게 «관측 대상 앱» 쪽에 있나</h2>
 * 재생은 <b>DB 를 되돌리고 요청을 다시 보내는 일</b>이다. 둘 다 앱만 할 수 있다 —
 * 어떤 DB 를 쓰는지, 어느 포트로 받는지는 앱이 안다. 그래서 {@code hs} 는 이걸 못 한다.
 * 🔴 <b>「명령줄에 앱이 없다」가 그 사실의 전부다.</b>
 *
 * <h2>⚠️ 그래서 «시험 소스»에 있다</h2>
 * 이 클래스가 앱의 운영 소스에 있으면, 관측 대상 앱이 <b>재생 코드를 배포에 싣고 다닌다.</b>
 * 그건 「기록기만 앱 안에 들어간다」는 경계를 무너뜨린다.
 *
 * <h2>🔴 이 명령이 «못 하는» 것 — 읽기 전에 알아야 한다</h2>
 * <b>기록에는 그때의 DB 내용이 들어 있지 않다.</b> 기록이 담는 것은 경계에서 오간 값
 * (요청 · 응답 · 질의문)이지 테이블의 행이 아니다.
 *
 * <p>그래서 이 명령은 <b>기록 시점 상태로 되돌리지 못한다.</b> 지금 떠 있는 앱의 DB 가
 * 무엇이든 그 위에서 요청을 다시 보낼 뿐이다. 🔴 그 사실을 등급에 <b>그대로 적는다</b> —
 * {@code stateRestore} 를 {@code null}(안 봤다/못 했다)로 넘기므로 등급은
 * {@code PARTIAL} 이 되고 {@code missing} 에 {@code STATE} 가 붙는다.
 *
 * <p>⚠️ <b>여기서 「되돌렸다」고 적으면 안 된다.</b> 그러면 되돌리지 않은 자리에서 잰 결과가
 * 패치 탓으로 읽힌다 — 이 도구가 잡으려는 결함 그 자체다.
 */
public final class ReplayRunner {

    private ReplayRunner() {}

    /**
     * 🔴 <b>보고서를 파일로도 남긴다.</b>
     *
     * <p>Gradle 을 거쳐 나온 글은 <b>콘솔 코드페이지로 다시 인코딩된다.</b> 윈도우 한국어
     * 콘솔(MS949)에서는 {@code ──} 와 이모지가 {@code ?} 가 된다 — 자식 JVM 쪽만
     * UTF-8 로 맞춰서는 안 되고, 받는 쪽(Gradle 데몬)과 찍는 쪽(콘솔)이 다 맞아야 한다.
     *
     * <p>2026-09-16 에 셋을 다 맞춰 봤지만 콘솔 단계에서 계속 깨졌다. 그래서
     * <b>콘솔에는 요약만 찍고, 온전한 글은 UTF-8 파일로 남긴다.</b>
     * ⚠️ 콘솔에서 깨져 보이는 것과 <b>글이 깨진 것은 다른 사실</b>이다.
     */
    private static Path 보고서파일;

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);

        String id = 인자에서_번호(args);
        if (id == null) {
            out.println("기록 번호가 필요하다.  예: ./gradlew :demo-app:replay -Pid=a1b2c3d4");
            System.exit(2);
            return;
        }

        Path store = 기록폴더();
        Recording 기록;
        try {
            기록 = 찾는다(store, id);
        } catch (Exception e) {
            out.println("기록을 못 읽었다: " + e.getMessage());
            out.println("기록 폴더: " + store.toAbsolutePath());
            System.exit(1);
            return;
        }
        if (기록 == null) {
            out.println("그 번호의 기록이 없다: " + id);
            out.println("`hs list` 로 있는 번호를 볼 수 있다. 기록 폴더: " + store.toAbsolutePath());
            System.exit(1);
            return;
        }

        Event.HttpIn 진입점 = 진입점을_찾는다(기록);
        if (진입점 == null) {
            // 🔴 「재생할 것이 없다」를 「재생했는데 같았다」로 넘기지 않는다.
            out.println("이 기록에는 들어온 요청이 «없다». 재생할 것이 없다.");
            System.exit(1);
            return;
        }

        out.println();
        out.println("── 재생한다 ──");
        out.println("  기록   " + 기록.id());
        out.println("  요청   " + 진입점.method() + " " + 진입점.path());
        out.println();
        out.println("🔴 되돌리지 «못한다». 기록에는 그때의 DB 내용이 없다 —");
        out.println("   기록이 담는 것은 경계에서 오간 값이지 테이블의 행이 아니다.");
        out.println("   그래서 이 재생의 등급에는 STATE 가 「못 봤다」로 남는다.");

        보고서파일 = store.resolve(기록.id() + "-replay.txt");
        StringBuilder 보고서 = new StringBuilder();
        int 종료코드 = 띄우고_재생한다(기록, 진입점, out, 보고서);
        보고서를_쓴다(보고서.toString(), out);
        System.exit(종료코드);
    }

    private static int 띄우고_재생한다(Recording 기록, Event.HttpIn 진입점,
                                  PrintStream out, StringBuilder 보고서) {
        SpringApplication app = new SpringApplicationBuilder(DemoApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0")
                .build();

        try (ConfigurableApplicationContext ctx = app.run()) {
            int 포트 = Integer.parseInt(ctx.getEnvironment().getProperty("local.server.port", "8080"));
            Recorder recorder = ctx.getBean(Recorder.class);

            // 🔴 재생 «중»에도 같은 기록기가 보고 있다. 등급은 주장이 아니라 관찰이어야 한다.
            ReplayProbe probe = ReplayProbe.open(recorder);

            HttpHeaders headers = new HttpHeaders();
            HttpEntity<String> entity;
            if (진입점.body() == null) {
                // 🔴 「본문 없음」을 빈 문자열로 바꾸지 않는다. 그건 다른 요청이다.
                entity = new HttpEntity<>(headers);
            } else {
                headers.setContentType(MediaType.APPLICATION_JSON);
                entity = new HttpEntity<>(진입점.body(), headers);
            }

            String url = "http://localhost:" + 포트 + 진입점.path();
            Throwable 나간예외 = null;
            ResponseEntity<String> 응답 = null;
            try {
                응답 = new RestTemplate().exchange(
                        url, HttpMethod.valueOf(진입점.method()), entity, String.class);
            } catch (RuntimeException e) {
                나간예외 = e;
            }

            var 관찰 = probe.observed(
                    응답 == null ? null : 응답.getStatusCode().value(),
                    응답 == null ? null : 응답.getBody(),
                    나간예외);

            ReplayObservation observation = ReplayObservation.builder()
                    .responseStatus(관찰.responseStatus())
                    .responseBody(관찰.responseBody())
                    .executedSql(관찰.executedSql())
                    .build();

            // 🔴 stateRestore 가 null 이다 — 「되돌렸다」가 아니라 «못 했다».
            // 🔴 baselineFailed 도 null 이다 — 이 명령은 패치 «전»을 안 돌려 봤다.
            ReplayResult 결과 = ReplayResult.of(기록, observation, null, null);

            찍는다(기록, 관찰.executedSql(), 결과, out, 보고서);
            return 0;
        } catch (Exception e) {
            out.println("재생하지 못했다: " + e);
            return 1;
        }
    }

    private static void 찍는다(Recording 기록, List<String> 나간질의,
                            ReplayResult 결과, PrintStream out, StringBuilder 보고서) {
        말한다(out, 보고서, "");
        말한다(out, 보고서, "── 결과 ──");
        말한다(out, 보고서, 결과.summary());

        말한다(out, 보고서, "");
        말한다(out, 보고서, "── 질의 반복 (이 재생이 낸 것) ──");
        if (나간질의 == null) {
            말한다(out, 보고서, "  🔴 질의를 «못 봤다». 반복 여부를 말할 수 없다");
        } else {
            Map<String, Integer> 모양별 = new LinkedHashMap<>();
            나간질의.forEach(sql -> 모양별.merge(SqlShapes.of(sql).normalized(), 1, Integer::sum));
            모양별.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .forEach(e -> 말한다(out, 보고서,
                            "  %4d번  %s".formatted(e.getValue(), 짧게(e.getKey()))));
        }

        말한다(out, 보고서, "");
        말한다(out, 보고서, "🔴 이 재생이 «증명하지 않는» 것");
        말한다(out, 보고서, "  ⬜ DB 를 기록 시점으로 못 되돌렸다 — 기록에 그 내용이 없다");
        말한다(out, 보고서, "  ⬜ 패치 «전»에 실패했는지 안 봤다 — 이 명령은 한 번만 돌린다");
        말한다(out, 보고서, "  ⬜ 다른 상황에서도 되는지는 재생이 원래 증명하지 않는다");
        기록의_구멍(기록, out, 보고서);
    }

    private static void 기록의_구멍(Recording 기록, PrintStream out, StringBuilder 보고서) {
        var integrity = 기록.integrity();
        if (integrity == null) {
            말한다(out, 보고서, "  🔴 이 기록은 「무엇을 못 담았나」를 «안 적었다»");
            return;
        }
        if (integrity.droppedEvents() > 0) {
            말한다(out, 보고서, "  🔴 큐가 차서 못 받은 이벤트가 " + integrity.droppedEvents() + "건 있다");
        }
        if (integrity.agentErrors() > 0) {
            말한다(out, 보고서, "  🔴 기록기가 삼킨 예외가 " + integrity.agentErrors() + "건 있다");
        }
        if (integrity.windowFellShort()) {
            말한다(out, 보고서, "  🔴 담으려던 %d초 중 실제로는 %.1f초만 담겼다".formatted(
                    integrity.windowRequestedSeconds(), integrity.windowActualSeconds()));
        }
    }

    /** 콘솔과 보고서에 «같은» 글을 남긴다. 🔴 둘이 다르면 어느 쪽이 진짜인지 아무도 모른다. */
    private static void 말한다(PrintStream out, StringBuilder 보고서, String 줄) {
        out.println(줄);
        보고서.append(줄).append(System.lineSeparator());
    }

    /**
     * 🔴 온전한 글을 <b>UTF-8 파일</b>로 남긴다.
     *
     * <p>Gradle 을 거친 출력은 콘솔 코드페이지로 다시 인코딩되어, 윈도우 한국어 콘솔에서는
     * {@code ──} 와 이모지가 {@code ?} 가 된다. ⚠️ <b>콘솔에서 깨져 보이는 것과
     * 글이 깨진 것은 다른 사실</b>이라, 확인할 수 있는 자리를 하나 남긴다.
     */
    private static void 보고서를_쓴다(String 보고서, PrintStream out) {
        if (보고서파일 == null || 보고서.isEmpty()) {
            return;
        }
        try {
            Files.writeString(보고서파일, 보고서, StandardCharsets.UTF_8);
            out.println();
            out.println("전체 보고서(UTF-8): " + 보고서파일.toAbsolutePath());
            out.println("  ⚠️ 콘솔에서 글자가 깨져 보이면 이 파일을 본다 — 글이 깨진 것이 아니다.");
        } catch (Exception e) {
            // 🔴 보고서를 못 썼다고 재생 결과를 «없던 일»로 만들지 않는다. 콘솔에는 이미 찍혔다.
            out.println("보고서 파일을 못 썼다: " + e.getMessage());
        }
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    private static String 인자에서_번호(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return args[0];
        }
        String p = System.getProperty("hindsight.replay.id");
        return p == null || p.isBlank() ? null : p;
    }

    private static Path 기록폴더() {
        String dir = System.getenv("HINDSIGHT_STORE_DIR");
        if (dir == null || dir.isBlank()) {
            dir = System.getProperty("HINDSIGHT_STORE_DIR");
        }
        return Path.of(dir == null || dir.isBlank() ? "recordings" : dir);
    }

    private static Recording 찾는다(Path store, String id) throws Exception {
        RecordingCodec codec = new RecordingCodec();
        try (Stream<Path> files = Files.list(store)) {
            return files.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .map(codec::read)
                    .filter(r -> id.equals(r.id()))
                    .findFirst()
                    .orElse(null);
        }
    }

    /** 🔴 기록의 «첫» 요청이 아니라, 방아쇠가 가리키는 진입점과 같은 요청을 찾는다. */
    private static Event.HttpIn 진입점을_찾는다(Recording 기록) {
        if (기록.events() == null) {
            return null;
        }
        List<Event.HttpIn> 들어온것 = 기록.events().stream()
                .filter(Event.HttpIn.class::isInstance).map(Event.HttpIn.class::cast).toList();
        if (들어온것.isEmpty()) {
            return null;
        }
        String 진입점 = 기록.trigger() == null ? null : 기록.trigger().entryPoint();
        if (진입점 == null) {
            return 들어온것.getFirst();
        }
        return 들어온것.stream()
                .filter(e -> 진입점.equals(e.method() + " " + e.path()))
                .findFirst()
                .orElse(들어온것.getFirst());
    }

    private static String 짧게(String sql) {
        return sql.length() <= 80 ? sql : sql.substring(0, 80) + "…";
    }
}
