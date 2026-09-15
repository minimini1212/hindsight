# CLAUDE.md — Hindsight

**Rules only.** Status, measurements, and decision history live in the source-of-truth docs
listed below — never duplicate them here. A rules file that mirrors content owned elsewhere
goes stale, and a stale rule is worse than no rule: the next session reads it as a gate.

> Standalone Gradle multi-module project. Not part of any monorepo.
> Conventions here are ported from the author's TypeScript projects; **no code is shared**.

## Language

- **Reply to the user in Korean.**
- **All repo artifacts stay Korean**: docs, reports, commit messages, code comments, test names.
- **This file is English** because it is instructions for Claude, not a deliverable.

### 🔴 Answer so a junior developer can follow it (HARD)

- 🔴 **Keep the term. Add a short gloss in parentheses right after it.** Do NOT swap the word
  out for a plain-Korean paraphrase — the user wants to learn the real word, so the word stays
  and the explanation rides along beside it.

  ```
  ⭕  계측(원본 소스를 안 고치고 실행 시점에 코드를 끼워 넣는 것)을 붙인다
  ❌  실행할 때 코드를 끼워 넣는 걸 붙인다        ← 용어를 없앴다
  ❌  계측을 붙인다                                ← 설명이 없다
  ```

- **Gloss on the first use in that reply**, then use the bare term. Do not skip the gloss
  because you explained it earlier in the conversation — assume the user reads only this message.
- 🔴 **A section number is never an explanation. Name what is in it.** Carry the subject inline,
  then the pointer.

  ```
  ⭕  재생 등급이 「모름」을 「없음」으로 접지 못하게 막는 자리(설계 §3-4)
  ❌  설계 §3-4 를 보라
  ```

- 🔴 **Never name a process you have not just described.** If a phrase first appears in this
  reply, spell out what physically happens — what gets recorded, what grows, what becomes
  possible — before naming it.
- **One concrete number beats one adjective.** `버퍼가 크다` → `60초 · 6,000건 · 60MB`.
- 🔴 **Do not put Korean prose inside `AskUserQuestion`.** In this environment that tool
  corrupts Korean text. Ask by writing numbered choices as prose in the reply body instead.

## How this user wants you to work

These are not preferences about this codebase — they are how the user works. They were
learned across months on their other projects, and they do not travel with a new session,
so they live here.

### 🔴 Don't ask. Do all of it, then report.

Two things are worth interrupting for: **"this would break something"** and **"this is the
user's call, not a technical one."** Everything else — sequencing, naming, which file first —
you decide and proceed. No progress updates mid-task either; finish, then report.

⚠️ When you catch yourself ending a reply with *"shall I do A or B?"* on a question you
could answer yourself: **pick one, say why in one line, and do it.**

### 🔴 "Check it" means check all of it

Never sample and extrapolate. If the user asks whether something holds, check every
instance, not a representative few. And **you do not get to decide which findings matter** —
report what you found and let them weigh it.

### 🔴 Measure before calling something a defect

Do not repeat a warning, a doc line, or a review finding as if it were an observed fact.
State how you counted and what you counted, then give the number. When you quote a ratio,
give the denominator.

This project has been wrong about itself twice — the "SQL is counted twice" worry did not
happen in v0, and the 13-module structure was defended with a reason that collapsed the
moment it was followed one step further. **Both were found by measuring, not by arguing.**

### Say it so someone new can follow it

- Keep the technical term, add a short gloss in parentheses on first use in that reply.
  Never swap the term out for a paraphrase — the user wants the real word.
- **Never open a sentence with a file path or a section number.** Say what happened and why
  it matters, then put the pointer in parentheses.
- 🔴 **A section number is not an explanation.** `§7-2` tells the reader nothing until they
  open it. Name what is in it, then point.
- Never name a process you have not just described. Spell out what physically happens.
- One concrete number beats one adjective.

---

## Sources of truth — read these, don't restate them here

| Question | File |
| --- | --- |
| What are we building, and why? | `docs/PRD.md` |
| **What are the modules, what does each stage take and return, what gets built first?** | `docs/DESIGN.md` **(read before writing code)** |
| Recording file structure, field names, schema version, env vars | `docs/DATA_CONTRACT.md` **(read before writing code)** |
| **Why is *this* shaped this way?** (decision history, reversals) | `docs/rules/<area>-decision.md` — index: `docs/rules/README.md` |
| **Where is everything, and why does each module exist?** | `docs/00_CODE_WALKTHROUGH.md` **(start here; update it in the same commit as the code)** |
| **Picking this up fresh — what to read, in what order** | `docs/reports/2026-09-11/handoff.md` |
| Current state, deferred work, pending decisions | `TODOS.md` (top section) |
| **What happened on a given day** (measurements, reviews, troubleshooting) | `docs/reports/<YYYY-MM-DD>/` — index: `docs/reports/README.md` |
| **How do I get past this error again** | `docs/reports/<YYYY-MM-DD>/troubleshooting/<symptom>.md` |

If a rule below conflicts with a source-of-truth doc, **the doc wins** — then fix this file.

---

## HARD RULES

### 🔴 The agent must never break the app it is attached to

`hindsight-agent` runs **inside someone else's JVM, on their classpath**. Everything below
exists because a monitoring tool that takes down the service it monitors is worse than no tool.

- 🔴 **Zero external dependencies in `hindsight-agent` and `hindsight-model`.** JDK standard
  library only. The one exception is ByteBuddy, and it is **shaded** into
  `io.hindsight.shaded.*` so it cannot collide with the host app's copy.
- 🔴 **Never let an exception escape agent code into the host app.** Every instrumentation
  entry point is wrapped in `try { … } catch (Throwable t) { … }` that swallows and counts.
  A failure in observation must never become a failure in the service.
- 🔴 **Self-disable on repeated failure.** After N consecutive internal failures the agent
  turns its own instrumentation off and records why. Degrading is always better than dying.
- 🔴 **Guard against re-entry.** Agent code performs I/O of its own. Without a `ThreadLocal`
  re-entry guard, writing a recording is itself recorded, forever.
- **Never block the host's request thread.** Ring-buffer writes go through a queue that a
  separate thread drains. 🔴 **When the queue is full, drop and count — never block.**
- **`hindsight-agent` compiles to Java 17**, not 21. It has to attach to apps in the wild.
- Spring and every heavy library belong in `hindsight-core`, nowhere near the agent.

Rationale and the incidents behind each line: `docs/rules/agent-safety-decision.md`.

### 🔴 Never fold 「unknown」 into 「none」

This is the defect class the whole project exists to detect, so the tool must not commit it.

- A replay grade of `PARTIAL` is **never** written as `VERIFIED_DETERMINISTIC`. A recording whose
  clock calls were not captured is *not* the same as one that made no clock calls.
- A truncated body is marked `truncated: true`, not silently shortened.
- Events dropped because the queue was full are **counted and reported**, never omitted.
- An unknown `schemaVersion` **halts with a clear message**. It never falls back to a default.
- 🔴 `?? []`, `?: emptyList()`, `Optional.orElse(defaults)` over a value that means "we did not
  look" is the exact shape being forbidden. Model absence and emptiness as different states.

### 🔴 The LLM may not edit its own grader

The verification loop grades LLM patches by replaying a recording. If the LLM can edit the
recording or the generated test, it will make the test pass by deleting it. This is observed
behavior in LLM coding tools, not a hypothetical.

- 🔴 Patches may only touch paths on the **whitelist** (default `src/main/**`).
- 🔴 `src/test/**` and any recording file are **read-only to the LLM, always**.
- Every patch's changed-file list is checked **before** it is applied. Off-list → discard.
- Replay tests are **regenerated from the recording** on every run. Never trust what is on disk.
- Attempts are capped (default 3). After that it is a human's problem, and the tool says so.

### 🔴 The tool does not deploy

It opens a PR and stops. Replay proves *this one situation is fixed*; it proves nothing about
what else may have broken. CI and a human decide the rest. Do not add a deploy step, and do not
propose one.

### Secrets and privacy

- 🔴 **Never hardcode secrets** (LLM API keys, the pseudonymization key, DB URIs). All of it
  lives in `.env` / environment variables.
- 🔴 **Claude never writes `.env` itself.** Ask the user; they add it.
- 🔴 **Pseudonymization happens in exactly one place**, immediately before a recording is
  written to disk. Spread it across call sites and one site will eventually be missed — and
  that is the moment real user data lands on disk.
- 🔴 **A recording is pseudonymized before it is sent to an LLM.** Bind the two steps inside one
  function so the order cannot be inverted by a later edit.
- `Authorization`, `Cookie`, `Set-Cookie`, and password-shaped fields are **dropped**, not
  pseudonymized.

### Git

- **Finishing a unit of work on a `<type>/<slug>` branch means: stage named paths, commit,
  `git push -u origin <that branch>`, and open the PR into `dev`.** Push was added by the
  user 2026-09-11, replacing "the user pushes, Claude never pushes" — a local commit that
  looks pushed is the quietest way to lose a day's work, and checking
  `git branch -r --contains <sha>` after the fact only catches it if someone remembers to look.
  **Opening the PR was added 2026-09-15**, replacing "opening the PR stays the user's action":
  a pushed branch is safe but invisible — the diff, the checks and the reason for the work
  only come together on the PR page, and that page is what the user actually looks at.
  - 🔴 **Only the branch that was just worked on.** Never `git push` `dev` or `main`,
    never `--force` / `--force-with-lease`, never `push --delete`. Rewriting or deleting
    what is already on the remote is the one thing a push must not do.
  - 🔴 **Never `git add .`** — stage named paths, every time. A push makes a stray
    staged file everyone else's problem.
  - 🔴 **Open the PR, then stop. Never merge it.** Not through the PR, not by merging
    locally and pushing, not with `--admin`, not by enabling auto-merge. Opening a PR
    *proposes*; merging *decides*, and **what enters `dev` is the user's decision.**
    The line moved from "before the PR" to "after the PR" — it did not disappear.
  - **The PR is a proposal, so it says what it is proposing**: what changed, what was
    measured, and 🔴 **what is still unverified**. A PR description that claims more than
    the work proved is the same defect class as folding 「unknown」 into 「none」.
    Title and body in Korean, like every other repo artifact — only the branch name is English.
  - 🔴 **Opening a PR needs `gh`, and `gh` needs its own login** — the Windows credential
    store that `git push` reads is not where `gh` looks, and Claude must never read a stored
    token to call the API by hand. Installing `gh` and logging it in once:
    `docs/reports/2026-09-15/troubleshooting/PR을-열려는데-gh가-없다고-나온다.md`.
    Pushing has its own trap (the credential helper list must be emptied first):
    `docs/reports/2026-09-11/troubleshooting/git-push-가-아무-말-없이-멈춘다.md`.
  - Report the branch name, the pushed SHA and the PR URL when the work is done, so there
    is nothing to verify by hand.
- **Commit only when the work is actually finished**, not at every edit — one commit is one
  topic, the same topic the branch is named after. `./gradlew build` passes first
  (that run includes `checkDocLinks` and `checkAgentDependencies`).
- Commit messages in Korean, with the request count when a run went out:
  `fix(에이전트): 계측 예외가 앱으로 새던 자리 — 요청 0건`

#### Branches — `dev` is the base, and nothing is committed onto it directly

Set 2026-09-11. `dev` is the default branch on GitHub; `main` is the released line.

- 🔴 **Never commit onto `dev` or `main`.** Branch first, always — even for a one-line fix.
- Name: `<type>/<english-kebab-slug>`, 2–4 words. The type is the same word the commit
  message uses (`feat` · `fix` · `docs` · `build` · `refactor`), so
  `git log --oneline dev..HEAD` reads as one story.
- 🔴 **Branch names are the one repo artifact that stays English.** They become URLs, CI job
  names and docker tags, where Korean turns into percent-encoding nobody can read.
  Commit messages, docs and comments stay Korean.
- Branch off `dev`, PR into `dev`. `git switch dev && git pull && git switch -c feat/…`
- 🔴 **One branch = one reason you sat down to work** — not one kind of file. Whatever that
  work produces rides along on it: the troubleshooting note the failure taught you, the index
  row, the doc the decision changed, the test that proves it.
  - **Two things still get their own branch**: a change to the discipline itself (this file),
    because it has to be revertable without reverting the work; and a fix with **no causal
    link** to what you are doing — someone else's bug you happened to notice.
  - ⚠️ Set 2026-09-11 after measuring the opposite: reading "one topic" as "one kind of file"
    produced **3 branches in one afternoon, two of them 1–2 files**, each with its own PR —
    and two of those touched the same `docs/reports/README.md`, so splitting them *added* a
    conflict instead of preventing one. Splitting earns its keep only when you would want to
    revert the halves separately.
- 🔴 **The branch does not exist for anyone else until it is pushed.** That is why
  finishing work now ends in a push (see the Git rules above). `git branch -r --contains <sha>`
  is how you confirm it landed — run it when a push errors out, not instead of pushing.
- 🔴 **Merged branches are kept, not deleted — local and remote both.** The user's call,
  2026-09-11. A branch name is a label on a piece of work that a commit range is not:
  `feat/replay-state-restore` says what those two commits were *for*, and it stays
  checkoutable long after the merge commit's subject line stops meaning anything.
  Never delete a branch, never propose deleting one, and leave GitHub's
  "automatically delete head branches" off.

---

## Code and verification

- **Java 21** everywhere except the modules that load inside the observed app —
  `hindsight-model`, `hindsight-agent`, `hindsight-agent-boot` (**Java 17**).
  Why not 25: `docs/rules/stack-decision.md`.
- **Gradle multi-module.** Build with `./gradlew`. Never commit `build/`.
- Prefer `record` for data, `sealed interface` for closed variant sets, pattern matching over
  instanceof chains. This project is partly a showcase of modern Java — use it, don't perform it.
- **Judgement and scoring live in pure functions**, separated from I/O, so they can be tested
  without a JVM under instrumentation.
- **Comments explain the choice, the rejected alternative, and what breaks without it** —
  never a translation of the line below. ASCII diagrams belong in files with non-obvious
  multi-step flow (the ring buffer, the replay pipeline, the verification loop).
  🔴 **A stale diagram is worse than none** — update it in the same commit as the code.
- Run `./gradlew :<changed-module>:test` while working. Run `./gradlew build` once before
  asking for a commit — not after every edit. That run includes `checkDocLinks` and
  `checkAgentDependencies`, so **a commit that breaks a doc link or the agent's dependency
  line cannot pass a full build.**
- 🔴 **A round-trip test is mandatory** for the recording format: what the agent's hand-written
  JSON writer produces must parse back to an equal object through `hindsight-core`. Two
  codebases serialize the same schema; without this test they drift silently.
- **When behavior or a contract changes, update the owning doc in the same change.**

### 🔴 Where each kind of writing goes

```
docs/
├── PRD.md · DESIGN.md · DATA_CONTRACT.md · 00_CODE_WALKTHROUGH.md
│                              "what it is now" — no history here
├── rules/<area>-decision.md   "why we chose this" — BY TOPIC, not by date
└── reports/
    ├── README.md              the index — every new file gets a row
    └── <YYYY-MM-DD>/          "what happened that day"
        ├── <topic>.md         measurements, reviews, the numbers that came out
        └── troubleshooting/
            └── <symptom>.md   "we got stuck, here's how we got out"
```

- **One folder per day.** Create it the day you write the first file for it.
  A day with three topics gets three files, not one long one.
- 🔴 **Name a troubleshooting file after the SYMPTOM, not the cause.**
  Next time you search by what you see, not by what it turned out to be —
  if you knew the cause you would not be searching.
  `테스트-출력의-한글이-깨진다.md` ⭕ / `인코딩-설정.md` ❌
- 🔴 **Every troubleshooting file records the approach that FAILED**, not just the fix.
  That is the part that saves the next person a day.
- **Add a row to `docs/reports/README.md`** in the same commit. Without the index,
  troubleshooting gets buried under a date nobody remembers.
- 🔴 **Never write the same thing in two places.** If a measurement changes the design,
  edit the design doc and leave only "what we measured and why it changed" in the report.

### 🔴 A document that points at a file that isn't there is a defect

The docs here point at each other constantly — design → decision record → measurement →
troubleshooting. **Move one file and those links break silently.** Nobody finds out until
someone clicks one, which is long after.

This is not hypothetical: the design review's finding list included *"three referenced
documents do not exist on disk"*, and moving docs into date folders on 2026-09-11 broke
five relative paths at once.

- **The build checks it.** `./gradlew checkDocLinks` (wired into `check`) fails on any
  relative link whose target is missing. 🔴 **Do not "fix" a failure by deleting the link** —
  a pointer to something that should exist is information; a missing pointer is not.
- **After moving any document, run it before you commit.** The usual breakage is *depth*:
  from `docs/reports/<date>/a.md` the rules folder is `../../rules/`, not `../rules/`.
- A rule the build cannot check is a rule that eventually is not followed. That is why this
  one, and the agent dependency line, are Gradle tasks and not paragraphs.

### 🔴 Create a folder only when you write its first file

Never pre-create empty module or package folders. An empty folder reads as "someone started
this", so the next person opens it to find out — every time. No folder, no question.
The map of what goes where lives in `docs/00_CODE_WALKTHROUGH.md` §2, not on disk.
`settings.gradle.kts` lists only modules that have files; the rest are comments there.

(This rule exists because 13 modules were pre-created on 2026-09-10, producing 56 empty
folders and the question "what are all these?" — then deleted.)

## Before changing anything

1. Read `docs/00_CODE_WALKTHROUGH.md` §2 to see where the thing you are adding belongs.
2. Read `docs/DESIGN.md` for the stage you are touching.
3. Read `docs/DATA_CONTRACT.md` if a recording field is involved.
4. Run `git status --short` — the user may have edits in flight.
5. Read the test next to the file you are about to change.
