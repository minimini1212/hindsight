package io.hindsight.demo.hindsight;

import io.hindsight.core.store.RecordingCodec;
import io.hindsight.model.Event;
import io.hindsight.model.Recording;
import io.hindsight.model.Trigger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔬 실험 ⑮ — <b>예외가 나갔을 때 응답 상태를 「안다」고 적지 않는다.</b>
 *
 * <h2>무엇이 틀려 있었나 (2026-09-16 에 찾음)</h2>
 * <pre>
 *   컨트롤러가 던진다
 *     → 우리 필터를 «지나» 나간다        ← 이 시점에 우리가 getStatus() 를 읽었다
 *       → 그 «뒤»에 컨테이너가 상태를 세팅한다
 *         → 🔴 클라이언트가 본 것과 기록에 적힌 것이 «다르다»
 * </pre>
 *
 * <p>그러면 재생이 <b>「응답 상태가 기록과 같다」로 통과</b>하는데,
 * 정작 그 상태는 <b>아무도 본 적이 없는 값</b>이다.
 *
 * <h2>⚠️ 500 으로 「고쳐」 적지 않는다</h2>
 * 컨테이너가 무엇을 세팅할지 우리는 모른다 — {@code @ExceptionHandler} 가 있으면
 * 200 일 수도 있다. 🔴 <b>모르는 것은 모른다고 적는다.</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("🔬 실험 ⑮ — 예외가 나가면 응답 상태를 「모른다」로 적는다")
class UnknownResponseStatusTest {

    private static final Path STORE =
            Path.of(System.getProperty("java.io.tmpdir"), "hindsight-unknown-" + System.nanoTime());

    @DynamicPropertySource
    static void 설정(DynamicPropertyRegistry registry) {
        registry.add("HINDSIGHT_STORE_DIR", STORE::toString);
        // 🔴 지연 방아쇠를 아주 크게 잡는다. 안 그러면 예외가 아니라 지연으로 잡힐 수 있고,
        //    그러면 이 시험이 «다른 것»을 보게 된다.
        registry.add("HINDSIGHT_LATENCY_TRIGGER_MS", () -> "600000");
        registry.add("HINDSIGHT_APP_NAME", () -> "demo-app");
    }

    @Autowired TestRestTemplate rest;

    @BeforeEach
    void 비운다() throws IOException {
        if (!Files.isDirectory(STORE)) {
            return;
        }
        try (Stream<Path> files = Files.walk(STORE)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 못 지워도 시험은 가장 최근 것만 본다
                }
            });
        }
    }

    @Test
    @DisplayName("🔴 예외가 나간 요청의 응답 상태는 «null» 이다 — 지어낸 숫자를 적지 않는다")
    void 예외가_나가면_상태를_모른다() throws IOException {
        // 없는 회원으로 주문한다 → OrderService 가 IllegalArgumentException 을 던진다
        ResponseEntity<String> 응답 = rest.postForEntity("/api/orders",
                java.util.Map.of("memberId", -1, "product", "없는물건"), String.class);

        Recording 기록 = 최근_기록();
        Event.HttpIn 들어온것 = 기록.events().stream()
                .filter(Event.HttpIn.class::isInstance).map(Event.HttpIn.class::cast)
                .findFirst().orElseThrow(() -> new IllegalStateException("들어온 요청이 기록에 없다"));

        System.out.println();
        System.out.println("── 실험 ⑮ ──");
        System.out.println("  클라이언트가 본 상태: " + 응답.getStatusCode().value());
        System.out.println("  기록에 적힌 상태    : " + 들어온것.responseStatus());
        System.out.println("  방아쇠              : " + 기록.trigger().kind());

        assertThat(기록.trigger().kind())
                .as("예외로 잡혔어야 한다 — 지연으로 잡혔으면 이 시험은 다른 것을 보고 있다")
                .isEqualTo(Trigger.Kind.EXCEPTION);

        assertThat(들어온것.responseStatus())
                .as("🔴 우리가 읽은 시점의 값은 클라이언트가 본 값이 아니다. "
                        + "지어낸 숫자를 적으면 재생이 그 숫자끼리 비교하고 「같았다」가 된다")
                .isNull();
    }

    @Test
    @DisplayName("정상 요청은 상태를 «안다» — 모를 때만 null 이지, 늘 null 이면 쓸모가 없다")
    void 정상_요청은_상태를_안다() throws IOException {
        rest.getForEntity("/api/orders", String.class);
        // 🔴 지연 문턱을 크게 잡아 뒀으므로 이 요청으로는 기록이 안 생긴다.
        //    그래서 «예외 요청»을 한 번 더 보내 기록을 만들고, 그 안의 정상 이벤트를 본다.
        rest.postForEntity("/api/orders",
                java.util.Map.of("memberId", -1, "product", "x"), String.class);

        Recording 기록 = 최근_기록();
        long 상태를_아는_것 = 기록.events().stream()
                .filter(Event.HttpIn.class::isInstance).map(Event.HttpIn.class::cast)
                .filter(e -> e.responseStatus() != null)
                .count();

        System.out.println("  상태를 아는 들어온 요청: " + 상태를_아는_것 + "건");
        assertThat(상태를_아는_것)
                .as("예외 없이 끝난 요청은 상태를 알 수 있다. 전부 null 이면 이 필드가 죽은 것이다")
                .isPositive();
    }

    private Recording 최근_기록() throws IOException {
        try (Stream<Path> files = Files.list(STORE)) {
            Path newest = files.filter(p -> p.toString().endsWith(".json"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("기록 파일이 없다"));
            return new RecordingCodec().read(newest);
        }
    }
}
