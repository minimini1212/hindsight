package io.hindsight.recorder;

/**
 * 「기록하는 일」이 다시 기록되지 않게 막는다.
 *
 * <h2>🔴 이게 없으면 무슨 일이 나나</h2>
 * 기록기도 자기 일을 하면서 입출력을 한다 — 파일을 쓰고, 경우에 따라 DB 를 읽는다.
 * 그런데 우리가 감싸 놓은 {@code DataSource} 는 <b>누가 부르든</b> 질의를 기록한다.
 * 그래서 이런 고리가 생긴다.
 *
 * <pre>
 *   기록을 쓴다 → 그 과정의 질의가 기록된다 → 그걸 또 쓴다 → 또 기록된다 → …
 * </pre>
 *
 * <p>이건 느려지는 문제가 아니라 <b>멈추지 않는</b> 문제다. 디스크가 차고 앱이 죽는다.
 * 관측 도구가 관측 대상을 죽이는 가장 흔한 모양이다.
 *
 * <h2>어떻게 막나</h2>
 * 기록기 코드로 들어갈 때 이 스레드에 깃발을 세우고, 나올 때 내린다. 깃발이 서 있는 동안
 * 들어온 기록 요청은 <b>조용히 버린다</b>. 우리 자신의 입출력은 관측 대상이 아니다.
 *
 * <p>🔴 {@code try/finally} 로만 쓴다. 깃발이 선 채로 남으면 그 스레드는
 * <b>그 뒤로 아무것도 기록하지 못한다</b> — 기록이 통째로 비는데 아무 오류도 안 난다.
 */
public final class ReentryGuard {

    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ReentryGuard() {}

    /** 지금 기록기 안에서 도는 중인가. 그렇다면 기록하지 않는다. */
    public static boolean inside() {
        return Boolean.TRUE.equals(INSIDE.get());
    }

    /**
     * 기록기 코드를 도는 동안 깃발을 세운다.
     *
     * <p>이미 세워져 있으면 {@code work} 를 돌리되 나올 때 내리지 «않는다» —
     * 바깥쪽 호출이 아직 안 끝났기 때문이다. 중첩을 세지 않고 이렇게 하는 이유는,
     * 숫자를 세다가 하나 틀리면 깃발이 영영 선 채로 남기 때문이다.
     */
    public static void run(Runnable work) {
        if (inside()) {
            work.run();
            return;
        }
        INSIDE.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            // 🔴 remove 다. set(FALSE) 로 두면 ThreadLocal 항목이 스레드에 남는다 —
            //    스레드 풀에서는 그게 그대로 누수다.
            INSIDE.remove();
        }
    }
}
