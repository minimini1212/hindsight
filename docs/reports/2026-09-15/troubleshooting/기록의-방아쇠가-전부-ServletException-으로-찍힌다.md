# 기록의 방아쇠가 전부 `ServletException` 으로 찍힌다 (그리고 두 번째부터는 기록이 아예 안 생긴다)

> 2026-09-15. [v0 기록기](../v0-recorder.md)를 진짜 스프링 앱에 붙이자마자 나왔다.

## 증상

컨트롤러는 분명히 `IllegalStateException` 을 던졌는데, 기록 파일에는 이렇게 적힌다.

```json
"trigger": {
  "kind": "EXCEPTION",
  "entryPoint": "GET /test/boom",
  "exception": {
    "type": "jakarta.servlet.ServletException",
    "message": "Request processing failed"
  }
}
```

검사에서는 이렇게 보인다.

```
Expecting actual:
  "jakarta.servlet.ServletException"
to contain:
  "IllegalStateException"
```

🔴 **그리고 더 나쁜 증상이 뒤따른다. 두 번째 사고부터는 기록 파일이 아예 안 생긴다.**
앱은 계속 터지는데 `recordings/` 폴더가 조용하다. 오류도 안 난다.

## 원인

**서블릿 컨테이너와 스프링은 컨트롤러가 던진 예외를 «감싸서» 필터에게 준다.**

```
컨트롤러  throw new IllegalStateException("...")
   │
   ▼
스프링 MVC   catch → throw new ServletException(원래예외)   ← 여기서 감싼다
   │
   ▼
우리 Filter  catch (Throwable t)   ← t 는 ServletException 이다
```

그래서 예외 «종류»를 그대로 적으면 모든 기록이 `ServletException` 이 된다.

### 🔴 두 번째부터 기록이 안 생기는 이유는 그 결과다

같은 사고를 묶는 열쇠가 이렇게 만들어진다.

```
dedupKey = 진입점 | 방아쇠 종류 | 스택 서명
```

스택 서명도 감싼 예외의 것을 쓰면 **스프링 내부의 같은 줄**을 가리킨다. 결국 서로 다른
버그가 전부 같은 열쇠를 갖고, [묶기 장치](../../../../hindsight-recorder-simple/src/main/java/io/hindsight/recorder/CaptureLimiter.java)가
*"이 사고는 이미 기록했다"* 로 판단한다.

🔴 **즉 첫 번째 버그 하나만 기록되고, 그 뒤의 모든 버그가 조용히 버려진다.**
묶기 장치는 제 일을 한 것이다 — 잘못된 것은 열쇠의 재료였다.

## 해결 — 껍데기를 벗기고 «진짜» 원인을 쓴다

```java
private static Throwable rootCauseOf(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; depth < 10; depth++) {
        Throwable cause = current.getCause();
        if (cause == null || cause == current) {
            return current;
        }
        current = cause;
    }
    return current;
}
```

그리고 방아쇠의 종류·메시지·스택·스택 서명을 **전부 이 벗겨낸 예외에서** 뽑는다.
🔴 넷 중 하나라도 감싼 쪽에서 뽑으면 묶는 열쇠가 다시 같아진다.

- **깊이를 제한하는 이유**: 예외가 자기 자신을 원인으로 갖는 고리가 실제로 만들어질 수 있다.
  제한이 없으면 여기서 무한히 돈다 — **관측 도구가 요청 스레드를 붙잡고 안 놓는 것**이고,
  그건 앱을 죽이는 것과 같다.

## 🔴 시도했다가 실패한 방법

| 해 본 것 | 왜 안 됐나 |
| --- | --- |
| 🔴 **기록기만 떼어 놓고 검사하기** | **이 결함이 안 보인다.** 그 검사에서는 우리가 예외를 직접 만들어 넘기므로 «감싸는 일이 일어나지 않는다». 검사 28건이 전부 통과했는데도 진짜 앱에서는 틀렸다 |
| `failure.getMessage()` 만 보고 판단하기 | `"Request processing failed"` — 원래 메시지가 아니다. 감싼 예외의 메시지는 **원인을 가린다** |
| 한 겹만 벗기기(`getCause()` 한 번) | 겹이 둘 이상인 경우가 있다. 예: `ServletException → NestedServletException → 진짜` |
| 예외 종류 대신 «메시지»로 묶기 | 메시지에 `id=7` 같은 값이 들어가면 **같은 버그가 매번 다른 사고로 세인다** — 반대 방향으로 고장 난다 |

## 곁다리로 알게 된 것

- **이 부류의 결함은 「감싸는 쪽」이 있어야 드러난다.** 같은 날 발견한 다른 하나
  (이벤트 순서가 섞이던 것)도 「같이 도는 쪽」이 있어야 드러나는 종류였다.
  🧭 [그날 기록](../v0-recorder.md)
- 검사에서 스프링 컨트롤러를 시험용으로 하나 띄울 때, 설정 클래스 안에 중첩한
  `@RestController` 에 `@Bean` 메서드까지 같이 쓰면 **같은 컨트롤러가 두 번 등록되어
  「Ambiguous mapping」 으로 앱이 아예 안 뜬다.** 중첩 클래스는 스프링이 알아서 등록한다
