package io.hindsight.demo.hindsight;

import io.hindsight.core.replay.GeneratedTest;
import io.hindsight.core.replay.H2StateSnapshot;
import io.hindsight.core.replay.ReplayTestGenerator;
import io.hindsight.core.replay.StateSnapshot;
import io.hindsight.core.store.RecordingCodec;
import io.hindsight.demo.member.Member;
import io.hindsight.demo.member.MemberRepository;
import io.hindsight.demo.order.Order;
import io.hindsight.demo.order.OrderRepository;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔬 실험 ⑬ — <b>생성된 테스트가 «패치 전»에 정말 실패하는가.</b>
 *
 * <h2>이게 채점의 첫 겹이고, 없으면 나머지가 다 무의미하다</h2>
 * 생성된 테스트가 패치 전에도 통과하면 <b>패치 전에도 초록불이고 패치 후에도 초록불</b>이다.
 * 그런데 도구는 「통과했다」며 <b>아무것도 안 고친 패치로 PR 을 올린다.</b>
 *
 * <p>여기까지 이 프로젝트는 생성된 소스가 <b>컴파일되는 것</b>까지만 확인했다.
 * 🔴 이 시험이 처음으로 <b>진짜로 돌려 본다.</b>
 *
 * <h2>무엇을 보나</h2>
 * <ol>
 *   <li>버그가 «있는» 코드에서 생성된 테스트가 <b>실패</b>하나 (㉠ 기준선 실패)</li>
 *   <li>버그를 «고친» 코드에서 <b>통과</b>하나 (㉡ 검증 통과)</li>
 * </ol>
 *
 * <p>⚠️ 「고친 코드」는 <b>사람이 미리 써 둔 것</b>이다(설정 한 줄로 갈아탄다).
 * 🔴 그래서 이 시험이 보이는 것은 <b>「고리가 도는가」이지 「LLM 이 고칠 수 있는가」가 아니다.</b>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("🔬 실험 ⑬ — 생성된 테스트가 패치 전에 «정말» 실패하는가")
class BaselineFailsTest {

    private static final Path STORE =
            Path.of(System.getProperty("java.io.tmpdir"), "hindsight-baseline-" + System.nanoTime());

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
    @Autowired io.hindsight.demo.order.OrderService orderService;

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
    }

    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 ① 버그가 그대로면 생성된 테스트가 «실패»한다 — 이게 없으면 채점이 성립 안 한다")
    void 패치_전에_실패한다() throws IOException {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            GeneratedTest generated = 기록하고_테스트를_만든다();

            DemoAppReplayTestBase.준비한다(
                    new DemoAppReplayTestBase.재생도구(rest, recorder, 떠둔것));
            var 결과 = GeneratedTestRunner.컴파일해서_돌린다(generated);

            System.out.println();
            System.out.println("── 실험 ⑬-① 버그가 있는 코드에서 ──");
            System.out.println("  돌았나: " + 결과.돌았나() + "   통과했나: " + 결과.통과했나());
            System.out.println("  이유: " + 결과.왜());

            // 🔴 「안 돌았다」를 「실패」로 치지 않는다. 돌았고, 실패해야 한다.
            assertThat(결과.돌았나())
                    .as("컴파일이 안 됐거나 실행이 안 됐으면 «실패»가 아니라 «못 돌린» 것이다")
                    .isTrue();
            assertThat(결과.통과했나())
                    .as("버그가 그대로인데 통과하면, 그 테스트는 버그를 못 살린 것이다")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("✅ ② 버그를 고치면 같은 테스트가 «통과»한다 — 고리가 한 바퀴 돈다")
    void 패치_후에_통과한다() throws IOException {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            GeneratedTest generated = 기록하고_테스트를_만든다();

            // 여기서 「패치」가 일어난다 — 진짜 도구에서는 LLM 이 쓴 코드가 들어오는 자리다.
            orderService.setNPlusOneFixedForTest(true);
            try {
                DemoAppReplayTestBase.준비한다(
                        new DemoAppReplayTestBase.재생도구(rest, recorder, 떠둔것));
                var 결과 = GeneratedTestRunner.컴파일해서_돌린다(generated);

                System.out.println();
                System.out.println("── 실험 ⑬-② 고친 코드에서 ──");
                System.out.println("  돌았나: " + 결과.돌았나() + "   통과했나: " + 결과.통과했나());
                if (!결과.통과했나()) {
                    System.out.println("  이유: " + 결과.왜());
                }

                assertThat(결과.돌았나()).isTrue();
                assertThat(결과.통과했나())
                        .as("고쳤는데도 실패하면 오라클이 버그와 무관한 것을 보고 있다는 뜻이다")
                        .isTrue();
            } finally {
                orderService.setNPlusOneFixedForTest(false);
            }
        }
    }

    @Test
    @DisplayName("🔴 ③ 생성된 테스트는 «디스크»에 안 남는다 — 남으면 고쳐서 통과시킬 수 있다")
    void 디스크에_안_남는다() throws IOException {
        GeneratedTest generated = 기록하고_테스트를_만든다();

        try (Stream<Path> files = Files.walk(Path.of("."), 6)) {
            boolean 소스가_있나 = files
                    .filter(p -> p.getFileName().toString().equals(generated.fileName()))
                    .findAny().isPresent();
            assertThat(소스가_있나)
                    .as("생성된 테스트 소스가 디스크에 있으면 LLM 이 그걸 고쳐서 통과시킬 수 있다")
                    .isFalse();
        }
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    /** 진짜 요청을 보내 기록을 만들고, 그 기록에서 테스트 소스를 만든다. */
    private GeneratedTest 기록하고_테스트를_만든다() throws IOException {
        rest.getForEntity("/api/orders", String.class);
        Recording 기록 = 최근_기록();

        GeneratedTest generated = ReplayTestGenerator.generate(기록,
                DemoAppReplayTestBase.class.getName());

        System.out.println();
        System.out.println("── 생성된 테스트 ──");
        System.out.println(generated.describe());
        return generated;
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
