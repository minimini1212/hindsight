package io.hindsight.core.guard;

import java.util.List;

/**
 * 패치를 적용해도 되는가에 대한 판정.
 *
 * <h2>🔴 왜 「된다/안 된다」 둘이 아닌가</h2>
 * 둘뿐이면 애매한 것을 어느 한쪽으로 밀어 넣게 된다. 그리고 그 방향은 <b>거의 항상
 * 「된다」 쪽</b>이다 — 막으면 도구가 아무것도 못 하는 것처럼 보이기 때문이다.
 *
 * <p>설정 파일 한 줄로 검증을 끄거나 기능 플래그를 뒤집을 수 있다. 그건 「막을 일」은
 * 아니지만 <b>사람이 봐야 하는 일</b>이다. 그 자리를 위해 가운데를 둔다.
 */
public record PatchVerdict(Level level, List<String> reasons, List<String> offendingPaths) {

    public PatchVerdict {
        reasons = List.copyOf(reasons);
        offendingPaths = List.copyOf(offendingPaths);
    }

    public enum Level {
        /** 화이트리스트 안이다. 나머지 조건이 맞으면 자동 PR 까지 갈 수 있다. */
        ALLOW,

        /**
         * 🔴 막지는 않지만 <b>자동으로 통과시키지도 않는다.</b> 사람이 본다.
         *
         * <p>설정 파일이 그렇다. {@code application.yml} 한 줄로 검증을 끄거나 기능 플래그를
         * 뒤집을 수 있어서, 「코드를 고쳤다」와 같은 무게로 볼 수 없다.
         */
        NEEDS_HUMAN,

        /** 🔴 건드리면 안 되는 자리다. 적용하지 않는다. */
        REJECT
    }

    public boolean allowsAutomatic() {
        return level == Level.ALLOW;
    }

    public static PatchVerdict allow(List<String> reasons) {
        return new PatchVerdict(Level.ALLOW, reasons, List.of());
    }

    public static PatchVerdict needsHuman(List<String> reasons, List<String> paths) {
        return new PatchVerdict(Level.NEEDS_HUMAN, reasons, paths);
    }

    public static PatchVerdict reject(List<String> reasons, List<String> paths) {
        return new PatchVerdict(Level.REJECT, reasons, paths);
    }

    /** 사람이 읽는 한 줄. PR 본문과 로그에 그대로 나간다. */
    public String summary() {
        StringBuilder line = new StringBuilder(level.name());
        if (!offendingPaths.isEmpty()) {
            line.append(" — ").append(String.join(", ", offendingPaths));
        }
        if (!reasons.isEmpty()) {
            line.append(" (").append(String.join(" / ", reasons)).append(')');
        }
        return line.toString();
    }
}
