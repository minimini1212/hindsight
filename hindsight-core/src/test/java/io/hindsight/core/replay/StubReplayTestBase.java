package io.hindsight.core.replay;

import io.hindsight.model.ReplayInfo;

/**
 * 관측 대상 앱이 채워 주는 자리를 흉내 낸 «구체» 기반 클래스.
 *
 * <h2>🔴 왜 «따로 파일»이고 «public» 인가</h2>
 * 생성된 테스트는 <b>패키지 없는 소스</b>로 만들어진다(어느 패키지에 둘지는 앱이 정한다).
 * 그래서 이 클래스가 시험 클래스 «안»에 중첩돼 있으면, 바깥 클래스가 package-private 이라
 * 생성된 소스에서 <b>접근할 수 없다</b> — 그리고 컴파일 오류 메시지는 그걸
 * 「추상 메서드를 안 채웠다」로 말해 줘서 원인이 가려진다.
 *
 * <p>2026-09-15 에 중첩 클래스로 뒀다가 이 오류를 두 번 겪고 옮겼다.
 */
public class StubReplayTestBase extends ReplayTestBase {

    @Override
    protected ReplayInfo.StateRestore 되돌린다() {
        return new ReplayInfo.StateRestore(true, true, null, null);
    }

    @Override
    protected Observed 재생한다(String method, String path, String body) {
        return new Observed(200, "", null, 0);
    }
}
