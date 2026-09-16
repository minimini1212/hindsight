package io.hindsight.demo.hindsight;

import io.hindsight.core.brain.Confidence;
import io.hindsight.core.brain.LayerOutcome;
import io.hindsight.core.brain.VerificationLoop;
import io.hindsight.core.replay.GeneratedTest;
import io.hindsight.core.replay.H2StateSnapshot;
import io.hindsight.core.replay.ReplayTestGenerator;
import io.hindsight.core.replay.StateSnapshot;
import io.hindsight.core.store.RecordingCodec;
import io.hindsight.demo.member.Member;
import io.hindsight.demo.member.MemberRepository;
import io.hindsight.demo.order.Order;
import io.hindsight.demo.order.OrderRepository;
import io.hindsight.demo.order.OrderService;
import io.hindsight.model.Recording;
import io.hindsight.recorder.Recorder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔬 실험 ⑭ — <b>채점 네 겹이 «진짜 앱»에서 다 돈다.</b>
 *
 * <h2>여기까지 무엇이 빠져 있었나</h2>
 * 네 겹의 판단 코드({@link VerificationLoop})는 2026-09-15 에 생겼고, 순수 함수라
 * 전수로 시험됐다. 그런데 그 판단에 <b>진짜 결과를 넣어 준 적이 없었다.</b>
 * 2026-09-16 에 ㉠㉡ 만 진짜로 돌았고, ㉢(앱의 기존 테스트)과 ㉣(되돌리기)은
 * <b>「돌릴 것이 없는 겹」</b>이었다.
 *
 * <p>🔴 이 시험이 넷을 다 돌린다.
 *
 * <h2>무엇을 보나</h2>
 * <ol>
 *   <li>진짜 고침이 들어오면 네 겹이 «전부» 통과하나</li>
 *   <li>🔴 ㉢ 이 진짜로 «막나» — 앱을 깨뜨리는 패치를 걸러내나</li>
 *   <li>🔴 ㉣ 이 진짜로 «되돌리나» — 되돌린 뒤 테스트가 다시 실패하나</li>
 * </ol>
 *
 * <p>⚠️ 「패치」는 설정 한 줄이다(자세한 이유는 {@link DemoAppRunner}).
 * 🔴 <b>이 시험이 보이는 것은 「네 겹이 도는가」이지 「LLM 이 고칠 수 있는가」가 아니다.</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("🔬 실험 ⑭ — 채점 네 겹이 진짜 앱에서 다 돈다")
class FourLayerEndToEndTest {

    private static final Path STORE =
            Path.of(System.getProperty("java.io.tmpdir"), "hindsight-fourlayer-" + System.nanoTime());

    /** 진짜 LLM 이 냈다고 가정한 패치. 🔴 경로가 화이트리스트 «안»이어야 채점이 시작된다. */
    private static final String 고친_경로 = "src/main/java/io/hindsight/demo/order/OrderService.java";

    @DynamicPropertySource
    static void 설정(DynamicPropertyRegistry registry) {
        registry.add("HINDSIGHT_STORE_DIR", STORE::toString);
        registry.add("HINDSIGHT_LATENCY_TRIGGER_MS", () -> "1");
        registry.add("HINDSIGHT_APP_NAME", () -> "demo-app");
    }

    @Autowired TestRestTemplate rest;
    @Autowired MemberRepository memberRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired DataSource dataSource;
    @Autowired Recorder recorder;
    @Autowired OrderService orderService;

    @BeforeEach
    void 기록_시점의_세상() throws IOException {
        비운다(STORE);
        orderRepository.deleteAll();
        memberRepository.deleteAll();
        // 🔴 주문마다 «다른» 회원. 같은 회원이면 하이버네이트가 한 번만 읽어서 N+1 이 안 난다.
        for (int i = 0; i < 5; i++) {
            Member member = memberRepository.save(new Member("회원" + i));
            orderRepository.save(new Order(member, "물건" + i));
        }
    }

    @AfterEach
    void 치운다() {
        DemoAppReplayTestBase.치운다();
        orderService.setNPlusOneFixedForTest(false);
    }

    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("✅ ① 진짜 고침이면 네 겹이 «전부» 통과하고 확신도가 올라간다")
    void 네_겹이_전부_돈다() throws IOException {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            DemoAppRunner runner = 고리를_준비한다(떠둔것);

            var 결과 = new VerificationLoop().run(
                    Map.of(고친_경로, "orderRepository.findAll();"),
                    Map.of(고친_경로, "orderRepository.findAllWithMember();"),
                    null,                      // 🔴 재생 등급을 «안 봤다». 그래서 높음이 안 나와야 한다
                    runner);

            찍는다("① 진짜 고침", 결과, runner);

            assertThat(결과.score().기준선_실패()).isEqualTo(LayerOutcome.PASSED);
            assertThat(결과.score().검증_통과()).isEqualTo(LayerOutcome.PASSED);
            assertThat(결과.score().기존_테스트())
                    .as("🔴 ㉢ — 앱 자신의 테스트가 진짜로 돌아야 한다")
                    .isEqualTo(LayerOutcome.PASSED);
            assertThat(결과.score().되돌리기())
                    .as("🔴 ㉣ — 되돌리면 다시 실패해야 한다")
                    .isEqualTo(LayerOutcome.PASSED);

            assertThat(결과.score().notRunLayers())
                    .as("네 겹이 다 돌았으면 「안 돌린 겹」이 비어야 한다")
                    .isEmpty();
            assertThat(결과.confidence())
                    .as("🔴 재생 등급을 안 봤으므로 「높음」이면 안 된다 — 안 본 것을 봤다고 치는 것이다")
                    .isEqualTo(Confidence.MEDIUM);
        }
    }

    @Test
    @DisplayName("🔴 ② ㉢ 이 진짜로 막는다 — 앱을 깨뜨리는 패치는 통과 못 한다")
    void 앱을_깨뜨리면_셋째겹이_막는다() throws IOException {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            DemoAppRunner 진짜 = 고리를_준비한다(떠둔것);

            // 🔴 ㉡ 까지는 똑같이 통과하지만 ㉢ 에서 앱이 깨지는 패치를 흉내 낸다.
            //    「질의를 덜 했나」만 보면 이런 패치는 «통과»한다 — 그게 ㉢ 이 있는 이유다.
            var 깨뜨리는_패치 = new VerificationLoop.Runner() {
                @Override public boolean 재생_테스트가_통과하나() { return 진짜.재생_테스트가_통과하나(); }
                @Override public boolean 기존_테스트가_전부_통과하나() { return false; }
                @Override public boolean 패치를_적용한다() { return 진짜.패치를_적용한다(); }
                @Override public boolean 패치를_되돌린다() { return 진짜.패치를_되돌린다(); }
            };

            var 결과 = new VerificationLoop().run(
                    Map.of(고친_경로, "before"), Map.of(고친_경로, "after"), null, 깨뜨리는_패치);

            찍는다("② 앱을 깨뜨리는 패치", 결과, 진짜);

            assertThat(결과.score().검증_통과())
                    .as("재생 테스트는 통과했다 — 그래서 ㉢ 이 없으면 이 패치가 나간다")
                    .isEqualTo(LayerOutcome.PASSED);
            assertThat(결과.score().기존_테스트()).isEqualTo(LayerOutcome.FAILED);
            assertThat(결과.score().되돌리기())
                    .as("🔴 ㉢ 에서 멈췄으면 ㉣ 은 «안 돌린» 것이다. 통과로 치면 안 된다")
                    .isEqualTo(LayerOutcome.NOT_RUN);
            assertThat(결과.confidence()).isEqualTo(Confidence.LOW);
            assertThat(결과.describe())
                    .as("무엇 때문에 막혔는지가 글에 남아야 한다")
                    .contains("기존 테스트가 실패");
        }
    }

    @Test
    @DisplayName("🔴 ③ ㉣ 이 진짜로 되돌린다 — 되돌린 앱에서 테스트가 다시 실패한다")
    void 되돌리면_다시_실패한다() throws IOException {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            DemoAppRunner runner = 고리를_준비한다(떠둔것);

            // 🔴 고리를 «거치지 않고» 손으로 확인한다. 고리가 스스로 「되돌렸다」고 말하는 것과
            //    진짜로 되돌아간 것은 다른 사실이다.
            assertThat(runner.재생_테스트가_통과하나())
                    .as("㉠ 패치 전 — 실패해야 한다")
                    .isFalse();

            runner.패치를_적용한다();
            assertThat(runner.재생_테스트가_통과하나())
                    .as("㉡ 패치 후 — 통과해야 한다")
                    .isTrue();

            runner.패치를_되돌린다();
            assertThat(runner.재생_테스트가_통과하나())
                    .as("🔴 ㉣ 되돌린 뒤 — «다시» 실패해야 한다. 여기서 통과하면 그 테스트는 "
                            + "패치와 무관하게 통과하던 것이고, ㉡ 의 「통과」가 아무 뜻이 없어진다")
                    .isFalse();

            System.out.println();
            System.out.println("── 실험 ⑭-③ 발자국 ──");
            runner.발자국().forEach(s -> System.out.println("  " + s));
        }
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    private DemoAppRunner 고리를_준비한다(StateSnapshot.Handle 떠둔것) throws IOException {
        rest.getForEntity("/api/orders", String.class);
        Recording 기록 = 최근_기록();
        GeneratedTest generated = ReplayTestGenerator.generate(기록, DemoAppReplayTestBase.class.getName());

        DemoAppReplayTestBase.준비한다(new DemoAppReplayTestBase.재생도구(rest, recorder, 떠둔것));
        return new DemoAppRunner(generated, orderService);
    }

    private static void 찍는다(String 이름, VerificationLoop.Result 결과, DemoAppRunner runner) {
        System.out.println();
        System.out.println("── 실험 ⑭-" + 이름 + " ──");
        System.out.println(결과.describe().stripTrailing());
        System.out.println("  발자국:");
        runner.발자국().forEach(s -> System.out.println("    " + s));
    }

    private Recording 최근_기록() throws IOException {
        try (Stream<Path> files = Files.list(STORE)) {
            Path newest = files.filter(p -> p.toString().endsWith(".json"))
                    .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElseThrow(() -> new IllegalStateException("기록 파일이 없다"));
            return new RecordingCodec().read(newest);
        }
    }

    private static void 비운다(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 못 지워도 시험은 가장 최근 것만 본다
                }
            });
        }
    }
}
