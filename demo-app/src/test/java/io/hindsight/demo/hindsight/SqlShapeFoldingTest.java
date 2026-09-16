package io.hindsight.demo.hindsight;

import io.hindsight.demo.experiment.SqlTap;
import io.hindsight.demo.member.Member;
import io.hindsight.demo.member.MemberRepository;
import io.hindsight.demo.order.Order;
import io.hindsight.demo.order.OrderRepository;
import io.hindsight.model.SqlShapes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔬 실험 ⑰ — <b>질의 「모양」 접기가 진짜 앱에서 얼마나 듣나.</b>
 *
 * <h2>⚠️ 이 시험은 2026-09-16 에 «뒤집혔다»</h2>
 * 처음 쓸 때는 <b>「얼마나 나쁜가」를 숫자로 못 박는</b> 시험이었다 —
 * 같은 코드 한 줄이 모양 <b>5가지</b>를 만들고, 5번 반복된 것이 오라클에는 <b>1번</b>으로 보였다.
 * 그날 접기를 고쳤고, 그래서 지금은 <b>「제대로 접히는가」</b>를 지킨다.
 * 🔴 옛 숫자를 주석에 남겨 둔다 — 고친 것이 무엇이었는지가 사라지면 안 된다.
 *
 * <h2>왜 이걸 재나</h2>
 * 이 도구는 N+1 을 <b>「같은 모양의 질의가 몇 번 반복됐나」</b>로 잡는다. 그 「모양」은
 * {@code SqlShapes.normalize} 가 만든다 — 따옴표 안의 글자와 숫자를 {@code ?} 로 바꾼다.
 *
 * <p>🔴 그런데 {@code IN} 절은 <b>인자 개수가 질의문 자체를 바꾼다.</b>
 * <pre>
 *   where id in (?,?)      ← 2개짜리
 *   where id in (?,?,?)    ← 3개짜리   🔴 «다른 모양»으로 세인다
 * </pre>
 *
 * <p>같은 코드 한 줄이 내는 질의인데 모양이 갈라지면 두 가지가 동시에 망가진다.
 * <ol>
 *   <li><b>반복이 안 세인다</b> — 20번 반복된 것이 「1번씩 20가지」로 보인다</li>
 *   <li><b>요약층이 넘친다</b> — 모양 목록이 인자 개수만큼 늘어난다</li>
 * </ol>
 *
 * <h2>🔴 고치면서 판 번호가 올라갔다 (1 → 2)</h2>
 * {@code Summary.SqlShape.sqlHash} 는 <b>파일에 저장되는 값</b>이다. 규칙이 바뀌면
 * 1판 파일에 적힌 해시를 지금 코드는 <b>절대 만들어 내지 못한다</b> —
 * 그걸 안 드러내면 「모름」이 「없음」이 된다.
 */
@SpringBootTest
@DisplayName("🔬 실험 ⑰ — 질의 모양 접기가 진짜 앱에서 얼마나 듣나")
class SqlShapeFoldingTest {

    /** 🔴 스프링이 빈을 만들기 «전»에 필요해서 static 이다 — 실험 ⑦ 과 같은 방식이다. */
    static final SqlTap TAP = new SqlTap();

    /** 스프링이 만든 {@code DataSource} 를 우리 껍데기로 바꿔치기한다. */
    @org.springframework.boot.test.context.TestConfiguration
    static class TapConfig {
        @org.springframework.context.annotation.Bean
        org.springframework.beans.factory.config.BeanPostProcessor wrapDataSource() {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return (bean instanceof javax.sql.DataSource ds) ? TAP.wrap(ds) : bean;
                }
            };
        }
    }

    @DynamicPropertySource
    static void 자기_디비를_쓴다(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:shape-folding;DB_CLOSE_DELAY=-1");
    }

    @Autowired OrderRepository orderRepository;
    @Autowired MemberRepository memberRepository;

    @BeforeEach
    void 채운다() {
        orderRepository.deleteAll();
        memberRepository.deleteAll();
        for (int i = 0; i < 10; i++) {
            Member 회원 = memberRepository.save(new Member("회원" + i));
            orderRepository.save(new Order(회원, "물건" + i));
        }
    }

    @Test
    @DisplayName("✅ 같은 코드 한 줄이면 IN 인자 개수가 달라도 «같은 모양»이다")
    void IN_절은_개수마다_모양이_갈라진다() {
        List<Long> 아이디 = orderRepository.findAll().stream().map(Order::getId).toList();

        Set<String> 모양들 = new LinkedHashSet<>();

        // 🔴 같은 «한 줄»이다. 인자 개수만 다르다.
        for (int n = 2; n <= 6; n++) {
            TAP.reset();
            orderRepository.findAllById(아이디.subList(0, n));
            TAP.executed().stream()
                    .filter(sql -> sql.toLowerCase().contains(" in ("))
                    .map(sql -> SqlShapes.of(sql).normalized())
                    .forEach(모양들::add);
        }

        System.out.println();
        System.out.println("── 실험 ⑰ IN 절의 모양 ──");
        모양들.forEach(m -> System.out.println("  " + 짧게(m)));
        System.out.println("  🔴 같은 코드 한 줄이 만든 «서로 다른 모양»: " + 모양들.size() + "가지");

        assertThat(모양들.size())
                .as("🔴 2026-09-16 «이전»에는 여기가 5 였다 — 인자 개수마다 갈라졌다. "
                        + "접기를 고쳐서 1 이 됐고, 그게 이 시험이 지키는 사실이다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("✅ 그래서 N+1 의 반복이 «제대로» 세인다")
    void 반복이_안_세인다() {
        List<Long> 아이디 = orderRepository.findAll().stream().map(Order::getId).toList();

        TAP.reset();
        // 같은 코드가 5번 도는데, 인자 개수가 매번 다르다 — 페이지 크기가 들쭉날쭉한 상황이다.
        for (int n = 2; n <= 6; n++) {
            orderRepository.findAllById(아이디.subList(0, n));
        }

        Map<String, Integer> 모양별횟수 = new LinkedHashMap<>();
        TAP.executed().stream()
                .filter(sql -> sql.toLowerCase().contains(" in ("))
                .forEach(sql -> 모양별횟수.merge(SqlShapes.of(sql).hash(), 1, Integer::sum));

        int 가장많이반복된것 = 모양별횟수.values().stream().mapToInt(Integer::intValue).max().orElse(0);

        System.out.println();
        System.out.println("── 실험 ⑰-② 반복이 얼마나 보이나 ──");
        System.out.println("  실제로 같은 코드가 돈 횟수: 5번");
        System.out.println("  🔴 오라클이 보는 「가장 많이 반복된 모양」: " + 가장많이반복된것 + "번");

        assertThat(가장많이반복된것)
                .as("🔴 2026-09-16 «이전»에는 여기가 1 이었다 — 5번 돈 것이 1번으로 보여서 "
                        + "문턱(2)을 못 넘고 N+1 을 통째로 놓쳤다. 이제 «실제로 돈 횟수»가 보인다")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("✅ IN 이 없는 질의는 값이 달라도 «같은 모양»이다 — 접기 자체는 듣는다")
    void IN_이_없으면_잘_접힌다() {
        TAP.reset();
        for (int i = 0; i < 5; i++) {
            memberRepository.findById((long) i);
        }

        Set<String> 모양들 = new LinkedHashSet<>();
        TAP.executed().stream()
                .filter(sql -> sql.toLowerCase().contains("member"))
                .map(sql -> SqlShapes.of(sql).hash())
                .forEach(모양들::add);

        System.out.println();
        System.out.println("── 실험 ⑰-③ IN 이 없을 때 ──");
        System.out.println("  값 5가지 → 모양 " + 모양들.size() + "가지");

        assertThat(모양들)
                .as("여기가 1 이 아니면 접기가 아예 안 듣는 것이다 — 그건 훨씬 큰 문제다")
                .hasSize(1);
    }

    private static String 짧게(String sql) {
        return sql.length() <= 90 ? sql : sql.substring(0, 90) + "…";
    }
}
