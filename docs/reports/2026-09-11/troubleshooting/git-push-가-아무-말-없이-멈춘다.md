# `git push` 가 아무 말 없이 멈춘다 (그리고 WSL 에서는 `could not read Username`)

## 증상

두 가지 모양으로 나온다. **같은 원인의 다른 얼굴이다.**

WSL 쪽 `git` 으로 밀면 즉시 실패한다.

```
fatal: could not read Username for 'https://github.com': No such device or address
```

윈도우 쪽 `git.exe` 로 밀면 **출력이 한 줄도 없이 그냥 멈춘다.** 2분을 기다려도 끝나지 않고,
리디렉션해 둔 출력 파일은 **0바이트**다.

```
$ timeout 110 cmd.exe /c "C:\...\gpush.bat feat/..."
$ echo $?
124        ← 아무 것도 안 찍고 시간만 갔다
```

## 원인

**자격 증명(계정·토큰)을 어디서 읽나가 둘 다 어긋난 것이다.**

- **WSL 쪽**: JDK 와 마찬가지로 **자격 증명이 WSL 안에 없다.** 윈도우의 자격 증명 저장소에
  들어 있고, WSL 의 `git` 은 그걸 볼 방법이 없다. 그래서 사용자 이름을 물어보려 하는데
  터미널이 대화형이 아니라 그 자리에서 죽는다.
- **윈도우 쪽**: 시스템 `gitconfig`(`C:\Program Files\Git\etc\gitconfig`)에
  `credential.helper=manager` 가 박혀 있다. **Git Credential Manager 는 저장된 토큰을 못 찾으면
  창을 띄운다.** 그 창은 우리가 보는 터미널이 아니라 윈도우 데스크톱에 떠 있고,
  아무도 누르지 않으므로 `git` 은 영원히 기다린다. 🔴 **그래서 출력이 0바이트다** —
  실패한 게 아니라 «기다리는» 것이다.

이 저장소의 자격 증명은 **옛 형식**으로 저장돼 있다. 확인하는 방법:

```
cmd.exe /c "cmdkey /list"
   → LegacyGeneric:target=git:https://github.com
   → LegacyGeneric:target=git:https://<사용자>@github.com
```

`LegacyGeneric` 은 `wincred` 헬퍼가 읽는 자리다. 새 `manager` 는 이걸 자기 것으로 취급하지 않는다.

## 해결 — 헬퍼 목록을 «비우고» `wincred` 하나만 준다

```bat
@echo off
cd /d C:\intellij_workspace\hindsight_project
set GIT_TERMINAL_PROMPT=0
git -c credential.helper= -c credential.helper=wincred push -u origin %1
exit /b %ERRORLEVEL%
```

- 🔴 **앞의 `credential.helper=` (빈 값)이 핵심이다.** `credential.helper` 는 «목록»이라서
  `-c` 로 하나 더 주면 **시스템 설정의 `manager` 에 더해진다.** 빈 값을 먼저 주면 목록이
  초기화되고, 그 뒤에 준 `wincred` 만 남는다.
- `GIT_TERMINAL_PROMPT=0` 은 안전장치다. 그래도 물어보려 하면 **멈추는 대신 즉시 실패**한다 —
  매달려 있는 것보다 낫다.
- ⚠️ `set` 은 줄 끝까지를 값으로 가져가므로 `.bat` 파일에 한 줄씩 쓴다.
  한 줄로 `&&` 로 이으면 값 끝에 공백이 붙는다
  ([그 함정](JAVA_HOME이-올바른-디렉터리가-아니라고-나온다.md)).

성공하면 이렇게 나온다.

```
branch 'feat/db-restore-strategy' set up to track 'origin/feat/db-restore-strategy'.
 * [new branch]      feat/db-restore-strategy -> feat/db-restore-strategy
EXIT=0
```

🔴 **밀었다고 믿지 말고 확인한다.** `git fetch origin && git branch -r --contains <sha>` 가
원격 브랜치 이름을 찍어 주면 진짜로 올라간 것이다.

## 🔴 시도했다가 실패한 방법

| 해 본 것 | 왜 안 됐나 |
| --- | --- |
| **WSL 의 `git push`** | 자격 증명이 WSL 안에 없다. `could not read Username` 으로 즉시 죽는다 |
| **윈도우 `git.exe` 로 그냥 push** | 🔴 **2분 넘게 멈춘다.** `manager` 헬퍼가 데스크톱에 창을 띄우고 기다리는데, 우리는 그 창을 볼 수도 누를 수도 없다. 출력이 0바이트라 «왜» 멈췄는지 단서조차 없다 |
| **`-c credential.helper=wincred` 만 추가** | 🔴 **여전히 멈춘다.** 헬퍼는 목록이라서 시스템 설정의 `manager` 가 그대로 남아 같이 돌아간다. **빈 값으로 목록을 비우는 단계가 빠지면 이 방법은 안 듣는다** |
| `GIT_TERMINAL_PROMPT=0` 만 주기 | 멈추는 대신 실패한다 — 원인은 그대로다. 진단에는 쓸모가 있다(멈춘 게 자격 증명 문제였다는 걸 알려 준다) |
| `gh` 명령줄로 밀기 | WSL·윈도우 양쪽에 `gh` 가 없다 |

## 곁다리로 알게 된 것

- **읽기는 자격 증명 없이도 된다.** 공개 저장소라 `git ls-remote --heads origin` 과
  `git fetch` 는 WSL 에서 그냥 동작한다. **그래서 「연결은 되는데 push 만 안 되는」 모양이 된다**
- 이 저장소에서 빌드를 돌리는 것도 같은 구조의 문제다 — JDK 가 윈도우에만 있어서
  `cmd.exe /c` 로 넘겨야 한다 ([JAVA_HOME 문서](JAVA_HOME이-올바른-디렉터리가-아니라고-나온다.md))
