# DATA_CONTRACT.md — 기록 파일의 구조

> 🔴 **코드를 쓰기 전에 읽는다.** 여기 적힌 것이 필드 이름·타입·「모름」의 표현 방식의 유일한 근거다.
> 왜 이런 모양인지는 [DESIGN.md](DESIGN.md), 결정의 번복 이력은 [rules/](rules/README.md).

---

## §0 이 문서에 나오는 말

| 말 | 뜻 |
| --- | --- |
| 기록(recording) | 사고 순간 앞뒤의 경계 값을 묶은 파일 하나 |
| 경계 이벤트(event) | 프로세스 밖과 값이 오간 한 건. HTTP 요청 하나, SQL 하나 |
| 스키마 버전 | 이 파일의 구조가 몇 번째 판인지. 재생 코드가 읽을 수 있는지 판단하는 근거 |
| 잘라내기(truncation) | 너무 큰 본문을 앞부분만 남기고 자르는 것. **잘랐다는 사실을 반드시 표시한다** |
| 가명화 | 원본 값을 가짜로 바꾸되 같은 원본은 늘 같은 가짜가 되게 하는 것 |

---

## §1 파일 한 개 = 기록 한 건

```
recordings/
  2026-09-10T14-23-11.482Z__a1b2c3d4.json     ← 포획 시각 + 짧은 고유 id
```

- 이름은 **정렬하면 시간순**이 되게 한다
- 🔴 **파일 하나가 완결적이다.** 재생에 필요한 모든 것이 이 안에 있다. 옆 파일을 안 읽는다

---

## §2 최상위 구조

```jsonc
{
  "schemaVersion": 1,              // 🔴 필수. 모르는 값이면 재생을 거부한다 (§7)
  "id": "a1b2c3d4",
  "capturedAt": "2026-09-10T14:23:11.482Z",
  "trigger": { … },                // §3
  "app": { … },                    // §4
  "events": [ … ],                 // §5 — 전문. 재생에 쓰는 것은 이것뿐이다
  "summary": { … },                // §5-0 — 요약. 재생엔 못 쓰고 사람이 원인을 찾는 데 쓴다
  "jfr": { … },                    // §6
  "replay": { … },                 // §7
  "integrity": { … }               // §8
}
```

### 🔴 §2-1 왜 `events` 와 `summary` 두 층인가

「최근 60초를 다 들고 있다」는 **산수가 안 맞는다.** 초당 200요청 × 60초 = 12,000건이고,
요청당 본문·SQL을 합쳐 7KB만 잡아도 84MB다. 자바 객체 부담을 빼고도 성능 예산(설계 §9)의
64MB를 넘긴다. 실제로는 **3~8초치만 담기고 버퍼가 밀려난다.**

🔴 **그런데 파일에는 「최근 60초」라고 적힌다.** 이건 이 프로젝트가 막으려는
「모름을 없음으로 접기」를 도구가 자기 자신에게 저지르는 것이다.

그래서 층을 나눈다.

| 층 | 크기 | 담는 것 | 쓰는 곳 |
| --- | --- | --- | --- |
| `events` | **16MB · 최근 5초** | 본문·파라미터·결과 행 전문 | **재생.** 이 층만 재생에 쓸 수 있다 |
| `summary` | **16MB · 최근 60초** | SQL 지문과 횟수, 요청 줄과 헤더 **이름만**. 본문·행 없음 | 사람이 원인을 찾는 데. 커넥션 누수처럼 **30초 전에 시작된 일**이 여기 보인다 |

이렇게 하면 설계 §4의 「원인은 결과보다 먼저 일어난다」와 §9의 메모리 예산이 **둘 다 참**이 된다.

```jsonc
"summary": {
  "windowSeconds": 60,
  "sqlShapes": [ { "sqlHash": "9f2a…", "normalized": "select o from Order o where o.memberId = ?",
                   "count": 812, "totalMs": 1204 } ],
  "requestLines": [ { "at": "…", "method": "GET", "path": "/api/orders", "status": 200, "ms": 42 } ],
  "connectionsOpen": [ { "at": "…", "leased": 8, "idle": 2 } ]
}
```

---

## §3 `trigger` — 무엇이 이 파일을 만들었나

```jsonc
"trigger": {
  "kind": "EXCEPTION",             // EXCEPTION | LATENCY | MANUAL | RESOURCE | MEMORY
  "at": "2026-09-10T14:23:11.482Z",
  "entryPoint": "GET /api/orders",
  "exception": {                   // kind=EXCEPTION 일 때만
    "type": "org.hibernate.LazyInitializationException",
    "message": "…",
    "stack": ["…"]                 // 최대 50줄
  },
  "latencyMs": 3412,               // kind=LATENCY 일 때만
  "dedupKey": "GET /api/orders|LazyInit|OrderService.findAll:88",
  "dedupCount": 1                  // 같은 dedupKey 로 접힌 건수 (§9)
}
```

---

## §4 `app` — 어떤 코드에서 났나

```jsonc
"app": {
  "name": "demo-app",
  "gitCommit": "8acb6ba…",         // 🔴 없으면 null. 「모름」이다 (§10)
  "gitDirty": true,                // 커밋 안 된 변경이 있었나
  "javaVersion": "21.0.4",
  "agentVersion": "0.1.0",
  "hostname": "prod-web-03"        // 🔴 가명화 대상 (§11)
}
```

🔴 **`gitCommit` 이 왜 필요한가**: 기록은 2주 전 코드에서 났는데 지금 소스는 바뀌어 있을 수 있다.
재생할 때 현재 HEAD와 다르면 **경고를 띄운다.** 막지는 않는다 — 알리기만 한다.

---

## §5 `events` — 경계에서 오간 값들

시간순 배열. 모든 이벤트가 아래 공통 필드를 갖는다.

```jsonc
{ "seq": 41, "corrId": "r-7f3a", "at": "…", "thread": "http-nio-8080-exec-3",
  "type": "…", "durationMs": 12 }
```

- `seq` 는 **에이전트가 매기는 전역 순번**이다. 재생은 이 순번으로 값을 되돌려준다.
- 🔴 `corrId` 는 **어느 요청에 속한 이벤트인지**를 말한다. 이게 없으면 요청 둘이 동시에 들어왔을 때
  SQL이 어느 쪽 것인지 알 수 없고, 재생이 잘라낼 조각을 못 만든다.
  🔴 **`thread` 로 대신하지 않는다** — 가상 스레드(virtual thread)는 요청마다 새로 생겼다 사라지고
  식별자가 재사용된다.

### §5-1 `HTTP_IN` — 들어온 요청과 그 응답

```jsonc
{
  "type": "HTTP_IN", "seq": 1, "corrId": "r-7f3a",
  "method": "GET", "path": "/api/orders", "query": {"page": "2"},
  "handler": "OrderController#list",       // 어느 메서드가 처리했나
  "headers": { "content-type": "application/json" },   // 🔴 Authorization·Cookie 는 제거됨
  "body": "…", "bodyTruncated": false, "bodyBytes": 340,
  "responseStatus": 500,
  "responseBody": "…", "responseBodyTruncated": false   // 🔴 §5-1-1
}
```

#### 🔴 §5-1-1 응답 본문은 반드시 남긴다

**생성된 테스트가 상태 코드만 검사하면 LLM은 그것만 맞추면 된다.** 예외를 통째로 삼키는
`@ExceptionHandler` 를 하나 추가하면 500이 200으로 바뀌고, 버그는 그대로인 채 재생이 통과한다.
이건 가정이 아니라 **가장 저항이 적은 통과 경로**다.

응답 본문까지 검사해야 「같은 값을 돌려주는가」를 물을 수 있다.

### §5-2 `HTTP_OUT` — 나간 요청 *(v1부터)*

`HTTP_IN` 과 같은 모양에 `url` 이 추가된다. 재생할 때 **진짜로 안 보내고 여기 적힌 응답을 돌려준다.**

### §5-3 `SQL` — DB 질의

```jsonc
{
  "type": "SQL", "seq": 42,
  "sql": "select o from Order o where o.memberId = ?",
  "params": ["m-4821"],            // 🔴 가명화 대상
  "rowCount": 1,
  "rows": [ { … } ],               // 최대 100행. 넘으면 앞 100행 + rowsTruncated:true
  "rowsTruncated": false,
  "repeatOf": 41,                  // 🔴 §5-4
  "repeatCount": 200
}
```

### §5-4 🔴 같은 질의가 반복될 때 — `repeatOf` / `repeatCount`

**N+1 문제는 이 도구가 잡으려는 대표 버그인데, 그 상황에서 SQL 201건의 결과를 전부 기록하면
버퍼가 터진다.** 그래서 같은 SQL 모양이 반복되면:

- **처음 3건만 전문**을 남긴다
- 나머지는 `{ "repeatOf": <첫 건의 seq>, "repeatCount": 200, "params": […] }` 로 접는다
- 파라미터는 남긴다. 결과는 안 남긴다

🔴 **이건 손실이 아니라 신호다.** 「같은 질의 200회」 자체가 진단에 필요한 바로 그 사실이다.

### §5-5 `CLOCK` · `RANDOM` *(v1부터)*

```jsonc
{ "type": "CLOCK", "seq": 12, "source": "java.time.Instant.now", "value": "2026-09-10T14:23:10.001Z" }
{ "type": "RANDOM", "seq": 13, "source": "java.util.UUID.randomUUID", "value": "3f2a…" }
```

재생은 `source` 별로 `seq` 순서대로 값을 되돌려준다. 🔴 **잡지 못한 호출은 여기 없다** —
그 사실이 §7의 재생 등급을 `PARTIAL` 로 만든다.

---

## §6 `jfr` — JVM 내부 지표

JFR(Java Flight Recorder, JVM이 기본 제공하는 실행 기록기)에서 뽑은 요약. **원본 `.jfr` 파일은
따로 저장하고 여기엔 요약만 넣는다.**

```jsonc
"jfr": {
  "dumpFile": "a1b2c3d4.jfr",      // 없으면 null
  "gcPauseMsTotal": 41, "gcCount": 3,
  "lockWaitMsTotal": 0, "lockHotspots": [],
  "heapUsedBytesAtTrigger": 412000000,
  "threadCount": 84
}
```

---

## §7 `replay` — 이 기록을 얼마나 믿을 수 있나

```jsonc
"replay": {
  "grade": "PARTIAL",              // VERIFIED_DETERMINISTIC | PARTIAL | DIVERGED | FAILED
  "missing": ["CLOCK"],            // 🔴 무엇을 못 잡았는지 이름을 적는다
  "baselineFailed": true,          // 🔴 §7-1. 패치 전에 정말 실패했나
  "diverged": null,                // §7-2. 기록에 없는 경계를 건드렸다면 무엇인지
  "stateRestore": {                // 🔴 §7-3. 재생 전에 «어디까지» 되돌렸나
    "rows": true,                  //   테이블 내용
    "identityCounters": true,      //   🔴 「다음 id 는 몇 번」. 행을 지워도 안 돌아간다
    "caches": null,                //   null = 안 봤다. false = 보았고 못 되돌렸다
    "external": null               //   외부 시스템 (v1 부터)
  },
  "staleAfter": "2026-10-10T…",    // 기본 30일. 지나면 STALE 표시 (막지는 않는다)
  "notes": "System.currentTimeMillis 는 v0 범위 밖"
}
```

| 등급 | 뜻 | 진단에 미치는 영향 |
| --- | --- | --- |
| `VERIFIED_DETERMINISTIC` | 재생이 만든 경계 호출이 기록과 **전부 맞았고 순서도 같았다**. 🔴 이름에 「확인됨」이 붙은 이유: 이건 **관찰이지 약속이 아니다** | 진단을 그대로 신뢰 |
| `PARTIAL` | 일부를 못 잡았다. `missing` 에 이름이 있다 | 진단에 **경고를 붙여** 사람에게 |
| `DIVERGED` | 🔴 재생 중 **기록에 없는 경계**를 건드렸다 (§7-2) | 채점 불가. 사람에게 근거와 함께 넘긴다 |
| `FAILED` | 재생 자체가 안 된다 | **LLM을 부르지 않는다.** 기록만 남긴다 |

🔴 **`PARTIAL` 을 `VERIFIED_DETERMINISTIC` 으로 접지 않는다.** 「모름」을 「없음」으로 접는 것과 같은 결함이다.
시각 호출을 못 잡은 기록은 **시각 호출이 없었던 기록과 다르다.**

🔴 **복원을 못 한 재생도 같다.** 되돌리지 않은 채 돌린 재생이 원본과 다른 것은 **패치 탓이 아니다.**
그걸 `DIVERGED` 나 `FAILED` 로 적으면 아무 잘못 없는 패치가 벌을 받는다. 등급은 `PARTIAL`,
`missing` 에 `"STATE"` — 그리고 왜 못 되돌렸는지를 `notes` 에 적는다 (§7-3).

### 🔴 §7-1 `baselineFailed` — 패치 전에 실패하지 않았으면 채점이 아니다

재생이 애초에 버그를 못 살리면, **패치 전에도 초록색이고 패치 후에도 초록색**이다. 그런데 도구는
「통과했다」며 아무것도 안 고친 패치로 PR을 올린다. 채점기가 있다고 믿는 구조에서 이건 치명적이다.

- 🔴 **생성된 테스트가 패치 전 코드에서 실패하지 않으면 등급은 `FAILED` 다.** LLM을 부르지 않는다
- 기준선(baseline) 실행과 검증 실행은 **같은 JVM·같은 설정·같은 씨앗**으로 돈다.
  20분 뒤 다른 조건에서 돌린 두 결과를 비교하지 않는다

### 🔴 §7-2 `DIVERGED` — 고치면 기록에 없는 질의가 생긴다

**이 도구가 잡으려는 대표 버그가 바로 이 문제를 일으킨다.**

N+1을 고치는 정석은 질의 201개를 **조인 한 개**로 바꾸는 것이다. 그런데 **그 새 질의는 기록에 없다.**
가짜 DB가 돌려줄 값이 없으니 재생이 멈춘다. `WHERE` 절 추가, 컬럼 하나 더 읽기, `LEFT JOIN` →
`INNER JOIN` 도 전부 같다. **가장 흔한 수정들이 전부 채점 불가다.**

🔴 **이걸 실패로 처리하면 도구가 쓸모없어진다. 그래서 별도의 결과로 만든다.**

```jsonc
"diverged": {
  "kind": "SQL_SHAPE_CHANGED",
  "before": { "sqlHash": "9f2a…", "count": 201 },
  "after":  { "normalized": "select o from Order o join fetch o.member", "count": 1 },
  "verdict": "진단이 말한 방향과 일치한다"     // 질의 201 → 1 은 N+1 해소와 부합
}
```

- 재생 중 기록에 없는 SQL을 만나면 **정규화한 모양**(테이블·조건)으로 기록의 지문(`summary.sqlShapes`)과
  맞춰 본다
- 가까운 것이 있으면 `DIVERGED` 로 등급을 매기고 **「무엇이 무엇으로 바뀌었나」를 PR 본문에 붙여**
  사람에게 넘긴다
- 🔴 **통과로 치지 않는다.** 다만 「고장」으로도 치지 않는다. 이건 **가장 좋은 PR 설명**이 된다:
  *"패치가 질의 201개를 1개로 바꿨다. 재생으로는 채점할 수 없으니 근거를 붙여 사람에게 넘긴다."*

⚠️ **이 한계는 문서 뒤로 숨기지 않는다.** 재생은 질의 모양이 바뀌는 수정을 채점할 수 없다.
그렇다고 말하고, 대신 증거를 만들어 준다.

### 🔴 §7-3 `stateRestore` — 되돌리지 않고 재생한 것은 재생이 아니다

**재생은 기록 시점 상태로 되돌린 뒤 요청을 다시 보내는 것이다.** 되돌리지 않으면
같은 요청이 다른 답을 낸다 — 그리고 그건 패치가 아니라 **우리가 만든 차이**다.

🔴 **행만 되돌리는 것은 되돌린 게 아니다.** 행을 같은 내용·같은 개수로 복원해도,
DB 가 들고 있는 「다음 `id` 는 몇 번」이라는 숫자는 안 돌아간다. 그러면 행에 붙는 `id` 가
어긋나고, 기록에 담긴 `memberId: 1` 이 가리키는 것이 사라져서 **재생이 예외로 죽는다.**
🧭 세 갈래로 잰 숫자: [`reports/2026-09-11/write-replay-experiment.md`](reports/2026-09-11/write-replay-experiment.md)

| 값 | 뜻 |
| --- | --- |
| `true` | 되돌렸다 |
| `false` | 🔴 **보았고 못 되돌렸다.** 등급은 `PARTIAL`, `missing` 에 `"STATE"` |
| `null` | 🔴 **안 봤다.** `false` 와 다른 사실이다 — 되돌릴 수 있었는지조차 모른다 |

- 🔴 **`stateRestore` 가 통째로 없는 기록을 「복원됨」으로 읽지 않는다.** 모르는 것은 모르는 것이다
- 복원이 `rows` 만 `true` 인 기록은 **가장 위험한 기록이다.** 부분 복원은 무복원보다 나쁘다 —
  무복원은 답이 다르게 나와서 눈에 띄지만, 부분 복원은 재생이 죽고 채점기가 그걸
  「패치가 못 고쳤다」로 읽는다

### §7-4 `schemaVersion` 이 모르는 값일 때

🔴 **거부하고 그렇게 말한다.** 기본값으로 읽지 않는다. 필드가 늘거나 뜻이 바뀐 파일을
옛 코드가 「대충」 읽으면, 그 잘못된 재생 결과가 LLM 진단의 근거가 된다.

---

## §8 `integrity` — 이 기록이 온전한가

```jsonc
"integrity": {
  "windowRequestedSeconds": 60,    // 🔴 담으려고 한 시간
  "windowActualSeconds": 4.2,      // 🔴 실제로 담긴 시간 — 이 둘은 거의 항상 다르다
  "droppedEvents": 0,              // 큐가 차서 아예 못 받은 건수
  "evictedEvents": 8140,           // 버퍼가 넘쳐 앞에서 밀려난 건수
  "evictedBytes": 71000000,
  "bufferBytes": 16700000,
  "agentErrors": 0,                // 에이전트 내부에서 삼킨 예외 건수
  "instrumentationDisabled": false // 자기 무력화가 걸렸나
}
```

### 🔴 §8-1 `windowRequestedSeconds` 와 `windowActualSeconds` 는 반드시 둘 다 적는다

설정에 60초라고 써 있다고 60초가 담기지 않는다. 트래픽이 많으면 바이트 상한이 먼저 걸려
**실제로는 4초치만 남는다.** 이때 파일에 「60초」만 적혀 있으면, 읽는 사람은 30초 전에 시작된
커넥션 누수가 **일어나지 않았다고** 결론 내린다. 안 담긴 것을 안 담겼다고 말하지 않으면
「없었다」와 「못 담았다」가 똑같아 보인다 — **이 프로젝트가 막으려는 바로 그 결함이다.**

- `windowActualSeconds` 가 요청값보다 짧으면 `hs show` 가 **눈에 띄게 경고한다**
- `evictedEvents` 가 0이 아니면 §5-0 의 요약층(`summary`)을 함께 읽으라고 안내한다
- 🔴 `droppedEvents` 와 `evictedEvents` 는 **다른 사실**이다. 앞은 「받지도 못했다」,
  뒤는 「받았다가 밀어냈다」. 합쳐서 하나로 적지 않는다

---

## §9 상한값 — 전부 설정으로 읽는다

🔴 **한 모듈에서만 읽는다.** 호출하는 쪽마다 기억하게 만들면 언젠가 한 곳이 잊는다.

| 이름 | 기본값 | 무엇 |
| --- | --- | --- |
| `HINDSIGHT_WINDOW_SECONDS` | 60 | 링 버퍼가 들고 있는 시간 |
| `HINDSIGHT_BUFFER_MAX_BYTES` | **32MB** | 🔴 시간과 **둘 중 먼저 오는 쪽**이 이긴다 |
| `HINDSIGHT_BODY_MAX_BYTES` | 64KB | 요청·응답 본문 잘라내기 |
| `HINDSIGHT_SQL_ROWS_MAX` | 100 | SQL 결과 행 |
| `HINDSIGHT_SQL_REPEAT_FULL` | 3 | 같은 질의를 몇 건까지 전문으로 남기나 |
| `HINDSIGHT_CAPTURES_PER_HOUR` | 20 | 시간당 포획 상한 |
| `HINDSIGHT_STORE_MAX_BYTES` | 1GB | `recordings/` 총량. 넘으면 오래된 것부터 지운다 |
| `HINDSIGHT_LATENCY_TRIGGER_MS` | 3000 | 지연 방아쇠 |
| `HINDSIGHT_AGENT_ERROR_LIMIT` | 50 | 이만큼 실패하면 계측을 스스로 끈다 |
| `HINDSIGHT_PSEUDONYM_KEY` | *(없음)* | 🔴 `.env`. 없으면 **가명화 대상 필드를 통째로 버린다** |
| `HINDSIGHT_LLM_MAX_ATTEMPTS` | 3 | 패치 시도 횟수 |
| `HINDSIGHT_LLM_MAX_USD` | **1.0** | 🔴 기록 하나당 비용 상한. 넘으면 멈추고 사람을 부른다. 「돈이 떨어져 멈췄다」와 「못 고쳤다」를 **다르게 적는다** |
| `HINDSIGHT_PATCH_ALLOWED_PATHS` | `src/main/**` | 🔴 LLM이 고칠 수 있는 경로 |

🔴 **값을 못 읽으면 멈춘다.** 기본값으로 조용히 되돌아가지 않는다.
그리고 **어디서 온 값인지 로그에 남긴다** — `30 (기본값)` 과 `30 (환경변수)` 는 다른 사실이다.

---

## §10 「모름」의 표현 — 이 프로젝트의 핵심 규율

| 상황 | 이렇게 적는다 | 🔴 이러면 안 된다 |
| --- | --- | --- |
| git 커밋을 못 알아냄 | `"gitCommit": null` | `""` 또는 필드 생략 |
| 본문이 잘림 | `"bodyTruncated": true` | 조용히 짧은 본문만 |
| 시각을 못 잡음 | `grade: PARTIAL, missing: ["CLOCK"]` | `grade: DETERMINISTIC` |
| 큐가 차서 버림 | `"droppedEvents": 17` | `0` 또는 필드 생략 |
| SQL 결과가 잘림 | `"rowsTruncated": true` | 앞 100행만 조용히 |

**`null` 은 「안 봤다/못 봤다」이고, `[]` 와 `0` 은 「보았고 없었다」다. 둘은 다른 사실이다.**

---

## §11 가명화 — 무엇을 어떻게 바꾸나

🔴 **기록을 파일로 쓰기 직전, 한 곳에서만 한다.**

| 분류 | 대상 | 처리 |
| --- | --- | --- |
| **버린다** | `Authorization`·`Cookie`·`Set-Cookie` 헤더, `password`·`secret`·`token` 이름의 필드, 카드번호·주민번호 패턴 | 필드째 제거하고 `"[dropped]"` 표시 |
| **가명화** | 이메일·전화·이름·주소로 지정된 필드 경로, `hostname` | `HMAC-SHA256(키, 원본)` → 형태를 지킨 가짜 값 |
| **그대로** | 나머지 | — |

```
hong@abc.com    →  user_7f3a1c@example.invalid    ← 같은 원본은 언제나 같은 가명
010-1234-5678   →  010-0000-4821                   ← 형태는 지키고 값만 바꾼다
```

🔴 **형태를 지키는 이유**: `***` 로 가리면 그 값으로 조회하던 코드가 재생에서 안 돈다.
개인정보는 사라지되 **값들 사이의 관계는 살아남아야** 재생이 성립한다.

🔴 **키가 없으면 가명화 대상 필드를 통째로 버린다.** 원본을 그대로 쓰는 쪽으로 흐르지 않는다.
