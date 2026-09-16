package io.hindsight.core.ship;

import io.hindsight.core.brain.Confidence;
import io.hindsight.core.brain.PullRequestDraft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR 을 올리는 «순서»가 맞는지, 그리고 <b>안 올려야 할 때 정말 안 올리는지</b> 본다.
 *
 * <h2>🔴 왜 네트워크 없이 전수로 시험할 수 있나</h2>
 * {@link PullRequestSubmitter} 는 명령을 <b>직접 실행하지 않는다</b> — {@code Shell} 로 받는다.
 * 여기서 틀리면 <b>남의 저장소에 잘못된 브랜치가 생기고</b>, 치우는 일은 언제나 사람 몫이다.
 * 그래서 「무슨 명령을 어떤 순서로 부르는가」가 시험할 수 있는 자리에 있어야 한다.
 */
@DisplayName("PR 을 올린다 — 그리고 안 올려야 할 때는 안 올린다")
class PullRequestSubmitterTest {

    /** 부른 명령을 적어 두는 가짜 껍데기. 어느 명령에서 실패할지 정할 수 있다. */
    private static final class 가짜셸 implements PullRequestSubmitter.Shell {
        final List<String> 부른것 = new ArrayList<>();
        String 여기서_실패한다;

        @Override
        public 결과 run(List<String> command) {
            String 한줄 = String.join(" ", command);
            부른것.add(한줄);
            if (여기서_실패한다 != null && 한줄.contains(여기서_실패한다)) {
                return new 결과(1, "fatal: 뭔가 잘못됐다");
            }
            return new 결과(0, "ok");
        }
    }

    private static PullRequestDraft 열어도_되는_글() {
        return new PullRequestDraft("hindsight/a1b2", "fix(주문): N+1 을 고쳤다",
                "본문\n둘째 줄", Confidence.HIGH, true);
    }

    private static final List<String> 바뀐경로 =
            List.of("src/main/java/a/OrderService.java");

    @Nested
    @DisplayName("🔴 안 올려야 할 때")
    class 안_올린다 {

        @Test
        @DisplayName("🔴 토큰이 없으면 «연습만» 한다 — 「토큰이 없다」와 「올렸다」를 같게 다루지 않는다")
        void 토큰이_없으면_연습만() {
            가짜셸 셸 = new 가짜셸();

            var r = new PullRequestSubmitter(셸, null)
                    .submit(열어도_되는_글(), 바뀐경로, "dev", "me/repo");

            assertThat(r.올렸나()).isFalse();
            assertThat(r.연습이었나()).isTrue();
            assertThat(셸.부른것)
                    .as("🔴 연습이면 명령을 «하나도» 부르면 안 된다")
                    .isEmpty();
            assertThat(r.왜()).contains(".env");
            assertThat(r.단계())
                    .as("무엇을 «했을» 것인지는 보여 준다 — 그래야 사람이 손으로 이어서 할 수 있다")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("🔴 「자동으로 열지 않기로」 판정된 글은 안 올린다")
        void 확신도가_낮으면_안_올린다() {
            가짜셸 셸 = new 가짜셸();
            PullRequestDraft 초안 = new PullRequestDraft("hindsight/a1b2", "제목", "본문",
                    Confidence.MEDIUM, false);

            var r = new PullRequestSubmitter(셸, "토큰")
                    .submit(초안, 바뀐경로, "dev", "me/repo");

            assertThat(r.올렸나()).isFalse();
            assertThat(셸.부른것).isEmpty();
            assertThat(r.왜()).contains("사람이 보고 연다");
        }

        @Test
        @DisplayName("🔴 바뀐 파일이 없으면 «빈 PR» 을 안 연다 — 사람의 시간을 쓰는 일이다")
        void 바뀐게_없으면_안_연다() {
            가짜셸 셸 = new 가짜셸();

            var r = new PullRequestSubmitter(셸, "토큰")
                    .submit(열어도_되는_글(), List.of(), "dev", "me/repo");

            assertThat(r.올렸나()).isFalse();
            assertThat(셸.부른것).isEmpty();
            assertThat(r.왜()).contains("빈 PR");
        }
    }

    @Nested
    @DisplayName("올릴 때 — 순서와 «무엇을 커밋하나»")
    class 올린다 {

        @Test
        @DisplayName("브랜치 → 스테이징 → 커밋 → 푸시 → PR 순서로 부른다")
        void 순서() {
            가짜셸 셸 = new 가짜셸();

            var r = new PullRequestSubmitter(셸, "토큰")
                    .submit(열어도_되는_글(), 바뀐경로, "dev", "me/repo");

            assertThat(r.올렸나()).isTrue();
            assertThat(셸.부른것).hasSize(5);
            assertThat(셸.부른것.get(0)).startsWith("git switch -c hindsight/a1b2");
            assertThat(셸.부른것.get(1)).startsWith("git add --");
            assertThat(셸.부른것.get(2)).startsWith("git commit -m");
            assertThat(셸.부른것.get(3)).startsWith("git push -u origin hindsight/a1b2");
            assertThat(셸.부른것.get(4)).contains("api.github.com");
        }

        @Test
        @DisplayName("🔴 `git add .` 를 «절대» 쓰지 않는다 — 곁다리 파일이 남의 저장소로 간다")
        void 이름을_적은_것만_커밋한다() {
            가짜셸 셸 = new 가짜셸();

            new PullRequestSubmitter(셸, "토큰")
                    .submit(열어도_되는_글(), 바뀐경로, "dev", "me/repo");

            String add = 셸.부른것.get(1);
            assertThat(add).doesNotContain("git add .");
            assertThat(add).endsWith("src/main/java/a/OrderService.java");
        }

        @Test
        @DisplayName("🔴 토큰이 단계 기록에 «안» 남는다 — 보고서는 그대로 사람에게 간다")
        void 토큰이_로그에_안_남는다() {
            가짜셸 셸 = new 가짜셸();

            var r = new PullRequestSubmitter(셸, "비밀토큰값")
                    .submit(열어도_되는_글(), 바뀐경로, "dev", "me/repo");

            assertThat(String.join(" ", r.단계())).doesNotContain("비밀토큰값");
            assertThat(r.describe()).doesNotContain("비밀토큰값");
        }

        @Test
        @DisplayName("PR 을 받을 브랜치와 저장소가 요청에 들어간다")
        void 기준브랜치와_저장소() {
            가짜셸 셸 = new 가짜셸();

            new PullRequestSubmitter(셸, "토큰")
                    .submit(열어도_되는_글(), 바뀐경로, "dev", "minimini1212/hindsight");

            String pr = 셸.부른것.get(4);
            assertThat(pr).contains("repos/minimini1212/hindsight/pulls");
            assertThat(pr).contains("\"base\":\"dev\"");
            assertThat(pr).contains("\"head\":\"hindsight/a1b2\"");
        }
    }

    @Nested
    @DisplayName("🔴 중간에 실패하면 «거기서» 멈춘다")
    class 실패 {

        @Test
        @DisplayName("푸시가 실패하면 PR 을 «안» 연다")
        void 푸시가_실패하면_PR을_안_연다() {
            가짜셸 셸 = new 가짜셸();
            셸.여기서_실패한다 = "git push";

            var r = new PullRequestSubmitter(셸, "토큰")
                    .submit(열어도_되는_글(), 바뀐경로, "dev", "me/repo");

            assertThat(r.올렸나()).isFalse();
            assertThat(셸.부른것)
                    .as("🔴 밀어붙이면 반쯤 올라간 브랜치가 남고, 그건 사람이 치워야 한다")
                    .noneMatch(c -> c.contains("api.github.com"));
            assertThat(r.왜()).contains("git push");
        }

        @Test
        @DisplayName("어디서 멈췄는지가 단계 목록에 남는다")
        void 어디서_멈췄는지_남는다() {
            가짜셸 셸 = new 가짜셸();
            셸.여기서_실패한다 = "git commit";

            var r = new PullRequestSubmitter(셸, "토큰")
                    .submit(열어도_되는_글(), 바뀐경로, "dev", "me/repo");

            assertThat(r.단계()).anyMatch(s -> s.startsWith("🔴 ") && s.contains("git commit"));
            assertThat(r.단계()).anyMatch(s -> s.startsWith("✅ ") && s.contains("git switch"));
        }

        @Test
        @DisplayName("PR 열기가 실패해도 «올렸다»고 하지 않는다")
        void PR_열기가_실패하면() {
            가짜셸 셸 = new 가짜셸();
            셸.여기서_실패한다 = "api.github.com";

            var r = new PullRequestSubmitter(셸, "토큰")
                    .submit(열어도_되는_글(), 바뀐경로, "dev", "me/repo");

            assertThat(r.올렸나()).isFalse();
            assertThat(r.왜()).contains("PR 을 못 열었다");
        }
    }

    @Nested
    @DisplayName("본문을 JSON 으로 싣는다")
    class 본문 {

        @Test
        @DisplayName("🔴 줄바꿈·따옴표·제어문자가 요청을 깨뜨리지 않는다")
        void 까다로운_글자() {
            String 까다로운것 = "따\"옴\\표\n둘째 줄\t탭" + new String(new char[]{0, 31});

            String json = PullRequestSubmitter.따옴표(까다로운것);

            assertThat(json).startsWith("\"").endsWith("\"");
            assertThat(json).contains("\\\"").contains("\\\\").contains("\\n").contains("\\t");
            assertThat(json)
                    .as("🔴 제어문자를 날것으로 두면 JSON 이 깨져서 «PR 을 못 여는데 이유를 모른다»")
                    .contains("\\u0000").contains("\\u001f");
        }

        @Test
        @DisplayName("본문이 없으면 null 이다 — 빈 문자열로 바꾸지 않는다")
        void 없는_본문() {
            assertThat(PullRequestSubmitter.따옴표(null)).isEqualTo("null");
        }
    }
}
