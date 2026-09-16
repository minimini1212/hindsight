package io.hindsight.core.brain;

import io.hindsight.core.privacy.Pseudonymizer;
import io.hindsight.model.Recording;

import java.util.Map;

/**
 * 진짜 LLM 을 부르는 구현. <b>OpenAI 호환 {@code chat/completions}</b> 한 벌만 쓴다.
 *
 * <h2>왜 OpenAI 호환인가</h2>
 * Gemini(Google AI Studio)도 OpenAI 와 «같은 모양»의 주소를 제공한다. 그래서 주소 하나만
 * 바꾸면 모델을 갈아탈 수 있다 — 붙이는 코드가 한 벌이면 <b>갈아탈 때 시험이 그대로 산다.</b>
 *
 * <h2>🔴 가명화와 전송이 «한 함수 안»에 묶여 있다</h2>
 * 규율이 그렇게 정해 뒀다: <i>「기록은 LLM 에 보내기 전에 가명화된다. 두 단계를 한 함수에
 * 묶어 나중 편집이 순서를 뒤집지 못하게 한다」</i>.
 * 여기서 {@link #진단한다}가 <b>받는 것은 날것의 기록</b>이고, 가명화는 이 안에서 일어난다.
 * <b>부르는 쪽에는 「가명화해서 넘겨라」를 맡기지 않는다</b> — 맡기면 언젠가 한 곳에서 빠뜨린다.
 *
 * <h2>🔴 네트워크를 직접 안 쓴다 — {@link 전송} 으로 받는다</h2>
 * 그래야 「답이 이상할 때 우리가 어떻게 다루나」를 <b>키도 돈도 없이 전수로</b> 시험한다.
 * 그리고 이 프로젝트에서 정말 시험해야 하는 것은 그쪽이다.
 */
public final class HttpDiagnosis implements Diagnosis {

    /** HTTP 한 번. 🔴 <b>예외를 던지지 않는다</b> — 실패도 결과이고, 그 사실이 보고에 남아야 한다. */
    public interface 전송 {
        응답 보낸다(String url, Map<String, String> headers, String body);

        record 응답(int status, String body, String 오류) {
            public boolean ok() {
                return 오류 == null && status >= 200 && status < 300;
            }
        }
    }

    private final LlmConfig config;
    private final 전송 전송기;
    private final Pseudonymizer 가명화기;

    public HttpDiagnosis(LlmConfig config, 전송 전송기) {
        this.config = config;
        this.전송기 = 전송기;
        // 🔴 가명화 키가 없으면 Pseudonymizer 가 «대상 필드를 버린다». 원본을 그대로 쓰지 않는다.
        this.가명화기 = new Pseudonymizer(System.getenv("HINDSIGHT_PSEUDONYM_KEY"));
    }

    HttpDiagnosis(LlmConfig config, 전송 전송기, Pseudonymizer 가명화기) {
        this.config = config;
        this.전송기 = 전송기;
        this.가명화기 = 가명화기;
    }

    @Override
    public 결과 진단한다(Recording recording, 소스맥락 소스맥락) {
        if (recording == null) {
            return 결과.못불렀다("기록이 없다");
        }
        if (!config.키가_있나()) {
            // 🔴 조용히 건너뛰지 않는다. 「키가 없다」와 「고칠 게 없다」는 다른 사실이다.
            return 결과.못불렀다("LLM_API_KEY 가 없다. 🔴 .env 에 사람이 넣는다 — Claude 는 안 쓴다");
        }

        // 🔴 여기가 그 한 줄이다. 아래 줄과 «절대» 떨어지면 안 된다.
        Recording 가명화된것 = 가명화기.apply(recording);
        // 로컬 이름이 중첩 클래스 이름을 가리면 안 된다 — 그래서 «보낼 글»이라고 부른다.
        String 줄 = System.lineSeparator() + System.lineSeparator();
        String 보낼글 = 프롬프트.규칙 + 줄
                + 프롬프트.소스를_적는다(소스맥락) + 줄
                + 프롬프트.기록을_적는다(가명화된것);

        전송.응답 응답 = 전송기.보낸다(
                config.chatCompletionsUrl(),
                Map.of("Authorization", "Bearer " + config.apiKey(),
                        "Content-Type", "application/json"),
                요청본문(보낼글));

        if (!응답.ok()) {
            // ⚠️ 못 보냈으면 «돈을 안 썼다». 예산을 깎지 않는다.
            return 결과.못불렀다("LLM 호출이 실패했다: "
                    + (응답.오류() != null ? 응답.오류() : "HTTP " + 응답.status() + " " + 짧게(응답.body())));
        }

        long 입력토큰 = 숫자를_꺼낸다(응답.body(), "prompt_tokens");
        long 출력토큰 = 숫자를_꺼낸다(응답.body(), "completion_tokens");
        Long 든센트 = (입력토큰 < 0 || 출력토큰 < 0) ? null : config.센트로_바꾼다(입력토큰, 출력토큰);

        String 답 = 내용을_꺼낸다(응답.body());
        if (답 == null) {
            return 결과.답이_쓸모없다("응답에서 내용을 못 꺼냈다. 주소나 모델 이름이 맞는지 본다",
                    입력토큰, 출력토큰, 든센트);
        }

        Map<String, String> 패치 = 응답읽기.패치를_꺼낸다(답);
        String 원인 = 응답읽기.원인을_꺼낸다(답);

        if (패치.isEmpty()) {
            // 🔴 「패치를 안 줬다」를 「고칠 게 없었다」로 넘기지 않는다.
            return new 결과(true, 원인, Map.of(), 입력토큰, 출력토큰, 든센트,
                    "패치 블록을 «하나도» 못 찾았다. 형식(```path:…)이 안 맞았다");
        }

        var 수상한것 = 응답읽기.수상한_경로(패치);
        String 왜 = 수상한것.isEmpty() ? null
                : "🔴 화이트리스트 밖 경로가 섞여 있다: " + String.join(", ", 수상한것)
                        + " — 적용은 guard 가 막는다";
        return new 결과(true, 원인, 패치, 입력토큰, 출력토큰, 든센트, 왜);
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    String 요청본문(String 프롬프트내용) {
        return "{\"model\":" + 따옴표(config.model())
                + ",\"messages\":[{\"role\":\"user\",\"content\":" + 따옴표(프롬프트내용) + "}]}";
    }

    /**
     * 🔴 JSON 을 손으로 «읽는다». 라이브러리를 안 쓰는 게 아니라, 이 경로에서는
     * <b>모양이 예상과 다를 때 조용히 null 이 되는 것</b>이 더 위험해서 직접 본다.
     * 못 찾으면 {@code null} 이고, 부르는 쪽이 그걸 「답이 쓸모없다」로 보고한다.
     */
    static String 내용을_꺼낸다(String json) {
        if (json == null) {
            return null;
        }
        int i = json.indexOf("\"content\"");
        if (i < 0) {
            return null;
        }
        int q = json.indexOf('"', json.indexOf(':', i) + 1);
        if (q < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int k = q + 1; k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '\\' && k + 1 < json.length()) {
                char n = json.charAt(++k);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (k + 4 < json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16));
                            k += 4;
                        }
                    }
                    default -> sb.append(n);
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null; // 🔴 닫는 따옴표를 못 만났다 = 잘린 응답이다. 반쪽을 «답»으로 쓰지 않는다
    }

    /** @return 못 찾으면 {@code -1} (모른다). 🔴 0 이 아니다 */
    static long 숫자를_꺼낸다(String json, String 이름) {
        if (json == null) {
            return -1;
        }
        var m = java.util.regex.Pattern.compile("\"" + 이름 + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** JSON 문자열 조각. 🔴 제어문자까지 이스케이프한다 — 기록 본문에 무엇이든 들어올 수 있다. */
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

    private static String 짧게(String s) {
        if (s == null) {
            return "(본문 없음)";
        }
        String 한줄 = s.replace('\n', ' ').trim();
        return 한줄.length() <= 200 ? 한줄 : 한줄.substring(0, 200) + "…";
    }
}
