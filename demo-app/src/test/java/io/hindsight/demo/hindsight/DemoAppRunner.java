package io.hindsight.demo.hindsight;

import io.hindsight.core.brain.VerificationLoop;
import io.hindsight.core.replay.GeneratedTest;
import io.hindsight.demo.order.OrderService;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.util.ArrayList;
import java.util.List;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * 🔴 <b>채점 네 겹을 «진짜 앱»에 잇는 자리.</b>
 *
 * <p>{@link VerificationLoop} 는 테스트를 어떻게 돌리는지 모른다 — 전부 {@code Runner} 로
 * 받는다. 그래야 판단이 JVM 을 계측 아래 띄우지 않고 전수로 시험된다.
 * <b>이 클래스가 그 «받는 쪽»을 처음으로 진짜 앱으로 채운 것이다.</b>
 *
 * <h2>어느 겹이 무엇으로 이어지나</h2>
 * <pre>
 *   ㉠ 기준선 실패  │ 기록에서 만든 테스트를 메모리에서 컴파일해 JUnit 으로 돌린다
 *   ㉡ 검증 통과    │ 같은 테스트를 «패치 후»에 다시 돌린다
 *   ㉢ 기존 테스트  │ 🔴 앱 «자신의» 테스트를 패키지째 훑어 돌린다
 *   ㉣ 되돌리기    │ 패치를 되돌리고 ㉠ 을 다시 본다
 * </pre>
 *
 * <h2>⚠️ 여기서 「패치」는 파일이 아니라 설정 한 줄이다</h2>
 * 진짜 도구에서는 LLM 이 소스를 고치고 {@code PatchApplier} 가 파일을 쓴 «뒤» 앱을 다시 띄운다.
 * 여기서는 {@link OrderService#setNPlusOneFixedForTest} 로 갈아탄다.
 *
 * <p>🔴 <b>그래서 이 고리가 보이는 것은 「네 겹이 진짜로 도는가」이지 「LLM 이 고칠 수
 * 있는가」가 아니다.</b> 다만 ㉣(되돌리기)만큼은 «진짜»다 — 되돌리면 앱의 동작이 실제로
 * 되돌아가고, 테스트가 다시 실패한다.
 *
 * <h2>🔴 ㉢ 은 앱 패키지를 훑되, 도구의 패키지는 «이름으로» 뺀다</h2>
 * 클래스 이름을 하나 적어 두면 앱에 테스트가 새로 생겨도 ㉢ 은 계속 옛날 것만 돌리고,
 * 그걸 「전부 통과」라고 말한다. 그렇다고 전부 돌리면 <b>이 고리가 자기 자신을 다시 부르고</b>
 * (재귀), 「도구가 스스로를 검사한 것」을 「앱이 안 깨졌다」로 읽게 된다.
 * 그래서 <b>훑되 빼는</b> 방식이다.
 */
final class DemoAppRunner implements VerificationLoop.Runner {

    private static final String 앱의_뿌리_패키지 = "io.hindsight.demo";

    /**
     * 🔴 ㉢ 에서 «빼는» 패키지. 도구가 스스로를 검사한 것을 「앱이 안 깨졌다」로 읽으면 안 되고,
     * 무엇보다 이 고리가 <b>자기 자신을 다시 부르게</b> 된다.
     */
    private static final String[] 도구의_패키지들 = {
            "io.hindsight.demo.hindsight",
            "io.hindsight.demo.experiment",
    };

    private final GeneratedTest generated;
    private final OrderService orderService;

    /** 무엇이 실제로 돌았는지 사람이 읽는 기록. 🔴 「안 돌린 것」을 나중에 가려내려면 필요하다. */
    private final List<String> 발자국 = new ArrayList<>();

    DemoAppRunner(GeneratedTest generated, OrderService orderService) {
        this.generated = generated;
        this.orderService = orderService;
    }

    @Override
    public boolean 재생_테스트가_통과하나() {
        var 결과 = GeneratedTestRunner.컴파일해서_돌린다(generated);
        발자국.add("재생 테스트: 돌았나=" + 결과.돌았나() + " 통과했나=" + 결과.통과했나()
                + (결과.왜() == null ? "" : " (" + 한줄로(결과.왜()) + ")"));

        // 🔴 「못 돌렸다」를 「통과」로 돌려주지 않는다. 컴파일이 안 됐는데 true 를 주면
        //    ㉠ 이 「패치 전에도 통과한다」로 읽혀 고리가 엉뚱한 곳에서 멈춘다.
        return 결과.돌았나() && 결과.통과했나();
    }

    @Override
    public boolean 기존_테스트가_전부_통과하나() {
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        Launcher launcher = LauncherFactory.create();
        // 🔴 클래스 이름을 하나 적어 두지 «않는다». 그러면 앱에 테스트가 새로 생겨도
        //    ㉢ 은 계속 옛날 것만 돌리고, 그걸 「전부 통과」라고 말한다.
        //    앱 패키지를 통째로 훑고, 도구 것만 «이름으로» 뺀다.
        launcher.execute(request()
                .selectors(selectPackage(앱의_뿌리_패키지))
                .filters(org.junit.platform.engine.discovery.PackageNameFilter
                        .excludePackageNames(도구의_패키지들))
                .build(), listener);

        TestExecutionSummary summary = listener.getSummary();
        if (summary.getTestsFoundCount() == 0) {
            // 🔴 「돌릴 게 없었다」를 「전부 통과」로 치지 않는다. 그게 이 프로젝트가
            //    잡으려는 「모름을 없음으로 접는」 결함 그 자체다.
            발자국.add("🔴 기존 테스트: 돌릴 것이 하나도 없었다 — 통과로 치지 않는다");
            return false;
        }
        boolean 통과 = summary.getTestsFailedCount() == 0;
        발자국.add("기존 테스트: " + summary.getTestsSucceededCount() + "/"
                + summary.getTestsFoundCount() + " 통과");
        return 통과;
    }

    @Override
    public boolean 패치를_적용한다() {
        orderService.setNPlusOneFixedForTest(true);
        발자국.add("패치 적용 (join fetch 로 갈아탄다)");
        return true;
    }

    @Override
    public boolean 패치를_되돌린다() {
        orderService.setNPlusOneFixedForTest(false);
        발자국.add("패치 되돌림");
        return true;
    }

    List<String> 발자국() {
        return List.copyOf(발자국);
    }

    private static String 한줄로(String s) {
        String 한줄 = s.replace('\n', ' ').replace('\r', ' ').trim();
        return 한줄.length() <= 80 ? 한줄 : 한줄.substring(0, 80) + "…";
    }
}
