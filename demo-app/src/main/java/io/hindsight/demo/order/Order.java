package io.hindsight.demo.order;

import io.hindsight.demo.member.Member;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 주문.
 *
 * <h2>🔴 여기에 N+1 의 씨앗이 있다</h2>
 * {@code member} 를 {@link FetchType#LAZY} 로 가져온다. 즉 주문을 읽을 때 회원은 «아직»
 * 안 읽는다. 나중에 {@code order.getMember().getName()} 을 부르는 그 순간에야
 * 회원을 조회하는 SQL 이 한 번 나간다.
 *
 * <p>주문이 200건이면 그 순간이 200번 온다. 주문 목록 조회 1번 + 회원 조회 200번 = 201번.
 * <b>이게 N+1 이다.</b>
 *
 * <p>⚠️ LAZY 자체는 잘못이 아니다. 오히려 기본으로 권장되는 설정이다.
 * 잘못은 <b>목록을 돌면서 매 건마다 연관을 건드리는 코드</b> 쪽에 있다
 * ({@link OrderService#findAll()}).
 */
@Entity
@Table(name = "orders") // ORDER 는 SQL 예약어라 테이블 이름을 바꿔야 한다
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    private String product;

    protected Order() {} // JPA 가 쓰는 생성자

    public Order(Member member, String product) {
        this.member = member;
        this.product = product;
    }

    public Long getId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public String getProduct() {
        return product;
    }
}
