package io.hindsight.demo.experiment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 🔬 <b>실측 실험 3.</b> 쓰기 요청은 정말로 「같아질 수 없나」.
 *
 * <h2>왜 이걸 다시 재나</h2>
 * 실험 ⑥(2026-09-11)은 쓰기 요청을 재생하면 응답이 다르다는 것을 보여 줬고, 거기서
 * <b>「쓰기 요청은 채점할 수 없다」</b>는 결론이 나왔다. 그런데 그 실험은
 * <b>원본 요청이 이미 DB 를 바꿔 놓은 상태 위에서</b> 재생을 돌렸다.
 * 즉 「쓰기 요청이라서 다른 것」인지 「DB 를 안 되돌려서 다른 것」인지가 갈리지 않았다.
 *
 * <p>🔴 <b>둘은 완전히 다른 결론으로 간다.</b>
 * 전자라면 등급 체계를 하나 더 만들어야 하고, 후자라면 만들 게 없다 —
 * 재생 절차에 「복원」이 빠져 있었을 뿐이다.
 *
 * <h2>그래서 세 갈래로 나눠 잰다</h2>
 * 원본 요청은 셋 다 똑같다. <b>재생 직전에 DB 를 어디까지 되돌렸는가만 다르다.</b>
 *
 * <pre>
 *   ⑦-A  아무것도 안 되돌린다        ← 실험 ⑥ 과 같은 조건 (대조군)
 *   ⑦-B  «행»만 되돌린다             delete + 같은 행을 다시 넣기
 *   ⑦-C  «행 + 자동 증가 카운터»     truncate ... restart identity + 다시 넣기
 * </pre>
 *
 * <p>⑦-B 와 ⑦-C 를 가르는 것은 <b>눈에 안 보이는 상태</b>다. 행을 지워도 H2 가 들고 있는
 * 「다음 id 는 몇 번」이라는 숫자는 안 돌아간다. 그것도 DB 상태다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WriteReplayExperimentTest {

    static final HttpTap TAP = new HttpTap();

    @TestConfiguration
    static class TapConfig {
        @Bean
        HttpTap httpTap() {
            return TAP;
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    /**
     * 🔴 기록 시점의 DB 를 만드는 <b>단 하나의</b> 절차. 재생 전 복원도 반드시 이걸 쓴다.
     * 씨 뿌리는 방법이 둘이면, 결과가 달라졌을 때 무엇 때문인지 알 수 없다.
     */
    private void 행을_넣는다() {
        jdbc.update("insert into member (name) values (?)", "홍길동");
        Long memberId = jdbc.queryForObject("select max(id) from member", Long.class);
        jdbc.update("insert into orders (member_id, product) values (?, ?)", memberId, "키보드");
        jdbc.update("insert into orders (member_id, product) values (?, ?)", memberId, "마우스");
    }

    /** 행만 지운다. 🔴 「다음 id 는 몇 번」이라는 카운터는 그대로 남는다. */
    private void 행만_지운다() {
        jdbc.update("delete from orders");
        jdbc.update("delete from member");
    }

    /** 행과 카운터를 같이 되돌린다. */
    private void 행과_카운터를_되돌린다() {
        jdbc.execute("set referential_integrity false");
        jdbc.execute("truncate table orders restart identity");
        jdbc.execute("truncate table member restart identity");
        jdbc.execute("set referential_integrity true");
    }

    @BeforeEach
    void 기록_시점_상태를_만든다() {
        행과_카운터를_되돌린다();
        행을_넣는다();
        TAP.reset();
    }

    /** 원본 요청 한 건을 보내고, 잡힌 것을 돌려준다. */
    private HttpTap.Captured 원본을_한_번_보낸다() throws Exception {
        Long memberId = jdbc.queryForObject("select min(id) from member", Long.class);
        String body = "{\"memberId\":" + memberId + ",\"product\":\"의자\"}";
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        HttpTap.Captured captured = TAP.last();
        TAP.reset();
        return captured;
    }

    /** 🔴 잡은 것«만» 보고 요청을 다시 만든다. 원본 코드를 보지 않는다 — 그게 재생이다. */
    private MvcResult 재생한다(HttpTap.Captured original) throws Exception {
        return mockMvc.perform(post(original.path())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(original.requestBody()))
                .andReturn();
    }

    private void 결과를_적는다(String 이름, String 되돌린_범위, HttpTap.Captured original, MvcResult replayed)
            throws Exception {
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────");
        System.out.println("│ 🔬 " + 이름 + " — 되돌린 범위: " + 되돌린_범위);
        System.out.println("├─────────────────────────────────────────────────────────────");
        System.out.println("│ 원본 응답 : " + original.status() + " " + original.responseBody());
        System.out.println("│ 재생 응답 : " + replayed.getResponse().getStatus() + " "
                + replayed.getResponse().getContentAsString());
        System.out.println("│ 같은가    : "
                + original.responseBody().equals(replayed.getResponse().getContentAsString()));
        System.out.println("│ 주문 행수 : " + jdbc.queryForObject("select count(*) from orders", Long.class) + " 건");
        System.out.println("└─────────────────────────────────────────────────────────────");
        System.out.println();
    }

    @Test
    @DisplayName("⑦-A 아무것도 안 되돌리고 재생하면 — 다르다 (실험 ⑥ 재확인)")
    void 되돌리지_않으면_다르다() throws Exception {
        HttpTap.Captured original = 원본을_한_번_보낸다();

        MvcResult replayed = 재생한다(original);
        결과를_적는다("⑦-A", "없음", original, replayed);

        assertThat(replayed.getResponse().getContentAsString())
                .as("원본이 남긴 행 위에 하나를 더 만들었으니 id 가 다르다")
                .isNotEqualTo(original.responseBody());
    }

    @Test
    @DisplayName("🔴 ⑦-B 행만 되돌리고 재생하면 — 재생이 «틀리는» 게 아니라 «터진다»")
    void 행만_되돌리면() throws Exception {
        HttpTap.Captured original = 원본을_한_번_보낸다();

        행만_지운다();
        행을_넣는다();

        // 🔴 여기서 재생은 「다른 답」을 주지 않는다. 아예 예외로 끝난다.
        //    행은 똑같이 세 줄인데, 그 줄에 붙은 id 가 1·2·3 이 아니라 2·4·5 라서
        //    기록에 담긴 「memberId: 1」 이 가리키는 회원이 «없다».
        Throwable thrown = catchThrowable(() -> 재생한다(original));

        Long 회원_id = jdbc.queryForObject("select min(id) from member", Long.class);
        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────");
        System.out.println("│ 🔬 ⑦-B — 되돌린 범위: 행만 (delete + 같은 행을 다시 넣기)");
        System.out.println("├─────────────────────────────────────────────────────────────");
        System.out.println("│ 원본 응답      : " + original.status() + " " + original.responseBody());
        System.out.println("│ 기록된 요청    : " + original.requestBody());
        System.out.println("│ 복원 후 회원 id: " + 회원_id + "  🔴 기록은 1 번을 가리키는데 실제로는 "
                + 회원_id + " 번이다");
        System.out.println("│ 재생 결과      : " + (thrown == null ? "정상" : "예외 — "
                + 근본_원인(thrown).getMessage()));
        System.out.println("│ 주문 행수      : "
                + jdbc.queryForObject("select count(*) from orders", Long.class) + " 건");
        System.out.println("└─────────────────────────────────────────────────────────────");
        System.out.println();

        assertThat(회원_id)
                .as("행을 지워도 「다음 id 는 몇 번」이라는 카운터는 안 돌아간다")
                .isNotEqualTo(1L);
        assertThat(thrown)
                .as("🔴 이게 가장 나쁜 모양이다 — 채점기는 이걸 「패치가 안 고쳤다」로 읽는다")
                .isNotNull();
        assertThat(근본_원인(thrown).getMessage()).contains("없는 회원");
    }

    private static Throwable 근본_원인(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Test
    @DisplayName("🔴 ⑦-C 행과 자동 증가 카운터까지 되돌리고 재생하면")
    void 카운터까지_되돌리면() throws Exception {
        HttpTap.Captured original = 원본을_한_번_보낸다();

        행과_카운터를_되돌린다();
        행을_넣는다();

        MvcResult replayed = 재생한다(original);
        결과를_적는다("⑦-C", "행 + 자동 증가 카운터 (truncate restart identity)", original, replayed);

        // 🔴 이 한 줄이 「쓰기 요청은 채점할 수 없다」를 뒤집는다.
        //    같아질 수 없는 게 아니었다. 되돌리지 않고 재생했을 뿐이다.
        assertThat(replayed.getResponse().getContentAsString())
                .as("복원 범위가 맞으면 쓰기 요청도 글자까지 같은 응답이 나온다")
                .isEqualTo(original.responseBody());
        assertThat(replayed.getResponse().getStatus()).isEqualTo(original.status());
    }
}
