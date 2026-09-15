package io.hindsight.recorder;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 「이 SQL 은 어느 요청 것인가」를 잇는 식별자.
 *
 * <h2>🔴 왜 스레드 번호를 쓰지 않나</h2>
 * 요청이 동시에 열 개 들어오면 SQL 도 열 갈래로 섞여 나간다. 어느 질의가 어느 요청 것인지
 * 모르면 기록 하나를 재생할 수 없다 — <b>남의 요청이 낸 질의까지 같이 들어간다.</b>
 *
 * <p>가장 쉬운 방법은 {@code Thread.currentThread().threadId()} 인데 <b>쓰면 안 된다.</b>
 * 가상 스레드(virtual thread, 자바 21 의 가벼운 스레드)는 <b>번호가 재사용된다.</b>
 * 요청 A 가 끝나고 그 번호를 요청 B 가 받으면, 두 요청의 이벤트가 같은 번호로 묶인다.
 * 스프링 부트 3.2 부터 설정 한 줄로 가상 스레드가 켜지므로, 이건 「나중에 생길 일」이 아니라
 * <b>앱 설정 한 줄에 달린 일</b>이다.
 *
 * <p>그래서 요청마다 새로 만든 문자열을 {@link ThreadLocal} 에 심는다.
 *
 * <h2>🔴 반드시 지운다</h2>
 * 서블릿 컨테이너는 스레드를 <b>재사용</b>한다. 지우지 않으면 다음 요청이 앞 요청의
 * 식별자를 물려받아, 두 요청이 한 기록으로 합쳐진다. 지우는 일은 {@code finally} 에서 한다.
 */
public final class Correlation {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private Correlation() {}

    /** 새 식별자를 만들어 이 스레드에 심는다. 요청 하나의 시작에서 부른다. */
    public static String begin() {
        String id = "r-" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL);
        CURRENT.set(id);
        return id;
    }

    /**
     * 지금 스레드에 심긴 식별자. 없으면 {@code null}.
     *
     * <p>🔴 {@code null} 일 때 아무 값이나 지어내지 않는다. 「요청 밖에서 난 질의」는
     * 실제로 있다(앱 시작 시 스키마 만들기, 배치 작업). 그걸 가짜 요청 번호로 묶으면
     * 재생할 때 있지도 않은 요청의 질의가 된다. 없으면 없다고 적는다.
     */
    public static String current() {
        return CURRENT.get();
    }

    /** 🔴 요청이 끝나면 «반드시» 부른다. 안 부르면 다음 요청이 이 번호를 물려받는다. */
    public static void clear() {
        CURRENT.remove();
    }
}
