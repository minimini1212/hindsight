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

    /**
     * 묶음 장부를 얼마나 들고 있나. 🔴 시간당 상한(1시간)보다 <b>길게</b> 잡는다 —
     * 짧게 잡으면 상한이 아직 유효한 동안 장부가 먼저 비어서, 같은 사고가 다시 파일을 만든다.
     */
    static final Duration 묶음을_기억하는_시간 = Duration.ofHours(6);

    /**
     * 장부에 들어갈 수 있는 열쇠 수의 <b>딱딱한</b> 상한.
     *
     * <p>🔴 시간만으로 막으면 「보통은 괜찮은」 방어다. 사고가 한꺼번에 수만 가지로 나면
     * 시간이 지나기 전에 메모리가 먼저 찬다 — 그리고 그게 바로 이 도구가 지켜야 하는 순간이다.
     * 열쇠 하나가 대략 100바이트 남짓이므로 1만 개면 1MB 안쪽이다.
     */
    static final int 장부_최대 = 10_000;

    /** 장부 청소 간격. 🔴 요청 스레드에서 도는 일이라 매번 훑지 않는다. */
    static final Duration 청소_간격 = Duration.ofMinutes(1);

    private final Object lock = new Object();

    /**
     * 묶음 열쇠 → 지금까지 몇 번 났나, 그리고 마지막으로 본 시각.
     *
     * <h2>🔴 이 장부가 «무한히» 자라던 자리다 (2026-09-16 에 막음)</h2>
     * 묶음 열쇠는 진입점을 그대로 들고 있고, 진입점은 URI 다 —
     * {@code GET /api/orders/1}, {@code GET /api/orders/2} … 는 <b>전부 다른 열쇠</b>다.
     * 경로에 식별자가 들어가는 API 가 하나만 있어도 열쇠는 <b>끝없이 늘어난다.</b>
     *
     * <p>⚠️ 이건 남의 JVM 안에서 자라는 메모리다. <b>관측 도구가 앱을 죽이는 모양</b>이고,
     * 이 프로젝트의 가장 굳은 규율을 정면으로 어긴다.
     *
     * <p>🔴 그래서 오래된 것을 <b>잊되, 잊었다는 사실을 센다</b>({@link #잊은_묶음_수()}).
     * 조용히 지우면 같은 사고가 다시 왔을 때 {@code dedupCount} 가 1 로 돌아가고,
     * 그러면 「1,204번 중 하나」였던 것이 「한 번 났다」로 읽힌다.
     */
    private final Map<String, 묶음> seen = new HashMap<>();

    /** 🔴 잊어버린 묶음 수. 「잊었다」를 「없었다」로 접지 않으려고 센다. */
    private long 잊은묶음 = 0;

    /**
     * 마지막으로 장부를 훑은 시각. 🔴 <b>{@code null} 이 「아직 한 번도 안 훑었다」다.</b>
     *
     * <p>⚠️ 처음에 {@code long} 에 {@code Long.MIN_VALUE} 를 넣어 뒀는데,
     * {@code nowNanos - Long.MIN_VALUE} 가 <b>넘쳐서 음수</b>가 됐다. 그래서
     * 「시간이 한참 지났다」가 「아직 멀었다」로 뒤집혔고, <b>첫 청소가 영영 안 돌았다.</b>
     * 시험이 그걸 잡았다 — 「없다」를 특별한 «숫자»로 나타내면 이런 일이 난다.
     */
    private Long 마지막청소나노 = null;

    /** 열쇠 하나의 장부. */
    private record 묶음(int 횟수, long 마지막나노) {}

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
            // 🔴 먼저 오래된 것을 잊는다. 안 그러면 이 장부가 앱이 도는 내내 자란다.
            //    ⚠️ 매번 훑지 않는다 — 이 메서드는 «앱의 요청 스레드»에서 돈다.
            청소할_때가_됐으면_청소한다(nowNanos);

            묶음 이전 = seen.get(dedupKey);
            int count = (이전 == null ? 0 : 이전.횟수()) + 1;
            seen.put(dedupKey, new 묶음(count, nowNanos));

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
            묶음 m = seen.get(dedupKey);
            return m == null ? 0 : m.횟수();
        }
    }

    /**
     * 🔴 지금까지 <b>잊어버린</b> 묶음 수.
     *
     * <p>잊은 열쇠가 다시 오면 {@code dedupCount} 가 1 부터 다시 센다. 그건 틀린 숫자는
     * 아니지만 <b>「한 번 났다」로 읽히는</b> 숫자다. 이 값이 0 이 아니면 그 가능성이 있다는 뜻이다.
     *
     * <p>⬜ <b>아직 기록 파일에는 안 적힌다.</b> 적으려면 {@code Integrity} 에 자리를 하나
     * 더 만들어야 하고, 그건 기록 형식이 바뀌는 일이라 판 번호가 같이 올라가야 한다.
     */
    public long 잊은_묶음_수() {
        synchronized (lock) {
            return 잊은묶음;
        }
    }

    /** 지금 장부에 몇 개가 들어 있나. 시험이 「정말 안 자라나」를 보는 자리다. */
    public int 장부_크기() {
        synchronized (lock) {
            return seen.size();
        }
    }

    /**
     * 오래된 묶음을 잊는다 — <b>두 겹으로</b>.
     *
     * <pre>
     *   ① 시간   마지막으로 본 지 {@code 묶음을_기억하는_시간} 이 지났으면 잊는다
     *   ② 개수   그래도 {@code 장부_최대} 를 넘으면 «가장 오래 안 본 것»부터 잊는다
     * </pre>
     *
     * <p>🔴 ②가 필요한 이유: 사고가 <b>한꺼번에</b> 수만 가지로 나면 ①의 시간이 지나기
     * 전에 이미 메모리가 찬다. 시간만으로 막으면 「보통은 괜찮은」 방어가 되고,
     * 이 도구가 지켜야 하는 것은 <b>보통이 아닐 때</b>다.
     */
    /**
     * 🔴 <b>청소는 «가끔»만 한다.</b> 이 메서드가 도는 자리는 앱의 요청 스레드다.
     *
     * <p>장부를 매번 통째로 훑으면 열쇠 1만 개에서 사고 한 번마다 1만 번 비교가 붙는다.
     * 기록기가 앱을 느리게 하면 안 된다는 규율이 그것보다 앞선다.
     *
     * <pre>
     *   ① 장부가 상한을 넘었다        → 지금 청소한다 (메모리가 걸린 문제라 미룰 수 없다)
     *   ② 마지막 청소 후 1분이 지났다 → 청소한다
     *   그 밖                         → 아무것도 안 한다
     * </pre>
     */
    private void 청소할_때가_됐으면_청소한다(long nowNanos) {
        // 🔴 «넣기 전»에 상한과 비교한다. > 로 두면 넣은 뒤 상한+1 이 되고,
        //    그건 「상한을 지킨다」가 아니라 「상한 근처에 머문다」다.
        boolean 넘쳤다 = seen.size() >= 장부_최대;
        boolean 때가됐다 = 마지막청소나노 == null
                || nowNanos - 마지막청소나노 >= 청소_간격.toNanos();
        if (!넘쳤다 && !때가됐다) {
            return;
        }
        마지막청소나노 = nowNanos;
        오래된_묶음을_잊는다(nowNanos);
    }

    private void 오래된_묶음을_잊는다(long nowNanos) {
        long 기억나노 = 묶음을_기억하는_시간.toNanos();
        var it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (nowNanos - it.next().getValue().마지막나노() > 기억나노) {
                it.remove();
                잊은묶음++;
            }
        }
        // 🔴 이 뒤에 하나가 «들어올» 것이므로 상한-1 까지 줄인다.
        //    그래야 넣고 난 뒤가 정확히 상한 이하다.
        int 남길것 = 장부_최대 - 1;
        if (seen.size() <= 남길것) {
            return;
        }
        // 가장 오래 안 본 것부터 버린다. 🔴 지운 수를 그대로 센다.
        seen.entrySet().stream()
                .sorted(java.util.Comparator.comparingLong(e -> e.getValue().마지막나노()))
                .limit(seen.size() - 남길것)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(k -> {
                    seen.remove(k);
                    잊은묶음++;
                });
    }

    private void expireOldCaptures(long nowNanos) {
        long windowNanos = hour.toNanos();
        while (!capturedAtNanos.isEmpty() && nowNanos - capturedAtNanos.peekFirst() > windowNanos) {
            capturedAtNanos.pollFirst();
        }
    }
}
