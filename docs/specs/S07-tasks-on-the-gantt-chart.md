# S07 — Tasks on the Gantt chart

**Status:** written on 2026-11-24 at the owner's request. **Builds on:** S01 section 4 ("Items with dates are
bars on the Gantt chart"), D-001 (the chart is our own drawing). **Story:** WP-US-7.

## 1. What this covers

The chart of the plan shows the steps of the plan that have dates, one bar each, and of the tasks only those
that belong to such a step, as marks on its bar. A planner whose work is mostly tasks — from e-mail, from a
file, typed in on the phone — therefore shows an empty chart, and its owner asked for "a Gantt view" that the
planner already had. This spec puts **every task that has a due day** on the chart and calls the view by its
name.

Not covered: the phone's timeline, which stays the read-only view of the steps of the plan that it is; tasks
waiting for each other (only steps of the plan do, as before).

## 2. What the chart shows

- The view is called **Gantt** in the plan's views. Its address stays `/plan/chart`.
- First the steps of the plan with dates, as before: a bar each, the lines between the ones that wait for each
  other, and on a bar the marks of the tasks that belong to that step.
- Below them, under the heading **Tasks**, **a row for every task with a due day that is not marked on a bar** —
  a task that belongs to no step, or whose step has no dates or is not shown. The rows run by due day, then by
  title.
- In its row a task is **a marker on its due day** — a diamond, the sign charts use for a single day — with its
  title where a bar has its name. A payment says its amount with the title.
- What a task's state means is said in words as well as shown: a task that is done is drawn quietly and says
  "done"; one that is open and past its day is drawn as a warning and says "overdue". Colour alone says nothing.
- The time the chart spans reaches from the first to the last day of anything on it, tasks included.
- **The chart is there as soon as anything has a day**: a step with dates or a task with a due day. With
  neither, the page says what would put something on it.
- The filters of the plan (what is shown, who, category, priority) apply to the tasks' rows as they do to the
  steps. **Tasks can be hidden** with one control beside the scale, and the address remembers it like the
  filters.

## 3. Moving a task

- A task's marker is **moved along its row** to change its due day: dragged with the pointer, or with the arrow
  keys when it has the keyboard — a day at a time, a week with Shift, as a bar is moved. There is nothing to
  stretch: a task has one day.
- The new day is shown at once and saved after it, one change after the other, exactly as a bar's dates are. If
  the task was changed by somebody else meanwhile, the chart says so and shows the task as it now is; nothing is
  overwritten.
- Enter, or a click that moves nothing, opens the task's form, which returns to the chart.
- A task that is marked on a step's bar is not moved on the chart, as before: it is opened from there.

## 4. How it is checked

- Where a marker lies is arithmetic on its day, like a bar's place, and is tested without a screen.
- The chart's tests drive it as a person would: the rows and their order, what each row says, a payment, done
  and overdue in words, hiding the tasks, the filters, a chart with tasks only, moving by pointer and by keys
  with what is sent, a change refused as "changed meanwhile".
- It was looked at as rendered pictures, wide and narrow, light and dark.
