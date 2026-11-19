# S05 — Suggestions that are added by themselves, and undo

**Status:** written on 2026-11-19 at the owner's request. **Builds on:** S01 section 6 ("Route"), S02
(suggestions), S04 (reading the mail), D-008. **Story:** WP-US-22.

## 1. What this covers

S02 lets every proposal of the e-mail import wait for a person. S01 wants more: a proposal the import is sure
enough of is **added at once**, marked as coming from e-mail, and can be **undone in one action**. This spec says
which proposals are added by themselves, what "added" and "undone" mean, where the owners see it, and how they
set how sure the import has to be.

Not covered, and tracked as a task of its own in WP-US-22: a later message of a thread changing the entry an
earlier one produced.

## 2. Which proposals are added by themselves

A proposal is added by itself when **all** of these hold:

1. Its kind is a **task** or a **step of the plan**. A **payment** and a **vendor** always wait for a person
   (D-008): nothing that names an amount of money or a new party to pay comes into the planner unseen.
2. How sure the import is reaches **the threshold** — 85 out of 100 to begin with.
3. What would be made is valid as it is proposed. A proposal that the planner would refuse (S02 section 3)
   simply waits.
4. Adding by itself is not switched off (section 6).

Everything else waits in the inbox as before.

## 3. What "added by itself" means

- The task or step is made at once, with the proposal's values, exactly as accepting it would make it — in the
  name of the owner whose inbox the message was in.
- The entry **says where it comes from**: wherever a task or step is shown with its details, one that came from
  e-mail says so and leads to its suggestion — the sender, the subject, when it arrived, the passage, and the
  link that opens the message in Gmail. This holds for every entry a suggestion made, accepted by a person or
  added by itself.
- The suggestion is **added by itself**: a fourth place it can stand in, beside waiting, accepted and rejected.

```
                      ┌─ accept ─▶ accepted
waiting ──────────────┤
   ▲                  └─ reject ─▶ rejected
   │ (not sure enough, a payment, a vendor)
proposal
   │ (sure enough, a task or a step)
   ▼
added by itself ──────┬─ keep ───▶ accepted
                      └─ undo ───▶ rejected   (the entry is removed)
```

## 4. Keep, and undo

Both are decisions of a person, and a decision stands (S02 section 3): the same one again changes nothing, the
other one is refused as already decided.

- **Undo** removes the entry that was added and rejects the suggestion: one action, and the message is not
  proposed again. It is meant for "this should not be in our plan".
- **Keep** says "yes, this belongs in our plan": the suggestion is accepted, by that person. Nothing else
  changes. Keeping is never necessary — an entry that was added by itself is a full entry whether or not
  anybody keeps it — it only takes the entry off the list of things to look at.
- **Undo is refused when the entry is no longer what was added**: somebody changed it, ticked it off, hung a
  task or a payment on it, or removed it. It is then theirs. The answer says so, and the suggestion counts as
  kept; the entry is changed or removed like any other. "No longer what was added" is exact: the entry was saved
  at least once since, or is not "not started" any more, or a task or payment hangs on it, or it waits for
  something or something waits for it, or an inspiration points to it.
- Undoing does not touch anything else: not other entries, not what the same message proposed besides.
- **Accept and reject are for what waits; keep and undo for what was added.** Asked of the other kind they are
  refused: accept or reject of an added one as already decided — by nobody yet: keep it or undo it.

## 5. Where the owners see it

- **The inbox** gets a part "Added by itself" beside what waits: each with what was added, where it comes from,
  the passage, and **Undo** and **Keep**. The newest first. It is not a list to work through: after thirty days
  an entry that nobody undid counts as kept and leaves the list — it is then shown as accepted, by nobody, and
  can no longer be undone; nothing has to run for that.
- **Home and the navigation** say how many were added by themselves and have not been looked at, next to how
  many wait — two numbers, because they ask different things of a person.
- **The entry itself** says "From e-mail" with the way to its source (section 3).
- On the **phone** the same: the part of the inbox, Undo and Keep — also without a connection, sent later like
  the other decisions (S02 section 7) — and the mark on an entry.

## 6. Setting how sure it has to be

On the e-mail import page, for the wedding (both owners see and change the same setting):

- **The threshold**, 50 to 100, to begin with 85: "Add tasks and steps by themselves when the import is at
  least this sure."
- **Never**: nothing is added by itself; everything waits. The number is kept for when it is switched on again.

A change applies to what is proposed from then on. It does not add what already waits, and it does not undo
what was already added.

## 7. What is kept, and what is not trusted

Nothing new is kept about a message. A suggestion that was added by itself holds what every suggestion holds
(S02 section 4), and the entry holds only that it came from a suggestion, and which.

What comes out of a mail is still not trusted (S02 section 5, S04 section 5). Adding by itself gives a mail one
thing it did not have before — a task or a step in the plan without a person having looked — and that is the
reason for the limits of section 2: no money, no new vendor, a threshold, a daily limit on what one mailbox can
propose (S04), and a mark on the entry that cannot be missed.

## 8. The API

| Call | What it does |
|---|---|
| `GET /api/suggestions?state=ADDED` | Those added by themselves that nobody has kept or undone, the newest first. `state=ALL` includes them; without `state` only what waits is answered, as before |
| `POST /api/suggestions/{id}/keep` | Keeps it: accepted, by the caller |
| `POST /api/suggestions/{id}/undo` | Removes the entry and rejects it; refused with `entry-changed` (409) when the entry is no longer what was added — the suggestion then counts as kept |
| `GET /api/suggestions/{id}` | One suggestion — what an entry's "From e-mail" leads to |
| `GET /api/suggestions/settings`, `PUT …` | `addsByItself` and `threshold` (50 to 100); to begin with on, at 85 |

A suggestion's `state` gains `ADDED`; while it is, nobody has decided (`decidedBy` and `decidedAt` are empty), and
`madeEntryId` is the entry that was added. After thirty days it is answered as `ACCEPTED` with nobody as the one
who decided.

An entry's answer gains `fromSuggestionId`: nothing, or the suggestion that made it — added by itself, or
accepted by a person. No request can set it. Every field is always present; an older phone app ignores the new
one and never asks for what was added.

## 9. Open, and where it is tracked

| What | Where |
|---|---|
| A later message of a thread changing the entry an earlier one produced | WP-US-22, a task of its own |
| Payments added by themselves | Decided against in D-008; to be opened again only by the owner |
