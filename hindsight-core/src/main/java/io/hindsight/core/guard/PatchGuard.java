package io.hindsight.core.guard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 패치가 <b>건드려도 되는 경로인지</b>를 «적용하기 전에» 본다.
 *
 * <h2>🔴 이게 없으면 무슨 일이 나나</h2>
 * LLM 에게 *"테스트를 통과시켜라"* 라고 하면 <b>테스트를 지워서 통과시킨다.</b>
 * 이건 가정이 아니라 LLM 코딩 도구에서 관찰된 행동이다. 채점기가 있다고 «믿는» 구조에서
 * 채점표를 고칠 수 있으면, 그 구조는 아무것도 보장하지 않는다.
 *
 * <h2>무엇을 어떻게 나누나</h2>
 * <table>
 *   <tr><th>자리</th><th>판정</th><th>왜</th></tr>
 *   <tr><td>{@code src/main/java/**}</td><td>✅ 허용</td><td>고쳐야 할 코드가 있는 곳</td></tr>
 *   <tr><td>{@code src/main/resources/**}</td><td>🟡 사람이 본다</td>
 *       <td>🔴 {@code application.yml} 한 줄로 <b>검증을 끄거나 기능 플래그를 뒤집을 수 있다</b></td></tr>
 *   <tr><td>{@code src/test/**}</td><td>🔴 거절</td><td><b>채점표 그 자체</b></td></tr>
 *   <tr><td>기록 파일</td><td>🔴 거절</td><td>채점의 «입력». 고치면 원하는 답을 만들 수 있다</td></tr>
 *   <tr><td>{@code .github/**}</td><td>🔴 거절</td><td><b>CI 를 고쳐서 통과시킬 수 있다</b></td></tr>
 *   <tr><td>{@code gradle/**} · {@code *.gradle*}</td><td>🔴 거절</td><td><b>빌드를 고쳐서 통과시킬 수 있다</b></td></tr>
 *   <tr><td>{@code .git/**}</td><td>🔴 거절</td><td>역사 자체를 고칠 수 있다</td></tr>
 *   <tr><td>{@code hindsight-*}</td><td>🔴 거절</td><td>🔴 <b>자기 채점기를 약화시킬 수 있다</b></td></tr>
 * </table>
 *
 * <h2>🔴 경로 문자열을 그대로 믿지 않는다</h2>
 * {@code src/main/java/../../../.github/workflows/ci.yml} 은 <b>화이트리스트로 시작한다.</b>
 * 그래서 「앞이 맞는지」만 보면 통과한다. 반드시 <b>펴서(normalize)</b> 보고,
 * 절대 경로와 저장소 밖으로 나가는 경로는 거절한다.
 *
 * <p>🔴 <b>이 검사는 순수 함수다</b> — 파일 시스템을 안 본다. 그래서 파일이 없어도,
 * 운영체제가 달라도 같은 답이 나오고, 검사로 전수 확인할 수 있다.
 * 심볼릭 링크처럼 «실제 파일»을 봐야 아는 것은 {@link PatchApplier} 가 본다.
 */
public final class PatchGuard {

    /** 기본 화이트리스트. 🔴 「고칠 수 있는 곳」을 넓히는 것은 언제나 위험을 «늘리는» 쪽이다. */
    public static final List<String> 기본_허용 = List.of("src/main/java/");

    /** 사람이 봐야 하는 자리 — 막지는 않는다. */
    private static final List<String> 사람이_본다 = List.of("src/main/resources/");

    /** 🔴 절대 금지. 여기 하나라도 걸리면 나머지가 아무리 멀쩡해도 거절한다. */
    private static final List<String> 금지_접두어 = List.of(
            "src/test/", ".git/", ".github/", "gradle/", "recordings/");

    private static final List<String> 금지_이름조각 = List.of(".gradle", "gradlew");

    /** 🔴 Hindsight 자신. 자기 채점기를 약화시키는 패치를 막는다. */
    private static final String 우리_모듈_접두어 = "hindsight-";

    private final List<String> 허용;

    public PatchGuard() {
        this(기본_허용);
    }

    public PatchGuard(List<String> 허용) {
        this.허용 = List.copyOf(허용);
    }

    /**
     * 이 경로들을 건드리는 패치를 적용해도 되나.
     *
     * @param changedPaths 패치가 «건드리는 모든» 경로. 🔴 일부만 넘기면 검사가 무의미하다
     */
    public PatchVerdict judge(List<String> changedPaths) {
        if (changedPaths == null || changedPaths.isEmpty()) {
            // 🔴 아무것도 안 바꾸는 패치는 「안전한 패치」가 아니라 «패치가 아니다».
            //    통과시키면 아무것도 안 고치고 「고쳤다」고 말하는 PR 이 올라간다.
            return PatchVerdict.reject(
                    List.of("바꾸는 파일이 하나도 없다. 이건 안전한 패치가 아니라 패치가 아니다"), List.of());
        }

        List<String> reasons = new ArrayList<>();
        Set<String> rejected = new LinkedHashSet<>();
        Set<String> human = new LinkedHashSet<>();

        for (String raw : changedPaths) {
            String path = normalize(raw);
            if (path == null) {
                rejected.add(raw);
                reasons.add("경로가 저장소 밖으로 나가거나 절대 경로다: " + raw);
                continue;
            }
            String why = 금지된_이유(path);
            if (why != null) {
                rejected.add(raw);
                reasons.add(why + ": " + path);
                continue;
            }
            if (시작하나(path, 사람이_본다)) {
                human.add(raw);
                reasons.add("설정 파일이다. 한 줄로 검증을 끄거나 기능 플래그를 뒤집을 수 있어 사람이 본다: " + path);
                continue;
            }
            if (!시작하나(path, 허용)) {
                rejected.add(raw);
                reasons.add("화이트리스트 밖이다(" + String.join(", ", 허용) + "): " + path);
            }
        }

        if (!rejected.isEmpty()) {
            return PatchVerdict.reject(reasons, List.copyOf(rejected));
        }
        if (!human.isEmpty()) {
            return PatchVerdict.needsHuman(reasons, List.copyOf(human));
        }
        return PatchVerdict.allow(List.of("전부 화이트리스트 안이다: " + String.join(", ", 허용)));
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    private static String 금지된_이유(String path) {
        if (시작하나(path, 금지_접두어)) {
            if (path.startsWith("src/test/")) {
                return "🔴 채점표 그 자체다. LLM 이 여기를 고치면 테스트를 지워서 통과시킨다";
            }
            if (path.startsWith(".github/")) {
                return "🔴 CI 를 고쳐서 통과시킬 수 있다";
            }
            if (path.startsWith("recordings/")) {
                return "🔴 채점의 «입력»이다. 고치면 원하는 답을 만들 수 있다";
            }
            return "🔴 건드리면 안 되는 자리다";
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        for (String piece : 금지_이름조각) {
            if (fileName.contains(piece)) {
                return "🔴 빌드를 고쳐서 통과시킬 수 있다";
            }
        }
        if (path.startsWith(우리_모듈_접두어)) {
            return "🔴 Hindsight 자신의 모듈이다. 자기 채점기를 약화시킬 수 있다";
        }
        if (path.endsWith(".json") && path.contains("recording")) {
            return "🔴 기록 파일이다. 채점의 입력을 고치는 것이다";
        }
        return null;
    }

    private static boolean 시작하나(String path, List<String> prefixes) {
        return prefixes.stream().anyMatch(path::startsWith);
    }

    /**
     * 경로를 펴서 저장소 안의 상대 경로로 만든다. 나갈 수 있으면 {@code null}.
     *
     * <h2>🔴 여기가 이 클래스에서 가장 중요한 조각이다</h2>
     * {@code src/main/java/../../../.github/workflows/ci.yml} 은 <b>화이트리스트로 시작한다.</b>
     * 「앞이 맞는지」만 보면 통과하고, 그 패치는 CI 를 고쳐서 모든 검사를 통과시킨다.
     *
     * <p>🔴 {@link java.nio.file.Path#normalize()} 를 쓰지 않는 이유: 운영체제마다 구분자와
     * 규칙이 달라서, <b>윈도우에서 통과한 것이 리눅스에서 안 통과하거나 그 반대</b>가 될 수 있다.
     * 채점의 안전선이 운영체제에 따라 달라지면 안 되므로 직접 편다.
     */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String path = raw.replace('\\', '/').trim();

        // 🔴 절대 경로는 무조건 거절한다. 저장소 «안»의 경로만 다룬다.
        if (path.startsWith("/") || path.matches("^[A-Za-z]:/.*")) {
            return null;
        }
        // "~" 도 홈 디렉터리로 펴질 수 있는 자리다.
        if (path.startsWith("~")) {
            return null;
        }

        List<String> parts = new ArrayList<>();
        for (String piece : path.split("/")) {
            if (piece.isEmpty() || piece.equals(".")) {
                continue;
            }
            if (piece.equals("..")) {
                if (parts.isEmpty()) {
                    return null; // 저장소 밖으로 나간다
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(piece);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }
}
