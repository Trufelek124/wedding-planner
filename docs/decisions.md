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
