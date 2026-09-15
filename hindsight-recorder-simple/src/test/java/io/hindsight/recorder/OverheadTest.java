package io.hindsight.recorder;

import io.hindsight.model.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔬 실험 ⑩ — <b>기록기를 붙이면 얼마나 더 드나.</b>
 *
 * <h2>왜 이 시험이 v0 안에 있나</h2>
 * 「얼마나 느려지는데?」에 답 못 하는 관측 도구는 아무도 안 붙인다. 그리고
 * 🔴 <b>「나중에 잰다」로 두면 안 재게 된다</b> — 그래서 설계가 측정 하네스를 v0 과제로 못 박았다.
 *
 * <h2>설계가 건 목표 (DESIGN §10)</h2>
 * <table>
 *   <tr><th>항목</th><th>목표</th></tr>
 *   <tr><td>경계 이벤트 하나당</td><td><b>2µs 이하</b></td></tr>
 *   <tr><td>요청당 추가 시간</td><td><b>50µs 이하</b></td></tr>
 *   <tr><td>상시 메모리</td><td>32MB 이하</td></tr>
 * </table>
 *
 * <h2>🔴 이 시험이 «하지 않는» 것 — 숫자로 빌드를 세우지 않는다</h2>
 * 절대값에 검사를 걸면 <b>기계가 바쁜 날 빌드가 빨갛게 된다.</b> 그리고 가끔 실패하는 검사는
 * 사람이 「또 그거네」 하고 넘기게 만들어, <b>진짜 고장까지 같이 넘어간다.</b>
 *
 * <p>그래서 검사는 <b>목표의 50배</b>라는 느슨한 자리에만 건다 — 「조금 느려졌나」가 아니라
 * 「자릿수가 바뀌었나」를 잡는 그물이다. 진짜 숫자는 출력으로 남기고
 * {@code docs/reports/} 에 손으로 옮긴다.
 */
@DisplayName("🔬 실험 ⑩ — 기록기를 붙이면 얼마나 더 드나")
class OverheadTest {

    /** 🔴 목표의 50배. 「느려졌나」가 아니라 「자릿수가 바뀌었나」를 잡는 그물이다. */
    private static final double 이벤트_하나당_그물_마이크로초 = 2.0 * 50;

    @TempDir
    Path storeDir;

    private Recorder recorder;

    @AfterEach
    void 정리() {
        if (recorder != null) {
            recorder.close();
        }
    }

    private Recorder recorderWith(RecorderConfig.Builder builder) {
        recorder = new Recorder(builder.storeDir(storeDir).appName("bench").build());
        return recorder;
    }

    private static Event.HttpIn httpEvent(long seq) {
        return new Event.HttpIn(seq, "r-bench", Instant.now(), "bench", 3L,
                "GET", "/api/orders", Map.of("page", "2"), null,
                Map.of("content-type", "application/json", "accept", "*/*"),
                null, false, 0, 200, "{\"orders\":[]}", false);
    }

    // ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("① 경계 이벤트 하나를 기록하는 데 얼마나 드나 (목표 2µs)")
    void 이벤트_하나당_비용() {
        Recorder r = recorderWith(RecorderConfig.builder());

        OverheadHarness.Result sql = OverheadHarness.measure(
                "SQL 이벤트 하나", 20_000, 50, 10_000,
                i -> r.recordSql("select o from Order o where o.memberId = ?", List.of(i), 1, 3));

        OverheadHarness.Result http = OverheadHarness.measure(
                "HTTP 이벤트 하나", 20_000, 50, 10_000,
                i -> r.recordHttp(httpEvent(i)));

        System.out.println();
        System.out.println("── 실험 ⑩-① 이벤트 하나당 (목표 2µs) ──");
        System.out.println(sql);
        System.out.println(http);
        System.out.println("  버린 이벤트: " + 버린것(r) + "건  (큐가 차서 «못 받은» 것)");

        // 🔴 자릿수가 바뀌었나만 본다.
        assertThat(sql.p99Micros()).isLessThan(이벤트_하나당_그물_마이크로초);
        assertThat(http.p99Micros()).isLessThan(이벤트_하나당_그물_마이크로초);
    }

    @Test
    @DisplayName("🔴 ② 링 버퍼가 가득 찬 뒤에도 비용이 안 늘어나나 — 여기가 무너지면 장애 때 앱이 죽는다")
    void 버퍼가_가득_찬_뒤의_비용() {
        // 버퍼를 1MB 로 줄여서 «금방» 가득 차게 만든다. 그 뒤로는 넣을 때마다 앞에서
        // 밀어내는 일이 같이 일어난다 — 그게 비싸지면 장애가 길어질수록 앱이 느려진다.
        Recorder r = recorderWith(RecorderConfig.builder().bufferMaxBytes(1024 * 1024));

        OverheadHarness.Result 빈상태 = OverheadHarness.measure(
                "빈 버퍼", 20_000, 30, 5_000,
                i -> r.recordSql("select 1", List.of(i), 1, 1));

        OverheadHarness.Result 가득찬상태 = OverheadHarness.measure(
                "가득 찬 버퍼", 20_000, 30, 5_000,
                i -> r.recordSql("select 1", List.of(i), 1, 1));

        System.out.println();
        System.out.println("── 실험 ⑩-② 버퍼가 찬 뒤 ──");
        System.out.println(빈상태);
        System.out.println(가득찬상태);
        System.out.println(OverheadHarness.delta("  밀어내기가 더 먹는 값", 빈상태, 가득찬상태));

        assertThat(가득찬상태.p99Micros()).isLessThan(이벤트_하나당_그물_마이크로초);
    }

    @Test
    @DisplayName("🔴 ③ 크기 「어림」이 실제 메모리와 몇 배 다른가 — 상한이 메모리를 지키는 장치이므로")
    void 크기_어림이_실제와_얼마나_다른가() {
        // 상한을 아주 크게 둬서 밀려나지 않게 한 뒤, 정해진 개수를 넣고 잰다.
        Recorder r = recorderWith(RecorderConfig.builder().bufferMaxBytes(512L * 1024 * 1024));

        long 넣기전 = OverheadHarness.usedHeapBytes();

        int 개수 = 50_000;
        int 받아준것 = 0;
        for (int i = 0; i < 개수; i++) {
            r.recordSql("select o from Order o where o.memberId = ? and o.status = ?",
                    List.of(i, "PAID"), 1, 3);
        }
        받아준것 = 개수 - (int) r.droppedEventsForTest();
        // 🔴 큐에 남은 것까지 링에 넣어야 «들고 있는 양»이 확정된다.
        boolean 다옮겼나 = r.awaitDrainedForTest(Duration.ofSeconds(30));

        long 넣은후 = OverheadHarness.usedHeapBytes();
        long 실제 = 넣은후 - 넣기전;
        long 어림 = r.bufferBytesForTest();
        long 링에담긴수 = r.bufferedEventCountForTest();

        System.out.println();
        System.out.println("── 실험 ⑩-③ 크기 어림 vs 실제 힙 ──");
        System.out.printf("  넣은 이벤트   : %,d건 (큐가 받아 준 것 %,d건, 버린 것 %,d건)%n",
                개수, 받아준것, r.droppedEventsForTest());
        System.out.printf("  링에 담긴 것  : %,d건 (다 옮겼나 %s)%n",
                링에담긴수, 다옮겼나 ? "예" : "🔴 아니오");
        System.out.printf("  우리 어림     : %,d 바이트 (%.1f MB)%n", 어림, 어림 / 1024.0 / 1024);
        System.out.printf("  실제 힙 증가  : %,d 바이트 (%.1f MB)%n", 실제, 실제 / 1024.0 / 1024);
        if (어림 > 0 && 실제 > 0) {
            System.out.printf("  🔴 실제 / 어림 = %.2f 배%n", (double) 실제 / 어림);
        }
        System.out.println("  ⚠️ 힙 측정은 System.gc() 가 «부탁»이라 자릿수로만 읽는다");

        // 🔴 여기서 「몇 배 이내」를 검사로 걸지 않는다. 힙 측정이 그만큼 정확하지 않다.
        //    숫자를 «남기는» 것이 이 시험의 일이고, 판단은 보고서에서 사람이 한다.
        //
        // 🔴 그리고 「5만 건이 «전부» 링에 담긴다」로 걸지 «않는다». 큐가 차면 버리는 것이
        //    이 설계의 «의도»라서, 기계가 바쁜 날에는 실제로 버려진다 —
        //    실제로 2026-09-15 빌드 중에 821건이 버려져 이 검사가 깨졌다.
        //    그렇게 쓴 검사는 「가끔 실패하는 검사」가 되고, 그건 사람이 넘기게 만들어
        //    진짜 고장까지 같이 넘긴다.
        //
        //    대신 «보존»을 건다: 받아 준 것은 전부 링에 있어야 한다. 이건 기계가 바쁘든
        //    말든 참이어야 하는 것이고, 어긋나면 이벤트가 «설명 없이 사라진» 것이다.
        assertThat(링에담긴수)
                .as("받아 준 이벤트가 설명 없이 사라지면 안 된다 (버린 것은 %d건)",
                        r.droppedEventsForTest())
                .isEqualTo(받아준것);
    }

    @Test
    @DisplayName("④ 사고가 나서 파일을 떨구는 데 얼마나 드나 — 요청 스레드가 이걸 기다린다")
    void 파일을_떨구는_비용() {
        Recorder r = recorderWith(RecorderConfig.builder().capturesPerHour(1_000_000));

        for (int i = 0; i < 2_000; i++) {
            r.recordSql("select 1", List.of(i), 1, 1);
        }
        r.awaitDrainedForTest(Duration.ofSeconds(10));

        // 🔴 회차 번호를 열쇠로 쓰면 «안 된다». 덥히는 구간이 0~19 를 다 써 버리고,
        //    본 측정에서 같은 번호가 다시 오면 묶기 장치가 「이미 기록했다」로 막는다.
        //    그러면 파일을 안 쓰고 바로 돌아오므로, 재는 것이 「파일 쓰기」가 아니라
        //    「막히는 데 걸리는 시간」이 된다 — 처음에 그렇게 재서 8µs 가 나왔다.
        //    파일 쓰기가 8µs 일 리 없다는 것이 이 버그를 알아챈 단서였다.
        java.util.concurrent.atomic.AtomicLong 매번다른번호 = new java.util.concurrent.atomic.AtomicLong();

        OverheadHarness.Result 떨구기 = OverheadHarness.measure(
                "기록 파일 하나 쓰기", 20, 20, 5,
                i -> {
                    long n = 매번다른번호.incrementAndGet();
                    r.capture(io.hindsight.model.Trigger.Kind.LATENCY,
                            "GET /bench/" + n, null, 4000L, "sig-" + n);
                });

        System.out.println();
        System.out.println("── 실험 ⑩-④ 파일 떨구기 ──");
        System.out.println(떨구기);
        System.out.printf("  링에 담긴 이벤트 %,d건짜리 기록을 쓴 값이다%n", r.bufferedEventCountForTest());
        System.out.println("  ⚠️ 이건 사고가 «났을 때만» 드는 값이다. 평상시 비용이 아니다.");
        System.out.println("  🔴 그래도 요청 스레드가 이걸 기다린다 — 여기가 커지면 사고 때 응답이 더 늦어진다.");

        // 🔴 파일을 «정말 썼는지» 확인한다. 안 그러면 「막히는 데 걸린 시간」을 재고도 모른다.
        assertThat(storeDir.toFile().listFiles()).isNotEmpty();
        // 파일 쓰기는 디스크가 개입하므로 그물을 훨씬 느슨하게 — 1초.
        assertThat(떨구기.p99Micros()).isLessThan(1_000_000.0);
    }

    private static long 버린것(Recorder r) {
        return r.droppedEventsForTest();
    }
}
