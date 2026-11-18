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
