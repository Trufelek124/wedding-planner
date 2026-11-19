# Decisions

One section per decision: what was decided, when, why, and what lost. A decision is never rewritten; a later one
that changes it says which one it replaces. The decisions already contained in the product scope
(`docs/specs/S01-product-scope.md`, sections 2, 8 and 10) join this log with WP-TS-2.

## D-001 — The Gantt chart is our own SVG component, not a library

**Date:** 2026-11-17 · **Task:** WP-TS-20 · **Applies to:** WP-TS-21 (web), later the Android timeline

### Context

The plan is shown and rescheduled on a timeline (story WP-US-7). The chart must:

1. show dated plan items as bars; dragging or resizing a bar changes its dates and saves them;
2. let a dependency be drawn between two bars, show it as a line, and take the server's refusal of a cycle;
3. mark a plan item that starts before something it depends on ends;
4. show a plan item's dated tasks as markers on its bar, and mark today and the wedding day;
5. read by week and by month, and stay responsive with 200 plan items;
6. offer by keyboard, or through the entry's form, every change that can be made by dragging.

It also has to follow the app's design tokens in light and dark, work at phone width, and be testable the way the
rest of the web app is: in jsdom, by driving the screen (`CLAUDE.md`, "The web app").

### What was compared

Versions and facts as of 2026-11-17, read from the npm packages themselves (readme, typings, bundles). The
libraries' documentation sites cannot be reached from the build environment, so nothing below rests on them.
Sizes were measured by building a one-page Vite 8 app with and without each library; they are what the library
adds, minified and gzipped. For scale: the whole web app is 93 kB today.

| | Our own SVG | SVAR React Gantt 2.8.3 | DHTMLX Gantt 10.0.3 Community | frappe-gantt 1.2.2 | react-modern-gantt 0.9.0 |
|---|---|---|---|---|---|
| Licence | ours | MIT | MIT | MIT | MIT |
| React 19, TypeScript | yes | yes | plain JavaScript; the React component is in the paid edition | plain JavaScript | yes |
| Drag and resize (1) | to write | yes | yes | yes | yes |
| Draw a dependency (2) | to write | yes | yes | lines are shown, none can be drawn | lines are shown; drawing not mentioned |
| Conflict mark (3) | to write | through a custom bar template | through a template | through a class | not mentioned |
| Task markers on a bar (4) | to write | to write, inside a custom bar template | to write, as an extra layer | no | no |
| Today and wedding-day lines (4) | to write | `markers` is in the typings and styles; not tried | paid edition only | today only | today only |
| Week and month scale (5) | to write | yes | yes | yes | yes |
| 200 items (5) | no virtual list needed at this size; to be measured in WP-TS-21 | virtual list | virtual list | plain SVG | not stated |
| Keyboard for every drag (6) | to write | no: keys move between rows, copy, delete and undo; bars have no role and no name | keyboard navigation and ARIA | none | none mentioned |
| Items without dates | ours, beside the chart | paid edition | paid edition | — | — |
| Added size, JS + CSS | a few kB | 85 + 7 kB | 172 + 51 kB | 13 + 2 kB | 252 + 3 kB |
| Look | the app's tokens | 114 theme variables of its own, plus its grid and menus | its own skins | its own variables | its own variables |
| Tests in jsdom | geometry comes from the data | throws without a canvas; renders with a stand-in | needs real layout | needs real layout | not tried |

Left out early: `gantt-task-react` (last release July 2022, React 18 only) and its fork `@wamra/gantt-task-react`
(needs Material UI 6, last release November 2024); `wx-react-gantt` (GPLv3, replaced by SVAR's MIT package);
`react-calendar-timeline` and `vis-timeline` (timelines without dependencies); Bryntum, Syncfusion, DHTMLX PRO and
`gantt-schedule-timeline-calendar` (paid licences, which a private two-person app does not justify).

### Decision

We write the chart ourselves, as one SVG inside a React component, with no charting dependency.

### Why

- **The libraries cover the smaller half.** Bars, dragging, resizing and scales come for free with a library. The
  parts that are particular to this planner do not: task markers on a bar, the conflict mark, the wedding-day line,
  the list of items without dates, and above all a keyboard equivalent for every drag. With any candidate those
  are still ours to write, and then on top of someone else's markup and events.
- **Keyboard use decides between the two serious candidates, and neither fits.** SVAR is the best match for React
  but its bars cannot be moved, resized or linked by keyboard and are not announced as anything. DHTMLX has the
  keyboard support but keeps the today line and unscheduled items in the paid edition, has no React component in the
  free one and would triple the app's size.
- **Tests.** Our own chart places a bar from its dates (a day is a fixed number of pixels), so a test can drag by a
  known distance and check the dates that are saved. The libraries measure the page to lay themselves out, which
  jsdom cannot do; SVAR does not even render there without a canvas.
- **Size and look.** Nothing is added to the download, and the chart uses the same tokens as every other screen
  instead of a second theme that has to be bent towards them.
- **The scope is small and fixed.** Whole days, two scales, one bar per plan item, finish-to-start dependencies.
  No auto-scheduling, resources, progress or critical path — the things Gantt libraries are mainly built for.

### What it costs, and what would change the decision

- We own the pointer handling, touch included, and the scrolling of a long timeline. WP-TS-21 carries that work.
- 200 items is an expectation until WP-TS-21 measures it in a real browser. If the chart cannot be made to respond
  with 200 items, or dragging cannot be made reliable on a phone, the fallback is SVAR React Gantt with our own
  keyboard layer, recorded as a new decision that replaces this one.

### What follows for WP-TS-21

- A date maps to a horizontal position by arithmetic alone; the week and month scales differ only in the width of
  a day. Moves and resizes snap to whole days.
- A changed bar is saved with the entry's `version`; a refused save (`changed-meanwhile`, a cycle) puts the bar back
  and says why.
- Every bar can be focused and has a name. Arrow keys move it, with a modifier they resize it, and a dependency can
  be added and removed without a pointer. The entry form gets a "Depends on" control as the second way.

## D-002 — The image is published by a script the owner runs on the Mac

> **Replaced by D-010 on 2026-11-19**: the image is published by a workflow at every push to `main`. The script
> remains as the way by hand.

**Date:** 2026-11-17 · **Task:** WP-TS-27 · **Decided by:** the owner · **Applies to:** WP-TS-28, the server's update

### Context

The planner runs on the owner's home server as a container (`docs/deployment.md`). A finished commit has to
become an image that server can pull. The server's processor is ARM, the repository is public, and
the server already runs another application that is published by a script from the owner's Mac.

### What was compared

| | A script on the owner's Mac | GitHub Actions in this repository | Building on the server, no registry |
|---|---|---|---|
| Cost | none | none while the repository is public | none |
| Who can publish | the owner, at the Mac | whoever can put a commit on `main` | whoever can update the server's checkout |
| When it happens | when the owner runs it | at every push to `main`, unattended | when the server updates |
| Registry credentials | the owner's own token, in the Mac's keychain | none stored: the workflow's short-lived token | none |
| Building for ARM | native on the Mac | on GitHub's ARM runner | native, but the server compiles Java and npm at every update |
| Going back to an older version | pull the older tag | pull the older tag | rebuild the older commit |
| What a public repository adds | nothing: no workflow can publish | a publishing workflow whose triggers, permissions and pinned actions have to be kept tight | nothing |

### Decision

A script in this repository, run by the owner on the Mac, publishes the image. It runs the checks, builds for
`linux/arm64` and pushes to the registry under a stable name, tagged with the full commit and with `latest`.
`main` is the branch that is published.

### What speaks for it

- Nothing goes live unless the owner runs the script. Where a server pulls `latest` by itself, an unattended
  workflow makes every merge to `main` a deployment.
- It is the way the other application on the same server is published, so there is one routine to remember.
- The registry token stays on the Mac. The repository holds no secret and no workflow that could publish.

### What it costs, and what would change the decision

- Publishing needs the owner and the Mac, with Docker, Java 25 and Node 22 on it. A fix cannot go live while the
  owner is away from it.
- The checks run on the Mac only as far as its tools reach; the script runs the parts the image is built from
  (backend and web) and can run the whole gate where the Android SDK is installed.
- If publishing by hand becomes a burden, GitHub Actions is the alternative, recorded as a new decision that
  replaces this one.

### What follows for WP-TS-28

- The script refuses to publish anything but a clean checkout of `main` that is already pushed, so that the
  commit in the image's label can always be found in the repository.
- It never asks for, stores or prints a token: the owner logs Docker in to the registry once.
- The image's name is not written into the repository's files; it is worked out from the repository's address
  or given when the script is called.

## D-003 — Link previews are fetched by the server, from the public internet only

**Date:** 2026-11-18 · **Task:** WP-TS-36 · **Applies to:** the inspirations module, and anything later that fetches an address a person supplied

### Context

For a saved link the planner shows the page's title and its preview picture, as a messenger does (S01 section 7).
Someone has to fetch the page. A browser may not read other sites' pages, so it is the server, and that makes the
server fetch an address somebody else chose. Left unguarded, such an address can name what only the server can
reach: the database beside it, the host's metadata service, an admin page on the home network. It can also be an
endless download or a picture that is small as a file and enormous in memory.

### What was compared

| | The server fetches, under rules | Nobody fetches | A preview service on the internet |
|---|---|---|---|
| A card shows | title and picture where the site gives them | the source and the note only | title and picture |
| Who learns which links the couple keeps | the linked site, as with any visit | nobody | a third company, for every link |
| Risk to the server's own network | has to be shut out by the rules below | none | none |
| Cost | the code and its tests | nothing | a subscription or a free tier's limits |

### Decision

The server fetches previews itself, and only under these rules:

1. **Only the web.** `http` and `https`, on ports 80 and 443, with no user name in the address.
2. **Only public addresses.** A host name is used only if every address it stands for is a public one. Everything
   that is not known to be public is refused: this machine, private and shared ranges, link-local addresses (where
   cloud metadata services live), multicast, reserved and documentation ranges, and the IPv6 forms that carry an
   IPv4 address inside.
3. **Checked where it counts.** The check is made by the HTTP client's own name lookup, on the very addresses it
   then connects to. A name cannot give one answer to the check and another to the connection.
4. **Every redirect is a new address.** At most three, each judged by rules 1 to 3.
5. **Limits.** Three seconds to connect, five without a byte, twelve for one fetch; a page up to 1 MB, a picture
   up to 10 MB.
6. **Nothing of the owners goes along.** No cookies, no sign-in, no referrer; a fixed, generic client name.
7. **Nothing comes back but a title and a picture.** The title is kept as plain text of at most 200 characters.
   The picture is checked like one a person uploads (its kind from its bytes, its size in pixels) and stored
   as the planner's own copy. No client is told why a fetch failed, so the answers cannot be
   used to look around the server's network.
8. **It can be switched off.** `PREVIEWS_ENABLED=false` and the server never goes out for a preview.

A preview is fetched after the save, in the background, so that saving never waits for someone else's server.
What is still to be fetched is recorded in the database and survives a restart. A site that does not answer is
tried three times in all.

### What speaks for it

- The alternative that is as safe by construction, not fetching at all, gives up what the owner asked for.
- A preview service would be told every link the couple keeps. The planner is private; that is not its to share.
- The rules are few, each has tests, and the tests fail when a rule is taken out.

### What it costs, and what would change the decision

- The rules are ours to keep right. A new kind of address that should be refused has to be added by hand; the
  list errs on the side of refusing.
- Instagram and TikTok mostly refuse such fetches. Their cards then show the source and the note, and a screenshot
  can be attached by hand; that was expected (S01 section 7).
- The rules are the only barrier: the container may reach whatever its network lets it reach. If the planner ever
  runs where that matters more than at home, a network rule that lets the container reach only the database and
  the internet is the second barrier to add.
- If outgoing requests have to pass through a proxy, rule 3 no longer holds, because the proxy does the lookup.
  Then the proxy must enforce rule 2, or previews are switched off.

## D-004 — A payment is a task with an amount, filed under the task's category and paid when it is done

**Date:** 2026-11-18 · **Task:** WP-TS-38 · **Applies to:** the budget on the web and the phone, vendors, the e-mail import

### Context

The owner's own example of a task is "pay for something" (S01 section 3), and the budget has to add such tasks up:
planned, committed and paid, in total and per category (story WP-US-17). Three things had to be settled before any
screen was built: where the money lives, what "budget category" means next to the categories the plan already has,
and what "paid" is next to "done".

### What was compared

| | On the task itself | A register of payments beside the tasks |
|---|---|---|
| "Pay the deposit" is entered | once | twice, or the two have to be linked |
| The task list and the budget can disagree | no: they read the same entry | yes |
| Due date, who does it, priority, the plan item | the task's own | repeated, or looked up through the link |

| | The entry's category is the budget category | A list of budget categories of its own |
|---|---|---|
| Lists the couple keeps | one | two that mostly say the same |
| A payment filed differently from its task | not possible | possible, and then two places to keep right |

| | Paid when the task is done | A "paid" mark of its own |
|---|---|---|
| Ticking a payment off in any list | pays it | leaves a done task that still counts as unpaid, or the reverse |
| A second state to explain | none | "done but not paid" |

### Decision

1. **A task can carry an amount; then it is a payment.** Only a task: a plan item carries no money, and a payment is
   not turned into a plan item until the amount is taken away.
2. **Its budget category is its category.** The budget's lines are the categories the plan already has; a payment
   without a category is counted apart.
3. **It is paid when it is done.** Marking it done records the paid date: today where the wedding takes place, or
   the day the person gives, never a future one. Reopening it forgets the date.
4. **Committed** is every payment that is not dropped; **paid** is every payment that is done; **planned** is what
   the couple set per category, beside a total for everything. The two are not forced to agree: the numbers show it
   when the categories add up to more than the total.
5. **Amounts are whole numbers of the currency's smallest unit**, in the database (`bigint`), in the sums (done by
   the database, on whole numbers) and in the API. A number with a fraction where a whole number belongs is refused,
   not rounded. One amount is at most 10,000,000,000 of the main unit.
6. **A kind** (deposit, instalment, final) may be said and may be left open.

### What speaks for it

- It is what the scope says ("one entry — it is in the task list and in the budget at once") with nothing added.
- The automatic priority already asks whether an entry is a payment (S01 section 5); now it can be answered.
- The e-mail import will propose "pay 2,500 by Friday" as one suggestion that becomes one entry.

### What it costs, and what would change the decision

- A payment made in parts is several tasks, one per part; there is no "partly paid".
- Planned amounts follow the plan's categories. If the couple wants budget lines the plan has no use for, they add
  a category; if that grows into a second list in disguise, budget categories of their own are the alternative,
  recorded as a new decision.
- Refunds and money coming in are not covered: an amount is above zero.
- Every amount is in the wedding's one currency. A payment in another currency is entered converted.

### What follows for the clients

- A change to an entry says the money again (`amount`, `paymentKind`, `paidOn`): a save replaces the whole entry,
  and leaving them out would take the money away. The generated types make them required for that reason.
- Amounts are shown by dividing by the currency's own number of decimals and never calculated with as fractions.

## D-005 — The Android app: the other app's libraries where the job is the same, wired by hand, with screens rendered to pictures on the JVM

**Date:** 2026-11-18 · **Task:** WP-TS-44 · **Applies to:** every Android task (WP-TS-45 to WP-TS-51)

### Context

The phone app has to sign in, show what the web shows, keep what it loaded for when there is no connection, send
what was done offline later, and take shares from other apps (S01 sections 4 and 7). The scope fixes Kotlin,
Compose and Room, and asks for the choices of the other app on the owner's server where the job is the same
(S01 section 8), so that both are maintained the same way. Where this project is built there is no emulator:
whatever is chosen has to be buildable and checkable on the JVM.

### What was compared

| Job | Chosen | The alternative, and why not |
|---|---|---|
| Talking to the server | Ktor client on the OkHttp engine, kotlinx.serialization | Retrofit: as good, but the other app uses Ktor. The generator's own client: one more generated layer to keep working; only the models are generated, as today |
| Keeping the session | The access token in memory; the refresh token encrypted with a key in the Android Keystore, the result in DataStore | EncryptedSharedPreferences: its library is no longer developed |
| What was loaded, and what waits to be sent | Room, its code generated by KSP | Files of JSON: no queries, no migrations |
| Sending later | WorkManager | A service of our own: WorkManager already waits for a connection and survives a restart |
| Sign-in | Credential Manager with a Google ID token, exchanged at the backend; the development login in debug builds only | The older Google Sign-In library: retired |
| Moving between screens | Navigation for Compose | A state variable: no back stack, nothing to share a link into |
| Handing objects to each other | One container written by hand, created with the application | Hilt, as in the other app: worth it there with dozens of modules, not for a dozen objects |
| Looking at a screen | Robolectric draws it, Roborazzi writes the picture | An emulator: none here. Nothing: a screen nobody has seen |
| Showing the pictures of the inspirations (added with WP-TS-60) | Fetched through the app's own `Api`, kept as files by a store written by hand, decoded by the platform | A picture library (Coil): the pictures need the access token, have to stay for when there is no connection and have to be gone at sign-out — all of which would have to be built into the library's parts anyway, for one board |

### Decision

1. **One Gradle module**, packages by what they are for (`session`, `api`, `data`, the screens by feature, `ui`
   for what they share). A second module comes when something has to be shared with another app, not before.
2. **The libraries above**, at the versions below; a task adds a library when it first uses it.
3. **No dependency-injection framework.** The application creates one container; a screen's view model takes what
   it needs from it. If the container grows past what one file holds comfortably, Hilt is the alternative.
4. **Tests run on the JVM**: the server is Ktor's stand-in engine, the database is Room in memory, background work
   is WorkManager's test helper, screens are driven through Compose's test rule under Robolectric.
5. **Every screen has a test that writes its picture** to `android/app/build/outputs/roborazzi/`. The pictures are
   recorded at every run, looked at by whoever changes the screen, and not compared with stored ones: no picture is
   kept in the repository, and no test fails over how a font rounds.

### Tried together

On 2026-11-18, with the Android Gradle plugin 9.5.1 and its built-in Kotlin 2.4.21, one build had all of these,
passed lint, built the debug app, and ran a test that stored a row with Room, got an answer from Ktor's stand-in
engine and wrote a picture of a screen:

Ktor 3.6.1 (client-okhttp, content-negotiation, serialization-kotlinx-json, client-mock) · kotlinx-coroutines 1.11.0 ·
Room 2.8.5 with KSP 2.3.14 · WorkManager 2.12.0 · DataStore 1.3.0 · Credentials 1.6.0 with
credentials-play-services-auth and googleid 1.2.1 · Lifecycle 2.11.0 · Navigation Compose 2.10.2 ·
Roborazzi 1.79.0 on Robolectric 4.18.

### What it costs, and what would change the decision

- What only a phone shows stays the owner's to try: signing in with Google, the share sheet, TalkBack, the keyboard.
- A picture is Robolectric's drawing of the screen, close to a phone's and not identical.
- With everything in, the debug app is about 45 MB; the release build shrinks it.
- A container written by hand is checked by nothing but its use; a missing object shows when a screen opens, in a test.

## D-006 — Reading Gmail: Google's own permission, read-only, as an app in production that is not verified

**Date:** 2026-11-19 · **Task:** WP-TS-68 · **Applies to:** the e-mail import (S03, WP-US-20 and WP-US-21)

### Context

The import has to read two private Gmail inboxes without their owners being there (S01 section 6). Google sorts
read access to Gmail among its *restricted* permissions, which an app open to the public may only ask for after
Google's verification and a yearly security assessment by a third party. S01 left "to verify when this is built"
whether two private accounts get by without that. The server does not exist yet, so nothing could be tried
against Google; this records what Google's own documentation said on 2026-11-19 and what follows from it.

### What was compared

| Route | What it costs | Verdict |
|---|---|---|
| Google's permission with the read-only scope, consent screen **In production**, app not verified | Google shows "this app is not verified" once per person, to be clicked through; at most 100 people may ever allow it. Google's exception for apps used by a few people the developer knows personally says the verification can be skipped | **Chosen** |
| The same with the consent screen left in **Testing** | Every permission, and with it the server's long-lived token, **ends after seven days**: both owners would connect again every week | No |
| Verification and the security assessment | Weeks, a paid assessor every year | Not for two people |
| IMAP with an app password | No consent screen and no warning. But an app password opens the whole mailbox, writing and deleting included — more than the planner needs — and it needs Google's two-step sign-in | **The fallback** |
| A narrower Gmail scope (headers only) | The text of a message could not be read, so nothing could be proposed from it | No |

### Decision

1. **Google's permission, asking for `gmail.readonly` and nothing else.** The planner can read; it cannot send,
   change or delete. The scope is asked for only when an owner connects their mail, never at sign-in.
2. **The consent screen is set to In production and left unverified.** The owners click through Google's warning
   once each; the page says so before they start (S03 section 2).
3. **The token is kept encrypted**, with a key that is given to the server from outside and is not in the
   database, so that a copy of the database alone does not open anyone's mail. Disconnecting withdraws the
   permission at Google and removes the token.
4. **The mail source is one replaceable part** (S03 section 7). If Google's route does not hold — the warning
   turns into a refusal, or the permission keeps ending — IMAP with an app password takes its place without
   touching what reads and judges messages.

### What it costs, and what would change the decision

- **Not tried against Google.** The owner checks it with both accounts when the server exists, and again after
  more than a week (S03 section 9). Until then this decision rests on documentation.
- Google's policy asks apps that keep restricted data on a server for the security assessment. The exception for
  personal use is about skipping verification as a whole; Google could enforce more than it does today. That
  is the case for the fallback.
- An app password would be a wider permission than the one chosen here. If the fallback is ever needed, that
  trade is to be decided again, with the owner.

### Sources (read on 2026-11-19)

- Google Cloud Console Help, "Manage App Audience": a permission given to an app in Testing ends after seven
  days, and so does its token; https://support.google.com/cloud/answer/15549945
- Google for Developers, "OAuth app state overview": apps in production that are not verified show the warning
  and are capped at 100 people; https://developers.google.com/identity/protocols/oauth2/production-readiness/overview
- Google for Developers, "Restricted scope verification" and "Choose Gmail API scopes": `gmail.readonly` is a
  restricted scope; what verification asks for;
  https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification and
  https://developers.google.com/workspace/gmail/api/auth/scopes
- Google Cloud Console Help, "Verification requirements" and its questions and answers: verification can be
  skipped for an app used by fewer than 100 people the developer knows personally;
  https://support.google.com/cloud/answer/13464321 and https://support.google.com/cloud/answer/13463817
- Google for Developers, "Synchronize clients with Gmail" and `users.history.list`: how to keep up, and that a
  marker that has grown too old is answered with 404 and a full look;
  https://developers.google.com/workspace/gmail/api/guides/sync

## D-007 — The AI steps: two models, answers of a fixed shape, no tools

**Date:** 2026-11-19 · **Task:** WP-TS-74 · **Applies to:** reading the mail (S04, WP-US-21)

### Context

S01 section 6 fixes two steps — a small model that sees the beginning of every message and decides whether it
concerns the wedding, a larger one that reads those messages in full and proposes entries — and that Claude is
called through the Anthropic API, by the backend only. Left open were which models, how an answer is made to
have a shape the server can rely on, what a message can make the models do, and what it costs. Nothing was
tried against the real service: there is no key and no server yet.

### What was compared

| Question | Chosen | The alternative, and why not |
|---|---|---|
| Triage | **Claude Haiku 5.5** (`claude-haiku-5-5`), at low effort | The larger model for both steps: every message of two mailboxes would cost forty times as much for a yes or no |
| Extraction | **Claude Opus 5.5** (`claude-opus-5-5`), at medium effort | A middle model: cheaper, but this is the step where a wrong day or amount lands in front of the owners, and only a few messages in a hundred reach it |
| The shape of an answer | The API's **structured output**: the answer must fit a schema, and the server checks it again | Asking for JSON in words: sometimes it is not. A tool the model is forced to call: no longer offered for these models |
| What the models can do | **Nothing but answer**: no tools are given | Tools for looking up vendors or tasks: a message could then steer what is looked up, and nothing here needs it |
| Calling the service | Anthropic's own **Java SDK** | Plain HTTP: retries, errors and the answer's types would be written again by hand |
| When the larger model declines a message | The service's own **fallback to another model** is switched on for extraction; if that declines too, the message is "could not be read" | Stopping at the first refusal: messages about contracts and money would be skipped more often than needed |
| Reading the first three months | **One message at a time**, as it is read later | The service's batch interface at half the price: answers within a day instead of at once, and a second way of reading to build and test. Worth it only if the cost below turns out much higher |

### Decision

1. **Triage: `claude-haiku-5-5`. Extraction: `claude-opus-5-5`.** Both are settings of the server; the decision
   is their starting values.
2. **Structured output and no tools**, for both. What a message says can at most become a proposal that waits
   for a person (S04 section 5).
3. **The Java SDK** (`com.anthropic:anthropic-java`), with its own retries; a refusal, an answer that was cut
   off and an answer that does not fit are each a verdict, not an error to try again.
4. **The server keeps count** of calls and tokens per day and model, shows an estimate of the cost, and stops
   at a monthly limit (S04 section 8). The account's own spending limit is the second brake.
5. **The models' instructions are in the repository**, in English, and say that the messages are in Polish,
   Ukrainian or English and that a title is written in the language of the message.

### What it should cost — an estimate, to be replaced by what is measured

Prices on 2026-11-19, per million tokens: Haiku 5.5 0.10 USD in and 0.50 out; Opus 5.5 4 USD in and 20 out.

| | Per message | Per 1,000 messages |
|---|---|---|
| Triage, every message | about 700 tokens in, 100 out | about 0.12 USD |
| Extraction, if 5 in 100 concern the wedding | about 6,000 tokens in, 1,500 out | about 2.70 USD |

So about **3 USD for a thousand messages**, nearly all of it extraction, and perhaps 15 USD for a first look over
two mailboxes with 5,000 messages between them. The share of messages that concern the wedding moves this more
than anything else; the monthly limit starts at 20 USD for that reason.

### What it costs otherwise, and what would change the decision

- **Not tried against the service.** How often triage is right in Polish and Ukrainian, how often a day or an
  amount is read correctly, and the real cost are measured with the sample set of S04 section 10 when the owner
  provides a key. If triage misses wedding mail, the larger model or more of the message is the first thing to try.
- With these models the instructions are short; the service's cache for repeated instructions is not relied on.
- If the first look costs several times the estimate, the batch interface is the next step.

## D-008 — Added by itself: tasks and steps only, never money or a new vendor; undo rejects

**Date:** 2026-11-19 · **Task:** WP-TS-80 · **Applies to:** suggestions added by themselves (S05, WP-US-22)

### Context

S01 section 6 says that a proposal the import is sure enough of is added at once, marked as coming from e-mail,
with one-action undo, and that the threshold starts at 0.85. It leaves open what "added" may include and what
undo does. A proposal comes out of a mail that anybody can write (S04 section 5); everything else in the import
ends in front of a person. Adding by itself is the one place where it does not.

### What was compared

| Question | Chosen | The alternative, and why not |
|---|---|---|
| What may be added without a person | **Tasks and steps of the plan** | Payments too: a mail made to look like a vendor's invoice would put "pay 5,000 by Friday" into the plan and into the budget's sums, and a plan is trusted because it is the couple's own. Vendors too: a new party with contact details nobody checked |
| What undo does | **Removes the entry and rejects the suggestion**, in one action | Putting the suggestion back to wait: the wrong entry would need a second action to be gone for good, and the inbox would fill with what was already looked at |
| Undo after the entry was changed | **Refused**; the entry is then the couple's own and is changed or removed like any other | Removing it anyway: somebody's later work would vanish with one tap on a list |
| What "kept" means | A person's **accept**, optional; after thirty days an entry nobody undid counts as kept | Requiring it: the point of adding by itself is that nobody has to do anything |
| Where the threshold lives | **One setting for the wedding**, 50 to 100 or never, on the e-mail page | One per owner: two answers to "did the planner add this" for one plan |
| How an entry knows where it came from | The entry holds **that a suggestion made it, and which** — for accepted ones as well | A flag "from e-mail" without the source: the mark would lead nowhere |

### Decision

1. **Only a task or a step of the plan is ever added by itself.** A payment and a vendor always wait.
2. **At or above the threshold** (85 to begin with; 50–100 or never), and only when the planner would take the
   proposal as it is.
3. **Undo removes the entry and rejects the suggestion; keep accepts it.** Both are a person's decisions and
   stand. Undo is refused once the entry is no longer what was added.
4. **Every entry a suggestion made says so and leads to its source**, whoever or whatever accepted it.

### What it costs, and what would change the decision

- A payment the import is certain of still needs one tap. That is the price of never having money in the plan
  that nobody looked at. The owner can ask for payments to be included; the reasons above then need an answer —
  for example only from senders that are already vendors of the planner.
- How often the import is sure and wrong is not known: nothing has run against the AI service. The sample set
  (S04 section 10) measures it; until then 85 is S01's starting value, not a finding.

## D-009 — Earlier mail is read only when its owner asks for it

**Date:** 2026-11-19 · **Task:** WP-TS-87 · **Applies to:** the e-mail import (S03 section 5, S04)

### Context

S01 section 6 has the last three months of a mailbox scanned the moment it is connected, and S03 built that: the
first look noted ninety days of mail, and the reading (S04) then went through all of it. With the planner
published, the owner asked for the first load to be something a person starts — a button on the e-mail page, for
about the last thirty days.

### What was compared

| Question | Chosen | The alternative, and why not |
|---|---|---|
| What connecting reads | **New mail only**, from that moment | Ninety days at once: every message of three months goes to the AI service, at a cost and over days, before anyone has said that they want it — and nothing on the page starts or stops it |
| How earlier mail is read | **When its owner asks**, on the e-mail page, for their own mailbox | For both mailboxes at once: one owner would send the other's mail to be read |
| How far back | **30 days to begin with, 7 to 90** | A fixed thirty: the weeks in which a venue was booked may lie further back. More than ninety: S01's bound, and the cost grows with every week |
| Asking again | **Harmless**: what is noted is not noted twice, what was read is not read again | Once only: a first try with fourteen days would shut the door on the rest |
| Mail noted by the old first look and not read yet | **Let go of** when the server is updated; a message that was read keeps its verdict | Keeping it: on a server that was connected before the update, three months would still be read unasked |

### Decision

1. **Connecting notes where the mailbox stands and nothing of what is in it.** New mail is picked up from then on.
2. **Looking back is asked for by the owner of the mailbox**, for the last 7 to 90 days, 30 to begin with; it can
   be asked for again and for longer.
3. What is found by looking back is **read like new mail**, under the same limits (S04 section 8).
4. This takes the place of "On connect the last 3 months are scanned" in S01 section 6.

### What it costs, and what would change the decision

- Somebody who connects and never presses the button gets proposals from new mail only. The page says so where
  the button is.
- A message that arrived between connecting and the update that brought this in, and was not read yet, is found
  again only by looking back. On a server where the reading was not yet switched on that is every message noted
  so far: each owner presses the button once after the update.
- The limits of S04 hold for a look back too: at most fifty new suggestions from one mailbox in a day. Thirty
  days of busy wedding mail can therefore take several days to appear; the limit is a setting.

## D-010 — The image is published at every push to main, and the server takes the newest by itself

**Date:** 2026-11-19 · **Task:** WP-TS-90 · **Decided by:** the owner · **Replaces:** D-002

### Context

D-002 had the owner publish from the Mac with a script, so that nothing went live unless the owner ran it, and
named GitHub Actions as the alternative "if publishing by hand becomes a burden". The first real publishing was
that burden: the Mac needed Java 25 installed, its Node was three versions newer than the one the project is
tested on, and two rounds of failing checks had to be explained before an image existed. The owner then asked
for it to be automatic, the way the other application on the same server works: a workflow publishes at every
push to `main`, and a job on the server pulls the newest image every ten minutes.

### What was compared

| Question | Chosen | The alternative, and why not |
|---|---|---|
| Who publishes | **A workflow in this repository**, at every push to `main` | The script on the Mac only: correct, and it depends on the tools of a machine that is also the owner's own computer |
| When the server updates | **By itself**, a job every ten minutes that asks for the tag the server's `.env` names | By hand after every publishing: the second half of the burden |
| What the workflow may do | Run for `main` and when started by hand, **never for a pull request**; no secret; only the job that pushes may write packages; GitHub's own three actions named by commit; the image built and pushed with plain `docker` | Third-party actions for building and pushing: more convenient, and more code from elsewhere with the right to publish |
| What it checks | The gate for the backend and the web app, the two parts the image is built from | The whole gate with the Android part: an Android SDK in every run, for a part that is not in the image; sessions run the whole gate before anything reaches `main` |
| Pushes that change no part of the image | **Publish nothing** (documents, the phone app, the tracker's tooling) | Publishing every push: the server would restart for a corrected sentence |
| Going back | `IMAGE_TAG=<commit>` in the server's `.env`: the job then stays on that commit | Stopping the job: one more thing to remember to start again |

### Decision

1. **`.github/workflows/publish.yml` publishes every push to `main`** that changes what the image is made of:
   the checks, a build for `linux/arm64`, a push under the full commit and as `latest`.
2. **`deploy/update.sh`, run every ten minutes on the server, takes the newest image** of the tag in `.env`.
3. **`scripts/publish-image.sh` stays** as the way to publish by hand.
4. **What reaches `main` goes live.** `main` is therefore only ever given what has passed the whole gate
   (CLAUDE.md), and a session says in its closing comment that the change is live once the workflow has run.

### What it costs, and what would change the decision

- Nobody looks before a change is live. A session that puts a mistake on `main` puts it on the server; a
  change of the database is applied with it. The answers are the gate, the nightly backup, and a rollback that
  is one line in `.env`. If that proves too little, the server's job can be given a tag that only the owner moves.
- A public repository now has a workflow that can publish. Its triggers, its permissions and its pinned actions
  are what keep that safe (the workflow's own first lines say how); a change to that file deserves a second look.
- The checks run on GitHub's machines and no longer on the Mac, where they found two real differences of the
  owner's machine. Those were faults of the tests, not of the planner; the script still runs them there.
- Publishing depends on GitHub Actions being available and allowed for the repository. When they are not, the
  script publishes.

## D-011 — PDF documents of wedding mail are read, as text the server takes out of them

**Date:** 2026-11-19 · **Task:** WP-TS-91 · **Decided by:** the owner asked for it · **Applies to:** reading the mail (S04)

### Context

S01 left attachments out of the first version: "a PDF invoice is seen only through the e-mail's own text". On
the first day with real mail the owner found what that costs. A venue's mail mentioned the deposit; the offer
attached to it held the second and third payment with their days, and nothing of that was proposed. The owner
asked for attachments to be read — and only where the planner is quite sure that the mail concerns the wedding.

### What was compared

| Question | Chosen | The alternative, and why not |
|---|---|---|
| How a PDF is read | **The server takes the text out** and sends it with the message | Sending the file to the AI service to read as pages: it reads scans and tables better, but then a quotation cannot be checked against anything the server holds, every page costs far more, and a whole file leaves the server instead of its words |
| Which attachments | **PDFs**: up to three per message, 10 MB and fifty pages each, 40,000 characters in all | Everything: pictures and spreadsheets need other readers, and each is a new kind of file from strangers for the server to open |
| When | **Triage said yes and was at least 80 in 100 sure** (a setting) | Every message about the wedding, also the doubtful ones: the owner's condition, and the reason for it — a document is more of somebody's mail than its text is |
| What a payment found in a document becomes | **A suggestion that waits**, like every payment (D-008) | Added by itself: money in the plan that nobody looked at, now from a file anybody can attach |
| Mail read before this | **Read once more for its documents**, by itself, telling the model what was already proposed | Leaving it: the venue's offer that prompted this would stay unread. Reading everything again from the start: paid twice, and proposed twice |
| Opening files from strangers | Text only, nothing run or fetched; limits on size, pages and time; **one try per message** | No guard: a file made to exhaust the server would do so again at every round |

### Decision

1. **The text of PDF documents is read with a message** that triage called a "yes" with at least 80 in 100.
2. **The server takes the text out**; the file is neither kept nor sent. A passage quoted from a document is
   checked against that text like one from the mail.
3. **Limits**: three documents, 10 MB and fifty pages each, 40,000 characters in all, a few seconds each; what
   does not fit or cannot be read is left out and the message is read without it.
4. **Up to eight proposals** from a message with documents; payments still wait for a person.
5. **A message's documents are tried once**, and messages read before this are read once more for theirs.

### What it costs, and what would change the decision

- **A scanned offer is not read**: a PDF that is pictures of pages has no text to take out. If that turns out to
  be how vendors send things, the next step is to let the AI service read such a file as pages — for those
  files only, with the quotation then unchecked and said to be.
- The server now opens files that anybody can send. The library that does it reads and does not run, and the
  limits bound what one file can cost; a file that stops the server is not opened a second time.
- More is sent to the AI service per message: an offer of five pages is roughly as much text again as a long
  thread. The estimate of D-007 holds per message without documents; the e-mail page shows what it comes to.
- Word files, spreadsheets and pictures stay unread. Each would be a decision like this one.

