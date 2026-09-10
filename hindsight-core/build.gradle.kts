// 기록을 읽고 쓰는 쪽. 관측 대상 앱 밖에서만 돌기 때문에 의존성이 자유롭다.
//
// 🔴 매핑 설정(믹스인)이 전부 여기 있는 이유: hindsight-model 에 Jackson 애너테이션을
// 붙이면 그 라이브러리가 남의 JVM 으로 딸려 들어간다. 그래서 model 은 애너테이션 없는
// 순수 record 로 두고, Jackson 에게 「이 클래스는 이렇게 읽어라」를 바깥에서 알려준다.

dependencies {
    api(project(":hindsight-model"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    testImplementation(project(":hindsight-testkit"))
}
