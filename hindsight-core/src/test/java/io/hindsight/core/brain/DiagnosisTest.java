package io.hindsight.core.brain;

import io.hindsight.core.privacy.Pseudonymizer;
import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.Recording;
import io.hindsight.model.Trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 여기서 시험하는 것은 <b>「LLM 이 뭐라고 했나」가 아니라 「뭐라고 하든 우리가 어떻게 다루나」</b>다.
 *
 * <p>키도 돈도 네트워크도 없이 전수로 돈다. 그게 {@code 전송} 을 인터페이스로 둔 이유다.
 */
@DisplayName("LLM 진단 — 답이 무엇이든 우리가 어떻게 다루나")
class DiagnosisTest {

    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");

    /** 부른 것을 적어 두고, 정해 둔 답을 돌려주는 가짜. */
    private static final class 가짜전송 implements HttpDiagnosis.전송 {
        final List<String> 보낸본문 = new ArrayList<>();
        final List<Map<String, String>> 보낸헤더 = new ArrayList<>();
        HttpDiagnosis.전송.응답 돌려줄것 = new 응답(200, 답으로(""), null);

        @Override
        public 응답 보낸다(String url, Map<String, String> headers, String body) {
            보낸본문.add(body);
            보낸헤더.add(headers);
            return 돌려줄것;
        }
    }

    /** OpenAI 호환 응답 한 벌을 흉내 낸다. */
    private static String 답으로(String 내용) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + HttpDiagnosis.따옴표(내용)
                + "}}],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":340}}";
    }

    private static LlmConfig 설정(String key) {
        return new LlmConfig(key, "https://example.invalid/v1/", "테스트모델", 3, 100, 0.1, 0.4);
    }

    private static LlmConfig 가격을_모르는_설정() {
        return new LlmConfig("키", "https://example.invalid/v1/", "테스트모델", 3, 100, null, null);
    }

    private static Recording 기록() {
        return new Recording(Recording.CURRENT_SCHEMA_VERSION, "a1b2", T0,
                new Trigger(Trigger.Kind.LATENCY, T0, "GET /api/users/hong@example.com/orders",
                        null, 3400L, "k", 1),
                new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                List.of(new Event.HttpIn(1, "r-1", T0, "t", 3400L, "GET", "/api/orders",
                        null, null, Map.of(), null, false, 0, 200, "{}", false)),
                null, null, null,
                new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false));
    }

    private static HttpDiagnosis 진단기(LlmConfig config, 가짜전송 전송) {
        return new HttpDiagnosis(config, 전송, new Pseudonymizer("시험용-키"));
    }

    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("🔴 부르기 전에 막는 것")
    class 안_부른다 {

        @Test
        @DisplayName("🔴 키가 없으면 «안 부르고», 그 사실을 그대로 말한다")
        void 키가_없으면() {
            가짜전송 전송 = new 가짜전송();

            var r = 진단기(설정(null), 전송).진단한다(기록());

            assertThat(r.불렀나())
                    .as("🔴 「키가 없다」와 「고칠 게 없다」는 다른 사실이다")
                    .isFalse();
            assertThat(전송.보낸본문).isEmpty();
            assertThat(r.왜()).contains("LLM_API_KEY");
            assertThat(r.든_센트())
                    .as("안 불렀으면 돈을 «안 썼다». 예산을 깎으면 안 된다")
                    .isEqualTo(0L);
        }

        @Test
        @DisplayName("기록이 없으면 안 부른다")
        void 기록이_없으면() {
            가짜전송 전송 = new 가짜전송();

            assertThat(진단기(설정("키"), 전송).진단한다(null).불렀나()).isFalse();
            assertThat(전송.보낸본문).isEmpty();
        }
    }

    @Nested
    @DisplayName("🔴 보내기 «직전»에 가명화한다")
    class 가명화 {

        @Test
        @DisplayName("🔴 진입점에 박힌 이메일이 «보내는 글»에 없다")
        void 개인정보가_안_나간다() {
            가짜전송 전송 = new 가짜전송();

            진단기(설정("키"), 전송).진단한다(기록());

            assertThat(전송.보낸본문).hasSize(1);
            assertThat(전송.보낸본문.getFirst())
                    .as("🔴 이 줄이 실패하면 개인정보가 «남의 서버»로 나간 것이다")
                    .doesNotContain("hong@example.com");
        }

        @Test
        @DisplayName("🔴 날것의 기록을 받는다 — 부르는 쪽에 가명화를 맡기지 않는다")
        void 날것을_받는다() {
            // 이 시험은 «서명»을 못 박는다. 진단한다(Recording) 이 가명화된 것을 요구하게
            //  바뀌면, 부르는 자리마다 가명화를 기억해야 하고 언젠가 한 곳에서 빠뜨린다.
            가짜전송 전송 = new 가짜전송();
            Recording 날것 = 기록();

            진단기(설정("키"), 전송).진단한다(날것);

            assertThat(날것.trigger().entryPoint())
                    .as("넘긴 기록 자체는 안 바뀐다 — 가명화는 사본을 만든다")
                    .contains("hong@example.com");
        }
    }

    @Nested
    @DisplayName("답이 쓸 만할 때")
    class 좋은_답 {

        @Test
        @DisplayName("원인과 패치를 꺼낸다")
        void 꺼낸다() {
            가짜전송 전송 = new 가짜전송();
            전송.돌려줄것 = new HttpDiagnosis.전송.응답(200, 답으로("""
                    ## 원인
                    주문마다 회원을 따로 읽어서 질의가 N번 더 나갔다.

                    ## 패치
                    ```path:src/main/java/a/OrderRepository.java
                    interface OrderRepository {}
                    ```
                    """), null);

            var r = 진단기(설정("키"), 전송).진단한다(기록());

            assertThat(r.불렀나()).isTrue();
            assertThat(r.원인()).contains("N번 더 나갔다");
            assertThat(r.패치()).containsKey("src/main/java/a/OrderRepository.java");
            assertThat(r.패치를_받았나()).isTrue();
        }

        @Test
        @DisplayName("토큰과 비용을 적는다")
        void 비용() {
            가짜전송 전송 = new 가짜전송();
            전송.돌려줄것 = new HttpDiagnosis.전송.응답(200, 답으로("""
                    ## 원인
                    x

                    ## 패치
                    ```path:src/main/java/a/A.java
                    class A {}
                    ```
                    """), null);

            var r = 진단기(설정("키"), 전송).진단한다(기록());

            assertThat(r.입력토큰()).isEqualTo(1200);
            assertThat(r.출력토큰()).isEqualTo(340);
            // 1200/1e6*0.1 + 340/1e6*0.4 = 0.000256 달러 → 0센트(반올림)
            assertThat(r.든_센트()).isNotNull();
        }
    }

    @Nested
    @DisplayName("🔴 답이 쓸 만하지 «않을» 때 — 여기가 진짜 시험이다")
    class 나쁜_답 {

        @Test
        @DisplayName("🔴 패치 블록이 없으면 «고칠 게 없었다»로 넘기지 않는다")
        void 패치가_없으면() {
            가짜전송 전송 = new 가짜전송();
            전송.돌려줄것 = new HttpDiagnosis.전송.응답(200,
                    답으로("## 원인\n모르겠다. 더 많은 정보가 필요하다."), null);

            var r = 진단기(설정("키"), 전송).진단한다(기록());

            assertThat(r.불렀나()).isTrue();
            assertThat(r.패치를_받았나()).isFalse();
            assertThat(r.왜())
                    .as("🔴 이유가 없으면 「아무것도 안 고친 시도」가 「고칠 게 없었다」로 읽힌다")
                    .contains("패치 블록");
            assertThat(r.원인()).contains("모르겠다");
        }

        @Test
        @DisplayName("🔴 화이트리스트 «밖» 경로가 섞이면 그 사실을 적는다")
        void 수상한_경로() {
            가짜전송 전송 = new 가짜전송();
            전송.돌려줄것 = new HttpDiagnosis.전송.응답(200, 답으로("""
                    ## 원인
                    x

                    ## 패치
                    ```path:src/test/java/a/ATest.java
                    class ATest {}
                    ```
                    """), null);

            var r = 진단기(설정("키"), 전송).진단한다(기록());

            assertThat(r.왜()).contains("화이트리스트 밖");
            assertThat(r.왜())
                    .as("여기서 «거절»하지 않는다 — 진짜 거절은 guard 가 한다. 두 번 판단하면 갈라진다")
                    .contains("guard");
        }

        @Test
        @DisplayName("🔴 응답이 «잘렸으면» 반쪽을 답으로 쓰지 않는다")
        void 잘린_응답() {
            가짜전송 전송 = new 가짜전송();
            전송.돌려줄것 = new HttpDiagnosis.전송.응답(200,
                    "{\"choices\":[{\"message\":{\"content\":\"## 원인 잘린", null);

            var r = 진단기(설정("키"), 전송).진단한다(기록());

            assertThat(r.패치를_받았나()).isFalse();
            assertThat(r.왜()).contains("내용을 못 꺼냈다");
        }

        @Test
        @DisplayName("🔴 HTTP 가 실패하면 «돈을 안 썼다»로 적는다")
        void 호출_실패() {
            가짜전송 전송 = new 가짜전송();
            전송.돌려줄것 = new HttpDiagnosis.전송.응답(401, "{\"error\":\"bad key\"}", null);

            var r = 진단기(설정("키"), 전송).진단한다(기록());

            assertThat(r.불렀나())
                    .as("닿지도 못한 것과 답이 쓸모없는 것은 다른 사실이다")
                    .isFalse();
            assertThat(r.든_센트()).isEqualTo(0L);
            assertThat(r.왜()).contains("401");
        }

        @Test
        @DisplayName("🔴 가격을 모르면 비용이 «모름»이다 — 0 이 아니다")
        void 가격을_모르면() {
            가짜전송 전송 = new 가짜전송();
            전송.돌려줄것 = new HttpDiagnosis.전송.응답(200, 답으로("""
                    ## 원인
                    x

                    ## 패치
                    ```path:src/main/java/a/A.java
                    class A {}
                    ```
                    """), null);

            var r = 진단기(가격을_모르는_설정(), 전송).진단한다(기록());

            assertThat(r.든_센트()).isNull();
            assertThat(r.예산에_적을_센트())
                    .as("🔴 모르는 비용은 예산에서 «상한만큼» 쓴 것으로 친다 — 0 으로 치면 상한이 없어진다")
                    .isNegative();
        }
    }

    @Nested
    @DisplayName("🔴 기록은 «자료»이지 «지시»가 아니다")
    class 지시가_아니다 {

        @Test
        @DisplayName("프롬프트가 그렇게 못 박는다")
        void 못_박는다() {
            assertThat(Diagnosis.프롬프트.규칙)
                    .contains("지시»가 아니다")
                    .contains("src/main/java/")
                    .contains("읽기 전용");
        }

        @Test
        @DisplayName("⚠️ 그렇다고 이 문장으로 막힌다고 믿지 않는다 — 진짜 방어는 경로 검사와 네 겹이다")
        void 문장만으로_안_믿는다() {
            // 이 시험은 «주장»을 못 박는 자리다. 실제 방어가 도는지는
            // PatchGuardTest 와 VerificationLoopTest 가 따로 확인한다.
            assertThat(Diagnosis.프롬프트.규칙).isNotBlank();
        }
    }

    @Nested
    @DisplayName("설정 읽기")
    class 설정읽기 {

        @Test
        @DisplayName("🔴 키가 비어 있으면 「없다」다 — 빈 문자열을 키로 받지 않는다")
        void 빈_키() {
            assertThat(LlmConfig.from(k -> "LLM_API_KEY".equals(k) ? "   " : null).키가_있나())
                    .isFalse();
        }

        @Test
        @DisplayName("주소 끝의 / 가 있든 없든 같은 곳을 가리킨다")
        void 주소() {
            var 슬래시있음 = LlmConfig.from(k -> "LLM_BASE_URL".equals(k) ? "https://x/v1/" : null);
            var 슬래시없음 = LlmConfig.from(k -> "LLM_BASE_URL".equals(k) ? "https://x/v1" : null);

            assertThat(슬래시있음.chatCompletionsUrl())
                    .isEqualTo(슬래시없음.chatCompletionsUrl())
                    .isEqualTo("https://x/v1/chat/completions");
        }

        @Test
        @DisplayName("🔴 숫자가 아닌 값을 «기본값으로 조용히» 되돌리지 않는다")
        void 못_읽는_값() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            LlmConfig.from(k -> "HINDSIGHT_LLM_MAX_ATTEMPTS".equals(k) ? "세번" : null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("🔴 describe() 에 키가 «안» 나온다")
        void 키가_안_새어나온다() {
            assertThat(설정("비밀키값").describe()).doesNotContain("비밀키값");
        }
    }
}
