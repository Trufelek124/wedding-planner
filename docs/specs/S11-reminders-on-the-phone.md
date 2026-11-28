# S11 — Reminders on the phone

**Status:** written on 2026-11-28 at the owner's request, and built (WP-TS-113; the daily work kept for the
widget, WP-TS-114), tested against stand-ins; not yet tried on a real phone. **Builds on:** S01 (which left phone notifications out
of the first version), D-005, D-016. **Story:** WP-US-27.

## 1. What this covers

The planner said what is due only when it was opened. The owner asked for **notifications of upcoming tasks on
the phone**. This spec says what the phone reminds of, when, how reminders are switched on and set, and what a
notification shows.

Not covered: reminders on the web or by e-mail; a notification for every single task at its own time;
notifications of what the partner did or of new suggestions; anything the server sends — the phone works it out
from the copy it keeps (D-016).

## 2. What the phone reminds of

- **What is due**: the tasks and the steps of the plan that are still open — not done, not dropped — and
  - are **overdue**: their day (a step's last day) is before today; or
  - are **due within the days the owner chose**: today only, today and tomorrow (to begin with), the next three
    days, or the next seven.
- "Today" is the day where the wedding takes place, as everywhere in the planner.
- A payment is named as a payment, with its amount.
- What waits on the phone to be sent counts as the phone shows it: a task ticked off there is not reminded of.

## 3. When, and what it looks like

- **Once a day, around a time the owner chooses** — 9:00 to begin with. The phone decides the exact minute
  itself (it may be some minutes later, more when the phone saves its battery): a reminder of the day does not
  need the minute, and asking for exact alarms would need a permission of its own.
- Before it looks, the phone **brings its copy up to date when there is a connection**, so that what the partner
  ticked off on the web is not reminded of. Without a connection it reminds from what it has.
- **One notification, or none.** With nothing due there is none. Otherwise one, in the place of yesterday's if
  that is still there:
  - its title says how much there is — "Pay the venue's deposit is due today" for one thing, "4 things are due"
    for several; on a locked phone that hides private content only the number is shown;
  - its lines name them, the overdue first, then by day, each with its day in words ("overdue since 22 Nov",
    "today", "tomorrow", "on 3 Dec"); as many as fit, then "and 3 more";
  - pressing it opens the planner at the tasks.
- It is shown once for a day. Opening the app, or the phone starting again, does not show it a second time.
- It makes no sound of its own choosing: it uses a channel "Reminders" that the phone's settings can make loud,
  quiet or silent.

## 4. Switching it on, and setting it

- **Reminders are off until the owner switches them on.** More has a row **Reminders** that leads to their
  screen: a switch, the time of day, and how far ahead (section 2).
- **The daily look itself goes on while somebody is signed in, reminders or not**, because it also brings the
  widget up to date (S12): the phone asks the server once a day, around the time that is set. With reminders
  off, or not allowed by the phone, it shows nothing.
- Switching them on **asks the phone for the permission to show notifications** where the phone wants that
  asked (Android 13 and later). Refused — or taken away later in the phone's settings — the screen says that
  the phone does not allow the planner's notifications and leads to the phone's settings for it; no reminder is
  attempted meanwhile.
- A change of the time or of how far ahead holds from the next reminder on.
- The settings are this phone's own: the other owner's phone has its own, and the server knows nothing of them.

## 5. What is kept, and what is not

- On the phone: whether reminders are on, the time, how far ahead, and the day a reminder was last shown.
- **Signing out switches the reminding off for that phone, removes a reminder that is still shown, and removes
  the settings** with everything else of the account.
- A notification shows names of tasks. Whether they are readable on a locked phone is the phone's own setting;
  the planner marks them as private, so that a phone set to hide private content hides them.
- Nothing of a task is written to the log: that a reminder was shown, and how many things it named.

## 6. How it is checked

- What is due is worked out by a piece of code of its own, tested without a phone: overdue, each choice of
  days, done and dropped left out, a task ticked off on the phone, steps, payments, the order, the wording of
  one and of several, "and n more".
- With the stand-ins for the phone and the server, and the test helper for background work: one notification
  for a plan with something due and none for one without; the copy brought up to date first, and the reminder
  still shown without a connection; once a day; the time and the days set; off by default; the permission
  refused; signing out.
- Not checkable here: when a real phone that saves its battery really runs the work, and how the notification
  looks on a real lock screen.
