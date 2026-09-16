package io.hindsight.demo.hindsight;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>이 클래스는 «사람의 작업 트리»를 고친다.</b> 그래서 시험이 지켜야 할 것이 하나 더 있다 —
 * <b>끝나고 나면 아무것도 안 바뀌어 있어야 한다.</b>
 *
 * <p>여기서는 임시 폴더를 저장소인 척해서, 진짜 저장소를 안 건드리고 그 성질을 확인한다.
 */
@DisplayName("진짜 수선공 — 진짜로 쓰고, 진짜로 되돌린다")
class RealFixerTest {

    /** 무엇을 부르는지 적어 두고, 정해 둔 결과를 내는 가짜 명령. */
    private static final class 가짜명령 implements RealFixer.명령 {
        final List<String> 부른것 = new ArrayList<>();
        int 종료코드 = 0;

        @Override
        public 결과 돌린다(List<String> 명령줄, Path 작업디렉터리) {
            부른것.add(String.join(" ", 명령줄));
            return new 결과(종료코드, "ok");
        }
    }

    private static final String 경로 = "src/main/java/a/OrderService.java";

    private static Path 파일을_만든다(Path 뿌리, String 내용) throws IOException {
        Path f = 뿌리.resolve(경로);
        Files.createDirectories(f.getParent());
        Files.writeString(f, 내용, StandardCharsets.UTF_8);
        return f;
    }

    @Nested
    @DisplayName("쓰기와 되돌리기")
    class 쓰고_되돌린다 {

        @Test
        @DisplayName("🔴 «준비»는 안 쓴다 — 네 겹이 ㉠(패치 전)을 «맨 처음» 재기 때문이다")
        void 준비는_안_쓴다(@TempDir Path 뿌리) throws IOException {
            Path f = 파일을_만든다(뿌리, "class OrderService { /* 버그 */ }");
            RealFixer 수선 = new RealFixer(뿌리, new 가짜명령());

            var runner = 수선.준비한다(Map.of(경로, "class OrderService { /* 고침 */ }"));

            assertThat(runner).isNotNull();
            assertThat(Files.readString(f))
                    .as("🔴 여기서 쓰면 「패치 전」이 「패치 후」가 되고, ㉠ 이 영원히 "
                            + "「패치 전에도 통과한다」로 나온다 — 2026-09-16 에 실제로 그랬다")
                    .contains("버그");
        }

        @Test
        @DisplayName("패치를 «진짜로» 파일에 쓴다 — 붙이라고 할 때")
        void 진짜로_쓴다(@TempDir Path 뿌리) throws IOException {
            Path f = 파일을_만든다(뿌리, "class OrderService { /* 버그 */ }");
            RealFixer 수선 = new RealFixer(뿌리, new 가짜명령());

            var runner = 수선.준비한다(Map.of(경로, "class OrderService { /* 고침 */ }"));
            assertThat(runner.패치를_적용한다()).isTrue();

            assertThat(Files.readString(f)).contains("고침");
        }

        @Test
        @DisplayName("🔴 «지난 시도»가 붙여 놓은 것을 먼저 뗀다 — 안 그러면 기준선이 기준선이 아니다")
        void 지난_시도를_먼저_뗀다(@TempDir Path 뿌리) throws IOException {
            Path f = 파일을_만든다(뿌리, "class OrderService { /* 버그 */ }");
            RealFixer 수선 = new RealFixer(뿌리, new 가짜명령());

            // 1차 시도: 붙이고 «안 떼고» 끝난다 (네 겹이 ㉠ 에서 일찍 끝나면 실제로 그렇게 된다)
            수선.준비한다(Map.of(경로, "class OrderService { /* 1차 */ }")).패치를_적용한다();
            assertThat(Files.readString(f)).contains("1차");

            // 2차 시도의 준비
            수선.준비한다(Map.of(경로, "class OrderService { /* 2차 */ }"));

            assertThat(Files.readString(f))
                    .as("🔴 안 떼면 «1차 패치»가 「원래 내용」으로 기록되고, 그 뒤로는 "
                            + "되돌려도 패치된 상태로 돌아간다")
                    .contains("버그");
        }

        @Test
        @DisplayName("🔴 되돌리면 «글자까지» 원래대로다")
        void 되돌리면_원래대로(@TempDir Path 뿌리) throws IOException {
            String 원래 = "class OrderService { /* 버그 */ }";
            Path f = 파일을_만든다(뿌리, 원래);
            RealFixer 수선 = new RealFixer(뿌리, new 가짜명령());

            수선.준비한다(Map.of(경로, "class OrderService { /* 고침 */ }")).패치를_적용한다();
            수선.되돌린다();

            assertThat(Files.readString(f))
                    .as("🔴 안 되돌리면 시험 한 번이 저장소를 바꿔 놓는다")
                    .isEqualTo(원래);
        }

        @Test
        @DisplayName("🔴 «원래 없던» 파일은 되돌릴 때 «지운다» — 빈 파일로 남기지 않는다")
        void 없던_파일은_지운다(@TempDir Path 뿌리) throws IOException {
            Files.createDirectories(뿌리.resolve("src/main/java/a"));
            RealFixer 수선 = new RealFixer(뿌리, new 가짜명령());
            String 새경로 = "src/main/java/a/NewThing.java";

            수선.준비한다(Map.of(새경로, "class NewThing {}")).패치를_적용한다();
            assertThat(뿌리.resolve(새경로)).exists();

            수선.되돌린다();

            assertThat(뿌리.resolve(새경로))
                    .as("🔴 빈 파일로 남기면 다음 빌드가 「빈 클래스」로 깨진다")
                    .doesNotExist();
        }

        @Test
        @DisplayName("🔴 ㉣ 뒤에 «다시 붙일» 수 있다 — 못 붙이면 PR 이 «패치 전» 코드를 올린다")
        void 다시_붙인다(@TempDir Path 뿌리) throws IOException {
            Path f = 파일을_만든다(뿌리, "class OrderService { /* 버그 */ }");
            RealFixer 수선 = new RealFixer(뿌리, new 가짜명령());

            var runner = 수선.준비한다(Map.of(경로, "class OrderService { /* 고침 */ }"));
            runner.패치를_적용한다();
            runner.패치를_되돌린다();
            assertThat(Files.readString(f)).contains("버그");

            assertThat(runner.패치를_적용한다()).isTrue();

            assertThat(Files.readString(f))
                    .as("네 겹의 ㉣ 은 되돌려 본 뒤 «다시 붙여 둔다». 그 상태를 PR 이 커밋한다")
                    .contains("고침");
        }
    }

    @Nested
    @DisplayName("🔴 경로 검사는 «쓰기 전»에 일어난다")
    class 경로검사 {

        @Test
        @DisplayName("🔴 테스트 파일을 고치려 들면 «아무것도 안 쓰고» 거절한다")
        void 테스트는_못_고친다(@TempDir Path 뿌리) throws IOException {
            Path 테스트 = 뿌리.resolve("src/test/java/a/ATest.java");
            Files.createDirectories(테스트.getParent());
            Files.writeString(테스트, "class ATest { /* 원래 */ }", StandardCharsets.UTF_8);
            RealFixer 수선 = new RealFixer(뿌리, new 가짜명령());

            var runner = 수선.준비한다(Map.of("src/test/java/a/ATest.java", "class ATest {}"));

            assertThat(runner)
                    .as("🔴 거절은 null 이다 — 그러면 고리가 아무것도 «돌리지 않는다»")
                    .isNull();
            assertThat(Files.readString(테스트))
                    .as("🔴 채점표를 고칠 수 있으면 채점이 아니다")
                    .contains("원래");
            assertThat(수선.마지막_경로판정()).isNotNull();
        }

        @Test
        @DisplayName("🔴 거절됐으면 «되돌릴 것도 없다» — 되돌리기가 멀쩡한 파일을 덮지 않는다")
        void 거절되면_되돌릴것도_없다(@TempDir Path 뿌리) throws IOException {
            Path 테스트 = 뿌리.resolve("src/test/java/a/ATest.java");
            Files.createDirectories(테스트.getParent());
            Files.writeString(테스트, "원래", StandardCharsets.UTF_8);
            RealFixer 수선 = new RealFixer(뿌리, new 가짜명령());

            수선.준비한다(Map.of("src/test/java/a/ATest.java", "바꾼것"));
            수선.되돌린다();

            assertThat(수선.건드린_경로()).isEmpty();
            assertThat(Files.readString(테스트)).isEqualTo("원래");
        }
    }

    @Nested
    @DisplayName("🔴 테스트는 «새 프로세스»로 돌린다")
    class 새_프로세스 {

        @Test
        @DisplayName("돌고 있는 JVM 은 «패치 전» 클래스를 들고 있어서, 안에서 돌리면 안 된다")
        void 바깥에서_돌린다(@TempDir Path 뿌리) throws IOException {
            파일을_만든다(뿌리, "class OrderService {}");
            가짜명령 명령 = new 가짜명령();
            RealFixer 수선 = new RealFixer(뿌리, 명령);

            var runner = 수선.준비한다(Map.of(경로, "class OrderService { /* 고침 */ }"));
            runner.재생_테스트가_통과하나();
            runner.기존_테스트가_전부_통과하나();

            assertThat(명령.부른것).hasSize(2);
            assertThat(명령.부른것.get(0)).contains(":demo-app:replayCheck");
            assertThat(명령.부른것.get(1))
                    .as("🔴 ㉢ 은 «앱 자신의» 테스트를 돌린다. 도구의 시험을 돌리면 재귀가 된다")
                    .contains("io.hindsight.demo.order.*");
        }

        @Test
        @DisplayName("바깥 프로세스가 실패하면 그 겹도 실패다")
        void 실패하면_실패() throws IOException {
            Path 뿌리 = Files.createTempDirectory("fixer-");
            try {
                파일을_만든다(뿌리, "class OrderService {}");
                가짜명령 명령 = new 가짜명령();
                명령.종료코드 = 1;
                RealFixer 수선 = new RealFixer(뿌리, 명령);

                var runner = 수선.준비한다(Map.of(경로, "class OrderService { /* 고침 */ }"));

                assertThat(runner.재생_테스트가_통과하나()).isFalse();
                assertThat(runner.기존_테스트가_전부_통과하나()).isFalse();
            } finally {
                try (var s = Files.walk(뿌리)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 임시 폴더다. 못 지워도 시험에는 영향이 없다
                        }
                    });
                }
            }
        }
    }

    @Nested
    @DisplayName("🔴 «성공했을 때»도 작업 트리를 되돌린다")
    class 성공해도_되돌린다 {

        @Test
        @DisplayName("🔴 ㉣ 뒤에 다시 붙인 것을 «마지막 되돌리기»가 뗀다")
        void 다시_붙인_것도_뗀다(@TempDir Path 뿌리) throws IOException {
            String 원래 = "class OrderService { /* 버그 */ }";
            Path f = 파일을_만든다(뿌리, 원래);
            RealFixer 수선 = new RealFixer(뿌리, new 가짜명령());

            // 네 겹이 실제로 하는 순서: 붙인다 → 뗀다(㉣) → 다시 붙인다
            var runner = 수선.준비한다(Map.of(경로, "class OrderService { /* 고침 */ }"));
            runner.패치를_적용한다();
            runner.패치를_되돌린다();
            runner.패치를_적용한다();
            assertThat(Files.readString(f)).contains("고침");

            // 바깥(FixRunner 의 finally)이 마지막으로 되돌린다
            수선.되돌린다();

            assertThat(Files.readString(f))
                    .as("🔴 안 되면 고리가 «성공했을 때» 사람의 작업 트리가 고쳐진 채로 남는다 — "
                            + "2026-09-16 에 실제로 그랬다. 실패 경로만 보고 성공 경로를 놓쳤다")
                    .isEqualTo(원래);
        }

        @Test
        @DisplayName("여러 번 되돌려도 안전하다")
        void 여러번_되돌려도_안전() throws IOException {
            Path 뿌리 = Files.createTempDirectory("fixer-idem-");
            try {
                String 원래 = "class OrderService { /* 버그 */ }";
                Path f = 파일을_만든다(뿌리, 원래);
                RealFixer 수선 = new RealFixer(뿌리, new 가짜명령());

                수선.준비한다(Map.of(경로, "고침")).패치를_적용한다();
                수선.되돌린다();
                수선.되돌린다();

                assertThat(Files.readString(f)).isEqualTo(원래);
            } finally {
                try (var s = Files.walk(뿌리)) {
                    s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // 임시 폴더다
                        }
                    });
                }
            }
        }
    }
}
