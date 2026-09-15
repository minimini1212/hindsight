package io.hindsight.recorder;

/**
 * 설정값을 읽지 못했을 때 던진다.
 *
 * <p>🔴 이 예외가 있는 이유는 「기본값으로 조용히 되돌아가지 않기」 위해서다.
 * 값이 이상하면 앱이 <b>뜨는 시점에</b> 죽는다. 이건 이 프로젝트의 다른 규칙
 * (「관측 도구가 앱을 죽이면 안 된다」)과 어긋나 보이지만 아니다 —
 * 그 규칙은 <b>돌고 있는</b> 앱을 죽이지 말라는 것이고, 여기는 시작 시점이다.
 * 잘못 설정된 채로 뜬 기록기는 상한이 다른 기록을 몇 주 동안 만들어 낸다.
 */
public class InvalidRecorderConfigException extends RuntimeException {

    // 예외는 직렬화될 수 있는 타입이라 이 번호가 없으면 컴파일러가 경고한다.
    // 이 프로젝트는 -Xlint:all 로 빌드하므로 경고를 남기지 않는다.
    private static final long serialVersionUID = 1L;

    private final String name;
    private final String rawValue;

    public InvalidRecorderConfigException(String name, String rawValue, String why) {
        super("설정값 " + name + " 을(를) 읽지 못했다: \"" + rawValue + "\" — " + why);
        this.name = name;
        this.rawValue = rawValue;
    }

    public String name() { return name; }

    public String rawValue() { return rawValue; }
}
