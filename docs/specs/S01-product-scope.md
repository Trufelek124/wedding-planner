# S01 — Product scope and working agreement

**Status:** accepted by the owner 2026-11-17 — the answers to the open questions are in §14, and none of the
proposals was refused. Nothing is built yet.
**Sources:** the owner's brief and two rounds of questions (2026-11-17); the LearningGerman (LG) project's documents in
infokuznia for stack, hosting and process (LG-DOC-18 architecture, LG-DOC-3 container contract, LG-DOC-56 container image,
LG-DOC-4 working with infokuznia).

Lines marked **Proposal** were my choices, put to the owner with "say no to any of them".

## 1. What we are building

A private wedding planner for the two of you: a web app, a native Android app and one backend.

- **Plan** — everything that has to happen, as a list and as a Gantt chart.
- **Tasks** — single actions ("pay the venue deposit"), with or without a date.
- **Priorities** — set by hand or worked out automatically.
- **Inspirations** — links and pictures, collected from Instagram or TikTok through Android's Share button.
- **E-mail import** — the backend reads both Gmail inboxes, decides what concerns the wedding and turns it into tasks.
- **Budget and payments, vendor contacts, guest list.**

## 2. Decisions taken with the owner (2026-11-17)

| Topic | Decision |
|---|---|
| Stack and deployment | Same as LG |
| Interface language | English |
| Users | The two of you; Google sign-in |
| E-mail providers | Gmail now; Outlook may follow |
| E-mail findings | Added automatically when the AI is confident; otherwise queued for review |
| E-mail history | Last 3 months when an inbox is connected, then continuously |
| In the first version | Plan + Gantt, tasks, priorities, inspirations with Android share, e-mail import, budget and payments, vendors, guest list |
| Tracker | A project of its own in infokuznia, code `WP`; the LG flow may be changed where it helps |
| Starter checklist | Yes: one wedding with Polish and Ukrainian formalities combined; anything on it can be deleted |
| First deployment | Right after the plan and the Gantt chart work; the owner configures the domain first |
| E-mail triage | Sees every message |
| Phone notifications | Not in the first version |
| Repository | Stays public: no host names, secrets or personal data in it. The tracker's address and the analyst's id are variables of the cloud environment |
| Story acceptance | The analyst of the InfoKuźnia project's stories is the analyst on this project's stories |

## 3. Concepts

**Wedding.** One per installation: date, currency, time zone, the two members.

**Plan item.** A step of the preparation that takes time: "choose a photographer", "send invitations".
Title, notes, category, start and end date (both optional), status (not started / in progress / done / dropped),
who does it (one of you or both), priority, the vendor it concerns, and "depends on" links to other plan items.
Items with dates are bars on the Gantt chart; items without dates stay in the list under "not scheduled".

**Task.** One action: "call the florist", "pay the deposit". Title, notes, optional due date, done or not, priority, who,
and optionally the plan item and the vendor it belongs to. A task under a plan item shows as a marker on that item's bar.
- **Proposal:** plan items and tasks are one kind of record underneath, so a task can be turned into a plan item and back.

**Payment.** A task that carries money: amount, budget category, kind (deposit / instalment / final), paid date.
"Pay for something" is therefore one entry — it is in the task list and in the budget at once.

**Budget.** A total and a planned amount per category. The summary shows planned, committed (all payments) and paid.

**Vendor.** Name, category, status (considering / booked / dropped), contact person, phone, e-mail, website, address,
agreed price, notes. Its page lists its plan items, tasks, payments and the e-mails that came from it.

**Guest.** Name, side (yours / your partner's / both), household, status (not invited / invited / confirmed / declined /
maybe), plus-one, children, dietary needs, needs accommodation, contact, notes. Counts per status.

**Inspiration.** A link or pictures, the source (Instagram, TikTok, Pinterest, web, photo), a board (Dress, Decor,
Flowers, Venue … — you edit the list), a note, tags, a preview picture when one can be fetched, who added it, and
optionally the plan item or vendor it relates to.

**Suggestion.** Something the e-mail import found: a proposed task, payment, plan item or vendor, with the e-mail it
came from (sender, subject, date, a link that opens it in Gmail), the passage it is based on, and a confidence.

**Starter checklist.** Offered when the wedding is set up: the usual steps as plan items and tasks, dated backwards
from the wedding day. It covers one wedding with both countries' formalities — Poland and Ukraine — together with the
general preparations. Entries are grouped (for example civil formalities, documents and translations, religious
ceremony, traditions, guests travelling from abroad), and you can delete a single entry or a whole group.
The legal entries are prompts to check with the registry office or consulate, each with its source; they are not
legal advice, and they are researched when the checklist is built.

## 4. What each surface does

**Web — full management.**
Home (countdown, what is next, what is overdue, budget at a glance, new suggestions) · Plan as list and Gantt (drag bars
to reschedule, draw dependencies) · Tasks · Inspirations board · Budget · Vendors · Guests · Suggestions inbox ·
Settings (wedding date, Gmail connections, auto-add threshold, boards and categories).

**Android — everything visible, quick actions, sharing.**
All of the above is readable. Editing in v1: tick a task off, add or edit a task, add an inspiration, accept or reject a
suggestion, change a guest's status. The Gantt is a read-only timeline on the phone. The app appears in Android's share
sheet. Last loaded data stays readable offline; shares made offline are queued and sent later.

**Backend.** One service with the API for both clients, the database, the Gmail sync, the AI pipeline, link previews
and picture storage.

## 5. Priorities

Four levels: Critical, High, Normal, Low. Every plan item and task is either **Auto** (the default) or **Manual** —
a level you set stays until you switch the entry back to Auto.

**Proposal — the automatic rule, version 1.** It is a fixed rule, so the result is predictable, and each entry shows
the reason.

| Level | When |
|---|---|
| Critical | overdue, or due within 3 days, or a payment due within 7 days |
| High | due within 14 days, or another entry due within 30 days depends on it |
| Normal | due within 60 days |
| Low | later, or no date |

For a plan item "due" is its end date; an item that should have started and has not moves up one level.
A deadline found in an e-mail ("please confirm by Friday") becomes the due date, so it goes through the same rule.
An AI that ranks entries is left out of v1.

## 6. E-mail import

1. **Connect.** Each of you connects Gmail once, right after the first sign-in. Access is read-only: the app never
   sends, changes or deletes mail.
2. **Read.** On connect the last 3 months are scanned; afterwards new mail is picked up every few minutes.
3. **Triage.** A small Claude model sees the sender, the subject and the beginning of each message and answers one
   question: does this concern the wedding?
4. **Extract.** For messages that do, a larger model reads the full text with its thread and proposes entries: kind,
   title, due date, amount, vendor, confidence, and the passage it relied on.
5. **Match.** A message in a thread that already produced an entry updates that entry instead of adding a second one.
   A sender who matches a vendor is linked to it.
6. **Route.** At or above the threshold (start value 0.85, adjustable) the entry is added, marked "from e-mail", with
   one-tap undo. Below it, the entry waits in the Suggestions inbox for accept, edit or reject.

**What is kept.** For every scanned message: its Gmail id and the verdict, so it is never read twice. For wedding
messages also sender, subject, date and the short passage behind the entry. Full message texts are not stored.

**What leaves the server.** The sender, subject and beginning of *every* message go to the Anthropic API for triage;
the full text only for wedding messages. The owner confirmed this scope (§14, question 4).

**Not in v1:** attachments (a PDF invoice is seen only through the e-mail's own text), Outlook, sending mail.
The mail source is built as a replaceable part so Outlook or plain IMAP can be added.

**To verify when this is built:** Google treats read access to Gmail as a restricted permission. For two private
accounts this normally works without Google's review, at the price of an "unverified app" warning on the consent
screen. If it does not hold up, the fallback is IMAP with an app password.

## 7. Inspirations and the Android share

- In Instagram or TikTok: Share → Wedding planner → pick a board, add a note → Save. Links and pictures both work.
- The backend tries to fetch a title and a preview picture. Instagram and TikTok often refuse this; then the card shows
  the platform and your note, and a screenshot can be attached by sharing it the same way. Tapping a card opens the
  original.
- On the web: paste a link or drop pictures; a board view with filters.
- Pictures are stored on the server and are part of the backup.

## 8. Technology

Taken from LG (LG-DOC-18 and its three code maps): the same versions, so both apps are run and maintained the same way.

| Part | Choice |
|---|---|
| Backend | Java 25, Spring Boot 4.1, Gradle 9; modular monolith; PostgreSQL 18; Flyway migrations at start-up |
| Web | Vite 8, React 19, TypeScript 5.9; built into the backend's image and served by the backend |
| Android | Kotlin + Jetpack Compose, native; local cache with Room |
| Sign-in | Google sign-in → the backend's own token; an allow-list holding your two addresses |
| Contract | The backend generates `openapi.json`; web and Android generate their types from it |
| AI | Claude through the Anthropic API, called only by the backend; tests never call the real API |
| Background work | A jobs table in PostgreSQL; no message broker |

**Proposal — where this differs from LG.**
- **One repository** (`backend/`, `web/`, `android/`, `docs/`, `scripts/`) instead of four. The app is small, one
  checkout builds the image, and infokuznia linked one git repository per project when LG documented it (LG-DOC-4).
- **A lighter backend structure.** Modules (identity, planning, budget, vendors, guests, inspirations, mail import)
  with checked boundaries, without LG's full hexagonal layering — that was introduced there for a much larger codebase.

## 9. Deployment

The LG container path (LG-DOC-3, LG-DOC-56): one image holding the backend and the web app, non-root, configured only
through environment variables, health endpoint, its own PostgreSQL, run by the home server next to LG, reached under a
public HTTPS name, backed up nightly from the host. Two points are decided when we get there:
- how the image is published — LG pushes it from the Mac with a script because GitHub Actions are off there;
- how the Android app reaches your partner's phone — LG gives testers a signed release build.

## 10. Working with infokuznia

The owner creates the project; I create and maintain everything inside it. (The tracker's agent tools cannot create
a project, and its REST interface is not reachable with the agent token.)

- Project code `WP`. Every story has an analyst, who gives it its final acceptance (§2).
- **Epic** = a product area (§11). **Story** = a spec or a feature you can see, with acceptance criteria.
  **Task** = one unit of work on one branch.
- **Task first.** A task exists before work on it starts. I claim it when I start, write `WP-TS-n: …` in the commit
  subjects, and close it with a comment: what changed, test totals, the commit, what was not verified. I complete the
  task once the work is pushed and its checks pass. Your review and the story's final acceptance stay yours.
- **Documents.** `docs/` in this repository is the source; a sync script mirrors it into infokuznia (specs as
  requirements linked to their story, decisions as ADRs, architecture, deployment). Nothing is edited in infokuznia by
  hand.
- **Decisions** are numbered in `docs/decisions.md` (D-001 …). `docs/state.md` holds one screen on where the project
  stands, updated after each finished task.

**Changed from the LG flow:** no separate card and brief layer (the task is the card; `docs/infokuznia-ids.json` maps
our keys and the public ids to UUIDs), no separate docs repository, and one agent both does and completes a task.

## 11. Build order

| Epic | Contents |
|---|---|
| 1 Foundation | Repository skeleton for all three parts, the test gate, sign-in with the allow-list, wedding setup, the API contract, infokuznia tooling and document sync |
| 2 Plan, tasks, priorities | Plan items and tasks, lists, automatic and manual priority, dependencies, the Gantt chart on the web, the starter checklist, the home screen |
| 3 First deployment | The image, the home-server entry, the domain (owner), backups — the planner is usable on the web from here |
| 4 Android app | Sign-in, home, lists, quick actions, offline reading, the release build for the second phone |
| 5 Inspirations | Storage and previews, the web board, the Android share target |
| 6 Budget, vendors, guests | The three registers and their links to plan items, tasks and payments |
| 7 E-mail import | Gmail connection and sync, triage and extraction, Suggestions inbox, auto-add |

E-mail import comes last because it writes into every other part.

## 12. What I need from the owner

| When | What |
|---|---|
| Done 2026-11-17 | The infokuznia project `WP`; push access to the repository; the decision that the repository stays public |
| Open | For the actor my token acts as: the analyst, documentation and tester roles. It holds developer, architect and product owner; tasks that ask for the other three are refused |
| Epic 1 | A Google OAuth client for sign-in (web and Android). I can build and test without it through a development login |
| Epic 3 | The domain configured, and the server-side entries |
| Epic 7 | Gmail API switched on for that Google project; an Anthropic API key on the server |

## 13. Not in the first version

Public RSVP page, seating plan, sending invitations or e-mail, e-mail attachments, Outlook, iOS, more than one wedding,
helpers with their own accounts, AI-ranked priorities, phone notifications for due dates.

## 14. Questions answered by the owner (2026-11-17)

1. Is `WP` / `WeddingPlanner` right for the infokuznia project? — **Yes.**
2. A starter checklist that pre-fills the plan? — **Yes, for Poland and Ukraine: one wedding with combined
   formalities; we must be able to delete the entries we do not want.**
3. Should the first deployment come right after the plan and Gantt work? — **Yes; the owner configures the domain
   first.**
4. Should the e-mail triage see every message? — **Every message.**
5. Phone notifications for due tasks left out of the first version? — **Agreed.**
