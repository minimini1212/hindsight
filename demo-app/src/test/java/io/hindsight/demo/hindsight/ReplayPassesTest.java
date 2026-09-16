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
 * 🔴 <b>「지금 코드에서 증상이 사라졌나」 하나만 묻는 재생 테스트.</b>
 *
 * <h2>왜 따로 있어야 하나 — 재서 알았다</h2>
 * 고리를 진짜 LLM 으로 처음 끝까지 돌렸더니(2026-09-16) ㉡(검증 통과)이 두 번 다 실패했다.
 * 그런데 <b>패치가 나쁜 게 아니었다.</b> ㉠㉡ 를 {@code BaselineFailsTest} 로 대신 돌리고
 * 있었는데, 그 시험은 <b>양쪽을 «둘 다» 단언</b>한다.
 *
 * <pre>
 *   BaselineFailsTest
 *     ① 버그가 있으면 «실패»한다      ← 🔴 패치가 붙으면 이 단언이 깨진다
 *     ② 고치면 «통과»한다
 * </pre>
 *
 * <p>즉 <b>패치를 붙인 상태에서는 ①이 반드시 실패</b>하고, 그러면 ㉡ 이 영영 통과할 수 없다.
 * 🔴 <b>「이 패치를 보는 테스트」가 아니었던 것이다.</b>
 *
 * <h2>그래서 이 시험은 «한 방향»만 본다</h2>
 * <pre>
 *   패치 «없이» 돌리면   → 실패한다  → 네 겹의 ㉠ 「기준선 실패」가 참이 된다
 *   패치를 붙이고 돌리면 → 통과한다  → 네 겹의 ㉡ 「검증 통과」가 참이 된다
 * </pre>
 *
 * <p>같은 시험이 <b>지금 코드의 상태에 따라</b> 다른 답을 낸다. 그게 이 시험이 하는 일 전부다.
 *
 * <p>⚠️ 그래서 이 시험은 <b>혼자 돌리면 실패하는 것이 정상</b>이다 —
 * 저장소의 기본 상태는 「버그가 있는」 상태이기 때문이다.
 * 🔴 전체 빌드에서 빼 두는 이유가 그것이다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("재생 — 지금 코드에서 증상이 사라졌나")
class ReplayPassesTest {

    private static final Path STORE =
            Path.of(System.getProperty("java.io.tmpdir"), "hindsight-replaypass-" + System.nanoTime());

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

    @Test
    @DisplayName("🔴 지금 코드에서 재생 테스트가 «통과»한다")
    void 증상이_사라졌나() throws IOException {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            rest.getForEntity("/api/orders", String.class);
            Recording 기록 = 최근_기록();

            GeneratedTest generated = ReplayTestGenerator.generate(기록,
                    DemoAppReplayTestBase.class.getName());

            DemoAppReplayTestBase.준비한다(
                    new DemoAppReplayTestBase.재생도구(rest, recorder, 떠둔것));
            var 결과 = GeneratedTestRunner.컴파일해서_돌린다(generated);

            System.out.println();
            System.out.println("── 재생 ──");
            System.out.println("  돌았나: " + 결과.돌았나() + "   통과했나: " + 결과.통과했나());
            if (!결과.통과했나()) {
                System.out.println("  이유: " + 결과.왜());
            }

            // 🔴 「안 돌았다」를 「실패」로 치지 않는다. 컴파일이 안 된 것과 증상이 남은 것은 다르다.
            assertThat(결과.돌았나())
                    .as("컴파일이 안 됐거나 실행이 안 됐으면 «실패»가 아니라 «못 돌린» 것이다")
                    .isTrue();
            assertThat(결과.통과했나())
                    .as("🔴 지금 코드에 증상이 남아 있으면 여기서 실패한다 — "
                            + "패치 «전»에는 그게 정상이고, 그것이 네 겹의 ㉠ 이다")
                    .isTrue();
        }
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
