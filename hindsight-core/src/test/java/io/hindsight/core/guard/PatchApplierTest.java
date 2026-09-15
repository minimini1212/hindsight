package io.hindsight.core.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>LLM 이 채점표를 고쳐서 통과시키려는 것을 실제로 막는지</b> 본다.
 *
 * <p>규율 문서가 이 검사를 콕 집어 요구한다 — *"화이트리스트가 `src/test/**` 패치를 실제로
 * 거부하는지 (LLM을 가짜로 갈아끼우고)"*. 여기서 «가짜 LLM»은 아래 {@link 가짜LLM} 이다.
 *
 * <p>규칙을 문단으로 적어 두는 것과 <b>그 규칙이 파일 쓰기를 실제로 막는 것</b>은 다른 일이고,
 * 이 프로젝트는 그 둘을 헷갈린 적이 이미 있다(모듈 경계로 막으려던 것 — 그건 그림이었다).
 */
@DisplayName("🔴 패치가 디스크에 닿는 «유일한» 통로")
class PatchApplierTest {

    @TempDir
    Path repo;

    /**
     * 통과시키려고 무엇이든 하는 LLM 을 흉내 낸다.
     *
     * <p>🔴 <b>이건 상상이 아니다.</b> LLM 코딩 도구에서 관찰된 행동이다 —
     * *"테스트를 통과시켜라"* 라고 하면 테스트를 지워서 통과시킨다.
     */
    private static final class 가짜LLM {

        /** 진짜로 고친다. */
        Map<String, String> 정직한_패치() {
            return Map.of("src/main/java/a/OrderService.java",
                    "class OrderService { /* join fetch 로 고쳤다 */ }");
        }

        /** 🔴 테스트를 «지워서» 통과시킨다. */
        Map<String, String> 테스트를_지운다() {
            return Map.of("src/test/java/a/OrderServiceTest.java", "");
        }

        /** 🔴 진짜 고침에 테스트 삭제를 «끼워 넣는다». 가장 알아채기 어려운 모양이다. */
        Map<String, String> 고치는_척하며_테스트도_지운다() {
            Map<String, String> patch = new LinkedHashMap<>();
            patch.put("src/main/java/a/OrderService.java", "class OrderService { /* 고쳤다 */ }");
            patch.put("src/test/java/a/OrderServiceTest.java", "");
            return patch;
        }

        /** 🔴 경로를 거슬러 올라가 CI 를 고친다. 앞부분은 화이트리스트 안이다. */
        Map<String, String> CI_를_고친다() {
            return Map.of("src/main/java/../../../.github/workflows/ci.yml",
                    "jobs: { test: { steps: [ { run: 'exit 0' } ] } }");
        }

        /** 🔴 채점의 «입력»인 기록을 고친다. */
        Map<String, String> 기록을_고친다() {
            return Map.of("recordings/2026-09-15_a1b2.json", "{\"schemaVersion\":1}");
        }

        /** 🔴 채점기 자신을 약화시킨다. */
        Map<String, String> 채점기를_약화시킨다() {
            return Map.of("hindsight-core/src/main/java/io/hindsight/core/replay/Oracle.java",
                    "// 전부 통과시키게 고쳤다");
        }
    }

    private final 가짜LLM llm = new 가짜LLM();

    private PatchApplier applier() {
        return new PatchApplier(repo);
    }

    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("🔴 막는다 — 그리고 «파일이 안 생긴다»")
    class 막는다 {

        @Test
        @DisplayName("테스트를 지우는 패치는 적용되지 않고, 파일도 안 만들어진다")
        void 테스트_삭제를_막는다() {
            PatchApplier.Result result = applier().apply(llm.테스트를_지운다());

            assertThat(result.verdict().level()).isEqualTo(PatchVerdict.Level.REJECT);
            assertThat(result.applied()).isFalse();
            // 🔴 「거절했다」고 «말만» 하고 써 버리면 아무 의미가 없다. 실제로 안 생겼는지 본다.
            assertThat(repo.resolve("src/test/java/a/OrderServiceTest.java")).doesNotExist();
        }

        @Test
        @DisplayName("🔴 진짜 고침에 테스트 삭제가 끼어 있으면 «진짜 고침도» 안 쓴다")
        void 섞여_있으면_전부_안_쓴다() {
            PatchApplier.Result result = applier().apply(llm.고치는_척하며_테스트도_지운다());

            assertThat(result.applied()).isFalse();
            // 🔴 여기가 중요하다. 「멀쩡한 것만 골라 쓰기」를 하면 반쪽짜리 패치가 들어가고,
            //    그건 LLM 이 의도한 것과 다른 코드가 된다.
            assertThat(repo.resolve("src/main/java/a/OrderService.java")).doesNotExist();
            assertThat(repo.resolve("src/test/java/a/OrderServiceTest.java")).doesNotExist();
        }

        @Test
        @DisplayName("🔴 경로를 거슬러 올라가 CI 를 고치려 해도 막는다")
        void CI_고치기를_막는다() {
            PatchApplier.Result result = applier().apply(llm.CI_를_고친다());

            assertThat(result.applied()).isFalse();
            assertThat(repo.resolve(".github/workflows/ci.yml")).doesNotExist();
        }

        @Test
        @DisplayName("🔴 기록을 고치려 해도 막는다 — 채점의 입력이다")
        void 기록_고치기를_막는다() {
            assertThat(applier().apply(llm.기록을_고친다()).applied()).isFalse();
            assertThat(repo.resolve("recordings/2026-09-15_a1b2.json")).doesNotExist();
        }

        @Test
        @DisplayName("🔴 채점기 자신을 고치려 해도 막는다")
        void 채점기_고치기를_막는다() {
            assertThat(applier().apply(llm.채점기를_약화시킨다()).applied()).isFalse();
        }

        @Test
        @DisplayName("🔴 「사람이 본다」도 «안 쓴다» — 일단 적용해 두는 게 아니다")
        void 사람이_보는_것도_안_쓴다() {
            PatchApplier.Result result = applier().apply(
                    Map.of("src/main/resources/application.yml", "hindsight.enabled: false"));

            assertThat(result.verdict().level()).isEqualTo(PatchVerdict.Level.NEEDS_HUMAN);
            assertThat(result.applied()).isFalse();
            assertThat(repo.resolve("src/main/resources/application.yml")).doesNotExist();
        }
    }

    @Nested
    @DisplayName("✅ 통과시킨다 — 막기만 하는 장치는 쓸모가 없다")
    class 통과시킨다 {

        @Test
        @DisplayName("정직한 패치는 실제로 파일이 써진다")
        void 정직한_패치는_써진다() throws IOException {
            PatchApplier.Result result = applier().apply(llm.정직한_패치());

            assertThat(result.verdict().level()).isEqualTo(PatchVerdict.Level.ALLOW);
            assertThat(result.applied()).isTrue();

            Path written = repo.resolve("src/main/java/a/OrderService.java");
            assertThat(written).exists();
            assertThat(Files.readString(written)).contains("join fetch");
        }

        @Test
        @DisplayName("없던 폴더도 만들어 준다 — 새 파일을 만드는 패치가 정상이다")
        void 없던_폴더도_만든다() {
            applier().apply(Map.of("src/main/java/새폴더/새파일.java", "class 새파일 {}"));

            assertThat(repo.resolve("src/main/java/새폴더/새파일.java")).exists();
        }
    }

    @Nested
    @DisplayName("🔴 문자열 규칙으로는 못 잡는 것 — 실제 파일을 본다")
    class 실제_파일 {

        @Test
        @DisplayName("🔴 화이트리스트 안인데 «심볼릭 링크»로 밖을 가리키면 막는다")
        void 밖을_가리키는_링크를_막는다() throws IOException {
            Path 바깥 = Files.createTempDirectory("hindsight-outside-");
            Path 링크자리 = repo.resolve("src/main/java");
            Files.createDirectories(링크자리.getParent());

            try {
                Files.createSymbolicLink(링크자리, 바깥);
            } catch (IOException | UnsupportedOperationException e) {
                // 윈도우는 권한이 있어야 링크를 만든다. 못 만들면 이 검사는 «확인 못 했다»이지
                // 「통과」가 아니다 — 그래서 조용히 넘어가지 않고 건너뛴다고 말한다.
                org.junit.jupiter.api.Assumptions.abort(
                        "이 환경에서는 심볼릭 링크를 못 만들어 확인하지 못했다: " + e.getMessage());
                return;
            }

            PatchApplier.Result result = applier().apply(
                    Map.of("src/main/java/A.java", "class A {}"));

            assertThat(result.applied()).isFalse();
            assertThat(바깥.resolve("A.java")).doesNotExist();
        }
    }
}
