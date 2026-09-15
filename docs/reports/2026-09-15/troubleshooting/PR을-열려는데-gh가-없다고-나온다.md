# PR 을 열려는데 `gh` 가 없다고 나온다 (그리고 깔아도 로그인이 안 돼 있다)

> 2026-09-15. [「작업을 마치면 PR 까지 연다」로 규율이 바뀌면서](../../../../CLAUDE.md) 처음 부딪힌 자리다.
> 밀어 넣는 쪽(`git push`)의 함정은 [따로 있다](../../2026-09-11/troubleshooting/git-push-가-아무-말-없이-멈춘다.md) — **원인이 다르다.**

## 증상

**두 단계로 나온다. 첫 번째를 고치면 두 번째가 나온다.**

```
$ gh --version
bash: gh: command not found
```

윈도우 쪽에서도 없다.

```powershell
PS> Get-Command gh
gh NOT FOUND
```

깔고 나면 이번엔 이렇게 나온다.

```
$ gh auth status
You are not logged into any GitHub hosts. To log in, run: gh auth login
```

## 원인

**「`git` 이 밀 수 있으니 PR 도 열 수 있겠지」가 틀렸다.** 둘은 자격 증명(계정·토큰)을
**다른 자리에서** 읽는다.

| | 어디서 읽나 | 지금 상태 |
| --- | --- | --- |
| `git push` | 윈도우 자격 증명 관리자. 이 저장소 것은 **옛 형식**(`LegacyGeneric`)이라 `wincred` 헬퍼로만 읽힌다 | ✅ 된다 ([그 함정](../../2026-09-11/troubleshooting/git-push-가-아무-말-없이-멈춘다.md)) |
| `gh pr create` | **`gh` 자신의 설정 파일**(`~/.config/gh/hosts.yml`) 또는 `GH_TOKEN` 환경변수 | ⬜ 로그인 전에는 비어 있다 |

🔴 **그래서 push 가 되는 것은 PR 이 될 거라는 근거가 아니다.** 「연결은 되는데 PR 만 안 되는」
모양이 나오고, 이건 공개 저장소라 읽기가 자격 증명 없이 되는 것과 같은 착시다.

## 해결

### 1. `winget` 으로 깐다 (관리자 권한 없이 된다)

```powershell
winget install --id GitHub.cli -e --source winget `
  --accept-source-agreements --accept-package-agreements `
  --disable-interactivity --scope user
```

```
찾음 GitHub CLI [GitHub.cli] 버전 2.100.0
...
경로 환경 변수 수정됨; 셸을 다시 시작하여 새 값을 사용합니다.
설치 성공
```

- 🔴 **`--scope user` 를 준다.** 기본값은 컴퓨터 전체 설치라 관리자 권한을 묻고,
  묻는 창은 우리가 볼 수 없는 자리에 뜬다 — `git push` 가 멈추던 것과 같은 함정이다.
- `--disable-interactivity` 는 안전장치다. 뭔가 물어보려 하면 **멈추는 대신 실패**한다.

### 2. 🔴 지금 셸에서는 `gh` 가 여전히 안 잡힌다 — 전체 경로로 부른다

`winget` 이 「경로 환경 변수 수정됨」이라고 찍지만 **그건 새로 여는 셸에 적용된다.**
지금 돌고 있는 셸의 `PATH` 는 그대로다.

```
C:\Users\USER\AppData\Local\Microsoft\WinGet\Packages\GitHub.cli_Microsoft.Winget.Source_8wekyb3d8bbwe\bin\gh.exe
```

찾는 방법:

```powershell
Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter gh.exe |
  Select-Object -First 1 -ExpandProperty FullName
```

### 3. 🔴 로그인은 «사람이» 한 번 해야 한다

```powershell
gh auth login --hostname github.com --git-protocol https --web
```

브라우저가 열리고 여덟 자리 코드를 넣는 화면이 나온다. **이 단계는 대화형이라
비대화형 세션에서 대신 해 줄 수 없다.** 한 번 하면 `~/.config/gh/hosts.yml` 에 남아서
다음부터는 묻지 않는다.

토큰을 이미 들고 있다면 대신 이렇게 해도 된다.

```powershell
$env:GH_TOKEN = "<PAT>"        # repo 권한이 있어야 PR 을 만들 수 있다
```

🔴 **토큰을 저장소 안 파일에 적지 않는다.** 환경변수나 `.env` 에 두고, `.env` 는 사용자가 쓴다.

### 4. 확인

```powershell
gh auth status
# ✓ Logged in to github.com account <사용자> (keyring)
```

### 5. PR 을 연다

```powershell
gh pr create --base dev --head <브랜치> --title "<한국어 제목>" --body-file <파일>
```

- 🔴 **`--base dev`** 를 반드시 준다. 기본값은 저장소의 기본 브랜치인데, 이 저장소는 `dev` 가
  기본이라 지금은 우연히 맞다. **우연에 기대지 않는다.**
- 본문은 `--body` 로 직접 주지 말고 **`--body-file` 로 파일에서 읽힌다.** 한국어 본문을
  명령줄에 그대로 넣으면 셸을 거치면서 깨질 수 있다
  ([같은 부류의 함정](../../2026-09-11/troubleshooting/gradle-테스트-한글-깨짐.md)).

## 🔴 시도했다가 실패한 방법

| 해 본 것 | 왜 안 됐나 |
| --- | --- |
| 🔴 **저장된 `git` 자격 증명을 읽어서 GitHub REST API 로 PR 만들기** (`git credential fill` → `POST /repos/.../pulls`) | **에이전트 안전장치가 막는다** — 저장된 비밀을 꺼내는 행위(Credential Materialization)로 분류되어 거부됐다. 🔴 **그리고 막는 게 맞다.** PR 하나 열자고 사용자의 토큰을 평문으로 꺼내 드는 건, 그 뒤에 무엇을 해도 되는 상태가 된다는 뜻이다. `gh` 는 토큰을 자기 안에서만 쓴다 |
| `gh` 가 깔려 있는지 `bash` 에서만 확인 | WSL·Git Bash 와 윈도우의 `PATH` 가 다르다. **양쪽 다 봐야** 「없다」가 참이 된다 |
| 설치 후 바로 `gh auth status` 를 전체 경로 없이 호출 | 지금 셸의 `PATH` 에 아직 없다. `command not found` 가 또 나와서 **설치가 실패한 줄 알기 쉽다** |
| `gh auth login` 을 비대화형으로 끝내기 | 브라우저 + 코드 입력이라 **자동화가 안 된다.** `--with-token` 은 토큰이 있어야 하고, 토큰을 얻는 것이 원래 문제다 |

## 곁다리로 알게 된 것

- **`git push` 와 PR 열기는 실패 모양이 다르다.** push 는 **아무 말 없이 멈추고**(자격 증명
  관리자가 보이지 않는 창을 띄운다), PR 은 **즉시 또렷하게 실패한다**(`not logged into`).
  🔴 조용한 실패가 더 나쁘다 — 단서가 0바이트다
- 이 컴퓨터에는 `choco` 도 있지만 `winget` 을 썼다. `choco` 는 기본이 관리자 권한이다
