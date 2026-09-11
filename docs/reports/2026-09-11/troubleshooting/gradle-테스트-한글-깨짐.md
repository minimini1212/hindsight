# 테스트 출력의 한글이 `?? ?? ??` 로 깨진다

## 증상

Gradle 로 테스트를 돌리면 **한글이 전부 깨져 나온다.** 테스트 이름도, `System.out` 출력도.

```
SqlCountExperimentTest > ���� �� ���� ���� �� �� �ϸ� ���� ���ڰ� ������ FAILED
�� ���� SQL �� Ƚ��  : 21 ��
```

🔴 **실측 결과를 읽을 수가 없다.** 숫자만 겨우 보이고 그게 무슨 숫자인지 모른다.

## 원인

윈도우에서 Gradle 이 테스트 JVM 을 **`x-windows-949`**(옛 한글 인코딩)로 띄운다.
Gradle 이 플랫폼 기본값을 그대로 물려주기 때문이다. 실제로 넘어가는 인자:

```
-Dfile.encoding=x-windows-949 -Duser.country=KR -Duser.language=ko
```

⚠️ **자바 18부터 파일 인코딩 기본값이 UTF-8 로 바뀌었지만**, Gradle 이 명시적으로
덮어써 버리므로 그 변화가 안 먹는다.

## 해결

루트 `build.gradle.kts` 의 `Test` 과제 설정에 박는다.

```kotlin
tasks.withType<Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
```

`stdout.encoding`·`stderr.encoding` 도 같이 줘야 한다. `file.encoding` 만 바꾸면
**파일은 제대로 쓰는데 콘솔 출력은 여전히 깨진다.**

## 남는 문제 — 읽는 쪽도 UTF-8 이어야 한다

빌드를 고쳐도 **PowerShell 콘솔은 여전히 깨져 보인다.** 콘솔 자체의 코드페이지가 949 라서다.
그래서 실패 원인을 볼 때는 콘솔 대신 **결과 파일을 읽는다.**

```bash
cd <모듈>/build/test-results/test
PYTHONIOENCODING=utf-8 python - <<'PY'
import glob, re, html, sys
sys.stdout.reconfigure(encoding="utf-8")
for f in glob.glob("*.xml"):
    txt = open(f, encoding="utf-8", errors="replace").read()
    for m in re.finditer(r"<system-out>(.*?)</system-out>", txt, re.S):
        print(html.unescape(m.group(1)))
PY
```

⚠️ `sys.stdout.reconfigure` 를 빼면 이번엔 **파이썬이** `cp949` 로 내보내려다 죽는다
(`UnicodeEncodeError: 'cp949' codec can't encode character '\U0001f52c'` — 이모지에서).

## 이게 왜 사소하지 않은가

이 프로젝트는 **문서·주석·테스트 이름·출력이 전부 한글**이다.
깨지면 「보기 나쁘다」가 아니라 **실패 원인을 알 수 없다.**
빌드 배관이 아니라 **필수 설정**으로 다룬다.

🧭 관련: [`../sql-count-experiment.md`](../sql-count-experiment.md) §5 — 이걸 겪은 실험
