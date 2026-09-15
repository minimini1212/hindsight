package io.hindsight.core.guard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("패치가 건드려도 되는 경로인가")
class PatchGuardTest {

    private final PatchGuard guard = new PatchGuard();

    private PatchVerdict.Level 판정(String... paths) {
        return guard.judge(List.of(paths)).level();
    }

    @Nested
    @DisplayName("✅ 고쳐도 되는 자리")
    class 허용 {

        @Test
        @DisplayName("src/main/java 안이면 허용한다")
        void 본체_코드는_허용() {
            assertThat(판정("src/main/java/io/hindsight/demo/order/OrderService.java"))
                    .isEqualTo(PatchVerdict.Level.ALLOW);
        }

        @Test
        @DisplayName("여러 파일이 전부 안쪽이면 허용한다")
        void 여러_파일도_허용() {
            assertThat(판정(
                    "src/main/java/a/A.java",
                    "src/main/java/b/B.java")).isEqualTo(PatchVerdict.Level.ALLOW);
        }
    }

    @Nested
    @DisplayName("🔴 채점표를 고치려는 패치 — 이 프로젝트가 막으려는 바로 그것")
    class 채점표 {

        @Test
        @DisplayName("🔴 src/test 를 건드리면 거절한다")
        void 테스트를_건드리면_거절() {
            PatchVerdict verdict = guard.judge(List.of("src/test/java/a/ATest.java"));

            assertThat(verdict.level()).isEqualTo(PatchVerdict.Level.REJECT);
            assertThat(String.join(" ", verdict.reasons())).contains("채점표 그 자체");
        }

        @Test
        @DisplayName("🔴 멀쩡한 파일 «하나라도» 섞여 있으면 전체를 거절한다")
        void 하나라도_섞이면_전체_거절() {
            // LLM 이 진짜 고침 + 테스트 삭제를 «같이» 내놓는 모양이다.
            assertThat(판정(
                    "src/main/java/a/A.java",
                    "src/test/java/a/ATest.java")).isEqualTo(PatchVerdict.Level.REJECT);
        }

        @Test
        @DisplayName("🔴 기록 파일을 건드리면 거절한다 — 채점의 «입력»이다")
        void 기록을_건드리면_거절() {
            assertThat(판정("recordings/2026-09-15_a1b2.json")).isEqualTo(PatchVerdict.Level.REJECT);
        }

        @Test
        @DisplayName("🔴 CI 를 고쳐서 통과시키려는 패치를 거절한다")
        void CI_를_건드리면_거절() {
            PatchVerdict verdict = guard.judge(List.of(".github/workflows/ci.yml"));

            assertThat(verdict.level()).isEqualTo(PatchVerdict.Level.REJECT);
            assertThat(String.join(" ", verdict.reasons())).contains("CI 를 고쳐서");
        }

        @Test
        @DisplayName("🔴 빌드를 고쳐서 통과시키려는 패치를 거절한다")
        void 빌드를_건드리면_거절() {
            assertThat(판정("build.gradle.kts")).isEqualTo(PatchVerdict.Level.REJECT);
            assertThat(판정("gradle/libs.versions.toml")).isEqualTo(PatchVerdict.Level.REJECT);
            assertThat(판정("gradlew")).isEqualTo(PatchVerdict.Level.REJECT);
        }

        @Test
        @DisplayName("🔴 Hindsight 자신을 건드리면 거절한다 — 자기 채점기를 약화시킬 수 있다")
        void 우리_모듈을_건드리면_거절() {
            PatchVerdict verdict = guard.judge(
                    List.of("hindsight-core/src/main/java/io/hindsight/core/replay/Oracle.java"));

            assertThat(verdict.level()).isEqualTo(PatchVerdict.Level.REJECT);
            assertThat(String.join(" ", verdict.reasons())).contains("자기 채점기");
        }
    }

    @Nested
    @DisplayName("🔴 경로 문자열을 그대로 믿지 않는다")
    class 경로_펴기 {

        @Test
        @DisplayName("🔴 화이트리스트로 «시작»하지만 밖으로 나가는 경로를 거절한다")
        void 거슬러_올라가는_경로를_거절() {
            // 앞이 src/main/java 라서 「시작하나」만 보면 통과한다. 그리고 CI 를 고친다.
            PatchVerdict verdict = guard.judge(
                    List.of("src/main/java/../../../.github/workflows/ci.yml"));

            assertThat(verdict.level()).isEqualTo(PatchVerdict.Level.REJECT);
        }

        @Test
        @DisplayName("🔴 펴 보면 테스트 폴더인 경로를 거절한다")
        void 펴면_테스트인_경로를_거절() {
            assertThat(판정("src/main/java/../../test/java/a/ATest.java"))
                    .isEqualTo(PatchVerdict.Level.REJECT);
        }

        @Test
        @DisplayName("절대 경로를 거절한다")
        void 절대_경로를_거절() {
            assertThat(판정("/etc/passwd")).isEqualTo(PatchVerdict.Level.REJECT);
            assertThat(판정("C:/Windows/system32/x.dll")).isEqualTo(PatchVerdict.Level.REJECT);
            assertThat(판정("~/.ssh/authorized_keys")).isEqualTo(PatchVerdict.Level.REJECT);
        }

        @Test
        @DisplayName("역슬래시로 써도 같게 본다 — 운영체제에 따라 안전선이 달라지면 안 된다")
        void 역슬래시도_같게_본다() {
            assertThat(판정("src\\test\\java\\a\\ATest.java")).isEqualTo(PatchVerdict.Level.REJECT);
        }

        @Test
        @DisplayName("저장소 밖으로 나가면 거절한다")
        void 밖으로_나가면_거절() {
            assertThat(판정("../other-repo/src/main/java/A.java")).isEqualTo(PatchVerdict.Level.REJECT);
        }
    }

    @Nested
    @DisplayName("🟡 막지는 않지만 사람이 보는 자리")
    class 사람이_본다 {

        @Test
        @DisplayName("설정 파일은 거절도 자동 통과도 아니다")
        void 설정_파일은_사람이_본다() {
            PatchVerdict verdict = guard.judge(List.of("src/main/resources/application.yml"));

            assertThat(verdict.level()).isEqualTo(PatchVerdict.Level.NEEDS_HUMAN);
            assertThat(verdict.allowsAutomatic()).isFalse();
            assertThat(String.join(" ", verdict.reasons())).contains("기능 플래그");
        }

        @Test
        @DisplayName("🔴 설정 파일과 «금지된 자리»가 같이 있으면 거절이 이긴다")
        void 금지가_사람보다_세다() {
            assertThat(판정(
                    "src/main/resources/application.yml",
                    "src/test/java/a/ATest.java")).isEqualTo(PatchVerdict.Level.REJECT);
        }
    }

    @Nested
    @DisplayName("🔴 아무것도 안 바꾸는 패치")
    class 빈_패치 {

        @Test
        @DisplayName("빈 목록은 «안전한 패치»가 아니라 패치가 아니다")
        void 빈_패치는_거절() {
            PatchVerdict verdict = guard.judge(List.of());

            assertThat(verdict.level()).isEqualTo(PatchVerdict.Level.REJECT);
            assertThat(String.join(" ", verdict.reasons())).contains("패치가 아니다");
        }

        @Test
        @DisplayName("null 도 거절한다")
        void null_도_거절() {
            assertThat(guard.judge(null).level()).isEqualTo(PatchVerdict.Level.REJECT);
        }
    }

    @Nested
    @DisplayName("화이트리스트를 넓히면 그만큼 넓어진다 — 그게 위험을 늘리는 쪽이라는 것을 보인다")
    class 화이트리스트 {

        @Test
        @DisplayName("넓혀도 «금지된 자리»는 여전히 거절한다")
        void 넓혀도_금지는_그대로() {
            PatchGuard 넓힌것 = new PatchGuard(List.of("src/"));

            assertThat(넓힌것.judge(List.of("src/main/kotlin/A.kt")).level())
                    .isEqualTo(PatchVerdict.Level.ALLOW);
            // 🔴 src/ 를 통째로 열어도 src/test/ 는 막힌다. 금지가 화이트리스트보다 세다.
            assertThat(넓힌것.judge(List.of("src/test/java/ATest.java")).level())
                    .isEqualTo(PatchVerdict.Level.REJECT);
        }
    }
}
