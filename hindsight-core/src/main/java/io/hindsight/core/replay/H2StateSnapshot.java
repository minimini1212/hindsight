package io.hindsight.core.replay;

import io.hindsight.model.ReplayInfo;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * H2 로 상태를 떠 두고 되돌린다. v0 의 재생 환경이 H2 라서 이것 하나면 된다.
 *
 * <h2>어떻게 뜨고 어떻게 되돌리나</h2>
 * <pre>
 *   뜰 때      SCRIPT TO '&lt;파일&gt;'          스키마 + 데이터 + 카운터가 SQL 로 떨어진다
 *   되돌릴 때  DROP ALL OBJECTS
 *              RUNSCRIPT FROM '&lt;파일&gt;'      전부 다시 만든다
 * </pre>
 *
 * 🔴 <b>{@code DROP ALL OBJECTS} 가 «반드시» 앞에 와야 한다.</b> 없이 {@code RUNSCRIPT} 만
 * 돌리면 기존 행 위에 덮어써지는 게 아니라 <b>행이 더 늘어난다</b> — 되돌린 게 아니라
 * 두 배가 된다. 그리고 그건 눈에 잘 안 띈다.
 *
 * <h2>🔴 「다음 id 는 몇 번」은 행을 봐서는 알 수 없다</h2>
 * 행을 같은 내용·같은 개수로 복원해도, DB 가 들고 있는 그 숫자는 안 돌아간다.
 * 그러면 새로 만들어지는 행의 {@code id} 가 어긋나고, 기록에 담긴 {@code memberId: 1} 이
 * 가리키는 것이 사라져서 <b>재생이 예외로 죽는다.</b>
 *
 * <p>🔴 <b>읽는 자리가 DB 마다 다르다.</b> H2 2.x 는 이 숫자를
 * {@code INFORMATION_SCHEMA.SEQUENCES} 에 <b>안 보여 준다</b>(그 목록은 0개로 나온다 —
 * 2026-09-11 실측). 자동 증가 컬럼의 카운터는 {@code COLUMNS.IDENTITY_BASE} 에 있다.
 * PostgreSQL 은 시퀀스, MySQL 은 {@code AUTO_INCREMENT} 속성이다.
 * <b>v1 에서 DB 가 바뀌면 이 클래스가 하나 더 생긴다.</b>
 */
public final class H2StateSnapshot implements StateSnapshot {

    private final DataSource dataSource;

    public H2StateSnapshot(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Handle take() {
        try {
            Path file = Files.createTempFile("hindsight-snapshot-", ".sql");
            // 🔴 뜨기 «전»에 세어 둔다. 되돌린 뒤 이 값과 견주는 것이 「확인」이다.
            Map<String, Long> identityBefore = readIdentityBases();
            Map<String, Long> rowsBefore = readRowCounts();

            execute("script to '" + sqlPath(file) + "'");
            return new H2Handle(file, identityBefore, rowsBefore);
        } catch (IOException e) {
            throw new UncheckedIOException("스냅숏 파일을 못 만들었다", e);
        }
    }

    private final class H2Handle implements Handle {

        private final Path file;
        private final Map<String, Long> identityBefore;
        private final Map<String, Long> rowsBefore;

        private H2Handle(Path file, Map<String, Long> identityBefore, Map<String, Long> rowsBefore) {
            this.file = file;
            this.identityBefore = identityBefore;
            this.rowsBefore = rowsBefore;
        }

        @Override
        public ReplayInfo.StateRestore restoreAndVerify() {
            // 🔴 DROP 이 먼저다. 빼면 되돌리는 게 아니라 행이 두 배가 된다.
            execute("drop all objects");
            execute("runscript from '" + sqlPath(file) + "'");

            // 🔴 되돌린 «뒤에» 다시 물어본다. 명령을 보낸 것과 되돌아온 것은 다른 사실이다.
            Boolean rows = verify(rowsBefore, readRowCounts());
            Boolean counters = verify(identityBefore, readIdentityBases());

            return new ReplayInfo.StateRestore(
                    rows,
                    counters,
                    // 🔴 앱 안에 쌓인 캐시는 «안 봤다». false 로 적으면 「보았고 못 되돌렸다」가 되고,
                    //    그건 우리가 확인한 적 없는 주장이다.
                    null,
                    // 외부 시스템도 마찬가지. v1 범위다.
                    null);
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // 임시 파일을 못 지운 것으로 재생을 실패시키지 않는다.
            }
        }
    }

    /**
     * 되돌린 뒤 값이 원래대로인가.
     *
     * <p>🔴 읽을 자리를 못 찾아 «비어 있었다»면 {@code null} 을 돌려준다 —
     * 「되돌렸다」도 「못 되돌렸다」도 아니고 <b>「안 봤다」</b>다. 여기서 {@code true} 를
     * 돌려주면, 확인한 적 없는 것을 확인했다고 적는 것이 된다.
     */
    private static Boolean verify(Map<String, Long> before, Map<String, Long> after) {
        if (before.isEmpty() && after.isEmpty()) {
            return null;
        }
        return before.equals(after);
    }

    /** 자동 증가 컬럼마다 「다음 id 는 몇 번」. 🔴 H2 전용 자리다. */
    private Map<String, Long> readIdentityBases() {
        return query("select table_name, column_name, identity_base "
                        + "from information_schema.columns "
                        + "where table_schema = 'PUBLIC' and identity_base is not null",
                rs -> rs.getString(1) + "." + rs.getString(2),
                rs -> rs.getLong(3));
    }

    /** 테이블마다 행 수. 「행이 되돌아왔나」의 근거다. */
    private Map<String, Long> readRowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            java.util.List<String> tables = new java.util.ArrayList<>();
            try (ResultSet rs = statement.executeQuery(
                    "select table_name from information_schema.tables "
                            + "where table_schema = 'PUBLIC' and table_type = 'BASE TABLE'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                try (ResultSet rs = statement.executeQuery("select count(*) from \"" + table + "\"")) {
                    if (rs.next()) {
                        counts.put(table, rs.getLong(1));
                    }
                }
            }
        } catch (SQLException e) {
            // 🔴 못 읽었으면 «빈» 결과를 돌려준다. 그러면 verify 가 null 을 내고,
            //    등급이 「안 봤다」로 간다. 여기서 예외를 던져 재생을 죽이면
            //    「패치가 못 고쳤다」와 구별이 안 되는 실패가 된다.
            return Map.of();
        }
        return counts;
    }

    // ── 배관 ────────────────────────────────────────────────────────────────

    private interface RowKey {
        String of(ResultSet rs) throws SQLException;
    }

    private interface RowValue {
        long of(ResultSet rs) throws SQLException;
    }

    private Map<String, Long> query(String sql, RowKey key, RowValue value) {
        Map<String, Long> result = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                result.put(key.of(rs), value.of(rs));
            }
        } catch (SQLException e) {
            return Map.of();
        }
        return result;
    }

    private void execute(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("상태를 되돌리지 못했다: " + sql, e);
        }
    }

    /** H2 는 경로 구분자로 {@code /} 를 쓴다. 윈도우의 역슬래시를 그대로 주면 깨진다. */
    private static String sqlPath(Path file) {
        return file.toString().replace('\\', '/');
    }
}
