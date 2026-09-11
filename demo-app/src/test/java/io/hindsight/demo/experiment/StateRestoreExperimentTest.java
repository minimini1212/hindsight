package io.hindsight.demo.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hindsight.demo.order.OrderView;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 🔬 <b>실측 실험 4.</b> 재생 전에 DB 를 기록 시점으로 «어떻게» 되돌리나.
 *
 * <h2>왜 이걸 재나</h2>
 * 실험 ⑦(2026-09-11)은 「되돌리면 쓰기 요청도 글자까지 같은 답이 나온다」를 보여 줬다.
 * 그런데 그 실험은 H2 메모리라 {@code truncate ... restart identity} 한 줄이면 됐다.
 * <b>진짜 서비스의 DB 는 그렇게 못 되돌린다.</b> 그래서 후보 셋이 남아 있었다.
 *
 * <pre>
 *   ㉠ 가짜 DB    기록된 질의에만 답한다 (설계 §6-2 가 이미 그 장치를 들고 있다)
 *   ㉡ 스냅숏     기록 시점 스냅숏을 떠 두고 그걸로 되돌린다
 *   ㉢ 롤백       트랜잭션을 열고 재생한 뒤 끝에서 되돌린다
 * </pre>
 *
 * <p>그리고 앞선 실험이 <b>답하지 않고 남긴 것</b>이 하나 더 있다 —
 * 「행으로 안 보이는 상태」를 <b>전수로 세지 않았다.</b> 지금 아는 것은 자동 증가 카운터
 * 하나뿐이고, 목록이 다섯을 넘으면 복원 방식 자체를 다시 봐야 한다.
 *
 * <h2>🔴 기대값을 먼저 박아 두지 않는다</h2>
 * 숫자를 출력해서 보고 그다음에 판단한다. 「스냅숏이 되겠지」로 시작하면 안 될 때
 * 원인을 안 찾고 실험을 고치게 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StateRestoreExperimentTest {

    /** 🔴 스프링이 빈을 만들기 «전»에 필요해서 static 이다. 다른 실험과 같은 이유. */
    static final SqlTap SQL = new SqlTap();
    static final HttpTap HTTP = new HttpTap();

    /** 회원 3명 · 주문 3건. 🔴 회원이 서로 달라야 N+1 이 실제로 일어난다. */
    private static final int SEED_COUNT = 3;

    @TestConfiguration
    static class TapConfig {
        @Bean
        BeanPostProcessor wrapDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
                    return (bean instanceof DataSource ds) ? SQL.wrap(ds) : bean;
                }
            };
        }

        @Bean
        HttpTap httpTap() {
            return HTTP;
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private EntityManager em;
    @Autowired private ObjectMapper json;

    // ── 기록 시점 상태를 만드는 단 하나의 절차 ──────────────────────────────

    private void 행과_카운터를_되돌린다() {
        jdbc.execute("set referential_integrity false");
        jdbc.execute("truncate table orders restart identity");
        jdbc.execute("truncate table member restart identity");
        jdbc.execute("set referential_integrity true");
    }

    private void 행을_넣는다() {
        for (int i = 1; i <= SEED_COUNT; i++) {
            jdbc.update("insert into member (name) values (?)", "회원" + i);
            Long memberId = jdbc.queryForObject("select max(id) from member", Long.class);
            jdbc.update("insert into orders (member_id, product) values (?, ?)", memberId, "상품" + i);
        }
    }

    @BeforeEach
    void 기록_시점_상태를_만든다() {
        행과_카운터를_되돌린다();
        행을_넣는다();
        SQL.reset();
        HTTP.reset();
    }

    /**
     * 「다음 id 는 몇 번」을 DB 에게 직접 묻는다. 행을 봐서는 알 수 없는 값이다.
     *
     * <p>🔴 H2 2.x 는 이 숫자를 {@code INFORMATION_SCHEMA.SEQUENCES} 에 <b>안 보여 준다</b>
     * (그 목록은 0개로 나온다 — ⑧-A 탐침). 자동 증가 컬럼의 카운터는
     * {@code COLUMNS.IDENTITY_BASE} 에 있다. 🔴 v1 에서 DB 를 바꾸면 이 자리도 바뀐다.
     */
    private long 다음_주문_id() {
        return jdbc.queryForObject(
                "select identity_base from information_schema.columns "
                        + "where table_schema = 'PUBLIC' and table_name = 'ORDERS' and column_name = 'ID'",
                Long.class);
    }

    // ────────────────────────────────────────────────────────────────────────
    // ⑧-A 「행으로 안 보이는 상태」를 DB 에게 직접 물어 전수로 센다
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 ⑧-A 행 말고 DB 가 들고 있는 상태를 전수로 센다 — 목록이 다섯을 넘나")
    void 행이_아닌_상태를_전수로_센다() {
        System.out.println();
        System.out.println("┌─ 🔬 ⑧-A 「행으로 안 보이는 상태」 전수 세기");
        System.out.println("│");
        System.out.println("│ 세는 방법: 기억에 의존해 나열하지 않고 DB 에게 «무슨 객체를 들고 있나»를 묻는다.");
        System.out.println("│ 분모: INFORMATION_SCHEMA 가 노출하는 목록 전부.");
        System.out.println("│");

        List<String> 목록 = jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'INFORMATION_SCHEMA' order by table_name",
                String.class);
        System.out.println("│ INFORMATION_SCHEMA 목록 " + 목록.size() + " 개:");
        System.out.println("│   " + String.join(", ", 목록));
        System.out.println("│");

        // 그중 «우리 스키마에서 값을 들고 있을 수 있는» 것을 하나씩 센다.
        // 🔴 스키마를 PUBLIC 으로 좁힌다. 안 좁히면 INFORMATION_SCHEMA 자신의 뷰 20개가
        //    「우리 뷰」로 세여서 숫자가 거짓말을 한다.
        long 행_밖의_객체 = 0;
        행_밖의_객체 += 세어서_적는다("시퀀스", "select count(*) from information_schema.sequences");
        행_밖의_객체 += 세어서_적는다("뷰", "select count(*) from information_schema.views where table_schema = 'PUBLIC'");
        행_밖의_객체 += 세어서_적는다("트리거", "select count(*) from information_schema.triggers");
        행_밖의_객체 += 세어서_적는다("루틴(프로시저·함수)", "select count(*) from information_schema.routines");
        행_밖의_객체 += 세어서_적는다("상수", "select count(*) from information_schema.constants");
        행_밖의_객체 += 세어서_적는다("도메인", "select count(*) from information_schema.domains");
        행_밖의_객체 += 세어서_적는다("동의어", "select count(*) from information_schema.synonyms");
        행_밖의_객체 += 세어서_적는다("미결 2단계 커밋", "select count(*) from information_schema.in_doubt");
        System.out.println("│   ─────────────────────────────");
        System.out.println("│   행 밖에서 값을 들고 있는 객체 합계 : " + 행_밖의_객체 + " 개");
        System.out.println("│");

        System.out.println("│ 참고 — 값이 아니라 «모양·연결»인 것들 (복원 대상이 아니다):");
        세어서_적는다("사용자 테이블", "select count(*) from information_schema.tables where table_schema = 'PUBLIC'");
        세어서_적는다("제약", "select count(*) from information_schema.table_constraints where table_schema = 'PUBLIC'");
        세어서_적는다("인덱스", "select count(*) from information_schema.indexes where table_schema = 'PUBLIC'");
        세어서_적는다("열려 있는 세션(커넥션 풀)", "select count(*) from information_schema.sessions");
        세어서_적는다("잠금", "select count(*) from information_schema.locks");
        System.out.println("│");

        long 자동_증가_컬럼 = jdbc.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_schema = 'PUBLIC' and is_identity = 'YES'", Long.class);
        System.out.println("│ 자동 증가 컬럼 " + 자동_증가_컬럼 + " 개가 들고 있는 메타데이터 전부 (탐침):");
        for (Map<String, Object> row : jdbc.queryForList(
                "select * from information_schema.columns "
                        + "where table_schema = 'PUBLIC' and is_identity = 'YES' order by table_name")) {
            System.out.println("│   ── " + row.get("TABLE_NAME") + "." + row.get("COLUMN_NAME"));
            row.forEach((k, v) -> {
                if (v != null && (k.contains("IDENTITY") || k.contains("SEQUENCE") || k.contains("DEFAULT"))) {
                    System.out.println("│      " + k + " = " + v);
                }
            });
        }
        System.out.println("└─");
        System.out.println();

        // 🔴 여기서 「전수」의 뜻: 기억에서 나열한 목록이 아니라, DB 가 스스로 노출하는
        //    35개 목록을 훑어서 «값을 들고 있을 수 있는» 것을 전부 센 결과다.
        assertThat(행_밖의_객체)
                .as("행 밖에서 값을 들고 있는 DB 객체 — 시퀀스·뷰·트리거·루틴·상수·도메인·동의어·미결2PC")
                .isZero();
        assertThat(자동_증가_컬럼)
                .as("되돌려야 하는 카운터는 자동 증가 컬럼마다 하나씩 있다")
                .isEqualTo(2);
        // TODOS 가 정한 재검토 기준: 「복원 목록이 다섯을 넘으면 복원 방식 자체를 다시 본다」.
        // 지금 목록은 둘이다 — 행, 그리고 자동 증가 카운터.
        assertThat(2).as("복원 목록 크기 (행 · 자동 증가 카운터)").isLessThanOrEqualTo(5);
    }

    private long 세어서_적는다(String 이름, String sql) {
        long 개수 = jdbc.queryForObject(sql, Long.class);
        System.out.println("│   " + 이름 + " : " + 개수 + " 개");
        return 개수;
    }

    // ────────────────────────────────────────────────────────────────────────
    // ⑧-B ㉢ 롤백 — 행은 되돌아오나, 카운터는?
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 ⑧-B 트랜잭션 롤백은 행을 되돌리지만 자동 증가 카운터는 되돌리나")
    void 롤백이_되돌리는_범위() {
        long 행수_전 = jdbc.queryForObject("select count(*) from orders", Long.class);
        long 카운터_전 = 다음_주문_id();

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.execute(status -> {
            jdbc.update("insert into orders (member_id, product) values (1, '롤백될주문')");
            status.setRollbackOnly(); // 🔴 되돌린다
            return null;
        });

        long 행수_후 = jdbc.queryForObject("select count(*) from orders", Long.class);
        long 카운터_후 = 다음_주문_id();

        System.out.println();
        System.out.println("┌─ 🔬 ⑧-B ㉢ 트랜잭션 롤백이 되돌리는 범위");
        System.out.println("├─ 주문 행수   : " + 행수_전 + " → " + 행수_후
                + (행수_전 == 행수_후 ? "   ✅ 되돌아왔다" : "   ❌ 안 되돌아왔다"));
        System.out.println("├─ 다음 주문 id : " + 카운터_전 + " → " + 카운터_후
                + (카운터_전 == 카운터_후 ? "   ✅ 되돌아왔다" : "   🔴 안 되돌아왔다"));
        System.out.println("└─");
        System.out.println();

        assertThat(행수_후).as("롤백은 행을 되돌린다").isEqualTo(행수_전);
        assertThat(카운터_후)
                .as("🔴 그런데 카운터는 안 되돌린다 — 롤백 단독은 «부분 복원»이다")
                .isNotEqualTo(카운터_전);
    }

    // ────────────────────────────────────────────────────────────────────────
    // ⑧-C 그 결과 — 롤백으로 감싼 재생을 두 번 하면 답이 갈리나
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 ⑧-C ㉢ 롤백으로 감싼 재생을 두 번 하면 — 같은 답이 나오나")
    void 롤백_재생을_두_번() throws Exception {
        String 원본 = 쓰기_요청을_보낸다();     // 기록에 담기는 원본 응답
        SQL.reset();

        String 첫_재생 = 롤백으로_감싸서_쓰기_요청을_보낸다();
        String 둘째_재생 = 롤백으로_감싸서_쓰기_요청을_보낸다();

        System.out.println();
        System.out.println("┌─ 🔬 ⑧-C ㉢ 롤백으로 감싼 재생을 두 번");
        System.out.println("├─ 원본     : " + 원본);
        System.out.println("├─ 재생 1회  : " + 첫_재생
                + (원본.equals(첫_재생) ? "   ✅ 원본과 같다" : "   🔴 원본과 다르다"));
        System.out.println("├─ 재생 2회  : " + 둘째_재생
                + (첫_재생.equals(둘째_재생) ? "   ✅ 1회와 같다" : "   🔴 1회와 다르다 — 재생이 결정론이 아니다"));
        System.out.println("├─ 주문 행수 : " + jdbc.queryForObject("select count(*) from orders", Long.class) + " 건");
        System.out.println("└─");
        System.out.println();

        // 🔴 이 두 줄이 ㉢ 을 탈락시킨다. 재생마다 답이 달라지면 채점기가 재생 결과를
        //    「패치가 못 고쳤다」로 읽는다 — 패치는 아무 잘못이 없다.
        assertThat(첫_재생).as("롤백은 행만 되돌리므로 id 가 어긋난다").isNotEqualTo(원본);
        assertThat(둘째_재생).as("🔴 재생을 두 번 하면 또 달라진다 — 결정론이 아니다").isNotEqualTo(첫_재생);
    }

    private String 쓰기_요청을_보낸다() throws Exception {
        MvcResult result = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content("{\"memberId\":1,\"product\":\"의자\"}"))
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    /**
     * 재생 한 번을 트랜잭션으로 감싸고 끝에서 되돌린다.
     *
     * <p>⚠️ 앱의 {@code @Transactional} 은 이 트랜잭션에 «참여»한다(REQUIRED).
     * 그래서 여기서 되돌리면 앱이 만든 행도 같이 되돌아간다.
     */
    private String 롤백으로_감싸서_쓰기_요청을_보낸다() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        return tx.execute(status -> {
            try {
                return 쓰기_요청을_보낸다();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                status.setRollbackOnly();
            }
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // ⑧-D ㉡ 스냅숏 — 행과 카운터를 «같이» 되돌리나, 얼마나 걸리나
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 ⑧-D ㉡ 스냅숏(SCRIPT/RUNSCRIPT)이 되돌리는 범위와 비용")
    void 스냅숏이_되돌리는_범위() throws Exception {
        Path 스냅숏 = Files.createTempFile("hindsight-snapshot-", ".sql");
        long 카운터_전 = 다음_주문_id();

        long 뜨는데_걸린_ms = 재고_돌린다(() ->
                jdbc.execute("script to '" + 스냅숏.toString().replace('\\', '/') + "'"));
        long 크기 = Files.size(스냅숏);

        // 기록 «뒤»에 세상이 움직인 것처럼 만든다 — 원본 요청이 행을 하나 더 만든다.
        String 원본 = 쓰기_요청을_보낸다();

        long 되돌리는데_걸린_ms = 재고_돌린다(() -> {
            jdbc.execute("drop all objects");
            jdbc.execute("runscript from '" + 스냅숏.toString().replace('\\', '/') + "'");
        });

        long 카운터_후 = 다음_주문_id();
        long 행수_후 = jdbc.queryForObject("select count(*) from orders", Long.class);
        String 재생 = 쓰기_요청을_보낸다();

        System.out.println();
        System.out.println("┌─ 🔬 ⑧-D ㉡ 스냅숏(H2 SCRIPT/RUNSCRIPT)");
        System.out.println("├─ 스냅숏 크기      : " + 크기 + " 바이트   (회원 " + SEED_COUNT + " · 주문 " + SEED_COUNT + " 건)");
        System.out.println("├─ 뜨는 데          : " + 뜨는데_걸린_ms + " ms");
        System.out.println("├─ 되돌리는 데      : " + 되돌리는데_걸린_ms + " ms");
        System.out.println("├─ 다음 주문 id     : " + 카운터_전 + " → (원본 실행) → " + 카운터_후
                + (카운터_전 == 카운터_후 ? "   ✅ 카운터까지 되돌아왔다" : "   🔴 카운터가 안 되돌아왔다"));
        System.out.println("├─ 복원 후 주문 행수 : " + 행수_후 + " 건");
        System.out.println("├─ 원본 응답        : " + 원본);
        System.out.println("├─ 재생 응답        : " + 재생
                + (원본.equals(재생) ? "   ✅ 글자까지 같다" : "   🔴 다르다"));
        System.out.println("└─");
        System.out.println();

        Files.deleteIfExists(스냅숏);

        assertThat(카운터_후)
                .as("스냅숏은 행과 자동 증가 카운터를 «같이» 되돌린다")
                .isEqualTo(카운터_전);
        assertThat(재생).as("그래서 쓰기 요청 재생이 글자까지 같아진다").isEqualTo(원본);
    }

    private long 재고_돌린다(Runnable 할_일) {
        long 시작 = System.nanoTime();
        할_일.run();
        return (System.nanoTime() - 시작) / 1_000_000;
    }

    // ────────────────────────────────────────────────────────────────────────
    // ⑧-E ㉠ 가짜 DB — 「제대로 고친 패치」에 답을 줄 수 있나
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 ⑧-E ㉠ 가짜 DB 가 「제대로 고친 패치」의 질의에 답할 수 있나")
    void 가짜_DB_는_고친_패치에_답을_못_준다() throws Exception {
        // ① 기록: N+1 이 있는 원본 요청. 가짜 DB 가 가진 «전부»가 이때 잡힌 질의다.
        MvcResult 원본 = mockMvc.perform(get("/api/orders")).andReturn();
        String 원본_응답 = 원본.getResponse().getContentAsString();
        Map<String, Long> 기록된_모양 = SQL.countByShape();
        Set<String> 기록된_질의 = new LinkedHashSet<>(기록된_모양.keySet());
        SQL.reset();

        // ② 패치: N+1 의 정석 수정. 회원을 조인으로 «함께» 읽는다.
        List<OrderView> 고친_결과 = new ArrayList<>();
        new TransactionTemplate(txManager).execute(status -> {
            em.createQuery("select o from io.hindsight.demo.order.Order o join fetch o.member",
                            io.hindsight.demo.order.Order.class)
                    .getResultList()
                    .forEach(o -> 고친_결과.add(
                            new OrderView(o.getId(), o.getProduct(), o.getMember().getName())));
            return null;
        });
        List<String> 패치가_보낸_질의 = List.copyOf(SQL.countByShape().keySet());

        // ⚠️ 줄 순서는 비교 대상이 아니다(원본 질의에도 order by 가 없다). id 로 세워서 값만 본다.
        고친_결과.sort(Comparator.comparing(OrderView::id));
        String 패치_응답 = json.writeValueAsString(고친_결과);

        long 기록에_있던_것 = 패치가_보낸_질의.stream().filter(기록된_질의::contains).count();

        System.out.println();
        System.out.println("┌─ 🔬 ⑧-E ㉠ 가짜 DB 가 고친 패치를 채점할 수 있나");
        System.out.println("│");
        System.out.println("├─ 기록에 담긴 질의 모양 " + 기록된_질의.size() + " 가지 · 실행 "
                + 기록된_모양.values().stream().mapToLong(Long::longValue).sum() + " 번:");
        기록된_모양.forEach((모양, 횟수) -> System.out.println("│     " + 횟수 + "회  " + 한_줄로(모양)));
        System.out.println("│");
        System.out.println("├─ 패치가 보낸 질의 " + 패치가_보낸_질의.size() + " 가지:");
        패치가_보낸_질의.forEach(모양 -> System.out.println("│     " + 한_줄로(모양)));
        System.out.println("│");
        System.out.println("├─ 그중 기록에 있던 것 : " + 기록에_있던_것 + " / " + 패치가_보낸_질의.size()
                + (기록에_있던_것 == 0 ? "   🔴 가짜 DB 는 답할 값을 하나도 못 가지고 있다" : ""));
        System.out.println("│");
        System.out.println("├─ 복원된 «진짜» DB 로 같은 패치를 돌리면:");
        System.out.println("│     원본 응답 : " + 원본_응답);
        System.out.println("│     패치 응답 : " + 패치_응답);
        System.out.println("│     같은가    : " + 원본_응답.equals(패치_응답)
                + (원본_응답.equals(패치_응답) ? "   ✅ 채점이 된다" : "   🔴 채점이 안 된다"));
        System.out.println("└─");
        System.out.println();

        // 🔴 ㉠ 이 탈락하는 자리. 가짜 DB 가 가진 것은 기록된 질의뿐이고,
        //    N+1 의 정석 수정은 «기록에 없는» 질의를 만든다 (설계 §6-2).
        assertThat(기록에_있던_것)
                .as("가짜 DB 가 고친 패치의 질의에 답할 수 있는 비율 (0 / " + 패치가_보낸_질의.size() + ")")
                .isZero();
        // 그리고 복원된 «진짜» DB 는 같은 패치를 채점할 수 있다.
        assertThat(패치_응답)
                .as("복원된 실 DB 는 기록에 없는 질의에도 진짜 값을 돌려준다")
                .isEqualTo(원본_응답);
    }

    private static String 한_줄로(String sql) {
        String 한줄 = sql.replaceAll("\\s+", " ").trim();
        return 한줄.length() <= 96 ? 한줄 : 한줄.substring(0, 93) + "...";
    }
}
