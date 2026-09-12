# Contributing to Kalendee

Thanks for taking the time to contribute! Kalendee is a self-hosted CalDAV
server with bespoke multiplatform clients, and it grows best with help from
people who use it, break it, document it, and build on it. This guide explains
how to get involved. It is deliberately short: when in doubt, open an issue or
a draft pull request and ask.

## Table of contents

- [Welcome](#welcome)
- [Code of Conduct](#code-of-conduct)
- [Ways to contribute](#ways-to-contribute)
- [Security issues](#security-issues)
- [Getting started](#getting-started)
- [Pull requests](#pull-requests)
- [Commit conventions](#commit-conventions)
- [Code style](#code-style)
- [AI-assisted contributions](#ai-assisted-contributions)
- [Licensing](#licensing)
- [Community](#community)

## Welcome

Kalendee is a Kotlin Multiplatform project: a Ktor server (`:server`), shared
non-UI code (`:core`), and Compose clients for Android, iOS, and Desktop
(`:app:*`). The long-term plan is a self-hosted CalDAV server, themeable
first-party clients, a Keel web UI, and a separate pure-KMP CalDAV protocol
library. It is all early-stage, so there is plenty of room to shape it.

Every kind of contribution is welcome:

- **Code** — features, bug fixes, tests, build and tooling work.
- **Documentation** — setup guides, configuration references, typo fixes.
- **Bug reports** — clear reproductions are incredibly valuable.
- **Design feedback** — UI, UX, and naming suggestions are real contributions.
- **Translations** — not set up yet. If you would like to help get i18n going,
  open an issue and say so.

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](./CODE_OF_CONDUCT.md).
By participating, you agree to uphold it. Report unacceptable behavior as
described in that file.

## Ways to contribute

### Report a bug

Open a new issue and choose the **Bug report** form
([`.github/ISSUE_TEMPLATE/bug_report.yml`](./.github/ISSUE_TEMPLATE/bug_report.yml)).
Include what you expected, what happened, and the exact steps or commands to
reproduce it. Logs, error messages, and which component (server, Android,
Desktop, iOS, web UI) are all helpful.

### Suggest a feature

Open a new issue and choose the **Feature request** form
([`.github/ISSUE_TEMPLATE/feature_request.yml`](./.github/ISSUE_TEMPLATE/feature_request.yml)).
Describe the problem you want solved before jumping to a solution — that makes
it easier to discuss alternatives.

### Improve documentation

Open a new issue and choose the **Documentation** form
([`.github/ISSUE_TEMPLATE/documentation.yml`](./.github/ISSUE_TEMPLATE/documentation.yml)),
or send a pull request directly for small fixes. Outdated setup instructions
are bugs.

### Submit code

Pick up an issue, or open one to discuss what you plan to build. See
[Getting started](#getting-started) below and the full setup guide in
[DEVELOPING.md](./DEVELOPING.md).

## Security issues

Do **not** open a public issue for security vulnerabilities. Contact the
repository owner privately instead (for example via a private message or the
contact address in the repository profile) and give them a chance to fix and
release before disclosing anything publicly. Include affected versions,
reproduction steps, and impact if you can.

## Getting started

1. Install the prerequisites and read [DEVELOPING.md](./DEVELOPING.md). It
   covers everything from zero to a running server and test suite.
2. Fork the repository (or ask the owner for access) and clone your fork. Some
   checkouts have no configured remote yet; add one with
   `git remote add origin <your-fork-url>` if needed.
3. Create a topic branch: `git switch -c feat/my-thing`.
4. Build and test before and after your change:

   ```bash
   ./gradlew :server:test                 # server
   ./gradlew :app:shared:jvmTest          # shared Compose/client logic (Desktop JVM)
   ./gradlew :app:shared:testAndroidHostTest
   ./gradlew :app:androidApp:assembleDebug
   ./gradlew :app:desktopApp:run
   ./gradlew :server:run                  # http://localhost:8080
   ```

5. For manual testing, run the server with the seeded demo data:

   ```bash
   KALENDEE_SEED_DEMO=true ./gradlew :server:run
   ```

   Log in as `demo` / `demo`. See
   [Demo data and screenshots](./DEVELOPING.md#demo-data-and-screenshots) for
   the full dataset and options.

## Pull requests

- Use the [pull request template](./.github/pull_request_template.md) — it
  tells reviewers what context to provide.
- Keep PRs focused and reviewable. One logical change per PR; split unrelated
  cleanups into separate PRs.
- Link related issues: `Closes #123` closes the issue when the PR merges,
  `Relates to #456` links without closing.
- Describe how you tested the change: exact commands, manual steps, and
  screenshots or recordings for UI changes.
- Maintainers may ask questions or request changes. That is normal review, not
  a rejection.
- Small PRs get reviewed faster than large ones.
- Draft PRs are welcome if you want early feedback on an approach.

## Commit conventions

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(calendar): add week-view keyboard navigation
fix(server): reject expired sessions on API routes
docs: clarify .env.local setup
refactor: extract CalDAV date parsing
test: cover recurring event exceptions
build: bump Ktor to 3.x
chore: update issue templates
```

- Use the imperative mood in the subject ("add", not "added").
- Keep the subject short; put details in the body.
- Reference issues in the body or footer: `Refs #123`, `Closes #123`.

## Code style

- Kotlin official code style (`kotlin.code.style=official` is set in
  `gradle.properties`).
- Match the file you are editing. Do not reformat unrelated code or mix
  formatting changes into a functional PR.
- Declare new libraries and plugins in
  [`gradle/libs.versions.toml`](./gradle/libs.versions.toml), never as string
  literals in module scripts.
- Add or update tests for behavior changes. Server tests are the main safety
  net; see [Running tests](./DEVELOPING.md#running-tests).
- Keep `:core` free of Compose, Android, Keel, and UI toolkit types — it must
  stay usable from the server.
- Do not commit `local.properties`, build outputs, `.idea/`, `.kotlin/`, or
  secrets.

## AI-assisted contributions

AI tools are welcome here. Code can be AI assisted to varying degrees as long
as the human responsible can explain it in the PR without using AI to write
the PR. That is the whole policy; the rules below are just what it means in
practice.

- **Allowed at any level.** Autocomplete, generating a first draft, refactoring,
  generating tests, and asking an AI to explain code are all fine.
- **You must be able to explain every change yourself.** If a reviewer asks why
  a line exists, why an approach was chosen, or what a function does, you need
  to answer from your own understanding.
- **Write the PR description and comments yourself.** Do not paste
  AI-generated PR descriptions, review responses, or issue reports. They may be
  grammatically polished, but they must be your words and your understanding.
- **Disclose AI usage in the PR template's AI assistance section.** Say which
  tools you used and what they helped with. Honest disclosure is expected and
  never penalized.
- **"The AI wrote it" is not an acceptable answer** to a review question. If
  you cannot explain a hunk, remove it or go learn it before resubmitting.
- **You are responsible for everything you submit.** Correctness, tests,
  licensing, and security are on the human opening the PR, not on the tool.
- **If you use a coding agent, point it at [AGENTS.md](./AGENTS.md).** It
  documents the repository layout, commands, and conventions that agents are
  expected to follow.

None of this is meant to discourage AI use. It is meant to keep accountability
with a person who understands the code, which is what makes review and
long-term maintenance possible.

## Licensing

Kalendee is licensed under [AGPL-3.0-only](./LICENSE) (inbound = outbound).
There is currently no CLA and no DCO sign-off requirement.

By opening a pull request you agree that your contribution may be distributed
under the AGPL-3.0-only license. Only submit code you can license this way: do
not paste code from projects with incompatible licenses (or from sources you
do not have the right to relicense), and do not copy code out of proprietary
projects.

## Community

- Issues and pull requests are the main place to talk; use them.
- Be patient and kind — this is a spare-time project and reviewers are people
  too.
- There is no such thing as a stupid question. Ask early, ask often.
