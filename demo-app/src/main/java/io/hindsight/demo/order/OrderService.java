package io.hindsight.demo.order;

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

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
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
        return orderRepository.findAll().stream()          // ← SQL 1번
                .map(o -> new OrderView(
                        o.getId(),
                        o.getProduct(),
                        o.getMember().getName()))          // ← 주문 건수만큼 SQL 이 더 나간다
                .toList();
    }
}
