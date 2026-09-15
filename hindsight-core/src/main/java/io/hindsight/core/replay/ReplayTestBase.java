package io.hindsight.core.replay;

import io.hindsight.model.ReplayInfo;

/**
 * 생성된 재생 테스트가 기대는 자리. <b>관측 대상 앱이 두 개를 채워 주면 된다.</b>
 *
 * <h2>왜 추상 클래스인가</h2>
 * 되돌리는 방법과 요청을 보내는 방법은 <b>앱마다 다르다</b> — 어떤 앱은 스프링 부트 테스트로,
 * 어떤 앱은 진짜 포트로, 어떤 앱은 DB 가 H2 가 아니다. 생성기가 그걸 다 알 수는 없다.
 *
 * <p>그래서 <b>생성기는 「무엇을 단언하는가」만 만들고</b>, 「어떻게 되돌리고 어떻게 보내는가」는
 * 앱이 채운다. 🔴 이 경계 덕분에 <b>생성된 소스가 앱에 대해 아무것도 몰라도 된다.</b>
 *
 * <h2>🔴 채워 넣는 쪽이 지켜야 하는 것</h2>
 * <ul>
 *   <li>{@link #되돌린다()} 는 되돌린 «뒤에» 확인해서 <b>확인된 것만</b> {@code true} 로 돌려준다.
 *       안 본 것은 {@code null} 이다 — {@code false}(보았고 못 되돌렸다)와 다른 사실이다</li>
 *   <li>{@link #재생한다} 는 <b>기록된 요청을 그대로</b> 보낸다. 본문이 {@code null} 이면
 *       🔴 빈 문자열로 바꾸지 «않는다» — 「본문 없음」과 「빈 본문」은 다른 요청이다</li>
 *   <li>{@link Observed#worstQueryRepeat()} 는 <b>그 요청이 낸</b> 질의만 센다.
 *       🔴 다른 요청이나 앱 시작 시의 질의를 같이 세면 숫자가 통째로 틀린다</li>
 * </ul>
 */
public abstract class ReplayTestBase {

    /**
     * 기록 시점 상태로 되돌린다.
     *
     * @return 🔴 <b>확인된</b> 복원 범위. 주장이 아니라 관찰이어야 한다
     */
    protected abstract ReplayInfo.StateRestore 되돌린다();

    /**
     * 기록된 요청을 그대로 다시 보내고, 그동안 무엇이 나갔는지 본다.
     *
     * @param body 🔴 {@code null} 이면 «본문 없이» 보낸다. 빈 문자열로 바꾸지 않는다
     */
    protected abstract Observed 재생한다(String method, String path, String body);

    /**
     * 재생 한 번을 보고 얻은 것.
     *
     * @param status            응답 상태
     * @param body              응답 본문
     * @param escapedTypeOrNull 진입점 밖으로 나간 예외의 타입. 🔴 안 나갔으면 {@code null}
     * @param worstQueryRepeat  🔴 <b>그 요청이 낸</b> 질의 중 같은 모양이 최대 몇 번 반복됐나
     */
    public record Observed(int status, String body, String escapedTypeOrNull, int worstQueryRepeat) {}
}
