package io.hindsight.demo.experiment;

import io.hindsight.demo.member.Member;
import io.hindsight.demo.member.MemberRepository;
import io.hindsight.demo.order.Order;
import io.hindsight.demo.order.OrderRepository;
import io.hindsight.demo.order.OrderService;
import io.hindsight.demo.order.OrderView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔬 <b>실측 실험.</b> 설계에 「그럴 것이다」라고 적어 둔 가정 둘을 확인한다.
 *
 * <ol>
 *   <li>{@code DataSource} 를 감싸면 앱이 보내는 SQL 이 <b>전부 보이나</b></li>
 *   <li>한 번 실행한 SQL 이 <b>한 번만 세이나</b> — 커넥션 풀이 껍데기를 한 겹 더 씌우므로
 *       두 번 세일 수 있다. 두 배로 세이면 「SQL 횟수」를 오라클로 쓰는 설계(§6-1)가 무너진다</li>
 * </ol>
 *
 * <p>🔴 <b>기대값을 먼저 박아 두지 않는다.</b> 숫자를 출력해서 보고, 그다음에 판단한다.
 * 「201 이 나와야 한다」로 시작하면 202 가 나왔을 때 원인을 안 찾고 기대값을 고치게 된다.
 */
@SpringBootTest
class SqlCountExperimentTest {

    /** 🔴 이 프로젝트 전체에서 유일하게 static 인 자리. 스프링이 빈을 만들기 «전»에 필요해서다. */
    static final SqlTap TAP = new SqlTap();

    private static final int ORDER_COUNT = 20;

    /**
     * 스프링이 만든 {@code DataSource} 를 우리 껍데기로 바꿔치기한다.
     *
     * <p>🔴 이 자리가 중요하다. 스프링이 만드는 것은 HikariCP 의 {@code DataSource} 이고,
     * 우리는 그 «바깥»에 붙는다. 즉 <b>v0 기록기가 실제로 서게 될 위치와 같다.</b>
     * 여기서 SQL 이 안 보이면 v0 기록 방식 자체를 다시 짜야 한다.
     */
    @TestConfiguration
    static class TapConfig {
        @Bean
        BeanPostProcessor wrapDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
                    return (bean instanceof DataSource ds) ? TAP.wrap(ds) : bean;
                }
            };
        }
    }

    // ⚠️ 생성자 주입이 아니라 필드 주입인 이유: 스프링 테스트는 기본적으로 생성자 주입을
    //    «안» 켠다(@TestConstructor 의 기본값이 ANNOTATED 라서). 운영 코드에서는 생성자
    //    주입이 옳지만, 테스트에서는 이게 관례다.
    @Autowired private OrderService orderService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private OrderRepository orderRepository;

    @BeforeEach
    void seed() {
        // ⚠️ 테스트에 @Transactional 을 안 붙였다. 붙이면 저장과 조회가 «같은» 영속성 문맥에
        //    묶여서, 회원이 이미 메모리에 있다는 이유로 N+1 이 «안 일어난다». 그러면
        //    실험이 거짓말을 한다.
        orderRepository.deleteAll();
        memberRepository.deleteAll();
        for (int i = 0; i < ORDER_COUNT; i++) {
            Member member = memberRepository.save(new Member("회원" + i));
            orderRepository.save(new Order(member, "상품" + i));
        }
        TAP.reset(); // 준비하며 나간 SQL 은 세지 않는다
    }

    @Test
    @DisplayName("실험 ① DataSource 를 감싸면 SQL 이 보이나 · ② 몇 번 세이나")
    void measure() {
        List<OrderView> result = orderService.findAll();

        Map<String, Long> byShape = TAP.countByShape();

        System.out.println();
        System.out.println("┌─────────────────────────────────────────────────────────────");
        System.out.println("│ 🔬 실측 결과 — 주문 " + ORDER_COUNT + "건 조회");
        System.out.println("├─────────────────────────────────────────────────────────────");
        System.out.println("│ 돌려받은 주문     : " + result.size() + " 건");
        System.out.println("│ 나간 SQL 총 횟수  : " + TAP.count() + " 번");
        System.out.println("│ SQL 모양 종류     : " + byShape.size() + " 가지");
        System.out.println("├─ 모양별 횟수 ───────────────────────────────────────────────");
        byShape.forEach((sql, n) -> {
            String shown = sql.length() > 68 ? sql.substring(0, 68) + "…" : sql;
            System.out.printf("│ %4d 번  %s%n", n, shown);
        });
        System.out.println("└─────────────────────────────────────────────────────────────");
        System.out.println();

        // 실험 ① — 아무것도 안 보이면 v0 기록 방식이 성립을 안 한다
        assertThat(TAP.count())
                .as("DataSource 를 감쌌는데 SQL 이 하나도 안 보인다면, v0 기록 방식 자체가 틀린 것이다")
                .isGreaterThan(0);

        // 앱이 제대로 돌기는 했는지
        assertThat(result).hasSize(ORDER_COUNT);
        assertThat(result.get(0).memberName()).isNotNull();
    }

    @Test
    @DisplayName("실험 ③ 같은 일을 두 번 하면 같은 숫자가 나오나 (결정론)")
    void countIsStable() {
        orderService.findAll();
        int first = TAP.count();

        TAP.reset();
        orderService.findAll();
        int second = TAP.count();

        System.out.println("🔬 같은 조회 두 번 → " + first + " 번 / " + second + " 번");

        // 🔴 이게 흔들리면 「SQL 횟수」를 오라클로 못 쓴다.
        //    채점기가 매번 다른 답을 내면 채점이 아니다.
        assertThat(second)
                .as("같은 조회인데 SQL 횟수가 다르면 이 값을 판정 기준으로 쓸 수 없다")
                .isEqualTo(first);
    }
}
