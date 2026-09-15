package io.hindsight.core.replay;

import io.hindsight.model.Event;
import io.hindsight.model.Recording;
import io.hindsight.model.SqlShapes;
import io.hindsight.model.Trigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 기록 하나를 <b>실패하는 JUnit 테스트</b>로 바꾼다.
 *
 * <h2>🔴 이 테스트가 «실패»해야 값이 있다</h2>
 * 패치 전 코드에서 통과하는 테스트는 아무것도 증명하지 않는다. 그런 테스트로 「고쳤다」를
 * 선언하면, 도구는 <b>아무것도 안 고친 패치로 PR 을 올린다.</b> 그래서 생성물의 목표는
 * 「통과하는 테스트」가 아니라 <b>「지금은 실패하고, 고치면 통과하는 테스트」</b>다.
 *
 * <h2>🔴 기록에 없는 것은 단언하지 «않는다»</h2>
 * 가장 쉬운 실수는 모든 자리를 다 단언하는 것이다. 그러면 기록이 못 잡은 자리 때문에
 * <b>버그와 무관하게 실패하는 테스트</b>가 되고, 채점기는 그 실패를 「패치가 못 고쳤다」로 읽는다.
 * 🔴 <b>아무 잘못 없는 패치가 벌을 받는다.</b>
 *
 * <p>그래서 단언은 <b>기록이 실제로 들고 있는 값에만</b> 붙이고, 못 붙인 자리는
 * {@link GeneratedTest#notAsserted()} 에 이름으로 남긴다 — 조용히 빼지 않는다.
 *
 * <h2>무엇을 만들어 내나</h2>
 * <pre>
 *   ① 되돌린다        기록 시점 상태로 (스냅숏)
 *   ② 다시 보낸다     기록된 요청 그대로
 *   ③ 단언한다        기록이 들고 있는 것만
 * </pre>
 */
public final class ReplayTestGenerator {

    private ReplayTestGenerator() {}

    /**
     * @param baseClassName 이 앱이 제공하는 «구체» 기반 클래스의 이름.
     *                      {@link ReplayTestBase} 를 상속해 되돌리기와 요청 보내기를 채운 것이다.
     *                      🔴 <b>생성기는 그 이름을 알 수 없으므로 받아야 한다</b> — 되돌리는 방법도
     *                      요청을 보내는 방법도 앱마다 다르다. 추상 클래스를 그대로 상속하게 만들면
     *                      <b>생성된 소스가 컴파일되지 않는다</b>(추상 메서드가 안 채워진다).
     */
    /**
     * 시험용 — 기반 클래스를 {@link ReplayTestBase} 로 두고 만든다.
     *
     * <p>🔴 <b>이걸로 만든 소스는 그대로는 컴파일되지 않는다</b>(추상 메서드가 안 채워진다).
     * 「무엇을 단언하는가」만 볼 때 쓴다. 진짜로 돌릴 소스는 앱의 «구체» 기반 클래스를 줘야 한다.
     */
    static GeneratedTest generateForShapeOnly(Recording recording) {
        return generate(recording, ReplayTestBase.class.getName());
    }

    public static GeneratedTest generate(Recording recording, String baseClassName) {
        if (baseClassName == null || baseClassName.isBlank()) {
            // 🔴 「알아서 되겠지」로 기본값을 넣지 않는다. 그러면 컴파일 안 되는 소스가
            //    말없이 만들어지고, 그걸 PR 에 붙이게 된다.
            throw new IllegalArgumentException(
                    "생성된 테스트가 상속할 기반 클래스 이름이 필요하다. "
                            + "io.hindsight.core.replay.ReplayTestBase 를 상속해 «되돌리기»와 «요청 보내기»를 "
                            + "채운 클래스를 앱이 제공해야 한다.");
        }
        Event.HttpIn entryPoint = Oracle.entryPointOf(recording);
        List<String> oracles = new ArrayList<>();
        List<String> notAsserted = new ArrayList<>();

        String className = classNameFor(recording, entryPoint);
        StringBuilder body = new StringBuilder();

        if (entryPoint == null) {
            // 🔴 진입점이 없으면 「무엇을 다시 보낼지」가 없다. 빈 테스트를 만들어
            //    통과시키느니, 만들 수 없다고 말하는 테스트를 만든다.
            body.append("        org.junit.jupiter.api.Assertions.fail(\n")
                    .append("                \"이 기록에는 들어온 요청이 없어서 재생할 수 없다. \"\n")
                    .append("                        + \"기록이 잘못된 것이지 코드가 잘못된 것이 아니다.\");\n");
            notAsserted.add("전부 — 기록에 들어온 요청이 없다");
            return new GeneratedTest(className, className + ".java",
                    render(className, recording, entryPoint, body.toString(), oracles, notAsserted, baseClassName),
                    oracles, notAsserted);
        }

        // ── ① 되돌리기 ──────────────────────────────────────────────────────
        body.append("        // ① 기록 시점 상태로 되돌린다.\n");
        body.append("        //    🔴 되돌리지 않으면 같은 요청이 다른 답을 낸다 — 그 차이는\n");
        body.append("        //       패치가 만든 것이 아니라 우리가 만든 것이다.\n");
        body.append("        var 되돌린범위 = 되돌린다();\n");
        body.append("        assertThat(되돌린범위.restoredEnoughToGrade())\n");
        body.append("                .as(\"되돌리기가 모자라면 이 테스트의 실패는 패치 탓이 아니다\")\n");
        body.append("                .isTrue();\n\n");
        oracles.add("재생 전에 기록 시점 상태로 되돌렸다 (행 + 「다음 id 는 몇 번」)");

        // ── ② 다시 보내기 ──────────────────────────────────────────────────
        body.append("        // ② 기록된 요청을 그대로 다시 보낸다.\n");
        body.append("        var 관찰 = 재생한다(\n");
        body.append("                \"").append(escape(entryPoint.method())).append("\",\n");
        body.append("                \"").append(escape(entryPoint.path())).append("\",\n");
        if (entryPoint.body() == null) {
            body.append("                null);   // 🔴 기록에 요청 본문이 «없다»(안 잡혔다). 빈 문자열로 바꾸지 않는다\n\n");
            notAsserted.add("요청 본문 — 기록에 없다(앱이 본문을 안 읽었거나 못 잡았다)");
        } else {
            body.append("                \"").append(escape(entryPoint.body())).append("\");\n\n");
        }

        // ── ③ 단언 ─────────────────────────────────────────────────────────
        body.append("        // ③ 기록이 «실제로 들고 있는 것»만 단언한다.\n");

        appendExceptionAssertion(recording, body, oracles, notAsserted);
        appendResponseAssertions(entryPoint, body, oracles, notAsserted);
        appendRepeatAssertion(recording, body, oracles, notAsserted);

        return new GeneratedTest(className, className + ".java",
                render(className, recording, entryPoint, body.toString(), oracles, notAsserted, baseClassName),
                oracles, notAsserted);
    }

    // ── 단언 조각 ───────────────────────────────────────────────────────────

    private static void appendExceptionAssertion(Recording recording, StringBuilder body,
                                                 List<String> oracles, List<String> notAsserted) {
        Trigger trigger = recording.trigger();
        if (trigger == null || trigger.exception() == null) {
            return;
        }
        String type = trigger.exception().type();
        body.append("        assertThat(관찰.escapedTypeOrNull())\n");
        body.append("                .as(\"기록된 예외가 다시 나오면 안 고쳐진 것이다\")\n");
        body.append("                .isNotEqualTo(\"").append(escape(type)).append("\");\n");
        oracles.add("기록된 예외(" + type + ")가 진입점 밖으로 다시 나오지 않는다");
    }

    private static void appendResponseAssertions(Event.HttpIn entryPoint, StringBuilder body,
                                                 List<String> oracles, List<String> notAsserted) {
        if (entryPoint.responseStatus() == null) {
            notAsserted.add("응답 상태 — 기록에 없다");
        } else {
            body.append("        assertThat(관찰.status()).isEqualTo(")
                    .append(entryPoint.responseStatus()).append(");\n");
            oracles.add("응답 상태가 " + entryPoint.responseStatus() + " 이다");
        }

        if (entryPoint.responseBody() == null) {
            // 🔴 기록에 본문이 없다. 「빈 본문이어야 한다」로 바꾸면 버그와 무관하게 실패한다.
            notAsserted.add("응답 본문 — 기록에 없다(안 잡혔다)");
        } else if (entryPoint.responseBodyTruncated()) {
            // 🔴 잘린 것끼리 견주면 「앞부분만 같으면 통과」가 된다. 그건 판정이 아니다.
            notAsserted.add("응답 본문 — 기록에서 «잘려» 있다. 잘린 것끼리 견주는 것은 판정이 아니다");
        } else {
            body.append("        assertThat(관찰.body())\n");
            body.append("                .as(\"상태만 보면 예외를 삼키고 200 을 주는 패치가 통과한다\")\n");
            body.append("                .isEqualTo(\"").append(escape(entryPoint.responseBody())).append("\");\n");
            oracles.add("응답 본문이 기록과 글자까지 같다");
        }
    }

    private static void appendRepeatAssertion(Recording recording, StringBuilder body,
                                              List<String> oracles, List<String> notAsserted) {
        List<Event.Sql> ofRequest = Oracle.queriesOfRequest(recording);
        if (ofRequest == null) {
            notAsserted.add("질의 반복 — 어느 질의가 이 요청 것인지 «가려낼 수 없다»(상관 식별자가 없다)");
            return;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Event.Sql sql : ofRequest) {
            SqlShapes.Shape shape = SqlShapes.of(sql.sql());
            counts.merge(shape.hash(), 1, Integer::sum);
            normalized.putIfAbsent(shape.hash(), shape.normalized());
        }
        int worst = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (worst < 2) {
            notAsserted.add("질의 반복 — 기록의 최대 반복이 " + worst + "번이라 반복 결함이 «없다»");
            return;
        }
        String worstShape = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");

        body.append("\n        // 🔴 «이상»이면 실패다. 「기록보다 더 나빠지면」으로 두면\n");
        body.append("        //    버그가 그대로인 실행이 통과해서, 이 테스트가 «패치 전에 실패»하지 않는다.\n");
        body.append("        assertThat(관찰.worstQueryRepeat())\n");
        body.append("                .as(\"같은 모양의 질의가 ").append(worst)
                .append("번 반복되던 것이 그대로면 안 고쳐진 것이다: ")
                .append(escape(normalized.get(worstShape))).append("\")\n");
        body.append("                .isLessThan(").append(worst).append(");\n");
        oracles.add("같은 모양의 질의 반복이 " + worst + "번보다 줄었다 (" + normalized.get(worstShape) + ")");
    }

    // ── 껍데기 ──────────────────────────────────────────────────────────────

    private static String render(String className, Recording recording, Event.HttpIn entryPoint,
                                 String body, List<String> oracles, List<String> notAsserted,
                                 String baseClassName) {
        StringBuilder source = new StringBuilder();
        source.append("""
                // 🔴 이 파일은 «기록에서 만들어진» 것이고, 채점할 때마다 새로 만들어진다.
                //    고쳐도 다음 채점에 반영되지 않는다 — 디스크에 있는 것을 믿지 않기 때문이다.
                //    고쳐야 할 것이 있으면 기록이나 생성기를 고친다.
                """);
        source.append("//\n");
        source.append("// 기록: ").append(recording.id())
                .append("  (").append(recording.capturedAt()).append(")\n");
        if (recording.trigger() != null) {
            source.append("// 방아쇠: ").append(recording.trigger().kind())
                    .append("  진입점: ").append(recording.trigger().entryPoint()).append('\n');
        }
        source.append("//\n// 이 테스트가 검사하는 것:\n");
        oracles.forEach(line -> source.append("//   ✅ ").append(line).append('\n'));
        if (!notAsserted.isEmpty()) {
            source.append("//\n// 🔴 검사하지 «못한» 것 — 기록에 없어서 단언할 수 없었다:\n");
            notAsserted.forEach(line -> source.append("//   ⬜ ").append(line).append('\n'));
            source.append("//   (여기를 단언하면 버그와 무관하게 실패하고, 채점기가 그걸\n");
            source.append("//    「패치가 못 고쳤다」로 읽는다)\n");
        }
        source.append("\nimport org.junit.jupiter.api.DisplayName;\n");
        source.append("import org.junit.jupiter.api.Test;\n\n");
        source.append("import static org.assertj.core.api.Assertions.assertThat;\n\n");
        source.append("class ").append(className)
                .append(" extends ").append(baseClassName).append(" {\n\n");
        source.append("    @Test\n");
        source.append("    @DisplayName(\"").append(escape(displayNameFor(recording, entryPoint))).append("\")\n");
        source.append("    void 재생() {\n");
        source.append(body);
        source.append("    }\n");
        source.append("}\n");
        return source.toString();
    }

    private static String displayNameFor(Recording recording, Event.HttpIn entryPoint) {
        String where = entryPoint == null ? "기록" : entryPoint.method() + " " + entryPoint.path();
        Trigger trigger = recording.trigger();
        if (trigger != null && trigger.exception() != null) {
            return where + " 가 " + simpleName(trigger.exception().type()) + " 로 죽지 않는다";
        }
        return where + " 가 기록 때와 같은 답을 내고 질의를 덜 낸다";
    }

    /**
     * 클래스 이름. 🔴 <b>같은 기록이면 같은 이름</b>이 나와야 한다 — 채점할 때마다 이름이 바뀌면
     * 사람이 「전에 본 그 테스트」인지 알 수 없고, 되돌리기 검사도 짝을 못 맞춘다.
     */
    private static String classNameFor(Recording recording, Event.HttpIn entryPoint) {
        StringBuilder name = new StringBuilder();
        if (entryPoint != null) {
            name.append(capitalize(entryPoint.method().toLowerCase()));
            for (String part : entryPoint.path().split("[^A-Za-z0-9]+")) {
                if (!part.isBlank()) {
                    name.append(capitalize(part));
                }
            }
        }
        if (name.isEmpty()) {
            name.append("Recording");
        }
        name.append("Replay").append(sanitize(recording.id())).append("Test");
        return name.toString();
    }

    private static String simpleName(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String sanitize(String text) {
        return text == null ? "" : text.replaceAll("[^A-Za-z0-9]", "");
    }

    /**
     * 자바 문자열 리터럴로 안전하게 만든다.
     *
     * <p>🔴 이걸 빠뜨리면 본문에 든 따옴표 하나로 <b>생성된 소스가 컴파일되지 않는다.</b>
     * 그리고 JSON 본문에는 따옴표가 반드시 들어 있다 — 즉 «거의 항상» 깨진다.
     * 줄바꿈도 마찬가지다.
     */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    // 제어 문자는 그대로 두면 소스가 깨진다. 유니코드 이스케이프로 바꾼다.
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
