package io.hindsight.core.ship;

import io.hindsight.core.brain.Confidence;
import io.hindsight.core.brain.PullRequestDraft;

import java.util.ArrayList;
import java.util.List;

/**
 * 🔴 <b>PR 을 실제로 올리는 자리. 그리고 «여기서 멈춘다».</b>
 *
 * <h2>이 클래스가 하는 일 넷</h2>
 * <pre>
 *   ① 브랜치를 만든다     hindsight/&lt;기록 번호&gt;
 *   ② 바뀐 파일을 커밋한다  🔴 «이름을 적은» 경로만. git add . 는 하지 않는다
 *   ③ 밀어 올린다
 *   ④ PR 을 «연다»        → 여기서 끝. 병합하지 않는다
 * </pre>
 *
 * <h2>🔴 배포하지 않는다. 병합도 안 한다</h2>
 * 재생이 증명하는 것은 <b>「이 상황 하나가 고쳐졌다」</b>뿐이고, 무엇이 더 깨졌는지는
 * 증명하지 않는다. CI 와 사람이 나머지를 정한다.
 *
 * <h2>🔴 이 클래스는 명령을 «직접 실행하지 않는다»</h2>
 * 실행은 {@link Shell} 이 한다. 그래서 <b>「무슨 명령을 어떤 순서로 부르는가」를
 * 네트워크도 저장소도 없이 전수로 시험</b>할 수 있다.
 *
 * <p>⚠️ 이게 중요한 이유: 여기서 틀리면 <b>남의 저장소에 잘못된 브랜치가 생긴다.</b>
 * 되돌릴 수는 있지만, 되돌리는 일은 언제나 사람 몫이다.
 *
 * <h2>🔴 토큰은 환경변수로만 받는다</h2>
 * {@code GITHUB_TOKEN} 이 없으면 <b>연습만 하고 아무것도 안 올린다</b>({@code dryRun}).
 * 토큰을 코드나 설정 파일에 적지 않는다 — 그 순간 저장소에 남는다.
 */
public final class PullRequestSubmitter {

    /** 🔴 명령을 실제로 돌리는 자리. 시험에서는 가짜로 갈아 끼운다. */
    public interface Shell {

        /**
         * @return 종료 코드와 출력. 🔴 <b>예외를 던지지 않는다</b> — 실패도 «결과»이고,
         *         어느 단계에서 멈췄는지가 보고에 남아야 한다
         */
        결과 run(List<String> command);

        record 결과(int exitCode, String output) {
            public boolean ok() {
                return exitCode == 0;
            }
        }
    }

    private final Shell shell;
    private final String token;

    /**
     * @param token {@code GITHUB_TOKEN}. 🔴 {@code null} 이거나 비어 있으면
     *              <b>연습만 한다</b> — 브랜치도 안 만들고 PR 도 안 연다
     */
    public PullRequestSubmitter(Shell shell, String token) {
        this.shell = shell;
        this.token = (token == null || token.isBlank()) ? null : token;
    }

    /** 토큰이 없으면 연습만 한다. 🔴 「토큰이 없다」와 「올렸다」를 절대 같게 다루지 않는다. */
    public boolean dryRun() {
        return token == null;
    }

    /**
     * 한 판의 결과.
     *
     * @param 올렸나   🔴 <b>진짜로 PR 이 열렸나.</b> 연습이면 {@code false} 다
     * @param 연습이었나 토큰이 없어서 아무것도 안 했나
     * @param 단계     실제로 부른(또는 부르려 한) 명령들. 어디서 멈췄는지가 여기 남는다
     * @param 왜       못 올렸으면 그 이유
     */
    public record Result(boolean 올렸나, boolean 연습이었나, List<String> 단계, String 왜) {

        public Result {
            단계 = List.copyOf(단계);
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(올렸나 ? "✅ PR 을 열었다" : (연습이었나 ? "⬜ 연습만 했다 (GITHUB_TOKEN 이 없다)" : "🔴 못 올렸다"));
            if (왜 != null) {
                sb.append(" — ").append(왜);
            }
            sb.append(System.lineSeparator());
            단계.forEach(s -> sb.append("  ").append(s).append(System.lineSeparator()));
            return sb.toString();
        }
    }

    /**
     * @param draft      올릴 글. 🔴 {@link PullRequestDraft#opensAutomatically()} 가
     *                   거짓이면 <b>아무것도 안 한다</b>
     * @param 바뀐경로   커밋할 경로들. 🔴 <b>이름을 적은 것만</b> 커밋한다
     * @param 기준브랜치 PR 을 받을 브랜치 (보통 {@code dev})
     * @param 저장소     {@code owner/repo}
     */
    public Result submit(PullRequestDraft draft, List<String> 바뀐경로,
                         String 기준브랜치, String 저장소) {
        List<String> 단계 = new ArrayList<>();

        if (draft == null) {
            return new Result(false, dryRun(), 단계, "올릴 글이 없다");
        }
        if (바뀐경로 == null || 바뀐경로.isEmpty()) {
            // 🔴 「바뀐 게 없다」로 빈 PR 을 열지 않는다. 그건 사람의 시간을 쓰는 일이다.
            return new Result(false, dryRun(), 단계, "커밋할 경로가 «하나도 없다». 빈 PR 은 안 연다");
        }
        if (!draft.opensAutomatically()) {
            // 🔴 확신도가 「높음」이 아니거나 유출 모양이 걸린 글이다. 사람이 본 뒤에 연다.
            return new Result(false, dryRun(), 단계,
                    "이 글은 «자동으로 열지 않기로» 판정됐다 (확신도 " + draft.confidence().korean()
                            + "). 사람이 보고 연다");
        }

        String 브랜치 = draft.branchName();
        List<List<String>> 명령들 = List.of(
                List.of("git", "switch", "-c", 브랜치),
                // 🔴 git add . 를 절대 쓰지 않는다. 남의 저장소에 곁다리 파일이 딸려 들어간다.
                합친다(List.of("git", "add", "--"), 바뀐경로),
                List.of("git", "commit", "-m", draft.title()),
                List.of("git", "push", "-u", "origin", 브랜치));

        if (dryRun()) {
            명령들.forEach(c -> 단계.add("⬜ (연습) " + String.join(" ", c)));
            단계.add("⬜ (연습) PR 열기 → " + 저장소 + "  " + 브랜치 + " → " + 기준브랜치);
            return new Result(false, true, 단계,
                    "GITHUB_TOKEN 이 없다. 🔴 토큰은 .env 에 사람이 넣는다");
        }

        for (List<String> 명령 : 명령들) {
            Shell.결과 r = shell.run(명령);
            단계.add((r.ok() ? "✅ " : "🔴 ") + 가린다(명령));
            if (!r.ok()) {
                // 🔴 중간에 멈추면 «거기서» 멈춘다. 다음 단계를 밀어붙이면
                //    반쯤 올라간 브랜치가 남고, 그건 사람이 치워야 한다.
                return new Result(false, false, 단계,
                        "「" + 가린다(명령) + "」 에서 멈췄다: " + 한줄로(r.output()));
            }
        }

        Shell.결과 pr = shell.run(PR_만드는_명령(draft, 기준브랜치, 저장소));
        단계.add((pr.ok() ? "✅ " : "🔴 ") + "PR 열기");
        if (!pr.ok()) {
            return new Result(false, false, 단계, "PR 을 못 열었다: " + 한줄로(pr.output()));
        }
        return new Result(true, false, 단계, null);
    }

    /**
     * 🔴 <b>GitHub API 를 직접 부른다. {@code gh} 에 기대지 않는다.</b>
     *
     * <p>{@code gh} 는 자기 토큰 저장소를 따로 쓰고, 그 로그인은 브라우저와 코드 입력이
     * 필요하다 — <b>사람 없이 끝낼 수 없다.</b> 도구가 스스로 PR 을 여는 것이 목표이므로
     * 토큰 하나로 끝나는 길을 쓴다.
     */
    private List<String> PR_만드는_명령(PullRequestDraft draft, String 기준브랜치, String 저장소) {
        return List.of("curl", "-sS", "-X", "POST",
                "-H", "Authorization: Bearer " + token,
                "-H", "Accept: application/vnd.github+json",
                "https://api.github.com/repos/" + 저장소 + "/pulls",
                "-d", 본문JSON(draft, 기준브랜치));
    }

    private static String 본문JSON(PullRequestDraft draft, String 기준브랜치) {
        return "{\"title\":" + 따옴표(draft.title())
                + ",\"head\":" + 따옴표(draft.branchName())
                + ",\"base\":" + 따옴표(기준브랜치)
                + ",\"body\":" + 따옴표(draft.body()) + "}";
    }

    /** 🔴 토큰이 로그에 남지 않게 가린다. 단계 목록은 보고서에 그대로 나간다. */
    static String 가린다(List<String> 명령) {
        return 명령.stream()
                .map(a -> a.startsWith("Authorization: Bearer ") ? "Authorization: Bearer ***" : a)
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    private static List<String> 합친다(List<String> 앞, List<String> 뒤) {
        List<String> all = new ArrayList<>(앞);
        all.addAll(뒤);
        return List.copyOf(all);
    }

    private static String 한줄로(String s) {
        if (s == null) {
            return "(출력 없음)";
        }
        String 한줄 = s.replace('\n', ' ').replace('\r', ' ').trim();
        return 한줄.length() <= 200 ? 한줄 : 한줄.substring(0, 200) + "…";
    }

    /** JSON 문자열 한 조각. 🔴 제어 문자까지 이스케이프한다 — 본문에 무엇이든 들어올 수 있다. */
    static String 따옴표(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * 어디에 올릴 것인가. 🔴 <b>둘 다 설정에서 온다</b> — 코드에 저장소 이름을 박으면
     * 이 도구는 이 저장소 전용이 된다.
     *
     * @param 저장소     {@code HINDSIGHT_GITHUB_REPO} — {@code owner/repo}. 🔴 없으면 {@code null}
     * @param 기준브랜치 {@code HINDSIGHT_GITHUB_BASE} — 기본 {@code dev}
     */
    public record 올릴곳(String 저장소, String 기준브랜치) {

        public static 올릴곳 from(java.util.function.Function<String, String> env) {
            String repo = env.apply("HINDSIGHT_GITHUB_REPO");
            String base = env.apply("HINDSIGHT_GITHUB_BASE");
            return new 올릴곳(
                    (repo == null || repo.isBlank()) ? null : repo.trim(),
                    (base == null || base.isBlank()) ? "dev" : base.trim());
        }

        /** 🔴 저장소를 모르면 «올릴 수 없다». 짐작해서 남의 저장소에 올리지 않는다. */
        public boolean 알고있나() {
            return 저장소 != null;
        }
    }

    /** 확신도만으로 「열어도 되나」를 다시 묻고 싶을 때. 🔴 판단은 brain 이 이미 했다. */
    public static boolean 열어도_되나(Confidence confidence) {
        return confidence != null && confidence.allowsAutomaticPullRequest();
    }
}
