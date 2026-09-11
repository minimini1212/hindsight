package io.hindsight.testkit;

import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.JfrSummary;
import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;
import io.hindsight.model.Summary;
import io.hindsight.model.Trigger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 테스트가 함께 쓰는 기록 표본.
 *
 * <p>🔴 여기 있는 값은 <b>전부 합성이다.</b> 진짜 트래픽에서 뜬 기록은 저장소에 올리지 않는다 —
 * 가명화를 거쳤더라도 형태와 흐름이 남고, 가명정보는 여전히 개인정보다.
 */
public final class Recordings {

    private Recordings() {}

    public static final Instant T0 = Instant.parse("2026-09-10T14:23:11.482Z");

    /**
     * 모든 자리가 채워진 기록. 왕복 테스트가 「빠뜨린 필드가 없나」를 보는 데 쓴다.
     *
     * <p>N+1 상황을 본떴다 — 주문 목록 요청 하나에 회원 조회가 200번 딸려 나가고,
     * 그중 처음 것만 전문으로 남고 나머지는 접혔다.
     */
    public static Recording full() {
        return new Recording(
                Recording.CURRENT_SCHEMA_VERSION,
                "a1b2c3d4",
                T0,
                new Trigger(
                        Trigger.Kind.LATENCY,
                        T0,
                        "GET /api/orders",
                        null,
                        3412L,
                        Trigger.dedupKeyOf("GET /api/orders", Trigger.Kind.LATENCY, "OrderService.findAll:88"),
                        1
                ),
                new AppInfo("demo-app", "8acb6ba", false, "21.0.4", "0.1.0", "host-7f3a"),
                List.of(
                        new Event.HttpIn(
                                1L, "r-7f3a", T0, "http-nio-8080-exec-3", 3412L,
                                "GET", "/api/orders", Map.of("page", "2"), "OrderController#list",
                                Map.of("content-type", "application/json"),
                                null, false, 0,
                                200, "{\"orders\":[]}", false
                        ),
                        new Event.Sql(
                                2L, "r-7f3a", T0.plusMillis(4), "http-nio-8080-exec-3", 6L,
                                "select o from Order o where o.status = ?", List.of("PAID"),
                                200, List.of(Map.of("id", 1)), true,
                                null, null
                        ),
                        // 접힌 반복 질의. 🔴 이게 N+1 의 신호 그 자체다.
                        new Event.Sql(
                                3L, "r-7f3a", T0.plusMillis(11), "http-nio-8080-exec-3", 3200L,
                                "select m from Member m where m.id = ?", List.of("m-4821"),
                                1, null, false,
                                2L, 200
                        ),
                        new Event.Clock(4L, "r-7f3a", T0.plusMillis(20), "http-nio-8080-exec-3", 0L,
                                "java.time.Instant.now", "2026-09-10T14:23:11.502Z"),
                        new Event.Rand(5L, "r-7f3a", T0.plusMillis(21), "http-nio-8080-exec-3", 0L,
                                "java.util.UUID.randomUUID", "3f2a1c00-0000-4000-8000-000000000001"),
                        new Event.HttpOut(
                                6L, "r-7f3a", T0.plusMillis(30), "http-nio-8080-exec-3", 120L,
                                "POST", "https://partner.example.invalid/notify", Map.of(),
                                "{}", false,
                                204, Map.of(), null, false
                        ),
                        // 🔴 어느 요청에도 안 붙는 이벤트. corrId 가 null 이다. 버리지 않는다.
                        new Event.Sql(
                                7L, null, T0.plusMillis(40), "scheduling-1", 2L,
                                "select j from Job j", List.of(),
                                0, List.of(), false,
                                null, null
                        )
                ),
                new Summary(
                        60,
                        List.of(new Summary.SqlShape("9f2a", "select m from Member m where m.id = ?", 812, 1204L)),
                        List.of(new Summary.RequestLine(T0.minusSeconds(30), "GET", "/api/orders", 200, 42L)),
                        List.of(new Summary.PoolSample(T0.minusSeconds(30), 8, 2))
                ),
                new JfrSummary("a1b2c3d4.jfr", 41L, 3, 0L, List.of(), 412_000_000L, 84),
                new ReplayInfo(
                        ReplayInfo.Grade.DIVERGED,
                        List.of("CLOCK"),
                        Boolean.TRUE,
                        new ReplayInfo.Divergence(
                                "SQL_SHAPE_CHANGED",
                                new ReplayInfo.Divergence.Side("9f2a", "select m from Member m where m.id = ?", 201),
                                new ReplayInfo.Divergence.Side(null, "select o from Order o join fetch o.member", 1),
                                "진단이 말한 방향과 일치한다"
                        ),
                        // 행과 자동 증가 카운터는 되돌렸고, 캐시·외부는 «보았지만 못 되돌렸다».
                        // 🔴 false 와 null 이 한 표본 안에 같이 있어야 왕복에서 둘이 안 섞이는지 보인다.
                        new ReplayInfo.StateRestore(true, true, false, false),
                        T0.plusSeconds(30L * 24 * 3600),
                        "System.currentTimeMillis 는 v0 범위 밖"
                ),
                new Integrity(60, 4.2, 0L, 8140L, 71_000_000L, 16_700_000L, 0L, false)
        );
    }

    /**
     * 알아낸 게 거의 없는 기록. 🔴 <b>「모름」이 살아서 왕복하는지</b>를 보는 데 쓴다.
     *
     * <p>커밋을 못 알아냈고, JFR 을 못 떴고, 요약이 없고, 재생을 아직 안 돌려봤다.
     * 이 값들이 왕복 뒤에 빈 목록이나 {@code false} 로 바뀌어 있으면 그게 결함이다 —
     * 「못 알아냈다」가 「그런 게 없었다」로 접힌 것이므로.
     */
    public static Recording mostlyUnknown() {
        return new Recording(
                Recording.CURRENT_SCHEMA_VERSION,
                "e5f6a7b8",
                T0,
                new Trigger(Trigger.Kind.EXCEPTION, T0, "POST /api/pay",
                        new Trigger.ExceptionInfo("java.lang.NullPointerException", null, List.of("at X.y(X.java:1)")),
                        null,
                        Trigger.dedupKeyOf("POST /api/pay", Trigger.Kind.EXCEPTION, null),
                        3),
                new AppInfo("demo-app", null, null, "21.0.4", "0.1.0", null),
                List.of(),
                null,
                null,
                // 🔴 stateRestore 가 null 이다 — 「되돌리지 않았다」가 아니라 «안 봤다».
                //    왕복 뒤에 이게 「전부 false」로 바뀌어 있으면 그게 결함이다.
                new ReplayInfo(ReplayInfo.Grade.PARTIAL, null, null, null, null, null, null),
                new Integrity(60, 60.0, 0L, 0L, 0L, 12_000L, 0L, false)
        );
    }
}
