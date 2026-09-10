package io.hindsight.demo.experiment;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 앱이 보내는 SQL 을 «중간에서» 세어 보는 도구.
 *
 * <h2>무엇을 하나</h2>
 * 앱이 DB 를 쓰는 길은 언제나 {@code DataSource → Connection → Statement → 실행} 이다.
 * 이 도구는 그 길목마다 얇은 껍데기를 씌워서, <b>지나가는 것을 세기만 하고 그대로 통과</b>시킨다.
 *
 * <pre>
 *   앱 ──▶ [DataSource 껍데기] ──▶ 진짜 DataSource
 *              │ getConnection() 이 돌려주는 Connection 도 껍데기로 바꿔치기
 *              ▼
 *          [Connection 껍데기] ──▶ 진짜 Connection
 *              │ prepareStatement() 가 돌려주는 Statement 도 껍데기로
 *              ▼
 *          [Statement 껍데기] ──▶ 진짜 Statement
 *              │ execute() 가 불릴 때 «센다»
 *              ▼
 *             DB
 * </pre>
 *
 * <h2>왜 클래스를 안 만들고 «동적 프록시»를 쓰나</h2>
 * {@code DataSource}·{@code Connection}·{@code PreparedStatement} 를 손으로 구현하면
 * 메서드가 <b>200개 넘게</b> 나온다. 우리가 관심 있는 건 그중 서너 개뿐인데 나머지도 전부
 * 「그냥 넘겨주는」 코드를 써야 한다.
 *
 * <p>{@link Proxy}(자바가 기본으로 주는 기능)를 쓰면 <b>「모든 메서드 호출」을 한 자리에서</b>
 * 받는다. 관심 있는 것만 처리하고 나머지는 그대로 넘긴다. 그래서 60줄로 끝난다.
 *
 * <h2>🔴 이 실험이 답해야 하는 질문</h2>
 * <ol>
 *   <li>이 자리에서 앱이 보내는 SQL 이 <b>전부 보이나</b></li>
 *   <li>한 번 실행한 SQL 이 <b>한 번만 세이나</b> — 커넥션 풀(HikariCP)이 자기 껍데기를
 *       한 겹 더 씌우기 때문에, 잘못 잡으면 같은 질의가 두 번 세일 수 있다</li>
 * </ol>
 */
public final class SqlTap {

    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

    /** 지금까지 실행된 SQL 을 순서대로. */
    public List<String> executed() {
        return List.copyOf(executed);
    }

    public int count() {
        return executed.size();
    }

    public void reset() {
        executed.clear();
    }

    /** 같은 모양의 SQL 이 몇 번씩 나왔는지. N+1 은 여기서 「한 놈이 200번」으로 보인다. */
    public java.util.Map<String, Long> countByShape() {
        return executed().stream().collect(java.util.stream.Collectors.groupingBy(
                s -> s.replaceAll("\\s+", " ").trim(),
                java.util.LinkedHashMap::new,
                java.util.stream.Collectors.counting()));
    }

    // ────────────────────────────────────────────────────────────────────────

    /** 진짜 {@link DataSource} 를 껍데기로 감싼다. 앱은 차이를 모른다. */
    public DataSource wrap(DataSource real) {
        return proxy(DataSource.class, real, (target, method, args) -> {
            Object result = call(target, method, args);
            // getConnection() 이 돌려준 Connection 도 껍데기로 바꿔서 내보낸다.
            // 이걸 안 하면 그 뒤로는 아무것도 못 본다.
            return (result instanceof Connection c) ? wrapConnection(c) : result;
        });
    }

    private Connection wrapConnection(Connection real) {
        return proxy(Connection.class, real, (target, method, args) -> {
            Object result = call(target, method, args);
            if (result instanceof PreparedStatement ps) {
                // prepareStatement("select ...") 의 첫 인자가 SQL 문자열이다.
                // PreparedStatement 는 «만들 때» SQL 을 받고 «나중에» 실행하므로,
                // 실행 시점에는 SQL 을 알 수 없다. 그래서 지금 붙잡아 둔다.
                String sql = (args != null && args.length > 0 && args[0] instanceof String s) ? s : "?";
                return wrapStatement(PreparedStatement.class, ps, sql);
            }
            if (result instanceof Statement st) {
                // 일반 Statement 는 실행할 때 SQL 을 인자로 받는다. 그때 잡는다.
                return wrapStatement(Statement.class, st, null);
            }
            return result;
        });
    }

    private <T extends Statement> T wrapStatement(Class<T> type, T real, String preparedSql) {
        return proxy(type, real, (target, method, args) -> {
            if (method.getName().startsWith("execute")) {
                String sql = preparedSql;
                if (sql == null && args != null && args.length > 0 && args[0] instanceof String s) {
                    sql = s;
                }
                executed.add(sql == null ? "(알 수 없음)" : sql);
            }
            return call(target, method, args);
        });
    }

    // ── 배관 ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, Handler handler) {
        return (T) Proxy.newProxyInstance(
                SqlTap.class.getClassLoader(),
                new Class<?>[]{type},
                (InvocationHandler) (p, method, args) -> handler.handle(target, method, args));
    }

    /**
     * 진짜 객체에 그대로 넘긴다.
     *
     * <p>🔴 {@code InvocationTargetException} 을 벗겨서 원래 예외를 던지는 게 중요하다.
     * 안 그러면 앱이 받는 예외가 <b>우리 때문에 다른 것으로 바뀐다</b> — 관측 도구가
     * 관측 대상의 동작을 바꾸는 것이고, 이 프로젝트가 절대 하면 안 되는 일이다.
     */
    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(Object target, Method method, Object[] args) throws Throwable;
    }
}
