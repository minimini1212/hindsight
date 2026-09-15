package io.hindsight.core.privacy;

import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Recording;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * 기록에서 사람을 가리키는 값을 바꾼다.
 *
 * <h2>🔴 이 클래스가 한 개인 것이 요점이다</h2>
 * 가명화를 부르는 자리를 여럿 두면 언젠가 한 자리가 빠진다. 그리고 그 한 자리가
 * <b>진짜 사용자 데이터가 디스크에 남는 순간</b>이다. 빠진 것을 알아채는 방법도 없다 —
 * 파일은 정상으로 보인다. 그래서 부르는 자리는 {@code RecordingStore.write} 하나뿐이고,
 * 거기서 쓰기와 <b>같은 함수 안에</b> 묶여 있다. 순서를 뒤집으려면 그 함수를 고쳐야 한다.
 *
 * <h2>세 갈래로 나눈다</h2>
 * <table>
 *   <tr><th>분류</th><th>대상</th><th>처리</th></tr>
 *   <tr><td><b>버린다</b></td>
 *       <td>{@code Authorization}·{@code Cookie}·{@code Set-Cookie} 헤더,
 *           이름에 password·secret·token 이 든 것, 카드번호·주민등록번호 모양</td>
 *       <td>값을 {@code [dropped]} 로 바꾼다</td></tr>
 *   <tr><td><b>가명화</b></td>
 *       <td>이메일·전화번호, {@code hostname}</td>
 *       <td>{@code HMAC-SHA256(키, 원본)} 으로 만든 «형태를 지킨» 가짜 값</td></tr>
 *   <tr><td><b>그대로</b></td><td>나머지</td><td>—</td></tr>
 * </table>
 *
 * <h2>🔴 왜 토큰은 가명화가 아니라 «버리기»인가</h2>
 * 가명화한 값은 되돌릴 수 없지만 <b>같은 원본이면 늘 같은 가짜 값</b>이다. 그게 이메일에는
 * 필요한 성질이다(같은 사람이 두 번 나온 걸 알아야 버그가 보인다). 그런데 인증 토큰은
 * 그 성질이 <b>아무 값도 안 하면서</b> 「이 토큰을 쓴 요청들」을 이어 준다.
 * 재생에도 안 쓰인다. 값이 없는 위험은 들고 있지 않는다.
 *
 * <h2>🔴 키가 없으면 가명화 대상을 «버린다»</h2>
 * 키가 없을 때 원본을 그대로 두는 것이 가장 나쁜 선택이다. 그 다음으로 나쁜 것이
 * 고정된 가짜 키로 가명화하는 것이다 — <b>가명화한 것처럼 보이지만 아무나 되돌릴 수 있다.</b>
 * 그래서 키가 없으면 그냥 버린다. 기록은 덜 쓸모 있어지고, 그건 알아챌 수 있는 손해다.
 *
 * <h2>가명정보는 익명정보가 아니다</h2>
 * 여기를 통과한 파일도 <b>여전히 조심해서 다룬다.</b> 형태와 흐름이 남아 있고,
 * 다른 자료와 맞춰 보면 사람이 특정될 수 있다. 저장소에 올리지 않는다.
 */
public final class Pseudonymizer {

    private static final String DROPPED = "[dropped]";

    /** 값째로 버리는 헤더. 소문자로 비교한다. */
    private static final List<String> DROPPED_HEADERS =
            List.of("authorization", "cookie", "set-cookie", "proxy-authorization");

    /** 이름에 이게 들어 있으면 값을 버린다. */
    private static final List<String> SECRET_NAME_PARTS =
            List.of("password", "passwd", "secret", "token", "apikey", "api-key", "credential");

    private static final Pattern EMAIL =
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");

    /** 010-1234-5678 · 01012345678 · 010 1234 5678 */
    private static final Pattern PHONE_KR =
            Pattern.compile("\\b01[0-9][- ]?\\d{3,4}[- ]?\\d{4}\\b");

    /** 주민등록번호 모양. 🔴 가명화가 아니라 «버린다». */
    private static final Pattern RRN_KR =
            Pattern.compile("\\b\\d{6}[- ]?[1-4]\\d{6}\\b");

    /** 카드번호 모양(13~16자리). 🔴 가명화가 아니라 «버린다». */
    private static final Pattern CARD =
            Pattern.compile("\\b(?:\\d[ -]?){13,16}\\b");

    private final String key;

    /**
     * @param key 가명화 키. {@code null} 이면 가명화 대상을 «버린다».
     *            🔴 빈 문자열을 키로 받지 않는다 — 「키가 없다」와 「키가 빈 문자열이다」를
     *            같게 다루면, 설정을 빠뜨린 것이 설정을 한 것처럼 보인다.
     */
    public Pseudonymizer(String key) {
        this.key = (key == null || key.isBlank()) ? null : key;
    }

    public boolean hasKey() {
        return key != null;
    }

    // ── 기록 전체 ───────────────────────────────────────────────────────────

    public Recording apply(Recording recording) {
        if (recording == null) {
            return null;
        }
        List<Event> events = null;
        if (recording.events() != null) {
            events = new ArrayList<>(recording.events().size());
            for (Event event : recording.events()) {
                events.add(applyToEvent(event));
            }
            events = List.copyOf(events);
        }
        return new Recording(
                recording.schemaVersion(),
                recording.id(),
                recording.capturedAt(),
                recording.trigger(),
                applyToApp(recording.app()),
                events,
                recording.summary(),
                recording.jfr(),
                recording.replay(),
                recording.integrity()
        );
    }

    private AppInfo applyToApp(AppInfo app) {
        if (app == null) {
            return null;
        }
        return new AppInfo(
                app.name(),
                app.gitCommit(),
                app.gitDirty(),
                app.javaVersion(),
                app.agentVersion(),
                // 🔴 호스트 이름은 사람이 아니라 기계를 가리키지만, 사내망에서는
                //    「누구 자리 PC 인가」로 바로 읽힌다. 그래서 가명화 대상이다.
                pseudonymOrDrop(app.hostname(), "host")
        );
    }

    private Event applyToEvent(Event event) {
        return switch (event) {
            case Event.HttpIn e -> new Event.HttpIn(
                    e.seq(), e.corrId(), e.at(), e.thread(), e.durationMs(),
                    e.method(), e.path(), cleanMap(e.query()), e.handler(),
                    cleanHeaders(e.headers()),
                    cleanText(e.body()), e.bodyTruncated(), e.bodyBytes(),
                    e.responseStatus(), cleanText(e.responseBody()), e.responseBodyTruncated());

            case Event.HttpOut e -> new Event.HttpOut(
                    e.seq(), e.corrId(), e.at(), e.thread(), e.durationMs(),
                    e.method(), cleanText(e.url()), cleanHeaders(e.requestHeaders()),
                    cleanText(e.requestBody()), e.requestBodyTruncated(),
                    e.responseStatus(), cleanHeaders(e.responseHeaders()),
                    cleanText(e.responseBody()), e.responseBodyTruncated());

            case Event.Sql e -> new Event.Sql(
                    e.seq(), e.corrId(), e.at(), e.thread(), e.durationMs(),
                    // 🔴 SQL 문 자체는 안 건드린다. 값이 아니라 «코드»이고,
                    //    바꾸면 재생이 다른 질의를 보낸다.
                    e.sql(), cleanParams(e.params()),
                    e.rowCount(), cleanRows(e.rows()), e.rowsTruncated(),
                    e.repeatOf(), e.repeatCount());

            case Event.Clock e -> e;
            case Event.Rand e -> e;
        };
    }

    // ── 조각들 ──────────────────────────────────────────────────────────────

    private Map<String, String> cleanHeaders(Map<String, String> headers) {
        if (headers == null) {
            return null; // 🔴 「헤더를 안 봤다」와 「헤더가 없었다」는 다른 사실이다
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            String lower = name.toLowerCase();
            if (DROPPED_HEADERS.contains(lower) || containsSecretName(lower)) {
                cleaned.put(name, DROPPED);
            } else {
                cleaned.put(name, cleanText(value));
            }
        });
        return Map.copyOf(cleaned);
    }

    private Map<String, String> cleanMap(Map<String, String> map) {
        if (map == null) {
            return null;
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        map.forEach((name, value) -> cleaned.put(
                name,
                containsSecretName(name.toLowerCase()) ? DROPPED : cleanText(value)));
        return Map.copyOf(cleaned);
    }

    private List<Object> cleanParams(List<Object> params) {
        if (params == null) {
            return null;
        }
        List<Object> cleaned = new ArrayList<>(params.size());
        for (Object param : params) {
            cleaned.add(param instanceof String s ? cleanText(s) : param);
        }
        return List.copyOf(cleaned);
    }

    private List<Map<String, Object>> cleanRows(List<Map<String, Object>> rows) {
        if (rows == null) {
            return null;
        }
        List<Map<String, Object>> cleaned = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> cleanedRow = new LinkedHashMap<>();
            row.forEach((column, value) -> {
                if (containsSecretName(column.toLowerCase())) {
                    cleanedRow.put(column, DROPPED);
                } else if (value instanceof String s) {
                    cleanedRow.put(column, cleanText(s));
                } else {
                    cleanedRow.put(column, value);
                }
            });
            cleaned.add(Map.copyOf(cleanedRow));
        }
        return List.copyOf(cleaned);
    }

    /**
     * 본문처럼 «무엇이 들었는지 모르는» 글에서 사람을 가리키는 모양을 찾아 바꾼다.
     *
     * <p>🔴 순서가 중요하다. 주민번호·카드번호를 «먼저» 버려야 한다. 전화번호 규칙이
     * 주민번호의 일부와 겹치는 모양이 있어서, 전화번호를 먼저 돌리면 주민번호가
     * 반쯤 가명화된 채로 남는다 — 그건 버린 것도 가명화한 것도 아니다.
     */
    private String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = RRN_KR.matcher(text).replaceAll(DROPPED);
        result = CARD.matcher(result).replaceAll(DROPPED);
        result = replaceAll(result, EMAIL, m -> pseudonymEmail(m.group()));
        result = replaceAll(result, PHONE_KR, m -> pseudonymPhone(m.group()));
        return result;
    }

    private static String replaceAll(String text, Pattern pattern, java.util.function.Function<MatchResult, String> f) {
        return pattern.matcher(text).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(f.apply(match)));
    }

    private static boolean containsSecretName(String lowerName) {
        return SECRET_NAME_PARTS.stream().anyMatch(lowerName::contains);
    }

    // ── 가명 만들기 ─────────────────────────────────────────────────────────

    private String pseudonymOrDrop(String value, String prefix) {
        if (value == null) {
            return null;
        }
        if (!hasKey()) {
            return DROPPED;
        }
        return prefix + "-" + hmacHex(value).substring(0, 8);
    }

    /** {@code hong@abc.com → user_7f3a1c@example.invalid} — 같은 원본은 언제나 같은 가명. */
    private String pseudonymEmail(String email) {
        if (!hasKey()) {
            return DROPPED;
        }
        return "user_" + hmacHex(email).substring(0, 6) + "@example.invalid";
    }

    /** {@code 010-1234-5678 → 010-0000-4821} — 형태는 지키고 값만 바꾼다. */
    private String pseudonymPhone(String phone) {
        if (!hasKey()) {
            return DROPPED;
        }
        String hex = hmacHex(phone);
        int tail = Math.abs(hex.substring(0, 6).hashCode()) % 10000;
        return "010-0000-" + String.format("%04d", tail);
    }

    private String hmacHex(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // 🔴 여기서 원본을 그대로 돌려주지 않는다. 가명화가 실패하면
            //    기록이 덜 쓸모 있어지는 쪽이지, 원본이 남는 쪽이 아니다.
            throw new IllegalStateException("가명화에 실패했다. 원본을 쓰지 않고 멈춘다.", e);
        }
    }
}
