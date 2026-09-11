# `JAVA_HOME is set to an invalid directory` — 경로는 맞는데 그렇게 나온다

## 증상

WSL·터미널에서 한 줄로 환경변수를 주고 빌드를 걸면 이렇게 나온다.

```
ERROR: JAVA_HOME is set to an invalid directory: C:\Users\USER\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2
Please set the JAVA_HOME variable in your environment to match the location of your Java installation.
```

🔴 **그런데 그 경로는 실재한다.** `ls` 로 들어가 보면 `bin`·`conf`·`lib` 가 다 있다.

## 원인

`cmd` 의 `set` 은 **줄 끝까지를 값으로 가져간다.** 그래서 `&&` 앞의 공백이 값에 붙는다.

```
set JAVA_HOME=C:\...\adoptium-17 && gradlew.bat build
                                 ↑ 이 공백까지 JAVA_HOME 이다
```

값이 `...adoptium-17` 이 아니라 `...adoptium-17 ` (뒤에 공백)이 되고, 그런 폴더는 없다.
🔴 **오류 메시지가 경로를 그대로 찍어 주는데도 안 보인다** — 눈에 안 띄는 공백이라서.
위 메시지의 경로 끝에 공백 두 칸이 붙어 있는 게 유일한 단서다.

## 해결 — 한 줄짜리 `.bat` 파일로 만든다

```bat
@echo off
set JAVA_HOME=C:\Users\USER\.gradle\jdks\eclipse_adoptium-21-amd64-windows.2
cd /d C:\intellij_workspace\hindsight_project
call gradlew.bat build --console=plain
exit /b %ERRORLEVEL%
```

`set` 이 자기 줄을 혼자 쓰므로 뒤에 붙을 공백이 없다.

## 🔴 시도했다가 실패한 방법

| 해 본 것 | 왜 안 됐나 |
| --- | --- |
| **값을 따옴표로 감싼다** — `set "JAVA_HOME=C:\..."` | 🔴 **안 먹혔다.** 바깥 셸(bash)이 따옴표를 한 겹 벗겨서 `cmd` 에는 다른 모양으로 도착한다. 셸 두 개를 거치는 동안 따옴표가 어떻게 남는지를 예측하는 쪽으로 가면 계속 헛돈다 |
| 경로가 진짜 있는지 다시 확인한다 | 있다. 원인이 경로가 아니라 **값 끝의 공백**이라서 아무리 봐도 안 보인다 |
| 17 대신 21 로 바꿔 본다 | 같은 오류. 버전 문제가 아니었다 |

## 곁다리로 알게 된 것

- **WSL 안에는 JDK 가 없다.** `/usr/lib/jvm` 도 `~/.gradle/jdks` 도 비어 있고,
  JDK 는 윈도우 쪽(`/mnt/c/Users/USER/.gradle/jdks`)에만 있다. 윈도우 실행 파일이라
  WSL 에서 직접 못 돌린다 — `cmd.exe /c` 로 넘겨야 한다
- 테스트가 찍은 것을 읽을 때는 콘솔 말고 **결과 XML** 을 본다
  (`demo-app/build/test-results/test/TEST-*.xml` 의 `<system-out>`).
  🧭 [한글 깨짐 문서](gradle-테스트-한글-깨짐.md)
