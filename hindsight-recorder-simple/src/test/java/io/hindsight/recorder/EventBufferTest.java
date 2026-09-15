package io.hindsight.recorder;

import io.hindsight.model.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("링 버퍼 — 「못 받았다」와 「밀어냈다」를 갈라서 센다")
class EventBufferTest {

    private EventBuffer buffer;

    @AfterEach
    void 정리() {
        if (buffer != null) {
            buffer.close();
        }
    }

    private static Event.Sql sql(long seq, String text) {
        return new Event.Sql(seq, "r-1", Instant.now(), "test", 1L,
                text, List.of(), null, null, false, null, null);
    }

    @Test
    @DisplayName("넣은 이벤트가 순서대로 나온다")
    void 넣은_순서대로_나온다() {
        buffer = new EventBuffer(RecorderConfig.builder().build());

        buffer.record(sql(1, "select 1"));
        buffer.record(sql(2, "select 2"));
        buffer.record(sql(3, "select 3"));
        assertThat(buffer.awaitDrained(Duration.ofSeconds(2))).isTrue();

        assertThat(buffer.snapshot())
                .extracting(e -> ((Event.Sql) e).sql())
                .containsExactly("select 1", "select 2", "select 3");
    }

    @Test
    @DisplayName("🔴 바이트 상한을 넘으면 앞에서 밀어내고 «센다» — 조용히 사라지지 않는다")
    void 바이트_상한을_넘으면_밀어내고_센다() {
        // 이벤트 하나가 어림 256바이트 남짓이므로, 상한을 700 으로 두면 두세 개만 남는다.
        buffer = new EventBuffer(RecorderConfig.builder().bufferMaxBytes(700).build());

        for (int i = 0; i < 20; i++) {
            buffer.record(sql(i, "select " + i));
        }
        assertThat(buffer.awaitDrained(Duration.ofSeconds(2))).isTrue();

        assertThat(buffer.snapshot()).hasSizeLessThan(20);
        assertThat(buffer.evictedEvents()).isPositive();
        assertThat(buffer.evictedBytes()).isPositive();
        // 밀어낸 것은 「못 받은 것」이 아니다. 둘을 합쳐서 세지 않는다.
        assertThat(buffer.droppedEvents()).isZero();
        assertThat(buffer.bufferBytes()).isLessThanOrEqualTo(700);
    }

    @Test
    @DisplayName("🔴 받는 큐가 차면 버리고 «센다» — 앱의 스레드를 세우지 않는다")
    void 큐가_차면_버리고_센다() {
        // 🔴 옮기는 스레드를 안 띄운다. 띄워 두면 「몇 개를 밀어 넣어야 차는가」가
        //    그날 기계 상태에 달려서, 어떤 날은 통과하고 어떤 날은 실패하는 테스트가 된다.
        //    가끔 실패하는 테스트는 사람이 넘기게 만들어 진짜 고장까지 같이 넘긴다.
        buffer = new EventBuffer(RecorderConfig.builder().build(), false);

        int refused = 0;
        for (int i = 0; i < EventBuffer.INTAKE_SLOTS + 10; i++) {
            if (!buffer.record(sql(i, "select " + i))) {
                refused++;
            }
        }

        // 칸 수를 넘긴 만큼 정확히 거절된다 — 「대략」이 아니라 숫자로 맞는다.
        assertThat(refused).isEqualTo(10);
        assertThat(buffer.droppedEvents()).isEqualTo(10);
    }

    @Test
    @DisplayName("🔴 비어 있을 때 담긴 시간은 0 이다 — 「모른다」가 아니라 「0초치가 담겼다」")
    void 비어_있으면_담긴_시간은_0() {
        buffer = new EventBuffer(RecorderConfig.builder().build());

        assertThat(buffer.actualWindowSeconds()).isZero();
    }

    @Test
    @DisplayName("이벤트 크기 어림이 본문 길이를 따라 커진다")
    void 크기_어림이_본문을_따라_커진다() {
        Event.HttpIn small = new Event.HttpIn(1, "r", Instant.now(), "t", 1L,
                "GET", "/a", null, null, Map.of(), "x", false, 1, 200, "y", false);
        Event.HttpIn big = new Event.HttpIn(2, "r", Instant.now(), "t", 1L,
                "GET", "/a", null, null, Map.of(), "x".repeat(10_000), false, 10_000, 200, "y", false);

        assertThat(EventBuffer.estimateBytes(big)).isGreaterThan(EventBuffer.estimateBytes(small));
    }
}
