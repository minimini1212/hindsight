# `record` 에 없는 필드가 JSON 에 나타나서 왕복이 깨진다

## 증상

기록을 JSON 으로 썼다가 다시 읽으면 실패한다.

```
UnrecognizedPropertyException:
  Unrecognized field "incomplete" (class io.hindsight.model.Integrity), not marked as ignorable
```

🔴 **`Integrity` 에는 `incomplete` 라는 구성 요소가 없다.** 그런데 JSON 에는 들어가 있다.

## 원인

Jackson 은 `isXxx()` 모양의 메서드를 **자동으로 JSON 필드로 만든다.**

```java
public record Integrity(long droppedEvents, ...) {
    public boolean isIncomplete() { ... }   // ← 이게 "incomplete" 필드가 된다
}
```

쓸 때는 문제가 없다(그냥 필드가 하나 더 붙는다). **읽을 때** record 의 생성자에
그런 인자가 없어서 터진다.

🔴 **평범한 클래스라면 `@JsonIgnore` 한 줄이면 끝난다. 그런데 이 모듈에는 못 붙인다.**
`hindsight-model` 은 관측 대상 앱의 클래스패스에 올라가므로 **Jackson 애너테이션을 붙이는 순간
`jackson-annotations` 가 남의 JVM 으로 딸려 들어간다.**
🧭 [`../../../rules/agent-safety-decision.md`](../../../rules/agent-safety-decision.md) §3

## ❌ 먼저 시도했다가 되돌린 것

매퍼에서 getter 자동 인식을 끄면 될 줄 알았다.

```java
.setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
.setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE)
```

**결과: 더 나빠졌다.** 통과 테스트가 3개에서 6개 실패로 늘었고, 출력이 이렇게 나왔다.

```
Expecting actual:  "{ }"
```

🔴 **Jackson 은 record 의 접근자도 getter 로 본다.** `id()`·`droppedEvents()` 가 전부 같이
꺼져서 **아무것도 직렬화되지 않았다.**

## ⭕ 실제 해결

**`hindsight-model` 의 파생 메서드를 `get`·`is` 로 시작하지 않게 짓는다.**

```java
public boolean hasGaps()                 // isIncomplete → hasGaps
public boolean windowFellShort()
public boolean allowsAutoPullRequest()
```

Jackson 은 `hasXxx()` 를 자동 인식하지 않는다. 애너테이션 없이 해결된다.

## 규칙을 사람이 기억하지 않게 한다

이름 규칙은 **언젠가 누가 잊는다.** 그래서 왕복 테스트가 지킨다.

```java
@Test
@DisplayName("🔴 파생 메서드가 JSON 필드로 새지 않는다")
void derivedHelpersDoNotLeakIntoJson() {
    String json = codec.toJson(Recordings.full());
    assertThat(json).doesNotContain("\"incomplete\"").doesNotContain("\"gaps\"");
    assertThat(json).contains("\"droppedEvents\"");   // 구성 요소는 살아 있어야 한다
}
```

🎯 **이 결함을 찾아낸 것도 왕복 테스트였다.** 붙이자마자 잡혔다.

## 배운 것

⚠️ **「의존성을 못 늘린다」는 제약은 애너테이션을 못 쓴다는 뜻이기도 하다.**
그걸 미리 생각 못 했다. 이 부류의 문제가 또 나오면 **매퍼 설정을 건드리기 전에
「모델 쪽 이름으로 피할 수 있나」를 먼저** 본다 — 매퍼 설정은 범위가 넓어서 옆엣것까지 끈다.

🧭 관련: [`../../../rules/stack-decision.md`](../../../rules/stack-decision.md) §4 ·
[`../design-review.md`](../design-review.md)
