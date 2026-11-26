# S10 — Steps of the plan on the phone

**Status:** written on 2026-11-26 at the owner's request. **Builds on:** S01 section 4 (the plan: steps with
tasks) and its part on the phone ("everything visible, quick actions"), D-005. **Story:** WP-US-13.

## 1. What this covers

On the phone the plan could only be looked at: a step of the plan was made and changed on the web, and an empty
plan said so. A task could be added to a step, one at a time, each through the whole form. The owner wants to
**add steps on the phone, and to add their points there** — the things to do that belong to a step, which the
planner calls its tasks.

This spec lets a step of the plan be **added and changed on the phone**, and lets **several tasks be added to a
step one after the other**.

Not covered, and staying on the web: removing a step; making a task of a step or a step of a task; what a step
waits for; moving a step on the timeline, which on the phone only shows (S01).

## 2. Adding and changing a step

- The plan's screen offers **Add a step**, as the tasks' screen offers "Add a task". An empty plan says that a
  step can be added, and no longer that the plan is put together on the web.
- A step in the list **opens on its form** when it is pressed, as a task does.
- **The form of a step** holds: its name; its first day and its last day; its status; its priority — set by the
  planner or chosen by hand, with what the planner works out, as on a task's form; who does it; its category; its
  vendor. It has no "belongs to" and no amount: those are a task's.
- **The planner's own rules hold**, with the words of the web's form: a name is needed; a step has both days or
  none ("Enter the end date too, or leave both dates empty."); the last day is not before the first. What the
  phone can tell by itself it says beside the field before anything is sent; what only the server can tell comes
  back beside the field as it does for a task.
- **It works like a task's change** in every other way (CLAUDE.md, "The Android app"):
  - what was added or changed is shown at once, in the list and on the timeline, and says that it waits to be
    sent while it does;
  - it is sent when there is a connection, also after the app was closed;
  - a new step gets its id on the phone, so that sending it twice adds it once;
  - a change carries the version it was made on and is never sent over a newer one: the step then shows the
    latest version and says that it has to be looked at;
  - a step that is gone from the server, or a change the server does not take, is said with the step's name.
- A step that waits to be sent can already be given tasks: they are sent after it.
- Signing out removes what waits, steps included, and asks first, as before.

## 3. Adding tasks to a step in a row

- Under a step that is open, **Add a task to this** stays where it is.
- The form that it opens — and only that one — has, beside "Save", **Save and add another**: the task is kept,
  the form stays, empty again and still belonging to that step, with the keyboard in the title. So the points of
  a step are typed one after the other.
- Each of them is a task like any other: kept at once, shown under the step, sent when there is a connection.
- "Save" keeps the last one and leads back to the plan, as before; "Cancel" keeps nothing more and leads back —
  what was already kept stays.

## 4. How it is checked

- The screens' tests drive the plan as a person would, against the stand-in server that keeps what it is sent:
  adding a step with and without days, the rules beside their fields, changing a step, each without a
  connection and after it is back; a step changed by the partner meanwhile; a step that is gone; tasks added to
  a step that still waits; several tasks added in a row and where the keyboard is after each.
- What the phone sends for a step is held against the contract; the database is carried over from the older
  layout with what waited in it.
- The pictures of the step's form and of the plan with a waiting step were looked at.
- Not checkable here: a real phone's keyboard, date picker and TalkBack.
