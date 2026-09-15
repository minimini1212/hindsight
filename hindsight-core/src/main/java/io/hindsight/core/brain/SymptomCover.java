package io.hindsight.core.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「증상만 덮는」 수정의 전형을 찾아낸다.
 *
 * <h2>🔴 네 겹을 다 통과하고도 나쁜 패치가 있다</h2>
 * 예외를 넓게 잡아 삼키면 <b>테스트는 통과한다.</b> 재시도를 붙이거나 타임아웃을 늘려도
 * 통과한다. 그런데 그건 <b>고친 것이 아니라 안 보이게 한 것</b>이다.
 *
 * <p>채점으로는 이 둘을 가를 수 없다 — 통과는 통과다. 그래서 <b>패치의 «모양»을 본다.</b>
 * 🔴 다만 이건 <b>거절하는 근거가 아니라 「사람이 본다」로 내리는 근거</b>다.
 * 넓은 {@code catch} 가 정답인 경우도 실제로 있고, 그걸 자동으로 막으면
 * <b>옳은 패치를 벌주게 된다</b> — 이 프로젝트가 이미 한 번 저지른 실수다.
 *
 * <h2>⚠️ 이 검사는 «문자열»로 본다. 그 한계를 알고 쓴다</h2>
 * 자바 소스를 제대로 파싱하지 않으므로, 주석이나 문자열 안의 단어에도 걸린다.
 * 🔴 <b>그래서 거절이 아니라 「사람이 본다」인 것이다</b> — 거짓 양성이 사람의 시간을
 * 조금 쓰는 것과, 거짓 음성이 나쁜 패치를 자동 통과시키는 것은 값이 다르다.
 */
public final class SymptomCover {

    private SymptomCover() {}

    /** 찾은 것 하나. */
    public record Finding(String path, String pattern, String why) {}

    private record Rule(String pattern, String why) {}

    private static final List<Rule> 규칙 = List.of(
            new Rule("@ExceptionHandler",
                    "예외 처리기를 새로 붙이면 예외가 «안 나오게» 된다 — 원인이 남아 있어도 테스트는 통과한다"),
            new Rule("catch (Exception",
                    "넓게 잡아 삼키면 무엇이 잘못됐는지 사라진다"),
            new Rule("catch (Throwable",
                    "🔴 Throwable 은 OutOfMemoryError 까지 잡는다. 삼킬 수 있는 것이 아니다"),
            new Rule("@Retryable",
                    "재시도는 실패를 «덜 자주» 보이게 할 뿐 원인을 안 고친다"),
            new Rule("setTimeout",
                    "타임아웃을 늘리는 것은 느린 원인을 그대로 두는 것이다"),
            new Rule("@Transactional(timeout",
                    "타임아웃을 늘리는 것은 느린 원인을 그대로 두는 것이다")
    );

    /**
     * 패치에서 「증상만 덮는」 모양을 찾는다.
     *
     * @param newContents 경로 → 패치 «후» 내용
     */
    public static List<Finding> scan(Map<String, String> newContents) {
        List<Finding> findings = new ArrayList<>();
        if (newContents == null) {
            return findings;
        }
        newContents.forEach((path, content) -> {
            if (content == null) {
                return;
            }
            for (Rule rule : 규칙) {
                if (content.contains(rule.pattern())) {
                    findings.add(new Finding(path, rule.pattern(), rule.why()));
                }
            }
        });
        return List.copyOf(findings);
    }

    /**
     * 🔴 <b>패치가 «새로» 넣은 것만</b> 본다.
     *
     * <p>원래 있던 {@code catch (Exception e)} 까지 세면, <b>그 파일을 건드리는 모든 패치가
     * 영원히 「사람이 본다」가 된다.</b> 그러면 이 신호가 아무 말도 안 하게 된다 —
     * 늘 켜져 있는 경고등은 꺼져 있는 것과 같다.
     *
     * @param before 경로 → 패치 «전» 내용. 없는 경로는 새 파일로 본다
     */
    public static List<Finding> scanAdded(Map<String, String> before, Map<String, String> after) {
        List<Finding> findings = new ArrayList<>();
        if (after == null) {
            return findings;
        }
        after.forEach((path, newContent) -> {
            if (newContent == null) {
                return;
            }
            String oldContent = before == null ? null : before.get(path);
            for (Rule rule : 규칙) {
                int 새것 = count(newContent, rule.pattern());
                int 옛것 = oldContent == null ? 0 : count(oldContent, rule.pattern());
                if (새것 > 옛것) {
                    findings.add(new Finding(path, rule.pattern(), rule.why()));
                }
            }
        });
        return List.copyOf(findings);
    }

    private static int count(String text, String pattern) {
        int total = 0;
        int at = text.indexOf(pattern);
        while (at >= 0) {
            total++;
            at = text.indexOf(pattern, at + pattern.length());
        }
        return total;
    }
}
