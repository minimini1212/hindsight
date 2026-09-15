package io.hindsight.demo.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * 🔴 <b>N+1 을 고친 «정석» 질의.</b> 회원을 함께 읽어 와서 주문마다 다시 묻지 않는다.
     *
     * <p>이게 여기 있는 이유는 v0 의 채점 고리를 <b>끝까지 보여 주기 위해서</b>다 —
     * 「패치 전에 실패하고, 패치 후에 통과한다」를 실제로 돌리려면 «패치 후»의 코드가 있어야 한다.
     * {@code OrderService} 가 설정 한 줄로 이 길과 원래 길을 오간다.
     *
     * <p>⚠️ 진짜 도구는 이 메서드를 LLM 이 «써 넣는다». 여기서는 사람이 미리 써 둔 것이고,
     * 그래서 이건 <b>「고리가 도는가」를 보는 장치이지 「LLM 이 고칠 수 있는가」의 증거가 아니다.</b>
     */
    @Query("select o from Order o join fetch o.member")
    List<Order> findAllWithMember();
}
