# Wedding planner — working rules

A private wedding planner for two people: a Spring Boot backend, a React web app and a native Android app.
What it does, the technology and the build order are in `docs/specs/S01-product-scope.md`. Read it before any work.

This repository is **public**. Never commit host names, secrets, tokens, e-mail addresses, or anything about real
guests, vendors or mail. Configuration that names the owner's systems lives in environment variables.

## Layout

| Path | What |
|---|---|
| `backend/` | Java 25, Spring Boot 4.1, Gradle 9, PostgreSQL 18, Flyway. Package `io.github.trufelek124.weddingplanner`; each direct sub-package is a module whose own package is its interface and whose sub-packages are internal |
| `web/` | Vite 8, React 19, TypeScript 5.9 (not newer: the lint tooling does not support TypeScript 7 yet). The backend serves the built files from the directory named by `APP_WEB_DIR` and answers the web app's pages with `index.html` |
| `android/` | Kotlin 2.4 and Jetpack Compose on the Android Gradle plugin 9.5 (its built-in Kotlin: no `kotlin-android` plugin). Application id `io.github.trufelek124.weddingplanner`, minSdk 28. Screens are tested on the JVM with Robolectric, which renders on Android 36 until it supports 37 |
| `Dockerfile`, `deploy/` | The image (backend with the web app inside) and a compose file to try it with PostgreSQL 18. `docs/deployment.md` says what the container needs |
| `docs/specs/` | Specs, `S<nn>-<slug>.md`. A change the owner can notice that no spec covers gets a spec first |
| `docs/decisions.md` | Numbered decisions, one `## D-nnn — title` section each. Read the ones that touch your task |
| `docs/state.md` | One screen on where the project stands — arrives with WP-TS-2 |
| `docs/backlog/` | Backlog batches that were imported into the tracker |
| `scripts/ops/` | Tracker tooling: `ik.py`, `ik_seed.py`, `ik_sync_docs.py` |

The interface is in English. Tests never call the real AI service or the real Gmail.

## Tools and commands

Needed: JDK 25 (the Gradle wrapper finds it by itself), Node 22, and the Android SDK with `ANDROID_HOME` pointing
at it (platform `android-37.0`, build tools `36.0.0`). The cloud environment's setup installs all of these.
Docker is **not** needed for the gate: the backend's tests start their own PostgreSQL 18.
It is needed only to build and try the image (`docs/deployment.md`). In a cloud session the daemon is not running:
start `dockerd` yourself. npm and Gradle inside the build then need the session's proxy and its certificates
(`/root/.ccr/README.md`); build from a copy of the `Dockerfile` with those added, with `--network host`, and never
commit that copy.
A missing tool or a refused download host is an environment matter — ask the owner; never lower a version to get by.

| Part | Check | Run it locally |
|---|---|---|
| `backend/` | `cd backend && ./gradlew test` | `./gradlew devDatabase` (PostgreSQL 18 on port 5433, prints the database variables), then `./gradlew bootRun` with those and `APP_JWT_SECRET` (32+ characters), `ALLOWED_EMAILS` (made-up addresses) and `DEV_LOGIN_ENABLED=true` set |
| `web/` | `cd web && npm ci && npm run lint && npm test && npm run build` | `npm run dev` (port 5173; forwards `/api` and `/actuator` to the backend on 8080) |
| `android/` | `cd android && ./gradlew lintDebug testDebugUnitTest assembleDebug` | No emulator in a cloud session: install `app/build/outputs/apk/debug/app-debug.apk` on a phone. The server address is a build setting: `-PserverUrl=https://…` |
| `scripts/ops/` | the script's `--dry-run` against the tracker | — |
| `docs/` | `scripts/ops/ik_sync_docs.py --dry-run` | — |

**The gate: `scripts/gate.sh`** runs every check of all three parts, stops at the first failure and exits non-zero.
`scripts/gate.sh backend web` runs only the parts named. A change is ready only when the whole gate passes; the
closing comment quotes its summary lines and its exit code.

## Signing in and who may call what

- Everything under `/api` needs an access token, except the paths in `SecurityConfiguration.PUBLIC_API`.
  Adding a public path is a security decision: `AccessControlIntegrationTest` holds the same list on purpose.
- A controller method in any module gets the caller by declaring a `UserId` parameter.
- Only addresses in `ALLOWED_EMAILS` can sign in, with Google or with the development login. The development
  login exists only while `DEV_LOGIN_ENABLED=true`; never set that where the server is reachable by others.
- Nothing personal in logs: users are named by id. A record that holds an address, a name or a token overrides
  `toString()` to hide it, because request and response bodies reach debug logs through that method.
  `NoPersonalDataInLogsIntegrationTest` checks this with debug logging on.
- In tests, `TestSignIn.signIn(http, PostgresIntegrationTest.ANNA)` signs an owner in through the real endpoint.

## The web app

- `App.tsx` decides the screen from two states: the session (`src/auth/session.ts`) and the wedding
  (`src/wedding/useWedding.ts`). A signed-out visitor sees the sign-in whatever the address.
- The access token lives in memory only, never in storage. The refresh token is a cookie scripts cannot read.
  `api` (in `src/api/client.ts`) sends the token and renews it once on a 401; `publicApi` is for the sign-in
  endpoints themselves.
- A signed-in owner's pages are routes in `App.tsx`, drawn inside `Shell` (`src/shell/Shell.tsx`): the top bar, the
  one `main`, and a new page starting at its top with the keyboard inside it. While such a page loads or fails it
  shows `PageWaiting` or `PageRetry`; `Waiting` and `Retry` are whole screens with a `main` of their own.
- A page reads the server with `useLoaded` (`src/planning/useLoaded.ts`). A list keeps its filters in the address
  (`src/planning/filters.ts`). A change to an entry sends the `version` it was based on; `CHANGED_MEANWHILE`
  offers the latest version and never overwrites.
- The chart of the plan (`src/planning/chart/`) is our own SVG, with no charting library (D-001). A bar's place is
  arithmetic on its dates (`layout.ts`); nothing is measured on the page, which is why tests can drag by a known
  distance. New dates go through `useDateChanges`: shown at once, saved one after the other. Whatever a pointer
  can do on the chart also has a key there or a control on the entry's form.
- "Today" is the date where the wedding takes place. The home page takes the days left from the server
  (`GET /api/home`) and never counts them from the device's clock.
- Every colour, size and radius comes from the tokens at the top of `src/index.css`; light and dark both work.
- What a person reads is written for that person. This includes the server's validation messages
  (`message = "Enter your partner's name."`), which the forms show next to the field.
- Tests replace the server with `fakeServer` from `src/test-server.ts` and drive the screens as a person would.
- Google's button is drawn by Google's own library (`src/auth/GoogleButton.tsx`); the page's content security
  policy in `SecurityConfiguration` allows exactly that library. It has not yet been tried against Google.

## Changing the API

`backend/api/openapi.json` is the contract. It is generated from the backend's code and committed; both clients
generate their types from it. The gate fails while any of the three disagree.

1. Change the backend. Then `cd backend && ./gradlew updateApiContract` rewrites `api/openapi.json`.
2. `cd web && npm run generate:api` rewrites `web/src/api/generated/schema.ts`. Never edit that file.
3. Android generates its models at every build (`android/app/build/generated`), so there is nothing to commit.
4. Commit the backend change, `openapi.json` and the web's generated file together.

Rules of the contract:
- Every field of a response is always present; a value that may be missing is `null`, not left out.
- Every error is a `Problem` (RFC 9457). Clients decide from its `type`, never from the wording. The kinds are the
  constants of `ProblemCode`; a module raises one with `ApiException`. A code is never renamed or reused.
- An old phone app meets a newer server: clients ignore unknown fields, and treat an unknown `type` as "some error".
- Money is whole numbers of the smallest unit (grosze), never a decimal fraction.
- The API description itself is served (`/v3/api-docs`) only when `API_DOCS_ENABLED=true`.

## The tracker: infokuznia, project `WP`

Work is tracked as Project → Epic → Story → Task in the owner's infokuznia. The tracker is the source of truth for
work status; this repository is the source of truth for specs, decisions and code.

**Access.** `scripts/ops/ik.py <tool> '<json>'` calls one tracker tool; `--tools` lists them. It needs `IK_MCP_URL`.
Sessions started in the cloud environment `wedding-planner` have that variable (and `IK_ANALYST_ID`), and the
network proxy adds the token. On the owner's Mac the token is read from `~/.infokuznia/token`. If `IK_MCP_URL` is
empty, ask the owner: the address is deliberately not in this repository. Never print, copy or commit a token.

**Ids.** People, commit subjects and docs use public ids (`WP-EP-1`, `WP-US-3`, `WP-TS-12`, `WP-DOC-1`).
Every tool takes the **UUID**. `docs/infokuznia-ids.json` maps our keys and the public ids to UUIDs.
Lists are paged (20 per page by default, `get_next_task` only 5): pass `"pageSize":50` and read every page.
A `GIT_REPOSITORY_NOT_CONFIGURED` note on a task only says the project has no repository linked in the tracker;
it blocks nothing.

### A task first, then the work

1. **Pick a task.** `scripts/ops/ik.py get_next_task '{"projectCode":"WP","pageSize":50}'` lists what this actor
   may take. The list is not in build order: take the lowest epic first, then the lowest task number (S01 §11), and
   check that the code the task builds on is in the repository — the tracker does not know every such dependency.
   Tasks that ask for `PRODUCT_OWNER` are the owner's own steps: never claim them. A task that is already
   `IN_PROGRESS` belongs to the session that claimed it: leave it unless the owner says to take it over.
   Read what the task asks with `scripts/ops/ik.py get_task_context '{"taskId":"<uuid>"}'`.
2. **No fitting task? Create it before starting.** Write a batch `docs/backlog/<date>-<slug>.json` (same shape as
   the first one), check it with `scripts/ops/ik_seed.py <file> --dry-run`, then import it without `--dry-run`.
   Stories need an analyst: `IK_ANALYST_ID` supplies one. Commit the batch and `docs/infokuznia-ids.json`.
3. **Claim it when you start:** `scripts/ops/ik.py claim_task '{"taskId":"<uuid>"}' --quiet`
   (the answer is a very large snapshot). A task whose dependency is unfinished cannot be claimed.
4. **Commit subjects carry the public id:** `WP-TS-12: <what changed>`.
5. **At the end, in this order:** the gate passes → update `docs/state.md` (once it exists) → commit → if
   anything under `docs/` changed, run the document sync and commit its state → push to the branch your session
   was given → `add_comment` → `complete_task`.
   `complete_task` takes `{"taskId","result"}`; `result` is one or two sentences with the commits — later tasks
   that depend on this one see it.

### The closing comment

English, terse, at most 20,000 characters:
- what changed, and which files or generated artefacts moved;
- what was verified: test totals and the gate's exit code;
- what was **not** verified;
- the commits and the branch that holds them.

Never in a comment: secrets, tokens, e-mail addresses, personal data, mail contents. For a security finding that is
not fixed yet: ids, counts and states only.

### When it does not go to plan

- **Stuck on something only the owner can do:** `block_task {"taskId","reason","waitingFor"}`, and say so in chat.
- **Claim refused with `TASK_ROLE_REQUIRED`:** the actor lacks a role. Ask the owner to grant it; do not lower the
  task's roles.
- **The work turned out wrong after completion:** `return_task {"taskId","reason","expectedCorrection"}`.
- Completing a task does not replace the owner's review. A story's final acceptance belongs to its analyst.
- Infokuznia itself is developed elsewhere. If it lacks something, tell the owner as a suggestion; do not work
  around it.

## Documents

`docs/` is the source; infokuznia holds a mirror. **Never edit a document in infokuznia by hand.**

- `scripts/ops/ik_sync_docs.py [--dry-run] [--only <path>]` mirrors the files listed in `docs/infokuznia-docs.json`.
  A new file under `docs/` is added to its `documents` list: `path`, `type`, and optionally `link` — **our key**
  from `docs/infokuznia-ids.json` (for example `EP1-S1`), not the public id. Types: `CONCEPT`, `REQUIREMENTS`,
  `ARCHITECTURE`, `ADR`, `API`, `DATA_MODEL`, `SECURITY`, `TEST_PLAN`, `USER_GUIDE`, `DEPLOYMENT`, `UX`, `OTHER`.
  `docs/decisions.md` needs no entry of its own: each of its sections becomes one `ADR` document.
- The file must be committed before the sync. The sync then rewrites `docs/infokuznia-docs.json`; commit that too.
- A second run must report `created 0, updated 0`.
