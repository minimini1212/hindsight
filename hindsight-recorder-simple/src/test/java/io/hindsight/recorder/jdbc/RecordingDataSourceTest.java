package io.hindsight.recorder.jdbc;

import io.hindsight.core.store.RecordingCodec;
import io.hindsight.model.Event;
import io.hindsight.model.Recording;
import io.hindsight.model.Trigger;
import io.hindsight.recorder.Recorder;
import io.hindsight.recorder.RecorderConfig;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DataSource 감싸기 — 진짜 H2 로")
class RecordingDataSourceTest {

    private static final AtomicInteger DB_NUMBER = new AtomicInteger();

    @TempDir
    Path storeDir;

    private Recorder recorder;
    private DataSource wrapped;

    @BeforeEach
    void 준비() throws SQLException {
        JdbcDataSource real = new JdbcDataSource();
        real.setURL("jdbc:h2:mem:recorder" + DB_NUMBER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        real.setUser("sa");

        try (Connection c = real.getConnection(); Statement s = c.createStatement()) {
            s.execute("create table member (id int primary key, email varchar(200))");
            s.execute("insert into member values (1, 'hong@abc.com')");
        }

        recorder = new Recorder(RecorderConfig.builder().storeDir(storeDir).appName("test").build());
        wrapped = RecordingDataSource.wrap(real, recorder);
    }

    @AfterEach
    void 정리() {
        recorder.close();
    }

    private List<Event.Sql> capturedSql() {
        Path file = recorder.capture(Trigger.Kind.LATENCY, "TEST", null, 9999L, null).orElseThrow();
        Recording recording = new RecordingCodec().read(file);
        return recording.events().stream()
                .filter(Event.Sql.class::isInstance)
                .map(Event.Sql.class::cast)
                .toList();
    }

    @Test
    @DisplayName("앱이 보낸 질의가 기록된다")
    void 질의가_기록된다() throws SQLException {
        try (Connection c = wrapped.getConnection();
             PreparedStatement ps = c.prepareStatement("select email from member where id = ?")) {
            ps.setInt(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
            }
        }

        assertThat(capturedSql())
                .extracting(Event.Sql::sql)
                .contains("select email from member where id = ?");
    }

    @Test
    @DisplayName("🔴 자리 번호대로 «정렬해서» 값을 담는다 — setXxx 를 부르는 순서는 앱 마음이다")
    void 값을_자리_번호대로_담는다() throws SQLException {
        try (Connection c = wrapped.getConnection();
             PreparedStatement ps = c.prepareStatement("select * from member where email = ? and id = ?")) {
            // 일부러 2번을 먼저 꽂는다.
            ps.setInt(2, 1);
            ps.setString(1, "hong@abc.com");
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
            }
        }

        Event.Sql sql = capturedSql().stream()
                .filter(e -> e.sql().contains("and id = ?"))
                .findFirst().orElseThrow();

        // 🔴 첫 자리가 이메일(가명화되어 있다), 둘째 자리가 1 이어야 한다.
        assertThat(sql.params()).hasSize(2);
        assertThat(sql.params().get(1)).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 실패한 질의도 기록된다 — 사고의 원인이 바로 그것일 때가 많다")
    void 실패한_질의도_기록된다() {
        assertThatThrownBy(() -> {
            try (Connection c = wrapped.getConnection();
                 Statement s = c.createStatement()) {
                s.execute("select * from 없는테이블");
            }
        }).isInstanceOf(SQLException.class);

        assertThat(capturedSql())
                .extracting(Event.Sql::sql)
                .anyMatch(s -> s.contains("없는테이블"));
    }

    @Test
    @DisplayName("🔴 앱이 받는 예외 종류가 우리 때문에 바뀌지 않는다")
    void 예외_종류가_안_바뀐다() {
        // 반사 호출은 예외를 InvocationTargetException 으로 감싼다. 그걸 안 벗기면
        // 앱은 SQLException 대신 엉뚱한 예외를 받는다.
        assertThatThrownBy(() -> {
            try (Connection c = wrapped.getConnection();
                 Statement s = c.createStatement()) {
                s.execute("이건 SQL 이 아니다");
            }
        })
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(java.lang.reflect.InvocationTargetException.class);
    }

    @Test
    @DisplayName("🔴 결과 행 수는 세지 않는다(null) — 세려면 커서를 움직여야 하고, 그러면 앱이 읽을 행이 사라진다")
    void 조회의_행수는_모름으로_남는다() throws SQLException {
        try (Connection c = wrapped.getConnection();
             PreparedStatement ps = c.prepareStatement("select * from member")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // 앱이 전부 읽는다
                }
            }
        }

        Event.Sql select = capturedSql().stream()
                .filter(e -> e.sql().equals("select * from member"))
                .findFirst().orElseThrow();

        assertThat(select.rowCount()).isNull();   // 🔴 0 이 아니라 null
        assertThat(select.rows()).isNull();
    }

    @Test
    @DisplayName("바꾼 행 수는 셀 수 있으므로 «센다»")
    void 갱신의_행수는_센다() throws SQLException {
        try (Connection c = wrapped.getConnection();
             PreparedStatement ps = c.prepareStatement("update member set email = ? where id = ?")) {
            ps.setString(1, "kim@abc.com");
            ps.setInt(2, 1);
            ps.executeUpdate();
        }

        Event.Sql update = capturedSql().stream()
                .filter(e -> e.sql().startsWith("update"))
                .findFirst().orElseThrow();

        assertThat(update.rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 같은 질의를 두 번 돌려도 두 배로 세이지 않는다")
    void 두_배로_세이지_않는다() throws SQLException {
        for (int i = 0; i < 5; i++) {
            try (Connection c = wrapped.getConnection();
                 PreparedStatement ps = c.prepareStatement("select id from member")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // 읽기만 한다
                    }
                }
            }
        }

        long count = capturedSql().stream()
                .filter(e -> "select id from member".equals(e.sql()))
                .count();

        assertThat(count).isEqualTo(5);
    }
}
