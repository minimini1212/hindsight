package io.hindsight.core.privacy;

import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Recording;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("가명화 — 무엇을 버리고 무엇을 바꾸나")
class PseudonymizerTest {

    private static final String KEY = "테스트용-키-절대-운영에-쓰지-않는다";

    private static Recording recordingWith(Event... events) {
        return new Recording(
                Recording.CURRENT_SCHEMA_VERSION, "test", Instant.parse("2026-09-15T00:00:00Z"),
                null,
                new AppInfo("demo-app", null, null, "21", "0.1.0", "minhee-laptop"),
                List.of(events), null, null, null, null);
    }

    private static Event.HttpIn httpIn(Map<String, String> headers, String body) {
        return new Event.HttpIn(1, "r-1", Instant.parse("2026-09-15T00:00:00Z"), "t", 5L,
                "POST", "/api/orders", null, null, headers, body, false, 0, 200, null, false);
    }

    @Nested
    @DisplayName("🔴 버리는 것 — 가명화가 아니라 «버린다»")
    class Dropped {

        @Test
        @DisplayName("Authorization · Cookie · Set-Cookie 헤더는 값이 [dropped] 가 된다")
        void 인증_헤더는_버린다() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("authorization", "Bearer eyJhbGciOi...");
            headers.put("cookie", "SESSION=abc123");
            headers.put("content-type", "application/json");

            Recording out = new Pseudonymizer(KEY).apply(recordingWith(httpIn(headers, null)));
            Map<String, String> cleaned = ((Event.HttpIn) out.events().get(0)).headers();

            assertThat(cleaned.get("authorization")).isEqualTo("[dropped]");
            assertThat(cleaned.get("cookie")).isEqualTo("[dropped]");
            // 나머지는 그대로다. 다 지우면 재생이 안 된다.
            assertThat(cleaned.get("content-type")).isEqualTo("application/json");
        }

        @Test
        @DisplayName("이름에 password · token 이 든 헤더도 버린다")
        void 비밀_이름의_헤더도_버린다() {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("x-api-token", "abcd-1234");
            headers.put("x-user-password", "hunter2");

            Recording out = new Pseudonymizer(KEY).apply(recordingWith(httpIn(headers, null)));
            Map<String, String> cleaned = ((Event.HttpIn) out.events().get(0)).headers();

            assertThat(cleaned.get("x-api-token")).isEqualTo("[dropped]");
            assertThat(cleaned.get("x-user-password")).isEqualTo("[dropped]");
        }

        @Test
        @DisplayName("🔴 본문 속 주민등록번호와 카드번호는 «버린다» — 가명화하지 않는다")
        void 주민번호와_카드번호는_버린다() {
            String body = "{\"rrn\":\"900101-1234567\",\"card\":\"4111 1111 1111 1111\"}";

            Recording out = new Pseudonymizer(KEY).apply(recordingWith(httpIn(Map.of(), body)));
            String cleaned = ((Event.HttpIn) out.events().get(0)).body();

            assertThat(cleaned).doesNotContain("900101-1234567");
            assertThat(cleaned).doesNotContain("4111");
            assertThat(cleaned).contains("[dropped]");
        }
    }

    @Nested
    @DisplayName("가명화 — 형태를 지키고 값만 바꾼다")
    class Pseudonymized {

        @Test
        @DisplayName("🔴 같은 원본은 언제나 같은 가명이 된다 — 같은 사람이 두 번 나온 걸 알 수 있어야 한다")
        void 같은_원본은_같은_가명() {
            String body = "{\"a\":\"hong@abc.com\",\"b\":\"hong@abc.com\",\"c\":\"kim@abc.com\"}";

            String cleaned = ((Event.HttpIn) new Pseudonymizer(KEY)
                    .apply(recordingWith(httpIn(Map.of(), body))).events().get(0)).body();

            assertThat(cleaned).doesNotContain("hong@abc.com");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("user_[0-9a-f]{6}@example\\.invalid").matcher(cleaned);
            assertThat(m.find()).isTrue();
            String first = m.group();
            assertThat(m.find()).isTrue();
            assertThat(m.group()).isEqualTo(first);   // 같은 사람 → 같은 가명
            assertThat(m.find()).isTrue();
            assertThat(m.group()).isNotEqualTo(first); // 다른 사람 → 다른 가명
        }

        @Test
        @DisplayName("전화번호는 010-0000-#### 모양을 지킨다")
        void 전화번호는_형태를_지킨다() {
            String cleaned = ((Event.HttpIn) new Pseudonymizer(KEY)
                    .apply(recordingWith(httpIn(Map.of(), "연락처는 010-1234-5678 입니다"))).events().get(0)).body();

            assertThat(cleaned).doesNotContain("010-1234-5678");
            assertThat(cleaned).containsPattern("010-0000-\\d{4}");
        }

        @Test
        @DisplayName("호스트 이름도 바꾼다 — 사내망에서는 「누구 자리 PC」로 읽힌다")
        void 호스트_이름도_바꾼다() {
            Recording out = new Pseudonymizer(KEY).apply(recordingWith(httpIn(Map.of(), null)));

            assertThat(out.app().hostname()).isNotEqualTo("minhee-laptop");
            assertThat(out.app().hostname()).startsWith("host-");
        }
    }

    @Nested
    @DisplayName("🔴 키가 없을 때")
    class NoKey {

        @Test
        @DisplayName("가명화 대상을 «버린다» — 고정된 가짜 키로 가명화한 척하지 않는다")
        void 키가_없으면_버린다() {
            Recording out = new Pseudonymizer(null)
                    .apply(recordingWith(httpIn(Map.of(), "hong@abc.com 으로 보내 주세요")));

            assertThat(new Pseudonymizer(null).hasKey()).isFalse();
            assertThat(((Event.HttpIn) out.events().get(0)).body()).doesNotContain("hong@abc.com");
            assertThat(((Event.HttpIn) out.events().get(0)).body()).contains("[dropped]");
            assertThat(out.app().hostname()).isEqualTo("[dropped]");
        }

        @Test
        @DisplayName("빈 문자열 키는 「키가 없다」와 같게 다룬다")
        void 빈_문자열_키는_없는_것이다() {
            assertThat(new Pseudonymizer("   ").hasKey()).isFalse();
        }
    }

    @Nested
    @DisplayName("🔴 건드리면 안 되는 것")
    class LeftAlone {

        @Test
        @DisplayName("SQL 문 자체는 안 바꾼다 — 값이 아니라 «코드»이고, 바꾸면 재생이 다른 질의를 보낸다")
        void sql_문은_안_바꾼다() {
            Event.Sql sql = new Event.Sql(1, "r", Instant.parse("2026-09-15T00:00:00Z"), "t", 3L,
                    "select * from member where email = ?", List.of("hong@abc.com"),
                    1, null, false, null, null);

            Event.Sql out = (Event.Sql) new Pseudonymizer(KEY).apply(recordingWith(sql)).events().get(0);

            assertThat(out.sql()).isEqualTo("select * from member where email = ?");
            // 그런데 «값»은 바뀐다.
            assertThat(out.params().get(0)).asString().doesNotContain("hong@abc.com");
        }

        @Test
        @DisplayName("🔴 null 은 null 로 남는다 — 「안 봤다」를 「보았고 없었다」로 바꾸지 않는다")
        void null_은_null_로_남는다() {
            Event.Sql sql = new Event.Sql(1, "r", Instant.parse("2026-09-15T00:00:00Z"), "t", 3L,
                    "select 1", null, null, null, false, null, null);

            Event.Sql out = (Event.Sql) new Pseudonymizer(KEY).apply(recordingWith(sql)).events().get(0);

            assertThat(out.params()).isNull();
            assertThat(out.rows()).isNull();
            assertThat(out.rowCount()).isNull();
        }
    }
}
