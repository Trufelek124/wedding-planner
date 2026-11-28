# S12 — The planner's widget on the home screen

**Status:** written on 2026-11-28 at the owner's request, and built (WP-TS-114), tested against stand-ins and
the widget library's own drawing; not yet seen on a real home screen. **Builds on:** S01 section 4, S10 (tasks and steps on
the phone), D-005, D-016. **Story:** WP-US-27.

## 1. What this covers

The owner asked for **a widget with the tasks and the steps of the plan** on the phone's home screen: what is
current, the possibility to complete one, and to add new ones — also ones that lie in the future. This spec says
what the widget lists, what can be done on it, and how it stays current.

Not covered: several kinds of widget or settings of its own; changing a task on the widget (that is the app's
form); the budget, guests or inspirations on a widget.

## 2. What the widget shows

- Its head: the planner's name, and how many things are listed. Pressing it opens the planner at the tasks.
- **What is current**: the tasks and the steps of the plan that are still open and
  - are overdue, or
  - are due — a step: end — within the next fourteen days, or
  - are in progress, whatever their day.
- By day: the overdue first, then the nearest; what has no day (in progress only) last. The list can be moved
  up and down where it is longer than the widget.
- **A row** is the name and, under it, its day in words ("overdue since 22 Nov", "today", "on 3 Dec") — a
  payment with its amount, a step with the word that it is a step. A task has a box to tick in front.
- With nothing current the widget says so, and still offers to add.
- **When nobody is signed in** the widget says that the planner has to be opened to sign in, and shows nothing
  of anybody's plan. Pressing it opens the app.
- Signed in on a phone that has not loaded the planner yet, it says that the planner has to be opened to load
  the plan.
- It follows the phone's light and dark, and can be made larger and smaller.

## 3. What can be done on it

- **Ticking a task off** marks it done — a payment as paid — exactly as the tick in the app does: it is kept on
  the phone at once, the row leaves the widget, and it is sent when there is a connection, also when the app is
  not open. A step has no box: it is finished on its form.
- **Adding**: the head has **Add a task** and **Add a step**. Each opens the planner at that form, where the new
  one gets its day — today's or any later one — and everything else. Saved, it is on the widget when it is
  current, and in the plan in any case.
- **Pressing a row** opens the planner at that task's or step's form.
- A press on the widget never does more than one of these: there is no way to remove or to change from it.

## 4. How it stays current

- The widget shows the phone's copy: it is drawn anew whenever the copy changes — after a load, after anything
  the app or the widget changed — and when the day changes.
- The daily piece of background work that reminds (S11) also brings the copy up to date and draws the widget
  anew, whether or not reminders are on: it is set for as long as somebody is signed in.
- A new day reaches the widget with that work, when the planner is opened, and with any change — not at
  midnight: the widget has no timer of its own.
- **Signing out empties it at once.**
- Nothing of a task is written to the log.

## 5. How it is checked

- What is current is worked out by a piece of code of its own, tested without a phone: each of the three ways
  in, done and dropped left out, the order, the words of a row, a task ticked off on the phone.
- The widget's content and what its presses do are tested as far as the platform's test tools for widgets go:
  the rows for a plan, nothing current, signed out, a tick kept and sent, the two buttons and a row opening the
  right form.
- The small release build is checked to still hold the classes the phone finds by name.
- A test runs the widget's own drawing from the first draw to the last: it changes after a tick and after
  signing out without being started again, and is not drawn again while nothing changes.
- Not checkable here: how a real launcher draws it, resizes it and lets it be put on the screen, its list being
  moved, and a press arriving from a real home screen.
