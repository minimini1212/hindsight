package io.hindsight.recorder.jdbc;

import io.hindsight.recorder.Recorder;
import io.hindsight.recorder.ReentryGuard;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * 앱이 보내는 SQL 을 지나가는 길에 기록하는 {@link DataSource} 껍데기.
 *
 * <h2>어디에 씌우나</h2>
 * 앱이 DB 를 쓰는 길은 언제나 {@code DataSource → Connection → Statement → 실행} 이다.
 * 길목마다 얇은 껍데기를 씌워서 <b>지나가는 것을 보기만 하고 그대로 통과</b>시킨다.
 *
 * <pre>
 *   앱 ──▶ [DataSource 껍데기] ──▶ 진짜 DataSource
 *              │ getConnection() 이 돌려준 Connection 도 껍데기로 바꿔치기
 *              ▼                    (안 하면 그 뒤로 아무것도 안 보인다)
 *          [Connection 껍데기] ──▶ 진짜 Connection
 *              │ prepareStatement() 가 돌려준 Statement 도 껍데기로
 *              ▼
 *          [Statement 껍데기] ──▶ 진짜 Statement
 *              │ setXxx() 로 값이 들어오면 «모아 두고»
 *              │ execute() 가 불리면 «시간을 재서 기록»한다
 *              ▼
 *             DB
 * </pre>
 *
 * <h2>왜 클래스를 직접 구현하지 않고 동적 프록시(실행 중에 만들어지는 대역)를 쓰나</h2>
 * {@code DataSource}·{@code Connection}·{@code PreparedStatement} 를 손으로 구현하면
 * 메서드가 <b>200개 넘게</b> 나온다. 관심 있는 건 서너 개뿐인데 나머지도 전부 「그냥
 * 넘겨주는」 코드를 써야 하고, 자바나 드라이버가 메서드를 하나 추가하면 컴파일이 깨진다.
 * {@link Proxy} 는 「모든 메서드 호출」을 한 자리에서 받으므로 그 문제가 없다.
 *
 * <h2>🔴 우리가 앱의 동작을 바꾸지 않는다는 것</h2>
 * <ul>
 *   <li>예외를 <b>원래 것 그대로</b> 다시 던진다. 반사 호출은 예외를
 *       {@link InvocationTargetException} 으로 감싸는데, 그걸 안 벗기면
 *       <b>앱이 받는 예외 종류가 우리 때문에 바뀐다</b></li>
 *   <li>기록에 실패해도 질의는 그대로 돈다. 우리 실패가 앱의 실패가 되면 안 된다</li>
 *   <li>🔴 기록기 자신이 낸 질의는 기록하지 않는다 — 안 그러면 기록하는 일이
 *       다시 기록되어 <b>멈추지 않는다</b></li>
 * </ul>
 *
 * <h2>🔴 커넥션 풀 때문에 두 번 세이지 않나</h2>
 * HikariCP 같은 풀은 {@code Connection} 에 자기 껍데기를 한 겹 더 씌운다. 그래서 잘못
 * 감싸면 같은 질의가 두 번 세일 수 있다. <b>이 자리(풀 «바깥»)에서 감싸면 한 번만 세인다</b> —
 * 2026-09-11 실측으로 확인했다(주문 20건 → 21번, 두 번 돌려도 21/21).
 */
public final class RecordingDataSource {

    private RecordingDataSource() {}

    /** 진짜 {@link DataSource} 를 껍데기로 감싼다. 앱은 차이를 모른다. */
    public static DataSource wrap(DataSource real, Recorder recorder) {
        return proxy(DataSource.class, real, (target, method, args) -> {
            Object result = call(target, method, args);
            return (result instanceof Connection c) ? wrapConnection(c, recorder) : result;
        });
    }

    private static Connection wrapConnection(Connection real, Recorder recorder) {
        return proxy(Connection.class, real, (target, method, args) -> {
            Object result = call(target, method, args);
            if (result instanceof PreparedStatement ps) {
                // PreparedStatement 는 «만들 때» SQL 을 받고 «나중에» 실행한다.
                // 실행 시점에는 SQL 을 알 수 없으므로 지금 붙잡아 둔다.
                String sql = (args != null && args.length > 0 && args[0] instanceof String s) ? s : null;
                return wrapStatement(PreparedStatement.class, ps, sql, recorder);
            }
            if (result instanceof Statement st) {
                // 일반 Statement 는 실행할 때 SQL 을 인자로 받는다. 그때 잡는다.
                return wrapStatement(Statement.class, st, null, recorder);
            }
            return result;
        });
    }

    private static <T extends Statement> T wrapStatement(Class<T> type, T real, String preparedSql, Recorder recorder) {
        // 🔴 자리 번호(1부터)로 정렬해 둔다. setXxx 가 부르는 순서는 앱 마음이라,
        //    받은 순서대로 담으면 재생이 «다른 순서로» 값을 꽂는다.
        TreeMap<Integer, Object> params = new TreeMap<>();

        return proxy(type, real, (target, method, args) -> {
            String name = method.getName();

            if (name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer index) {
                params.put(index, args[1]);
                return call(target, method, args);
            }
            if (name.equals("clearParameters")) {
                params.clear();
                return call(target, method, args);
            }
            if (!name.startsWith("execute")) {
                return call(target, method, args);
            }

            String sql = preparedSql;
            if (sql == null && args != null && args.length > 0 && args[0] instanceof String s) {
                sql = s;
            }

            long startedNanos = System.nanoTime();
            Object result;
            try {
                result = call(target, method, args);
            } catch (Throwable failure) {
                // 🔴 실패한 질의도 기록한다. 사고의 원인이 바로 이것일 때가 많고,
                //    「성공한 것만 기록」하면 원인 없는 기록이 남는다.
                note(recorder, sql, params, null, elapsedMs(startedNanos));
                throw failure;
            }
            note(recorder, sql, params, rowCountOf(result), elapsedMs(startedNanos));
            return result;
        });
    }

    private static void note(Recorder recorder, String sql, TreeMap<Integer, Object> params,
                             Integer rowCount, long tookMs) {
        // 🔴 기록기 자신이 낸 질의라면 기록하지 않는다.
        if (ReentryGuard.inside()) {
            return;
        }
        try {
            recorder.recordSql(
                    sql, // null 이면 「SQL 을 못 알아냈다」— 지어내지 않는다
                    List.copyOf(new ArrayList<>(params.values())),
                    rowCount,
                    tookMs);
        } catch (Throwable ourFailure) {
            // 🔴 기록 실패가 앱의 DB 호출 실패가 되면 안 된다.
        }
    }

    /**
     * 몇 행이 오갔나.
     *
     * <p>🔴 {@link ResultSet} 을 돌려주는 질의는 <b>여기서 셀 수 없다.</b> 세려면 우리가
     * 커서를 움직여야 하는데, 그러면 <b>앱이 읽을 행이 사라진다</b> — 관측이 관측 대상을
     * 망가뜨리는 그 모양이다. 그래서 {@code null} 로 둔다. 「안 봤다」이지 「0행」이 아니다.
     */
    private static Integer rowCountOf(Object result) {
        if (result instanceof Integer updated) {
            return updated; // UPDATE·DELETE·INSERT 가 바꾼 행 수
        }
        return null;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    // ── 배관 ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, Handler handler) {
        return (T) Proxy.newProxyInstance(
                RecordingDataSource.class.getClassLoader(),
                new Class<?>[]{type},
                (InvocationHandler) (p, method, args) -> handler.handle(target, method, args));
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            // 🔴 이 한 줄이 없으면 앱이 받는 예외가 우리 때문에 다른 것으로 바뀐다.
            throw e.getCause();
        }
    }

    @FunctionalInterface
    private interface Handler {
        Object handle(Object target, Method method, Object[] args) throws Throwable;
    }
}
