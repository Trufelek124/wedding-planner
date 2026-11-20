# S06 — Tasks from a file

**Status:** written on 2026-11-20 at the owner's request. **Builds on:** S01 section 4 (the plan and its tasks),
D-004 (a payment is a task with an amount), D-013. **Story:** WP-US-25.

## 1. What this covers

A task list often starts outside the planner: a checklist from a site, a spreadsheet, a list another tool wrote.
An owner uploads such a list as **a file**, at the bottom of the task list on the web. The file has a written
format that the planner itself hands out, so that another tool can make it. Uploading the same file again, with
changes, **changes the tasks the planner already has from it and adds the ones it has not**. Nothing in a file
ever removes a task.

Not covered: uploading on the phone (the phone shows, the web sets — S01 section 4); steps of the plan, guests,
vendors or the budget from a file; handing the task list out as such a file; who a task is assigned to, which
step of the plan it belongs to and what it waits for. A task that came from a file has none of the last three
until an owner sets them in the planner, and an upload never touches them.

## 2. The file

A JSON file, UTF-8, at most 1 MB, with at most 1,000 tasks:

```json
{
  "format": "wedding-planner-tasks",
  "version": 1,
  "tasks": [
    {
      "key": "venue-second-instalment",
      "title": "Pay the venue's second instalment",
      "notes": "By transfer. The account is in the contract.",
      "dueDate": "2027-01-15",
      "status": "NOT_STARTED",
      "priority": "HIGH",
      "category": "Venue",
      "vendor": "Riverside Barn",
      "amount": { "value": "12000.00", "currency": "PLN" },
      "paymentKind": "INSTALMENT",
      "paidOn": null
    },
    { "key": "order-the-rings", "title": "Order the rings", "dueDate": "2027-03-01" }
  ]
}
```

**The format is published.** Its definition is a JSON Schema, and with it comes an example that a wedding
whose currency is PLN takes as it is:

- in the repository: `web/public/formats/wedding-planner-tasks-1.schema.json` and
  `web/public/formats/wedding-planner-tasks-1.example.json`;
- from the planner itself, without signing in (they hold nothing of anybody's):
  `/formats/wedding-planner-tasks-1.schema.json` and `/formats/wedding-planner-tasks-1.example.json`.

The upload page links both. A later version of the format gets a new number and new files; version 1 stays
readable.

| Field | | What it is |
|---|---|---|
| `format`, `version` | needed | `"wedding-planner-tasks"` and `1`. Anything else is not this format. |
| `tasks` | needed | The rows, 1 to 1,000. |
| `key` | needed | What the row is known by: any text of 1 to 100 characters that its author chooses, without a line break and without a space at its beginning or end. No two rows of a file have the same key. Capital and small letters are different keys. |
| `title` | needed | 1 to 200 characters. |
| `notes` | | Up to 10,000 characters. `""` empties them. |
| `dueDate` | | A day, `YYYY-MM-DD`, or `null` for none. |
| `status` | | `NOT_STARTED`, `IN_PROGRESS`, `DONE` or `DROPPED`. |
| `priority` | | `CRITICAL`, `HIGH`, `NORMAL` or `LOW`; `null` lets the planner set it from the dates, as it does for a task whose priority nobody chose. |
| `category` | | The name of a category, up to 60 characters, or `null` for none. A name the planner does not have yet **is added** as a category. Compared without regard to capital letters and to spaces around it. |
| `vendor` | | The name of a vendor the planner has, or `null` for none. Compared the same way. A name the planner does not have, or has twice, is refused: a vendor is not made from a file. |
| `amount` | | `null`, or `{ "value", "currency" }`: the task is then a payment (D-004). `value` is the amount as people write it without separators — `"12000"`, `"12000.00"`, or the same as a number — with no more decimals than the currency has; `currency` is the wedding's currency. The server turns it into its whole numbers exactly, and refuses what it would have to round. |
| `paymentKind` | | `DEPOSIT`, `INSTALMENT`, `FINAL`, or `null`. |
| `paidOn` | | The day a payment was paid, or `null`. |

A field the format does not name is refused, with its name: a slip of the pen (`duedate`) must not pass for a
field that was left out.

## 3. What a row does

- **A row is known again by its key.** The planner keeps the key with the task. A row whose key the planner has
  — on a task of this wedding — is that task; any other key is a new task.
- **A new task** takes what the row gives. What the row leaves out starts as on the planner's form: no notes, no
  day, not started, the priority set by the planner, no category, no vendor, no amount.
- **A task the planner already has is changed only in the fields the row gives**, and only where they differ.
  A field the row leaves out is not touched — so a file that knows only titles and days does not empty the notes
  or untick what was done. A field given as `null` (or `""` for the notes) is emptied.
- **What was changed in the planner since is overwritten by what the row gives.** The file is what its author
  wants the task to be; that is why the page shows every change before it is made (section 4). A file that
  should not move a task's status simply does not give `status`.
- **The planner's own rules hold for every row**, with the same words as on the form: a kind of payment needs an
  amount; a paid date needs an amount, a task that is done, and a day that is not in the future; a day is a real
  day. They are checked against the task as it would be after the row: what the row gives, and for the rest
  what the task has.
- **A file is taken whole or not at all.** If one row cannot be taken, nothing is changed, and the answer says
  which rows and why.
- **Nothing is removed.** A task that is not in the file stays as it is, whether it came from an earlier file
  or not. There is no row that deletes; a task that is no longer wanted is given the status `DROPPED`.
- A task that an owner removed in the planner is gone with its key: if a later file still has the row, it is a
  new task again.
- A key that belongs to something that is no longer a task (an owner made a step of the plan of it) is refused.
- A task made from a file is a task like any other: it can be changed and removed in the planner, appears on
  the phone, and counts in the budget when it has an amount.

## 4. The page

At the bottom of the task list, a part **Upload tasks**:

- two sentences on what it does — tasks from a file are added, the ones the planner already has from an earlier
  file are updated, nothing is removed — with the links **The format of the file** and **An example**;
- a file to choose. Choosing one **checks it at once**, and changes nothing;
- **what the file would do**: how many tasks are new, how many would change, how many stay as they are, and
  which categories would be added. The new ones by title; the changed ones by title with each change as "what:
  before → after"; the unchanged ones only as a number;
- when something cannot be taken: nothing can be applied, and the page lists it row by row — the row's number
  in the file, its key, what is wrong, in words for a person;
- one button that says what it will do (**Add 3 and change 2**). One press, one request; then what was done,
  and the list above shows it;
- a file larger than 1 MB is said before anything is sent; a file that is not JSON, or not this format, is said
  in words.

Everything that comes out of a file is shown as plain text.

## 5. What is refused, and what is kept

| What the file has | What the server says |
|---|---|
| Not JSON, not an object, or not `"format": "wedding-planner-tasks"` with `"version": 1` | That it is not a file of tasks in this format. Nothing else is looked at. |
| More than 1 MB, more than 1,000 rows, no rows | Which limit. |
| A row without a key or a title, a key used twice, a field the format does not have | The row, the field, and what is wrong. |
| A value of the wrong kind or beyond its length; a day that is no day; a status, priority or kind of payment that is not one of the list | The row, the field, and what is expected. |
| An amount in another currency, with too many decimals, negative, or too large | The row, and what is expected. Nothing is rounded. |
| A vendor the planner does not have, or has twice by that name | The row, and that the vendor is to be added first or left out. |
| A row that breaks one of the planner's rules (section 3) | The row, the field, and the form's own words. |
| A key that belongs to a step of the plan | The row, and that it is no longer a task. |

- Both calls need a signed-in owner and work on that owner's wedding only.
- **The file is not kept.** Of it, the planner keeps the tasks it made or changed and each task's key.
- **Nothing of a file is logged**: the log says that a file was checked or applied by which member and how many
  rows were new, changed, unchanged and refused.
- Checking changes nothing, however often it is done. Applying happens in one piece: a second owner never sees
  half a file.
- If the planner changed between the check and the press, what is applied is worked out anew, and the answer
  says what was done.

## 6. How it is checked

- The backend's tests cover every row of section 3 and every refusal of section 5, the same file applied twice
  (nothing twice), a field left out against a field given as `null`, both owners, and another wedding's keys.
- A test holds the server against the published schema — the same fields, the same fixed values, the same
  limits — and the published example is taken as it is by a wedding in PLN.
- The web's tests drive the part in each state with the stand-in server.
- Not checkable here: a file made by a real other tool. The schema and the example are what such a tool is
  given.
