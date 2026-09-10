// 관측 대상. Hindsight 가 지켜볼 앱이다.
//
// 🔴 지금은 「배관이 되는지」를 재기 위한 최소 상태다. 설계(§3-4)가 정한 대로
// JPA 연관관계·트랜잭션·Redis·Docker Compose 를 갖춘 진짜 서비스로 키운다.
// 이유: 평가할 수 없는 포트폴리오는 높은 점수가 아니라 0점을 받는다.
//
// DB 는 H2 다 — 파일도 서버도 없이 메모리에서 돈다. 그래도 커넥션 풀은
// HikariCP 를 그대로 쓰므로, 「풀 프록시 때문에 SQL 이 두 번 세이나」는
// 이 상태에서도 그대로 확인된다.

plugins {
    alias(libs.plugins.springboot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
