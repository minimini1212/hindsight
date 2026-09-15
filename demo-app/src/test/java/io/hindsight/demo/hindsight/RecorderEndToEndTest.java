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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔬 실험 ⑨ — 기록기를 «진짜 앱에 붙여» 한 바퀴 돌린다.
 *
 * <p>여기까지의 실험은 도구를 따로 떼어 시험한 것이었다. 이 시험이 묻는 것은 다르다.
 *
 * <ol>
 *   <li>🔴 <b>기록기를 붙이고도 앱이 멀쩡한가</b> — 본문 있는 요청이 정상 응답을 받나.
 *       본문은 한 번만 읽을 수 있어서, 잘못 잡으면 앱에 빈 본문이 도착한다</li>
 *   <li>사고가 났을 때 <b>파일이 실제로 생기나</b></li>
 *   <li>그 파일 안에서 <b>요청과 SQL 이 같은 번호로 묶여 있나</b></li>
 *   <li>🔴 <b>인증 헤더가 파일에 안 남나</b></li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 🔴 시험마다 앱을 새로 띄운다. 기록기는 «같은 사고를 두 번 기록하지 않기» 때문에,
//    앱을 공유하면 두 번째 시험부터는 파일이 안 생겨서 전부 실패한다.
//    그건 기록기가 고장 난 게 아니라 «일부러 그렇게 만든 것»이므로, 시험 쪽을 맞춘다.
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("🔬 실험 ⑨ — 기록기를 붙인 채로 앱을 돌린다")
class RecorderEndToEndTest {

    /**
     * 🔴 {@code @TempDir} 대신 직접 만든다. 기록 폴더 경로는 앱이 «뜨기 전»에 정해져야 하는데,
     * 그 시점에는 JUnit 의 임시 폴더가 아직 없다.
     */
    private static final Path STORE = Path.of(System.getProperty("java.io.tmpdir"), "hindsight-e2e-" + System.nanoTime());

    @DynamicPropertySource
    static void 기록기_설정(DynamicPropertyRegistry registry) {
        registry.add("HINDSIGHT_STORE_DIR", STORE::toString);
        // 🔴 지연 방아쇠를 1ms 로 낮춘다. 사고를 «일으키려고» 앱에 이상한 코드를 심는 대신,
        //    방아쇠 쪽을 낮춰서 같은 길을 타게 한다. 관측 대상은 그대로 둔다.
        registry.add("HINDSIGHT_LATENCY_TRIGGER_MS", () -> "1");
        registry.add("HINDSIGHT_PSEUDONYM_KEY", () -> "실험용-키");
        registry.add("HINDSIGHT_APP_NAME", () -> "demo-app");
    }

    /**
     * 일부러 터지는 경로. 🔴 «시험용»이라 demo-app 본체에 넣지 않는다 — 포트폴리오 산출물이라서.
     *
     * <p>⚠️ 여기에 {@code @Bean} 메서드를 «따로 두지 않는다». 설정 클래스 안에 중첩된
     * {@code @RestController} 는 스프링이 알아서 빈으로 등록하므로, {@code @Bean} 까지 쓰면
     * 같은 컨트롤러가 두 번 등록되어 <b>「Ambiguous mapping」 으로 앱이 아예 안 뜬다.</b>
     */
    @TestConfiguration
    static class 일부러_터지는_경로 {
        @RestController
        static class BoomController {
            @GetMapping("/test/boom")
            public String boom() {
                throw new IllegalStateException("일부러 터뜨렸다");
            }
        }
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private io.hindsight.demo.member.MemberRepository memberRepository;

    @Autowired
    private io.hindsight.demo.order.OrderRepository orderRepository;

    private Long memberId;

    @BeforeEach
    void 회원과_주문을_심는다() {
        orderRepository.deleteAll();
        memberRepository.deleteAll();
        io.hindsight.demo.member.Member member =
                memberRepository.save(new io.hindsight.demo.member.Member("홍길동"));
        memberId = member.getId();
        orderRepository.save(new io.hindsight.demo.order.Order(member, "키보드"));
        orderRepository.save(new io.hindsight.demo.order.Order(member, "마우스"));
    }

    @BeforeEach
    void 기록_폴더를_비운다() throws IOException {
        if (Files.isDirectory(STORE)) {
            try (Stream<Path> files = Files.walk(STORE)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 지우지 못해도 시험은 최근 파일만 본다
                    }
                });
            }
        }
    }

    private List<Path> 기록들() throws IOException {
        if (!Files.isDirectory(STORE)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(STORE)) {
            return files.filter(p -> p.toString().endsWith(".json")).toList();
        }
    }

    private Recording 최근_기록() throws IOException {
        List<Path> files = 기록들();
        assertThat(files).isNotEmpty();
        return new RecordingCodec().read(files.get(0));
    }

    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 ① 본문 있는 요청이 기록기를 통과해도 앱은 정상 응답한다")
    void 본문을_잡아도_앱이_정상이다() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"memberId\":" + memberId + ",\"product\":\"모니터\"}";

        ResponseEntity<String> response = rest.exchange(
                "/api/orders", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        // 🔴 여기가 핵심이다. 본문을 먼저 읽어 버렸다면 앱은 빈 본문을 받고 400 을 냈을 것이다.
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotBlank();
        assertThat(response.getBody()).contains("모니터");
    }

    @Test
    @DisplayName("② 예외가 나면 기록 파일이 생긴다")
    void 예외가_나면_파일이_생긴다() throws IOException {
        rest.getForEntity("/test/boom", String.class);

        Recording recording = 최근_기록();

        assertThat(recording.trigger().kind()).isEqualTo(Trigger.Kind.EXCEPTION);
        assertThat(recording.trigger().entryPoint()).isEqualTo("GET /test/boom");
        assertThat(recording.trigger().exception().type()).contains("IllegalStateException");
        assertThat(recording.trigger().exception().stack()).isNotEmpty();
    }

    @Test
    @DisplayName("🔴 ③ 요청과 그 요청이 낸 SQL 이 «같은 번호»로 묶인다")
    void 요청과_SQL_이_같은_번호로_묶인다() throws IOException {
        rest.getForEntity("/api/orders", String.class);

        Recording recording = 최근_기록();

        Event.HttpIn request = recording.events().stream()
                .filter(Event.HttpIn.class::isInstance).map(Event.HttpIn.class::cast)
                .filter(e -> "/api/orders".equals(e.path()))
                .findFirst().orElseThrow();

        List<Event.Sql> sqls = recording.events().stream()
                .filter(Event.Sql.class::isInstance).map(Event.Sql.class::cast)
                .filter(e -> request.corrId().equals(e.corrId()))
                .toList();

        // 🔴 SQL 이 하나도 없으면 「질의를 안 했다」가 아니라 「우리가 못 묶었다」일 수 있다.
        //    주문 목록은 반드시 DB 를 읽으므로, 비어 있으면 상관 식별자가 깨진 것이다.
        assertThat(sqls).isNotEmpty();
        assertThat(request.corrId()).isNotNull();
    }

    @Test
    @DisplayName("🔴 ④ 인증 헤더가 파일에 안 남는다")
    void 인증_헤더가_파일에_안_남는다() throws IOException {
        // ⚠️ HTTP 헤더 값은 아스키만 허용된다. 한글을 넣으면 요청을 «보내기도 전에»
        //    클라이언트가 거절한다 — 기록기와 무관한 실패라 값을 아스키로 둔다.
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer TOKEN-SHOULD-NOT-APPEAR-1234");
        headers.set("Cookie", "SESSION=COOKIE-SHOULD-NOT-APPEAR");

        rest.exchange("/api/orders", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        String json = Files.readString(기록들().get(0));

        assertThat(json).doesNotContain("TOKEN-SHOULD-NOT-APPEAR-1234");
        assertThat(json).doesNotContain("COOKIE-SHOULD-NOT-APPEAR");
        assertThat(json).contains("[dropped]");
    }

    @Test
    @DisplayName("🔴 ⑤ 담으려던 시간과 담긴 시간이 «둘 다» 적힌다")
    void 두_시간이_둘_다_적힌다() throws IOException {
        rest.getForEntity("/api/orders", String.class);

        Recording recording = 최근_기록();

        assertThat(recording.integrity().windowRequestedSeconds()).isEqualTo(60);
        assertThat(recording.integrity().windowActualSeconds()).isLessThan(60.0);
        // 방금 뜬 앱이라 실제로 담긴 시간이 훨씬 짧다. 그 사실이 파일에 보여야 한다.
        assertThat(recording.integrity().windowFellShort()).isTrue();
    }

    @Test
    @DisplayName("🔴 ⑥ 재생 정보는 null 이다 — 아직 재생해 본 적이 없다")
    void 재생_정보는_아직_null() throws IOException {
        rest.getForEntity("/api/orders", String.class);

        assertThat(최근_기록().replay()).isNull();
    }
}
