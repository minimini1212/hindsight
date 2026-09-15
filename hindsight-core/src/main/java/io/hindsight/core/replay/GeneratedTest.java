package io.hindsight.core.replay;

import java.util.List;

/**
 * 기록 하나에서 만들어 낸 JUnit 테스트 한 벌.
 *
 * <h2>🔴 이건 «디스크에서 읽어 오는 것»이 아니라 매번 새로 만드는 것이다</h2>
 * 재생 테스트를 파일로 두고 그걸 돌리면, LLM 이 그 파일을 고쳐서 통과시킬 수 있다.
 * *"테스트를 통과시켜라"* 라고 하면 <b>테스트를 지워서 통과시킨다</b> — 이건 가정이 아니라
 * LLM 코딩 도구에서 관찰된 행동이다.
 *
 * <p>그래서 채점에 쓰는 테스트는 <b>매번 기록에서 새로 만든다.</b> 디스크에 있는 것을 믿지 않는다.
 * 여기서 만든 소스는 <b>사람이 읽고 PR 에 붙이는 용도</b>이고, 채점 자체는 이 소스를 거치지 않는다
 * ({@link ReplayResult} 가 제자리에서 한다).
 *
 * @param className 클래스 이름 (패키지 없음)
 * @param fileName  저장한다면 쓸 파일 이름
 * @param source    자바 소스 전문
 * @param oracles   이 테스트가 «무엇을 검사하는지» 사람이 읽는 말로. 🔴 PR 본문에 그대로 나간다
 * @param notAsserted 🔴 <b>검사하지 «못한» 것.</b> 기록에 없어서 단언할 수 없었던 자리다
 */
public record GeneratedTest(
        String className,
        String fileName,
        String source,
        List<String> oracles,
        List<String> notAsserted
) {

    public GeneratedTest {
        oracles = List.copyOf(oracles);
        notAsserted = List.copyOf(notAsserted);
    }

    /**
     * PR 본문에 붙일 요약.
     *
     * <p>🔴 <b>「검사하지 못한 것」을 «항상» 같이 적는다.</b> 오라클 목록만 보여 주면
     * 읽는 사람은 그게 전부라고 믿는다 — 그리고 안 적힌 것은 「확인됐다」로 읽힌다.
     */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("생성된 테스트: ").append(className).append('\n');
        text.append("이 테스트가 검사하는 것:\n");
        oracles.forEach(line -> text.append("  ✅ ").append(line).append('\n'));
        if (notAsserted.isEmpty()) {
            text.append("검사하지 «못한» 것: 없다\n");
        } else {
            text.append("🔴 검사하지 «못한» 것 (기록에 없어서 단언할 수 없었다):\n");
            notAsserted.forEach(line -> text.append("  ⬜ ").append(line).append('\n'));
        }
        return text.toString();
    }
}
