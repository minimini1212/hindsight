package io.hindsight.demo.order;

import io.hindsight.demo.member.Member;
import io.hindsight.demo.member.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 주문 조회.
 *
 * <h2>🔴 이 클래스가 이 프로젝트의 첫 번째 시험 문제다</h2>
 * {@link #findAll()} 은 주문 목록을 한 번 읽은 뒤, <b>주문마다 회원 이름을 꺼낸다.</b>
 * 회원은 지연 로딩({@link Order} 참조)이라 그때마다 SQL 이 한 번씩 더 나간다.
 *
 * <p>Hindsight 가 이걸 잡아내야 한다. 그런데 <b>예외가 안 난다.</b> 응답도 정상이다.
 * 느려질 뿐이다. 그래서 「예외가 났나」로는 못 잡고, <b>「SQL 이 몇 번 나갔나」로 잡는다</b>
 * — 그게 설계 §6-1 의 오라클이다.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final MemberRepository memberRepository;

    /**
     * 🔴 N+1 을 고친 길로 갈 것인가. 기본은 «안 고친» 길이다 — 이 앱은 버그를 «들고 있는» 것이
     * 제 일이고, 고친 길은 채점 고리를 끝까지 돌려 보려고 둔 것이다.
     */
    @org.springframework.beans.factory.annotation.Value("${demo.n-plus-one-fixed:false}")
    private boolean nPlusOneFixed;

    /**
     * 시험이 「패치」를 흉내 내는 자리. 🔴 <b>운영 코드가 부르는 길은 아니다</b> —
     * 진짜 도구에서는 LLM 이 «소스를 고쳐» 이 자리를 만든다.
     */
    public void setNPlusOneFixedForTest(boolean fixed) {
        this.nPlusOneFixed = fixed;
    }

    public OrderService(OrderRepository orderRepository, MemberRepository memberRepository) {
        this.orderRepository = orderRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * 주문을 만든다.
     *
     * <p>🔬 이 메서드는 「본문이 있는 요청」을 재려고 만들었다. 그리고 재생 실험에서
     * <b>상태를 바꾸는 요청은 재생해도 같은 답이 안 나온다</b>는 것을 보여주는 자리이기도 하다.
     */
    @Transactional
    public OrderView create(CreateOrderRequest request) {
        Member member = memberRepository.findById(request.memberId())
                .orElseThrow(() -> new IllegalArgumentException("없는 회원: " + request.memberId()));
        Order saved = orderRepository.save(new Order(member, request.product()));
        return new OrderView(saved.getId(), saved.getProduct(), member.getName());
    }

    /**
     * 🐛 <b>일부러 심은 N+1.</b>
     *
     * <p>고치는 방법은 {@code join fetch} 로 회원을 함께 읽어 오는 것이다.
     * 🔴 그런데 그렇게 고치면 <b>SQL 의 모양이 바뀐다</b> — 그게 설계 §6-2 의 {@code DIVERGED} 이고,
     * 이 프로젝트에서 가장 어려운 문제다. 지금은 고치지 않는다.
     */
    @Transactional(readOnly = true)
    public List<OrderView> findAll() {
        // 🔴 설정 한 줄로 「버그가 있는 길」과 「고친 길」을 오간다.
        //    이게 있는 이유는 v0 의 채점 고리를 «끝까지» 돌려 보기 위해서다 —
        //    「패치 전에 실패하고 패치 후에 통과한다」를 확인하려면 «패치 후»의 코드가 있어야 한다.
        //    ⚠️ 진짜 도구에서는 LLM 이 그 코드를 써 넣는다. 여기서는 사람이 미리 써 둔 것이라,
        //       이건 「고리가 도는가」를 보는 장치이지 「LLM 이 고칠 수 있는가」의 증거가 아니다.
        List<Order> orders = nPlusOneFixed
                ? orderRepository.findAllWithMember()      // 조인 1번으로 끝
                : orderRepository.findAll();               // ← SQL 1번, 그리고 아래에서 N번 더

        return orders.stream()
                .map(o -> new OrderView(
                        o.getId(),
                        o.getProduct(),
                        o.getMember().getName()))          // 고치기 «전»에는 주문 건수만큼 SQL 이 더 나간다
                .toList();
    }
}
