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
| `docs/specs/` | Specs, `S<nn>-<slug>.md`. A change the owner can notice that no spec covers gets a spec first. S02 is the suggestions, S03 the Gmail connection and sync (with D-006) S04 reading the mail with the two AI steps (with D-007) and S05 what is added by itself and undone (with D-008): read them before touching the e-mail import |
| `docs/decisions.md` | Numbered decisions, one `## D-nnn — title` section each. Read the ones that touch your task |
| `docs/state.md` | One screen on where the project stands — arrives with WP-TS-2 |
| `docs/backlog/` | Backlog batches that were imported into the tracker |
| `scripts/ops/` | Tracker tooling: `ik.py`, `ik_seed.py`, `ik_sync_docs.py` |
| `scripts/publish-image.sh` | Publishes the image. The owner runs it on the Mac, from `main` (D-002); a session never publishes |
| `docs/android-release.md` | The phone app's release build: the owner's signing key, its fingerprint for Google sign-in, installing |

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
From a cloud session Maven Central has answered 404 for files that exist; Gradle then reports "Could not find" for a
dependency that is there. That is the session's network, not the build: do not change a version, and do not run
`--refresh-dependencies`, which makes Gradle ask again for everything it already has and remember each wrong 404.

| Part | Check | Run it locally |
|---|---|---|
| `backend/` | `cd backend && ./gradlew test` | `./gradlew devDatabase` (PostgreSQL 18 on port 5433, prints the database variables), then `./gradlew bootRun` with those and `APP_JWT_SECRET` (32+ characters), `ALLOWED_EMAILS` (made-up addresses), `APP_MEDIA_DIR` (any directory; pictures go there) and `DEV_LOGIN_ENABLED=true` set |
| `web/` | `cd web && npm ci && npm run lint && npm test && npm run build` | `npm run dev` (port 5173; forwards `/api` and `/actuator` to the backend on 8080) |
| `android/` | `cd android && ./gradlew lintDebug testDebugUnitTest assembleDebug` and `./gradlew checkReleaseApk -PserverUrl=https://planner.example` | No emulator in a cloud session: install `app/build/outputs/apk/debug/app-debug.apk` on a phone. The server address is a build setting: `-PserverUrl=https://…` |
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
  `toString()` to hide it, because the web framework prints what every endpoint is called with through that method
  once its logging is at TRACE. `NoPersonalDataInLogsIntegrationTest` checks this at that level; a module that
  holds personal data gets a test like it (`VendorsLeaveNoPersonalDataInLogsIntegrationTest`).
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
- A picture of an inspiration is only handed out with the access token, which an `<img>` cannot send. The page
  fetches it through `pictureAddress` (`src/inspirations/pictures.ts`) and shows the browser's own `blob:` address;
  never put an `/api/…` address into `src`. Signing out lets go of all of them.
- Money is shown with `money()` and read from what a person typed with `moneyTyped()` (`src/money/money.ts`), both
  on whole numbers of the currency's smallest unit; nothing divides by 100 on its own. Over a limit is said in
  words, with the colour and the sign (`src/ui/Warning.tsx`) only beside them. A bar of money is `Meter`
  (`src/budget/Meter.tsx`).
- A list tells the form where to return to (`state={{ back }}`), filters included.
- The suggestions inbox (`src/suggestions/`, S02) shows what the e-mail import proposes. Everything that came out of
  a mail is plain text, never markup or a link made from it; "Open the e-mail" is drawn only for an `https`
  address, and only on a suggestion from your own inbox (the other owner's mail cannot be opened by you). A decision is sent once; `already-decided` overwrites nothing and says what was decided and by whom.
  "Change and accept" sends every value of the proposal, because the server makes what is sent and nothing else.
  How many wait is asked once in `Shell` and shared with Home and the pages (`WaitingContext`).
- The e-mail import page (`src/mail/`, S03) is at `/mail`, the address the server sends the browser back to after
  Google. It sends the browser on only to an `https` address at `accounts.google.com` (`googleAddress`), says what
  happened once and takes the parameter out of the address, never shows the partner's address, and asks on the
  page before disconnecting. It has no entry in the top bar: it is reached from the suggestions inbox and, while
  the mail is not connected, from Home. Its part "Reading your mail" (S04 section 8) says the reading's state in
  words, never by its name, the numbers of both mailboxes together, and the estimated cost in US dollars
  (`dollars()` in `src/mail/mailApi.ts`, from whole cents — `money()` is for the wedding's currency).
- The top bar is one row from 64rem up. Below that the name and the signed-in block share a row and the entries
  are one row that moves sideways, with the entry of the page that is shown brought into view; the entries stay a
  plain list of links at every width, with no menu button.
- Every colour, size and radius comes from the tokens at the top of `src/index.css`; light and dark both work.
- What a person reads is written for that person. This includes the server's validation messages
  (`message = "Enter your partner's name."`), which the forms show next to the field.
- Tests replace the server with `fakeServer` from `src/test-server.ts` and drive the screens as a person would.
- Google's button is drawn by Google's own library (`src/auth/GoogleButton.tsx`); the page's content security
  policy in `SecurityConfiguration` allows exactly that library. It has not yet been tried against Google.

## The Android app

How it is put together is D-005: one module, Ktor on OkHttp, Room, WorkManager, DataStore with a Keystore key,
Credential Manager, a container written by hand, no dependency-injection framework. A task adds a library when it
first uses it, at the version D-005 names.

- `AppRoot` (`ui/AppRoot.kt`) decides the screen from the session (`session/Session.kt`), as the web's `App.tsx`
  does. A screen is a stateless composable fed by a view model; the view models of a signed-in person's screens are
  asked for inside the signed-in part of `AppRoot`, where `SignedInModels` holds them and lets go of them at
  sign-out.
- What the screens share is made once in `AppContainer` (`WeddingPlannerApp.kt`). What only a phone has — the
  network, the Keystore, Google's account picker — comes into it from outside. Whatever a task keeps on the phone
  is removed in the container's `forgetEverything`: signing out leaves nothing of the account behind.
- `Api` (`api/Api.kt`) sends the access token and renews it once on a 401; `getOpen`/`postOpen` are for the sign-in
  endpoints. What goes wrong is an `ApiFailure`; a screen decides from its kind what to say, in words from
  `strings.xml`. The refresh token is on the phone only encrypted; the access token only in memory.
- The phone keeps a copy of the planner (`data/`): `PlannerRepository.load()` asks the server for everything the
  screens show and puts it in the place of the last load, all of it or nothing; the screens read only the copy, so
  they work without a connection and say from when it is. The tables are in `Rows.kt`; their layout is written to
  `android/app/schemas/` at every build and committed. The database also holds what exists nowhere else (the
  changes waiting to be sent), so every change of the tables needs a migration and a new version;
  `DatabaseCarriesOverTest` opens a database built from the older layout with the newer app.
- A change made on the phone (a tick, a new task, an edit) is written to the table `waiting_change` first, laid
  over what the screens show at once, and sent by `PlannerRepository.sync()`, which then loads again: by the app
  while it is open, and by `SendWorker` (WorkManager) once there is a connection when it is not. One change waits
  per task; a further one is folded into it. A new task gets its id on the phone. An edit carries the version it
  was made on and is never sent over a newer one: the task then shows the latest version and says that it has to
  be looked at. What the phone sends is JSON written by hand in `data/Changes.kt`, held against the generated
  request models by `ChangesAgreeWithTheContractTest`.
- The planner is in other apps' share sheet for a link (apps hand one over as plain text) and for pictures:
  `share/ShareActivity`, declared in the manifest, reads what was handed over with `sharedOf(intent)`. Saving
  keeps the share in the table `waiting_share`, with its pictures copied into the app's own folder (the other app
  only lends them), and closes the screen; `sync()` saves it as an inspiration, the inspiration first under an id
  chosen on the phone, then its pictures unless the server already holds them. What goes on after a screen has
  closed runs in `AppContainer.background`. Someone who is signed out signs in on the same screen and finds the
  share still there.
- The screens follow `PlannerRepository.planner`, which reads again after every write the repository itself
  makes. Write to the database only through the repository, or the screens do not hear of it.
- Signing out removes the waiting changes too, and asks first when there are any. It must always end signed out:
  what clears up after it may fail, the sign-out may not.
- The phone reads the server's answers into classes of its own (`data/Wire.kt`): only the fields it uses, and a
  value from a fixed set as text, so a field or a value a newer server adds does not break an app that is already
  on a phone. `WireAgreesWithTheContractTest` holds them against the generated models, and the tests' own answers
  (`StandIn`) against the contract.
- How a fact reads is in `planner/Words.kt` (the budget's in `planner/BudgetWords.kt`), the same words as the web's
  pages and its `labels.ts`; money is `money()` in `money/Money.kt`, on whole numbers of the smallest unit as on the
  web. Screen texts are in `strings.xml`.
- The bar at the bottom has four places: Home, Plan, Tasks and More. What has no place of its own is a row in More
  (`planner/MoreContent.kt`) and an entry in `BEHIND_MORE` in `PlannerScreens.kt`, which gives it its title (the
  name of the vendor, guest or idea it shows), the arrow back at the top, and keeps More chosen below. The budget,
  the vendors, the guests and the inspirations are only shown on the phone and are set on the web; what can be
  changed there is a guest's status, and an inspiration is added through the share sheet (S01 §4).
- The inspirations are part of the copy (`inspiration`, `inspiration_picture`) and stand on a board behind More,
  narrowed down by board and by tag; what was shared on the phone and still waits stands first. A card's face is a
  slot: the picture, in its own shape within bounds, and words where there is none to show. What a waiting share's
  link is called before the server has seen it repeats the server's rule (`sourceOfLink` in
  `planner/InspirationWords.kt`, `Links.sourceOf` in the backend): change both together. An idea's link opens
  through `planner/Contact.kt` like every other address.
- A picture of an inspiration is only handed out with the access token. `Api.fetchTo` streams it to a file with a
  limit on its size; `PictureStore` (`data/`) keeps the files in a folder under the cache directory: under a
  temporary name until whole, one fetch per picture however many ask, a few at a time, and what could not be had
  is not asked for again until the next load. After every load the board's small pictures are fetched in the
  background and the files of what is gone are removed; signing out removes all of them, also what is on its way.
  `PictureDecoding` never reads a picture larger than its place needs and never on the main thread;
  `PicturesModel` holds what is decoded and lets go of it with the signed-in person's models. There is no picture
  library (D-005). A test makes its own pictures (`TestPictures.kt`); none is committed.
- A guest's status chosen on the phone waits in a table of its own (`waiting_guest_status`, one row per guest; a
  further choice replaces it) and is sent by `sync()` after the tasks and before the shares. The server takes it
  whatever version it has, so there is no "changed meanwhile" here: only "the guest is gone" and "not taken", which
  the guests screen says with the guest's name. A choice made while the first is on its way is a change of what
  the server is about to have and is kept (`onTheWay`), also when it goes back to what the copy says. The same
  holds for a task's tick (`tasksOnTheWay`): taking it back before it was sent leaves nothing to send, taking it
  back while it is on its way is sent after it.
- How many people come is the server's count (`Planner.guestCounts`), never added up on the phone, and a copy
  without one shows none. A list is narrowed down by status with `StatusChips`; the guest list comes before the
  head count's breakdown, because finding a guest is what the screen is mostly opened for. While a status chosen
  on the phone is not sent, the head count says that it is as it was loaded.
- The suggestions that wait (S02) are part of the copy (`suggestion`) and stand in an inbox behind More; Home says
  how many wait. A server that does not know them yet (a 404 on that one route) counts as "none wait" and the
  rest of the planner still loads. Everything that came out of a mail is plain text and never printed or logged;
  "Open the e-mail" is drawn only for an `https` address whose host is exactly `mail.google.com`
  (`mailLinkAddress` in `planner/Contact.kt`).
- Accepting or rejecting a suggestion on the phone waits in a table of its own (`waiting_suggestion_decision`, one
  row per suggestion) and is sent by `sync()` after the tasks and the guests' statuses and before the shares, as
  proposed: the request has no body. A decision stands on the server, so the phone is strict about it: one that
  waits can be taken back or replaced by the other, one that is on its way (`decisionsOnTheWay`) can be neither.
  Decided otherwise by the other owner, gone, or not taken by the server are said with the suggestion's title and
  never sent again by themselves. If the answer to a sent decision is lost, the phone cannot know that it landed:
  taking it back then only changes the phone, and the next load shows what the server has.
- A phone number, an e-mail address, an address and a website open in the phone's own apps through
  `planner/Contact.kt`, and only through it. What the server holds was typed by a person: each way builds one fixed
  kind of intent, a number is dialled and never called, and a website opens only as `http` or `https` — anything
  else is shown as text. Whether an app can take it is not asked beforehand; it is started, and the screen says so
  when none can.
- A copy that an older version of the app made lacks what a newer one keeps until it is loaded again. A screen
  leaves out what such a copy cannot know and never shows it as nothing (`categoriesKnown` in `BudgetWords.kt`).
- The plan's timeline (`planner/timeline/`) is drawn by us, like the web's chart (D-001): where a bar lies is
  arithmetic on its dates (`TimelineLayout.kt`), tested without a screen. It only shows: nothing on it can be
  moved. Time scrolls sideways on a node of its own, around the list that scrolls up and down; putting both on
  one node takes the list's own scrolling away from a screen reader. What a drag does, buttons do as well.
- The release build is made small (R8) with names left as they are; `checkReleaseApk` looks into the finished app
  for what is only found by name when it runs. A class that Android or a library makes from its name goes on that
  task's list; a rule in `proguard-rules.pro` says why it is there. The key that signs it is the owner's and comes
  from outside the repository (`docs/android-release.md`); a session never has it, and builds unsigned.
- The app runs on Android 9 and later (minSdk 28): lint is part of the gate and stops what only a newer Android has.
- Nothing personal in the log: no address, name or token. What holds one overrides `toString()`.
- Everything is tested on the JVM (Robolectric); there is no emulator in a cloud session. `StandInPhone` is the
  container with a `StandInServer`, a key outside the Keystore, a `StandInGoogle`, a clock set by hand and a real
  database in a folder; `AppRootTest` and `PlannerScreensTest` drive the screens with it as a person would, and
  check the log and what is left in the phone's files.
- The app asks for several things at once, so the stand-in server answers on several threads: what a stand-in
  writes while answering must bear that (`StandInServer.calls`). `LivingPlanner.holdUp` keeps a request waiting
  so that a test can act while it is on its way.
- A screen test that changes something uses `LivingPlanner`, a stand-in server that keeps what it is sent, and
  waits for what it then checks (`waitFor` a text, `waitUntil` a state of the server): the app answers a tap on
  threads of its own. The test's phone is switched off by the `SwitchedOffAfterwards` rule, after the screen is
  gone; closing it earlier closes the database under an app that is still at work.
- A character that cannot be seen (a no-break space, a minus sign that is not a hyphen) is written as its escape
  (`\u00a0`), in Kotlin as in TypeScript.
- A screen has a test that writes its picture: `compose.picture("name")` (`ui/Pictures.kt`), in a test class with
  `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and `@Config(qualifiers = PHONE)`. The pictures land in
  `android/app/build/outputs/roborazzi/`; whoever changes a screen opens its picture. None is committed.
- What only a phone shows (Google sign-in, the share sheet, TalkBack) is the owner's to try, and is listed as not
  verified.

## The backend's modules

`api`, `appinfo`, `webapp`, `identity`, `wedding`, `vendors`, `planning`, `inspirations`, `guests`, `suggestions`, `mail`,
`mailreading`. A module
uses another only through the types in that module's own package, and never in a circle (`ModularityTest`).

- `planning` and `inspirations` use `vendors` (`Vendors.exists`), not the other way round. What the register needs
  from them it asks through interfaces of its own that they implement: `VendorUse` (what points to each vendor,
  and what its payments come to) and `VendorCategories`. A new kind of record that can point to a vendor implements
  `VendorUse` in its own module.
- `inspirations` uses `planning` (`PlanItems.exists`).
- `guests` stands by itself: the guest list, its households and the head count.
- `suggestions` (S02) uses `wedding`, `planning` and `vendors`, and only `mailreading` uses it. What accepting makes is made by
  those modules' own "add" (`PlanEntries.addEntry`, `VendorRegister.addVendor`), so the same rules and the same
  messages per field apply; no rule is copied. A module that makes what another module's form makes goes the same
  way, with `BeanRules` for the rules the request's type declares.
- A suggestion is handed in through `Suggestions.handIn`, inside the server: there is no HTTP call that adds one.
  A decision stands: accept and reject hold the row (`FOR UPDATE`), the same decision again changes nothing, the
  other one is `already-decided`, and a refused acceptance leaves nothing behind. A suggestion holds parts of
  someone's mail: nothing of it is logged, tests use made-up mail, and its link can only be an `https` address at
  `mail.google.com` (checked in the code and by the table).

- `mail` (S03, D-006) connects an owner's Gmail and keeps the key to it. Its rules are security rules:
  - `GET /api/mail/gmail/return` is the one path of the feature that answers without a sign-in. It trusts nothing
    but a ticket the server issued — known by its hash, in time, used once, from the browser it was issued to —
    and always redirects to one fixed page. Nothing in a request may change where it goes.
  - The scope is `gmail.readonly` and nothing else, when asking and when checking what Google granted.
  - A token rests only sealed (`TokenVault`: AES-256-GCM, a key from outside the database, bound to the owner's
    row). The client's secret, a code, a state, a cookie's value, a token, the key and a mailbox's address never
    reach a log, a response, an exception's message or a `toString()`; the loggers that would print request lines,
    cookies and statement values are held at INFO in `application.yml`.
  - The import is off unless all its settings are there and valid (`MailSetup`); off, it asks nothing of Google.
  - It goes out only to Google's configured addresses, with short limits and no redirects followed.
  - The sync (`MailSync`, every five minutes) reads a mailbox through `MailSource`, which has nothing of Gmail in
    its words; `GmailSource` is Gmail behind it. Of a message it keeps **only Google's identifier of it and of its
    thread** (`mail_message`): no sender, subject, snippet, label or date. A message is noted once; a look counts
    only when it is complete (the marker and "last looked at" move with the last of what it found); a refusal by
    Google puts that mailbox off for growing times that are kept in the table; a mailbox that has to be connected
    again is left alone. The log says that a look happened and how many messages it noted, never which.
  - What another module may do with a noted message is `MailReading`, in `mail`'s own package: the messages not
    read yet (newest first, without keeping a message's date: the order of noting is kept), a message's content
    fetched from Gmail at that moment — sender, subject, date, text, and the earlier messages of its thread —
    keys that name a message and its thread without saying anything, and marking a message with its verdict,
    which is what makes it "read" for good. A message's content is never kept or logged; attachments are never
    fetched; a text comes from the plain part, or from the HTML part with no markup, script or style left.
    It also makes the link that opens a message in Gmail (`linkTo`), which names the mailbox so that it opens in
    the right Google account: that address is its owner's, and `suggestions` answers the other owner the link
    without it.
  - Tests use `StandInGoogle`, which also stands in for a mailbox, and move a test clock; the sync does not run by
    itself in tests. **No test ever calls the real Google.**

- `mailreading` (S04, D-007) reads the noted messages with two AI steps and hands in what they propose. It uses
  `mail`, `suggestions`, `wedding`, `vendors` and `planning`, and nothing uses it. Its rules are security rules too:
  - The two steps are `MailTriage` and `MailExtraction`, through the AI service's own Java SDK. **Do not write
    Claude API code from memory**: the API changes; read the current guide (the `claude-api` skill) first. Models
    are settings (`claude-haiku-5-5`, `claude-opus-5-5`, never with a date added); thinking, a temperature and a
    prefilled answer are not sent (the models refuse them); the answer's shape is asked for (structured output).
  - **The models are given no tools.** What a message says can at most become a suggestion that waits for a
    person; the only thing this module does with an answer is `Suggestions.handIn`.
  - A request carries what S04 section 3 names and nothing else. What somebody else wrote stands between lines
    with a marker made for that one request; the instructions are the two files in `resources/mailreading/`.
  - What is not an answer is a value of `Outcome.Why`, never an exception with a text: declined, cannot be used,
    too many proposals, try later, the key or the account is not accepted, asked nothing.
  - An answer is checked again by the server (`Checking`): kinds, lengths, real days, and **the passage must stand
    in what was sent** or the proposal is dropped. Names are matched by the server (`Vendors.withAddress`,
    `Vendors.named`, `PlanCategories.named`), and money is worked out by the server (`Money`, exact, only in the
    wedding's currency) — never by the model.
  - The round (`ReadingRound`, every minute) takes the newest messages that wait, each one whole or not at all:
    **marking is the last thing**, and a proposal's source key is `<thread key>/<message key>/<n>`, so a message
    done twice proposes nothing twice. The brakes are in the settings: a monthly limit on the estimated cost, a
    daily limit of messages, fifty new suggestions from one mailbox in a day; a busy service makes it wait, a key
    that is not accepted stops it until the server is started again.
  - `reading_usage` counts calls and tokens per day, step and model, and nothing of a message. **Nothing of a
    message, an answer or the key is kept or logged**; of an exception only its kind.
  - What the e-mail page says of the reading, `mail` asks through an interface of its own that this module
    implements (`mail.ReadingReport`, as `vendors` does with `VendorUse`): `mail` cannot use `mailreading`.
  - How good the two steps are is measured, not tested: sixty invented messages in Polish, Ukrainian and English
    (`backend/src/test/resources/mailreading/samples/`) and `./gradlew measureReading`, which runs the steps over
    them **against the real service** and costs money. Nothing depends on that task, it needs the key and
    `-PspendMoney=yes`, and it is the owner's to run — a session runs at most `-PdryRun=yes`. A change to the
    instructions or to what a step answers is a reason to tell the owner to measure again.
  - It is off without `APP_AI_API_KEY`: no client of the service is made. Tests use `StandInAnthropic` and the
    mail tests' `StandInGoogle`. **No test, and nothing a session runs, ever calls the real AI service.**

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
- Money is whole numbers of the smallest unit (grosze), never a decimal fraction. The server refuses `12.5` where a
  whole number belongs instead of cutting it down. A payment is a task with an amount (D-004).
- Whoever adds an entry or an inspiration may choose its id (`NewEntry.id`, `NewInspiration.id`), and both
  clients do: a request sent again after no answer came is answered with what is already there, and nothing is
  added twice or overwritten. The web takes the id from `newId()` (`src/api/ids.ts`) when the form opens.
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
6. **Then bring `main` up to date.** The owner wants `main` kept current: it is where the branches are merged, and
   what the image is published from (D-002). `git fetch origin main`; if `main` is an ancestor of the branch,
   `git push origin <branch>:main`. If `main` has commits the branch lacks, merge `origin/main` into the branch,
   pass the gate again, push the branch, and only then push it to `main`. Never force a push to `main`, and never
   put there what has not passed the gate.

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
