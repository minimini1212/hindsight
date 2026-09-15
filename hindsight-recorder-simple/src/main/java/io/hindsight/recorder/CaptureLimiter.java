package io.hindsight.recorder;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 사고가 초당 수백 번 날 때 파일도 수백 개 만들지 않게 막는다.
 *
 * <h2>🔴 왜 필요한가</h2>
 * 장애는 한 번 나면 조용히 한 번 나지 않는다. 같은 예외가 <b>초당 수백 번</b> 터진다.
 * 상한이 없으면 우리가 그 순간 디스크를 채우고, 앱은 원래 장애에 더해
 * <b>「디스크 가득 참」으로 한 번 더 죽는다.</b> 관측 도구가 사고를 키우는 모양이다.
 *
 * <h2>두 겹으로 막는다</h2>
 * <ol>
 *   <li><b>묶기</b> — {@code (진입점, 방아쇠 종류, 스택 서명)} 이 같으면 한 건으로 본다.
 *       두 번째부터는 파일을 안 만들고 «몇 번째였는지»만 센다</li>
 *   <li><b>시간당 상한</b> — 서로 다른 사고라도 한 시간에 기본 20건까지만</li>
 * </ol>
 *
 * <h2>🔴 예외 종류만으로 묶지 않는다</h2>
 * 지연 방아쇠에는 예외가 <b>없다</b>. 예외 종류를 묶는 열쇠로 쓰면 모든 지연이 한 건으로
 * 접혀서, 서로 다른 느린 API 열 개가 기록 하나만 남긴다.
 *
 * <h2>🔴 막은 것은 «세어서» 파일에 적는다</h2>
 * {@code dedupCount} 가 그것이다. 기록이 하나뿐이어도 「이게 1,204번 중 하나」라는 것과
 * 「이게 한 번 났다」는 완전히 다른 사실이다. 안 세면 뒤로 읽힌다.
 */
public final class CaptureLimiter {

    private final RecorderConfig config;
    private final Duration hour = Duration.ofHours(1);

    private final Object lock = new Object();

    /** 묶음 열쇠 → 지금까지 몇 번 났나. */
    private final Map<String, Integer> seen = new HashMap<>();

    /** 실제로 파일을 만든 시각들. 시간당 상한을 여기서 센다. */
    private final Deque<Long> capturedAtNanos = new ArrayDeque<>();

    public CaptureLimiter(RecorderConfig config) {
        this.config = config;
    }

    /** 판정 결과. 「만들어라」와 「막았다」를 둘 다 숫자와 함께 돌려준다. */
    public record Verdict(boolean capture, int dedupCount, String reason) {}

    /**
     * 이 사고로 파일을 만들어도 되나.
     *
     * @param dedupKey {@code Trigger.dedupKeyOf(진입점, 종류, 스택 서명)} 로 만든 열쇠
     */
    public Verdict admit(String dedupKey, long nowNanos) {
        synchronized (lock) {
            int count = seen.merge(dedupKey, 1, Integer::sum);

            if (count > 1) {
                // 같은 사고의 두 번째부터. 파일은 안 만들지만 몇 번째인지는 들고 있는다.
                return new Verdict(false, count, "같은 사고로 이미 기록을 만들었다");
            }

            expireOldCaptures(nowNanos);
            if (capturedAtNanos.size() >= config.capturesPerHour()) {
                return new Verdict(false, count,
                        "시간당 상한 " + config.capturesPerHour() + "건에 걸렸다");
            }

            capturedAtNanos.addLast(nowNanos);
            return new Verdict(true, count, "만든다");
        }
    }

    /**
     * 이 열쇠로 지금까지 몇 번 났나. 파일을 쓸 때 {@code dedupCount} 로 적는다.
     *
     * <p>🔴 파일을 만든 «뒤»에도 같은 사고는 계속 난다. 그래서 기록 파일에 적히는 숫자는
     * 「만들 때까지 센 것」이고, 그 뒤의 것은 이 기록에 안 들어간다. 그 한계를
     * 아는 채로 쓴다 — 0 이나 1 로 적어서 「한 번 났다」로 보이게 하지 않는다.
     */
    public int countFor(String dedupKey) {
        synchronized (lock) {
            return seen.getOrDefault(dedupKey, 0);
        }
    }

    private void expireOldCaptures(long nowNanos) {
        long windowNanos = hour.toNanos();
        while (!capturedAtNanos.isEmpty() && nowNanos - capturedAtNanos.peekFirst() > windowNanos) {
            capturedAtNanos.pollFirst();
        }
    }
}
