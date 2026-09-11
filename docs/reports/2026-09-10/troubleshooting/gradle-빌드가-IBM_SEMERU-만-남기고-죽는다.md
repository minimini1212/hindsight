# 빌드가 `IBM_SEMERU` 한 줄만 남기고 죽는다

## 증상

`./gradlew build` 가 실패하는데 **원인 설명이 없다.** 에러 메시지가 그냥 이것뿐이다.

```
FAILURE: Build failed with an exception.
* What went wrong:
IBM_SEMERU
```

🔴 **무엇이 잘못됐는지 한 글자도 안 알려준다.** 스택 트레이스도, 어느 과제인지도 없다.

## 원인

`foojay-resolver-convention` 플러그인 **0.9.0** 이 **Gradle 9.3** 과 안 맞는다.

이 플러그인은 「이 기계에 없는 자바 판을 자동으로 받아 오는」 역할을 한다.
받아 올 JDK 목록을 훑으면서 공급자 이름을 Gradle 의 `JvmVendorSpec` 으로 바꾸는데,
**Gradle 9 에서 그 목록이 바뀌어 `IBM_SEMERU` 를 못 알아본다.**
그 실패가 설정 단계에서 나기 때문에 어느 과제인지조차 안 나온다.

## 해결

플러그인 버전을 올린다.

```kotlin
// settings.gradle.kts
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
```

🔴 **버전을 내리지 말 것.** 0.9.0 은 이 프로젝트의 Gradle 에서 못 쓴다.

## 왜 이 플러그인이 필요한가

이 기계에는 **JDK 17 하나만** 있다. 그런데 이 프로젝트는 판을 나눠 쓴다.

| 모듈 | 자바 |
| --- | --- |
| 관측 대상 앱 «안»에 들어가는 것 | **17** |
| 바깥 | **21** |

플러그인이 없으면 Gradle 이 21 툴체인을 못 찾아 빌드가 죽는다.
있으면 **직접 받아 온다** — 실제로 `eclipse_adoptium-21` 을 받아서 돌았다.

## 알아낸 방법

에러가 말을 안 해주므로 **바꿀 수 있는 것을 하나씩 지웠다.**
`:hindsight-model:compileJava` 같은 개별 과제는 되는데 `build` 가 안 되는 것을 보고
설정 단계 문제로 좁혔고, 그 단계에서 도는 것이 플러그인 하나뿐이었다.

⚠️ **메시지가 한 단어뿐인 실패는 대개 설정 단계다.** 과제 이름이 안 나오면 그쪽부터 본다.

🧭 관련: [`../../../rules/stack-decision.md`](../../../rules/stack-decision.md) §4
