package io.hindsight.demo.hindsight;

import io.hindsight.core.replay.H2StateSnapshot;
import io.hindsight.core.replay.OracleVerdict;
import io.hindsight.core.replay.ReplayObservation;
import io.hindsight.core.replay.ReplayResult;
import io.hindsight.core.replay.StateSnapshot;
import io.hindsight.core.store.RecordingCodec;
import io.hindsight.demo.member.Member;
import io.hindsight.demo.member.MemberRepository;
import io.hindsight.demo.order.Order;
import io.hindsight.demo.order.OrderRepository;
import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;
import io.hindsight.recorder.Recorder;
import io.hindsight.recorder.ReplayProbe;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔬 실험 ⑫ — <b>기록을 «되돌린 자리»에서 다시 흘려 넣고 채점까지 한 바퀴 돌린다.</b>
 *
 * <p>여기까지의 검사는 「재생해 보니 이랬다」를 손으로 넣어 줬다. 이 시험은 그걸
 * <b>진짜 앱으로</b> 한다 — 기록을 만들고, 세상이 움직이게 두고, 되돌리고, 다시 보내고, 채점한다.
 *
 * <h2>🔴 이 시험이 답하는 질문</h2>
 * <ol>
 *   <li><b>되돌리지 않으면 정말 갈라지나</b> — 갈라지지 않는다면 복원 장치 전체가 불필요하다</li>
 *   <li><b>되돌리면 정말 같아지나</b> — 여기가 무너지면 v0 의 채점 고리가 성립하지 않는다</li>
 *   <li><b>되돌린 것을 「되돌렸다」고 «확인»할 수 있나</b> — 주장이 아니라 관찰이어야 한다</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("🔬 실험 ⑫ — 되돌리고 재생해서 채점까지")
class ReplayRoundTripTest {

    private static final Path STORE =
            Path.of(System.getProperty("java.io.tmpdir"), "hindsight-replay-" + System.nanoTime());

    @DynamicPropertySource
    static void 기록기_설정(DynamicPropertyRegistry registry) {
        registry.add("HINDSIGHT_STORE_DIR", STORE::toString);
        // 방아쇠를 아주 낮춰서 요청 하나가 곧바로 기록이 되게 한다.
        registry.add("HINDSIGHT_LATENCY_TRIGGER_MS", () -> "1");
        registry.add("HINDSIGHT_APP_NAME", () -> "demo-app");
    }

    @Autowired TestRestTemplate rest;
    @Autowired MemberRepository memberRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired DataSource dataSource;
    @Autowired Recorder recorder;

    private Long memberId;

    @BeforeEach
    void 기록_시점의_세상을_만든다() throws IOException {
        비운다(STORE);
        orderRepository.deleteAll();
        memberRepository.deleteAll();
        // 🔴 주문마다 «다른» 회원을 붙인다. 같은 회원으로 묶으면 하이버네이트가 영속성
        //    컨텍스트에서 같은 객체를 돌려줘서 «회원 조회가 한 번밖에 안 나간다» —
        //    즉 N+1 이 일어나지 않는다. 2026-09-15 에 그렇게 해 놨다가, 채점이 통과로
        //    나오길래 기록을 열어 보고 알았다. 재현하려는 결함이 재현되지 않는 시험은
        //    통과해도 아무것도 말해 주지 않는다.
        Member first = memberRepository.save(new Member("홍길동"));
        memberId = first.getId();
        orderRepository.save(new Order(first, "물건0"));
        for (int i = 1; i < 5; i++) {
            Member other = memberRepository.save(new Member("회원" + i));
            orderRepository.save(new Order(other, "물건" + i));
        }
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    private String 주문을_만든다() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange("/api/orders", HttpMethod.POST,
                new HttpEntity<>("{\"memberId\":" + memberId + ",\"product\":\"모니터\"}", headers),
                String.class);
        return response.getBody();
    }

    private ResponseEntity<String> 목록을_본다() {
        return rest.getForEntity("/api/orders", String.class);
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
                    // 지우지 못해도 시험은 «가장 최근» 파일만 본다
                }
            });
        }
    }

    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 ① 되돌리지 «않으면» 같은 요청이 다른 답을 낸다 — 복원이 왜 필요한가")
    void 안_되돌리면_갈라진다() {
        String 원본 = 주문을_만든다();

        // 기록 «뒤»에 세상이 움직였다고 치자 — 다른 요청이 행을 하나 더 만든다.
        주문을_만든다();

        String 재생 = 주문을_만든다();

        // 🔴 축은 「읽기냐 쓰기냐」가 아니라 「되돌렸나」다. 안 되돌리면 id 가 밀린다.
        assertThat(재생).isNotEqualTo(원본);
    }

    @Test
    @DisplayName("🔴 ② 되돌리면 글자까지 같아진다 — v0 채점 고리가 성립하는 자리")
    void 되돌리면_글자까지_같다() {
        StateSnapshot snapshot = new H2StateSnapshot(dataSource);

        try (StateSnapshot.Handle 떠둔것 = snapshot.take()) {
            String 원본 = 주문을_만든다();

            // 세상이 움직인다.
            주문을_만든다();
            주문을_만든다();

            ReplayInfo.StateRestore 되돌린범위 = 떠둔것.restoreAndVerify();
            String 재생 = 주문을_만든다();

            assertThat(재생).isEqualTo(원본);
            // 🔴 「되돌렸다」가 주장이 아니라 확인이어야 한다.
            assertThat(되돌린범위.rows()).isTrue();
            assertThat(되돌린범위.identityCounters()).isTrue();
            assertThat(되돌린범위.restoredEnoughToGrade()).isTrue();
        }
    }

    @Test
    @DisplayName("🔴 ③ 안 본 것은 「안 봤다」로 남는다 — 캐시와 외부 시스템")
    void 안_본_것은_null_로_남는다() {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            ReplayInfo.StateRestore 되돌린범위 = 떠둔것.restoreAndVerify();

            // 되돌린 것은 true, 안 본 것은 null. false 로 적으면 「보았고 못 되돌렸다」가 된다.
            assertThat(되돌린범위.caches()).isNull();
            assertThat(되돌린범위.external()).isNull();
        }
    }

    @Test
    @DisplayName("🔴 ④ 되돌리고 재생해서 «채점»까지 — 버그가 그대로면 실패로 나온다")
    void 되돌리고_재생해서_채점한다() throws IOException {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            // ① 기록을 만든다 — N+1 이 심긴 목록 조회.
            목록을_본다();
            Recording 기록 = 최근_기록();

            // ② 세상이 움직인다.
            주문을_만든다();

            // ③ 되돌린다.
            ReplayInfo.StateRestore 되돌린범위 = 떠둔것.restoreAndVerify();

            // ④ 재생한다 — 재생 중에도 «같은» 기록기가 보고 있다.
            ReplayProbe probe = ReplayProbe.open(recorder);
            ResponseEntity<String> 재생응답 = 목록을_본다();
            ReplayObservation 관찰 = probe.observed(
                    재생응답.getStatusCode().value(), 재생응답.getBody(), null);

            // ⑤ 채점한다.
            ReplayResult 결과 = ReplayResult.of(기록, 관찰, 되돌린범위, true);

            System.out.println();
            System.out.println("── 실험 ⑫ 재생 채점 ──");
            System.out.println("  " + 결과.summary());
            결과.verdicts().forEach(v ->
                    System.out.println("    " + v.outcome() + "  " + v.oracle() + " — " + v.reason()));

            // 🔴 코드를 «안 고쳤으므로» N+1 은 그대로다. 채점이 그걸 잡아야 한다 —
            //    여기서 통과가 나오면 채점기가 아무것도 안 보고 있다는 뜻이다.
            assertThat(결과.allowsAutoPullRequest())
                    .as("버그를 안 고쳤는데 자동 PR 이 되면 채점기가 고장 난 것이다")
                    .isFalse();

            // 질의는 «보았다». 한 건도 못 봤으면 관찰 장치가 안 붙은 것이다.
            assertThat(관찰.executedSql()).isNotEmpty();
            assertThat(결과.verdicts()).isNotEmpty();
            assertThat(결과.verdicts()).allSatisfy(v ->
                    assertThat(v.outcome()).isNotEqualTo(OracleVerdict.Outcome.NOT_JUDGED));
        }
    }

    @Test
    @DisplayName("🔴 ⑤ 재생이 «기록과 같은» 질의를 낸다 — 갈라졌다고 잘못 말하지 않는다")
    void 재생이_기록과_같은_질의를_낸다() throws IOException {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            목록을_본다();
            Recording 기록 = 최근_기록();

            ReplayInfo.StateRestore 되돌린범위 = 떠둔것.restoreAndVerify();

            ReplayProbe probe = ReplayProbe.open(recorder);
            ResponseEntity<String> 재생응답 = 목록을_본다();
            ReplayObservation 관찰 = probe.observed(
                    재생응답.getStatusCode().value(), 재생응답.getBody(), null);

            ReplayResult 결과 = ReplayResult.of(기록, 관찰, 되돌린범위, true);

            // 🔴 같은 코드가 같은 질의를 냈으므로 DIVERGED 가 «아니어야» 한다.
            //    여기가 DIVERGED 로 나오면 기록하는 쪽과 재생하는 쪽의 계산이 어긋난 것이다.
            assertThat(결과.replay().diverged()).isNull();
            assertThat(결과.replay().grade()).isNotEqualTo(ReplayInfo.Grade.DIVERGED);
        }
    }

    @Test
    @DisplayName("🔴 ⑥ 재생이 낸 질의를 «안 봤으면» 통과로 치지 않는다")
    void 관찰이_없으면_통과가_아니다() throws IOException {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            목록을_본다();
            Recording 기록 = 최근_기록();
            ReplayInfo.StateRestore 되돌린범위 = 떠둔것.restoreAndVerify();

            // 관찰 장치를 «안 붙이고» 응답만 넣는다 — 실제로 있을 수 있는 상황이다.
            ResponseEntity<String> 재생응답 = 목록을_본다();
            ReplayObservation 관찰없음 = ReplayObservation.builder()
                    .responseStatus(재생응답.getStatusCode().value())
                    .responseBody(재생응답.getBody())
                    .noExceptionEscaped()
                    .build();

            ReplayResult 결과 = ReplayResult.of(기록, 관찰없음, 되돌린범위, true);

            assertThat(결과.verdicts())
                    .anyMatch(v -> v.outcome() == OracleVerdict.Outcome.NOT_JUDGED);
            assertThat(결과.allowsAutoPullRequest()).isFalse();
            assertThat(결과.summary()).contains("판정 못 한 것");
        }
    }

    @Test
    @DisplayName("재생 정보를 기록에 적으면 왕복해서 그대로 읽힌다")
    void 재생_정보가_왕복한다() throws IOException {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            목록을_본다();
            Recording 기록 = 최근_기록();
            ReplayInfo.StateRestore 되돌린범위 = 떠둔것.restoreAndVerify();

            ReplayProbe probe = ReplayProbe.open(recorder);
            ResponseEntity<String> 재생응답 = 목록을_본다();
            ReplayResult 결과 = ReplayResult.of(기록,
                    probe.observed(재생응답.getStatusCode().value(), 재생응답.getBody(), null),
                    되돌린범위, true);

            Recording 채점된기록 = new Recording(
                    기록.schemaVersion(), 기록.id(), 기록.capturedAt(), 기록.trigger(), 기록.app(),
                    기록.events(), 기록.summary(), 기록.jfr(), 결과.replay(), 기록.integrity());

            RecordingCodec codec = new RecordingCodec();
            Recording 다시읽음 = codec.fromJson(codec.toJson(채점된기록));

            assertThat(다시읽음.replay()).isEqualTo(결과.replay());
            assertThat(다시읽음.replay().stateRestore().identityCounters()).isTrue();
            // 🔴 「안 봤다」가 왕복해도 「안 봤다」로 남아야 한다.
            assertThat(다시읽음.replay().stateRestore().caches()).isNull();
        }
    }

    @Test
    @DisplayName("🔴 ⑦ 기록기를 붙인 채로 되돌려도 앱이 계속 돈다")
    void 되돌린_뒤에도_앱이_돈다() {
        try (StateSnapshot.Handle 떠둔것 = new H2StateSnapshot(dataSource).take()) {
            목록을_본다();
            떠둔것.restoreAndVerify();

            // 되돌리기는 drop all objects 로 스키마를 통째로 지웠다가 다시 만든다.
            // 🔴 그 뒤에도 커넥션 풀과 JPA 가 멀쩡히 도는지 확인한다 — 안 그러면
            //    「복원이 됐는데 그 뒤가 안 되는」 상태가 된다.
            ResponseEntity<String> 응답 = 목록을_본다();

            assertThat(응답.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(응답.getBody()).isNotNull();
            assertThat(주문을_만든다()).isNotNull();
        }
    }
}
