package io.hindsight.demo.order;

import io.hindsight.demo.member.Member;
import io.hindsight.demo.member.MemberRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 🔴 <b>이 앱이 「원래 갖고 있던」 테스트다. Hindsight 를 모른다.</b>
 *
 * <h2>왜 이게 따로 있어야 하나</h2>
 * 채점 네 겹의 ㉢ 은 <b>「패치가 다른 것을 깨뜨리지 않았나」</b>를 본다. 그걸 보려면
 * <b>깨질 수 있는 테스트가 실제로 있어야 한다.</b> 2026-09-16 까지 이 앱의 시험 12개는
 * 전부 Hindsight 실험이었고, 그래서 ㉢ 은 <b>돌릴 것이 없는 겹</b>이었다.
 *
 * <p>🔴 <b>여기에 Hindsight 를 import 하면 안 된다.</b> ㉢ 이 「도구가 스스로를 검사하는 것」이
 * 되어 버리고, 그러면 패치가 앱을 깨뜨려도 초록불이 뜬다. 이 파일이 평범한 앱 테스트로
 * 남아 있는 것 자체가 ㉢ 의 전제다.
 *
 * <h2>무엇을 단언하나 — 「몇 번 물었나」가 아니라 「무엇이 나왔나」</h2>
 * N+1 은 <b>답을 안 바꾼다.</b> 느려질 뿐이다. 그래서 이 테스트는 고친 길과 안 고친 길에서
 * <b>똑같이 통과해야 한다</b> — 그게 「패치가 답을 바꾸지 않았다」의 뜻이고, ㉢ 이 재는 것이다.
 */
@SpringBootTest
@DisplayName("주문 조회 — 앱 자신의 시험")
class OrderServiceTest {

    /**
     * 🔴 <b>자기 DB 를 쓴다.</b> 기본 설정은 {@code jdbc:h2:mem:demo} 하나를 모두가 나눠 쓰는데,
     * 거기에 {@code ddl-auto: create-drop} 과 {@code @DirtiesContext} 가 겹치면 이렇게 된다.
     *
     * <pre>
     *   Hindsight 실험이 끝난다 → @DirtiesContext 가 스프링 문맥을 닫는다
     *     → create-drop 이 «공유» 메모리 DB 의 테이블을 전부 떨어뜨린다
     *       → 🔴 먼저 만들어져 캐시돼 있던 이 시험의 문맥은 빈 DB 를 보게 된다
     *         → "Table ORDERS not found (this database is empty)"
     * </pre>
     *
     * <p>2026-09-16 에 이 시험을 만들자마자 그렇게 깨졌다. 혼자 돌리면 통과하고
     * 전체를 돌리면 실패해서, <b>순서에 따라 달라지는</b> 모양이었다.
     *
     * <p>⚠️ 그리고 자기 DB 를 쓰는 편이 ㉢ 에도 맞다 — ㉢ 은 이 시험을 «재생 도중»에 돌리는데,
     * 같은 DB 를 쓰면 여기서 {@code deleteAll()} 한 것이 <b>되돌려 놓은 재생 상태를 지운다.</b>
     */
    @DynamicPropertySource
    static void 자기_디비를_쓴다(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:order-service-test;DB_CLOSE_DELAY=-1");
    }

    @Autowired OrderService orderService;
    @Autowired OrderRepository orderRepository;
    @Autowired MemberRepository memberRepository;

    @BeforeEach
    void 비운다() {
        orderRepository.deleteAll();
        memberRepository.deleteAll();
    }

    @Test
    @DisplayName("주문 목록에 회원 이름이 붙어 나온다")
    void 목록에_회원_이름이_붙는다() {
        Member 회원 = memberRepository.save(new Member("김하나"));
        orderRepository.save(new Order(회원, "키보드"));

        List<OrderView> 목록 = orderService.findAll();

        assertThat(목록).hasSize(1);
        assertThat(목록.getFirst().product()).isEqualTo("키보드");
        assertThat(목록.getFirst().memberName()).isEqualTo("김하나");
    }

    @Test
    @DisplayName("🔴 회원이 여럿이어도 각 주문에 «자기» 회원 이름이 붙는다")
    void 회원이_섞이지_않는다() {
        // 🔴 이게 ㉢ 의 핵심이다. N+1 을 join fetch 로 고칠 때 조인을 잘못 쓰면
        //    행이 곱해지거나 이름이 엇갈리는데, 「질의를 몇 번 했나」로는 그걸 못 잡는다.
        for (int i = 0; i < 5; i++) {
            Member 회원 = memberRepository.save(new Member("회원" + i));
            orderRepository.save(new Order(회원, "물건" + i));
        }

        List<OrderView> 목록 = orderService.findAll();

        assertThat(목록).hasSize(5);
        assertThat(목록).allSatisfy(주문 -> {
            String 번호 = 주문.product().replace("물건", "");
            assertThat(주문.memberName())
                    .as("물건%s 를 시킨 사람은 회원%s 여야 한다".formatted(번호, 번호))
                    .isEqualTo("회원" + 번호);
        });
    }

    @Test
    @DisplayName("주문이 없으면 빈 목록이다 — null 이 아니다")
    void 주문이_없으면_빈_목록() {
        assertThat(orderService.findAll())
                .as("🔴 「없다」를 null 로 돌려주면 부르는 쪽이 매번 확인해야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("주문을 만들면 회원 이름이 붙은 채로 돌아온다")
    void 주문을_만든다() {
        Member 회원 = memberRepository.save(new Member("이둘"));

        OrderView 만든것 = orderService.create(new CreateOrderRequest(회원.getId(), "모니터"));

        assertThat(만든것.id()).isNotNull();
        assertThat(만든것.product()).isEqualTo("모니터");
        assertThat(만든것.memberName()).isEqualTo("이둘");
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 없는 회원으로 주문하면 «조용히 넘어가지 않고» 거절한다")
    void 없는_회원으로는_주문이_안_된다() {
        assertThatThrownBy(() -> orderService.create(new CreateOrderRequest(-1L, "없는물건")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("없는 회원");

        assertThat(orderRepository.count())
                .as("거절했으면 아무것도 안 남아야 한다")
                .isZero();
    }
}
