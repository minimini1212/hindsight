// 관측 대상 앱 «밖»에서 도는 것 전부. 그래서 의존성이 자유롭다.
//
// 안은 패키지로 나뉜다 — store(읽기·쓰기) · privacy(가명화) · replay(재생·오라클) ·
// guard(패치 경로 검사) · brain(진단·채점) · cli(명령어).
// 🔴 이것들을 모듈로 나누지 않는 이유: 나눠도 얻는 게 없다. 모듈 경계는 「누구의
// 클래스패스에 올라가나」를 가를 때만 값을 한다. 이 안의 코드는 전부 같은 자리에 올라간다.
// 🧭 근거: docs/rules/module-boundary-decision.md
//
// 🔴 매핑 설정(믹스인)이 여기 있는 이유: hindsight-model 에 Jackson 애너테이션을
// 붙이면 그 라이브러리가 남의 JVM 으로 딸려 들어간다. 그래서 model 은 애너테이션 없는
// 순수 record 로 두고, Jackson 에게 「이 클래스는 이렇게 읽어라」를 바깥에서 알려준다.

dependencies {
    api(project(":hindsight-model"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    testImplementation(testFixtures(project(":hindsight-model")))
}
