package io.hindsight.core.brain;

import java.util.function.Function;

/**
 * LLM 을 부르는 데 필요한 설정. <b>전부 환경변수에서 온다.</b>
 *
 * <h2>🔴 키를 코드나 설정 파일에 적지 않는다</h2>
 * 적는 순간 저장소에 남고, 저장소에 남은 것은 지워도 이력에 남는다.
 *
 * <h2>🔴 「키가 없다」를 「진단할 게 없다」로 접지 않는다</h2>
 * 키가 없으면 {@link #키가_있나()} 가 거짓이고, 부르는 쪽은 <b>그 사실을 그대로 보고한다.</b>
 * 조용히 건너뛰면 「LLM 이 못 고쳤다」와 「LLM 을 안 불렀다」가 구별되지 않는다.
 *
 * <h2>⚠️ 가격은 «추측하지 않는다»</h2>
 * 토큰 수는 응답이 알려 주지만 <b>1토큰이 얼마인지는 모델마다 다르고 자주 바뀐다.</b>
 * 그래서 가격이 설정에 없으면 비용을 <b>「모른다」</b>로 둔다 —
 * 그러면 {@link AttemptBudget} 이 그 호출을 <b>상한만큼 쓴 것</b>으로 치고 멈춘다.
 * 🔴 모르는 비용을 0 으로 치면 상한이 없는 것과 같아진다.
 */
public record LlmConfig(
        String apiKey,
        String baseUrl,
        String model,
        int maxAttempts,
        long maxCents,
        Double usdPerMillionInputTokens,
        Double usdPerMillionOutputTokens
) {

    private static final String 기본_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/";
    private static final String 기본_MODEL = "gemini-2.5-flash";

    /** 환경변수에서 읽는다. @param env 이름 → 값. 없으면 {@code null} */
    public static LlmConfig from(Function<String, String> env) {
        return new LlmConfig(
                비어있으면_null(env.apply("LLM_API_KEY")),
                기본값(env.apply("LLM_BASE_URL"), 기본_BASE_URL),
                기본값(env.apply("LLM_MODEL"), 기본_MODEL),
                정수(env.apply("HINDSIGHT_LLM_MAX_ATTEMPTS"), AttemptBudget.기본_시도_횟수),
                달러를_센트로(env.apply("HINDSIGHT_LLM_MAX_USD"), AttemptBudget.기본_상한_센트),
                실수또는_모름(env.apply("HINDSIGHT_LLM_USD_PER_MTOK_IN")),
                실수또는_모름(env.apply("HINDSIGHT_LLM_USD_PER_MTOK_OUT")));
    }

    public static LlmConfig fromEnvironment() {
        return from(System::getenv);
    }

    /** 🔴 키가 없으면 부르지 않는다. 그리고 <b>그 사실을 보고한다.</b> */
    public boolean 키가_있나() {
        return apiKey != null;
    }

    /**
     * 🔴 가격을 <b>모르면 {@code null}</b>. 「공짜」가 아니다.
     *
     * @return 이 호출에 든 센트. 가격을 모르면 {@code null}
     */
    public Long 센트로_바꾼다(long 입력토큰, long 출력토큰) {
        if (usdPerMillionInputTokens == null || usdPerMillionOutputTokens == null) {
            return null;
        }
        double usd = 입력토큰 / 1_000_000.0 * usdPerMillionInputTokens
                + 출력토큰 / 1_000_000.0 * usdPerMillionOutputTokens;
        return Math.round(usd * 100);
    }

    /** 채팅 완성 주소. 끝의 {@code /} 가 있든 없든 같은 결과가 나온다. */
    public String chatCompletionsUrl() {
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return base + "chat/completions";
    }

    /** 사람이 읽는 한 줄. 🔴 <b>키는 절대 안 나온다.</b> */
    public String describe() {
        return "모델 %s · %s · 시도 %d회 · 상한 %d센트 · 가격 %s".formatted(
                model,
                키가_있나() ? "키 있음" : "🔴 키 없음",
                maxAttempts,
                maxCents,
                usdPerMillionInputTokens == null ? "🔴 모름(그래서 한 번만 부른다)" : "설정됨");
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    private static String 비어있으면_null(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String 기본값(String v, String 기본) {
        String 값 = 비어있으면_null(v);
        return 값 == null ? 기본 : 값;
    }

    private static int 정수(String v, int 기본) {
        String 값 = 비어있으면_null(v);
        if (값 == null) {
            return 기본;
        }
        try {
            return Integer.parseInt(값);
        } catch (NumberFormatException e) {
            // 🔴 못 읽은 값을 기본값으로 «조용히» 되돌리지 않는다. 설정을 한 사람은
            //    자기 값이 쓰이고 있다고 믿는다 — 데이터 계약 §9 가 정한 규율이다.
            throw new IllegalArgumentException("숫자가 아니다: " + 값);
        }
    }

    private static long 달러를_센트로(String v, long 기본센트) {
        String 값 = 비어있으면_null(v);
        if (값 == null) {
            return 기본센트;
        }
        try {
            return Math.round(Double.parseDouble(값) * 100);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("달러 값이 숫자가 아니다: " + 값);
        }
    }

    private static Double 실수또는_모름(String v) {
        String 값 = 비어있으면_null(v);
        if (값 == null) {
            return null; // 🔴 「모른다」. 0 이 아니다
        }
        try {
            return Double.parseDouble(값);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("가격이 숫자가 아니다: " + 값);
        }
    }
}
