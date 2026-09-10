package io.hindsight.demo.experiment;

import io.hindsight.demo.member.Member;
import io.hindsight.demo.member.MemberRepository;
import io.hindsight.demo.order.Order;
import io.hindsight.demo.order.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 🔬 <b>실측 실험 2.</b> {@code Filter} 로 잡은 요청·응답이 <b>재생에 쓸 만한가.</b>
 *
 * <p>TODOS 의 「먼저 확인해야 결정이 유지되는 것」 중 남은 하나다.
 * 이게 안 되면 바이트코드를 v1 로 미룬 근거(만드는 순서 결정 §5)가 사라진다.
 *
 * <p>확인할 것 넷:
 * <ol>
 *   <li>Filter 가 본문을 읽고도 <b>앱이 정상 동작하나</b> (본문은 한 번만 읽을 수 있다)</li>
 *   <li>응답 본문을 잡고도 <b>클라이언트가 응답을 받나</b></li>
 *   <li>잡은 것만으로 <b>같은 요청을 다시 만들 수 있나</b> — 그게 재생이다</li>
 *   <li>🔴 <b>상태를 바꾸는 요청</b>을 재생하면 어떻게 되나</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpCaptureExperimentTest {

    static final HttpTap TAP = new HttpTap();

    @TestConfiguration
    static class TapConfig {
        @Bean
        HttpTap httpTap() {
            return TAP;
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private MemberRepository memberRepository;
    @Autowired private OrderRepository orderRepository;

    private Long memberId;

    @BeforeEach
    void seed() {
        orderRepository.deleteAll();
        memberRepository.deleteAll();
        Member member = memberRepository.save(new Member("홍길동"));
        memberId = member.getId();
        orderRepository.save(new Order(member, "키보드"));
        orderRepository.save(new Order(member, "마우스"));
        TAP.reset();
    }

    @Test
    @DisplayName("실험 ④ 본문을 잡고도 앱이 정상인가 · 응답이 클라이언트에 가나")
    void capturingBodyDoesNotBreakTheApp() throws Exception {
        String body = "{\"memberId\":" + memberId + ",\"product\":\"모니터\"}";

        MvcResult result = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        HttpTap.Captured c = TAP.last();

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────");
        System.out.println("│ 🔬 실험 ④ — 본문이 있는 요청을 잡아 봤다");
        System.out.println("├─────────────────────────────────────────────────────────────");
        System.out.println("│ 앱이 돌려준 상태  : " + result.getResponse().getStatus());
        System.out.println("│ 앱이 돌려준 본문  : " + result.getResponse().getContentAsString());
        System.out.println("├─ 우리가 «잡은» 것 ──────────────────────────────────────────");
        System.out.println("│ 메서드·경로       : " + c.method() + " " + c.path());
        System.out.println("│ 요청 본문         : " + c.requestBody());
        System.out.println("│ 응답 상태         : " + c.status());
        System.out.println("│ 응답 본문         : " + c.responseBody());
        System.out.println("│ 헤더              : " + c.headers().keySet());
        System.out.println("└─────────────────────────────────────────────────────────────");
        System.out.println();

        // ① 앱이 망가지지 않았나 — 본문을 우리가 먼저 읽었는데도 앱이 제대로 처리했나
        assertThat(result.getResponse().getStatus())
                .as("우리가 본문을 읽어서 앱에 빈 본문이 갔다면 여기서 400 이 난다")
                .isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .as("copyBodyToResponse() 를 잊으면 여기가 빈 문자열이 된다")
                .contains("모니터");

        // ② 우리가 재생에 필요한 것을 다 잡았나
        assertThat(c.method()).isEqualTo("POST");
        assertThat(c.path()).isEqualTo("/api/orders");
        assertThat(c.requestBody()).as("요청 본문을 못 잡으면 재생을 못 한다").contains("모니터");
        assertThat(c.responseBody()).as("응답 본문을 못 잡으면 채점할 기준이 없다").contains("모니터");
        assertThat(c.headers()).as("Authorization·Cookie 는 처음부터 안 담는다")
                .doesNotContainKey("authorization").doesNotContainKey("cookie");
    }

    @Test
    @DisplayName("실험 ⑤ 잡은 것만으로 재생하면 같은 응답이 나오나 (읽기 요청)")
    void replayOfReadRequestMatches() throws Exception {
        // 1) 원본 요청
        mockMvc.perform(get("/api/orders")).andReturn();
        HttpTap.Captured original = TAP.last();

        // 2) 🔴 잡은 것«만» 보고 요청을 다시 만든다. 원본 코드를 안 본다 — 그게 재생이다
        TAP.reset();
        MvcResult replayed = mockMvc.perform(
                        get(original.path() + (original.query() == null ? "" : "?" + original.query()))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(original.requestBody()))
                .andReturn();

        System.out.println();
        System.out.println("🔬 실험 ⑤ — 읽기 요청 재생");
        System.out.println("   원본 응답 : " + original.status() + " " + original.responseBody());
        System.out.println("   재생 응답 : " + replayed.getResponse().getStatus() + " "
                + replayed.getResponse().getContentAsString());
        System.out.println();

        assertThat(replayed.getResponse().getStatus()).isEqualTo(original.status());
        assertThat(replayed.getResponse().getContentAsString())
                .as("잡은 것만으로 같은 응답이 안 나오면 재생이 성립하지 않는다")
                .isEqualTo(original.responseBody());
    }

    @Test
    @DisplayName("🔴 실험 ⑥ 상태를 바꾸는 요청을 재생하면 — 같은 답이 안 나온다")
    void replayOfWriteRequestDiverges() throws Exception {
        String body = "{\"memberId\":" + memberId + ",\"product\":\"의자\"}";

        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        HttpTap.Captured original = TAP.last();

        TAP.reset();
        MvcResult replayed = mockMvc.perform(post(original.path())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(original.requestBody()))
                .andReturn();

        String originalBody = original.responseBody();
        String replayedBody = replayed.getResponse().getContentAsString();

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────");
        System.out.println("│ 🔴 실험 ⑥ — 쓰기 요청을 재생하면?");
        System.out.println("├─────────────────────────────────────────────────────────────");
        System.out.println("│ 원본 응답 : " + originalBody);
        System.out.println("│ 재생 응답 : " + replayedBody);
        System.out.println("│ 같은가    : " + originalBody.equals(replayedBody));
        System.out.println("│ 주문 개수 : " + orderRepository.count() + " 건 (재생이 하나 더 만들었다)");
        System.out.println("└─────────────────────────────────────────────────────────────");
        System.out.println();

        // 🔴 이건 「실패」가 아니라 「알아야 할 사실」이다.
        //    상태를 바꾸는 요청은 DB 가 이미 달라져 있어서 같은 답이 안 나온다.
        //    설계 §4-2 의 「앱 안에 쌓인 상태는 재현 안 된다」가 여기서 눈에 보인다.
        assertThat(replayedBody)
                .as("🔴 쓰기 요청 재생은 원본과 다르다. 이걸 「고쳐졌다/안 고쳐졌다」로 읽으면 안 된다")
                .isNotEqualTo(originalBody);
    }
}
