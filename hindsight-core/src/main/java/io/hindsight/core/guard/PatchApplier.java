package io.hindsight.core.guard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🔴 <b>LLM 이 만든 패치가 디스크에 닿는 유일한 통로.</b>
 *
 * <h2>검사와 쓰기가 «한 함수 안»에 묶여 있다</h2>
 * 이 둘을 따로 두면 언젠가 누군가 편의상 {@code Files.write} 를 직접 부르고, 그날
 * <b>화이트리스트가 없는 것과 같아진다.</b> 그리고 아무 오류도 안 난다 — 패치는 잘 적용되고,
 * 그게 {@code src/test/} 였다는 것만 아무도 모른다.
 *
 * <pre>
 *   apply(패치)
 *      ├─ 1. 경로 판정        ← 🔴 «쓰기 전»에. 건너뛰는 경로가 없다
 *      ├─ 2. 실제 파일 확인    ← 심볼릭 링크로 밖을 가리키나
 *      └─ 3. 쓴다             ← 여기까지 와야 쓴다
 * </pre>
 *
 * <h2>🔴 모듈 경계로 막으려 했다가 접은 자리다</h2>
 * 한때 이걸 «모듈»로 뺐다. 「자기 모듈이면 우회하기 어렵다」는 이유였는데 <b>틀렸다</b> —
 * 모듈 경계는 파일 쓰기를 못 막는다. 같은 프로젝트의 다른 코드가 {@code java.nio.file.Files} 를
 * 직접 부르면 그만이고, Gradle 은 그걸 모른다. <b>그건 장치가 아니라 그림이었다.</b>
 *
 * <p>지금 실제로 막는 것은 <b>「적용 전에 경로를 확인한다」와 「테스트를 매번 새로 만든다」</b>
 * 두 줄이다. ⬜ 「다른 코드가 {@code java.nio} 를 직접 부르면 빌드 실패」는 아직 검사가 없다.
 */
public final class PatchApplier {

    private final Path repositoryRoot;
    private final PatchGuard guard;

    public PatchApplier(Path repositoryRoot) {
        this(repositoryRoot, new PatchGuard());
    }

    public PatchApplier(Path repositoryRoot, PatchGuard guard) {
        this.repositoryRoot = repositoryRoot;
        this.guard = guard;
    }

    /**
     * 적용 결과.
     *
     * @param verdict 경로 판정. 🔴 {@code NEEDS_HUMAN} 이면 <b>안 쓴다</b> —
     *                「사람이 본다」는 「일단 적용하고 알려 준다」가 아니다
     * @param written 실제로 쓴 파일
     */
    public record Result(PatchVerdict verdict, List<Path> written) {

        public Result {
            written = List.copyOf(written);
        }

        public boolean applied() {
            return !written.isEmpty();
        }
    }

    /**
     * 패치를 적용한다. <b>경로 검사가 이 안에서 «먼저» 일어난다.</b>
     *
     * @param newContents 경로 → 새 내용. 경로는 저장소 뿌리 기준 상대 경로다
     */
    /**
     * 🔴 <b>«쓰지 않고» 판정만 한다.</b>
     *
     * <p>부르는 쪽이 「지금은 아직 쓰면 안 되는데 경로는 미리 보고 싶다」일 때 쓴다 —
     * 네 겹 채점이 <b>㉠(패치 «전»에 실패하나)을 맨 처음</b> 재기 때문에, 그 전에
     * 파일을 쓰면 「패치 전」이 「패치 후」가 된다.
     *
     * <p>⚠️ {@link #apply} 와 <b>같은 판정</b>을 쓴다. 두 벌로 만들면 한쪽만 고쳐지고,
     * 그러면 「미리 볼 때는 통과했는데 쓸 때 거절되는」 자리가 생긴다.
     */
    public PatchVerdict judgeOnly(Map<String, String> newContents) {
        List<String> paths = new ArrayList<>(newContents.keySet());
        PatchVerdict verdict = guard.judge(paths);
        if (verdict.level() != PatchVerdict.Level.ALLOW) {
            return verdict;
        }
        PatchVerdict realPathVerdict = checkRealPaths(paths);
        return realPathVerdict != null ? realPathVerdict : verdict;
    }

    public Result apply(Map<String, String> newContents) {
        List<String> paths = new ArrayList<>(newContents.keySet());

        // 1. 🔴 경로 판정이 먼저다. 이 줄과 쓰기 사이에 다른 것이 끼면 안 된다.
        PatchVerdict verdict = guard.judge(paths);
        if (verdict.level() != PatchVerdict.Level.ALLOW) {
            // 🔴 NEEDS_HUMAN 도 «안 쓴다». 「사람이 본다」를 「일단 적용해 둔다」로 읽으면
            //    설정 파일을 고친 패치가 조용히 들어간다.
            return new Result(verdict, List.of());
        }

        // 2. 실제 파일을 본다 — 문자열 규칙으로는 알 수 없는 것.
        PatchVerdict realPathVerdict = checkRealPaths(paths);
        if (realPathVerdict != null) {
            return new Result(realPathVerdict, List.of());
        }

        // 3. 여기까지 와야 쓴다.
        List<Path> written = new ArrayList<>();
        Map<String, String> ordered = new LinkedHashMap<>(newContents);
        try {
            for (Map.Entry<String, String> entry : ordered.entrySet()) {
                Path target = repositoryRoot.resolve(PatchGuard.normalize(entry.getKey()));
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
                written.add(target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("패치를 쓰지 못했다", e);
        }
        return new Result(verdict, written);
    }

    /**
     * 심볼릭 링크로 저장소 밖을 가리키는 경로를 거절한다.
     *
     * <h2>🔴 문자열 규칙으로는 못 잡는 자리다</h2>
     * {@code src/main/java/x} 가 <b>저장소 밖을 가리키는 심볼릭 링크</b>면, 경로 문자열은
     * 완벽하게 화이트리스트 안이다. 그런데 쓰는 순간 밖에 쓴다.
     *
     * <p>⚠️ 파일이 «아직 없는» 경우가 정상이다(새 파일을 만드는 패치). 그때는 있는 데까지
     * 거슬러 올라가 확인한다 — 없는 파일의 실제 경로는 물어볼 수 없기 때문이다.
     */
    private PatchVerdict checkRealPaths(List<String> paths) {
        Path root;
        try {
            root = repositoryRoot.toRealPath();
        } catch (IOException e) {
            // 뿌리를 못 펴면 확인할 수 없다. 🔴 「확인 못 했다」를 「괜찮다」로 치지 않는다.
            return PatchVerdict.reject(
                    List.of("저장소 뿌리의 실제 경로를 확인하지 못했다: " + repositoryRoot), paths);
        }

        for (String raw : paths) {
            Path target = repositoryRoot.resolve(PatchGuard.normalize(raw));
            Path existing = target;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                continue;
            }
            try {
                if (!existing.toRealPath().startsWith(root)) {
                    return PatchVerdict.reject(
                            List.of("🔴 심볼릭 링크가 저장소 밖을 가리킨다: " + raw), List.of(raw));
                }
            } catch (IOException e) {
                return PatchVerdict.reject(
                        List.of("실제 경로를 확인하지 못했다: " + raw), List.of(raw));
            }
        }
        return null;
    }
}
