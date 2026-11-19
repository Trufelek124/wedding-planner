# S02 — Suggestions: what the e-mail import proposes, and what becomes of it

**Status:** agreed with the owner on 2026-11-19 as the first step of epic 7: the server side of the suggestions and
the inbox on the phone. **Builds on:** S01 sections 4 and 6. **Story:** WP-US-22.

## 1. What this covers

The e-mail import (S01 section 6) reads mail and proposes entries. A proposal that is not added by itself waits for
one of the two owners to accept or reject it. This spec says what such a proposal — a **suggestion** — is, what
happens when it is accepted or rejected, how the clients ask for it, and how the phone shows it.

It does **not** cover how suggestions come into being. Connecting Gmail (WP-US-20) and the two AI steps (WP-US-21)
are built later and need the owner's Google project and an API key. Until then nothing produces suggestions outside
the tests, and the inbox is empty in real use. Also left for later, and kept as tasks of WP-US-22: adding by itself
at the threshold with undo, a later message updating an entry that an earlier one produced, and the inbox on the web.

## 2. What a suggestion is

| Part | What it holds |
|---|---|
| What is proposed | A **kind** — task, payment, step of the plan, or vendor — and what the import read out of the mail for it: a title (a vendor: its name), notes, a first day and a day it is due, an amount with the kind of payment, a category, a vendor the planner already has (found by the sender's address), or only a vendor's name where none matched. For a vendor: e-mail address, phone number, website. Everything but the title may be missing |
| Where it comes from | The sender's name and address, the subject, when the message arrived, whose inbox it was in (one of the two members), and a link that opens the message in Gmail |
| Why | The **passage** of the message the proposal rests on: a short quotation, never the whole message (S01 section 6, "What is kept") |
| How sure | The import's confidence, as a whole percentage from 0 to 100 |
| Where it stands | Waiting, accepted or rejected; who decided and when; and what accepting it made: the entry or the vendor |

A payment is a task with an amount (D-004): a suggestion of the kind "payment" becomes such a task.

## 3. Its life

```
waiting ──accept──▶ accepted   (an entry or a vendor now exists)
        ──reject──▶ rejected   (nothing was made)
```

- **Either owner decides, and a decision stands.** Asking for the same decision again changes nothing and is
  answered with the suggestion as it is: a request that is sent twice because its answer was lost does no harm, and
  accepting twice makes one entry. Asking for the *other* decision is refused with the problem `already-decided`,
  and the client shows what was decided and by whom. Two people on two devices can therefore never accept and
  reject the same suggestion, and nobody's decision is overturned by a phone that was offline.
- **Accepting makes the thing**: a task, a task with an amount, a step of the plan, or a vendor (as "considering").
  It is made with the values of the proposal, or with those values as the person changed them before accepting.
  What is made is an ordinary entry or vendor from then on; changing or removing it later does not touch the
  suggestion.
- **Accepting is checked like adding by hand.** The same rules as for a new entry or vendor apply, with the same
  messages next to the same fields. A proposal that points to a vendor or category that no longer exists is
  refused with a message that says so; nothing is dropped silently.
- **Rejecting makes nothing.** The suggestion stays in the database as rejected, so that the same message is not
  proposed again.
- There is no way back from a decision in this step. A tap on the wrong button is caught on the device, before it
  is sent (section 6).

## 4. What is kept, and who sees it

- A suggestion holds parts of someone's mail: a sender's name and address, a subject, a quotation. This is
  personal data and mail content. It is kept as S01 section 6 allows and no further: no full text, no attachment,
  no other recipients.
- Only the two owners can read or decide suggestions, like everything else under `/api`.
- **Nothing of it is logged**: not the sender, the subject, the passage or the proposed title. The records that
  hold them hide them from `toString()`, and the module has a test at TRACE like the vendors' and the guests'.
- **Nothing of it is written into the tracker, a commit or a test fixture.** Tests use made-up mail from made-up
  senders at `.example` addresses.

## 5. What comes out of a mail is not trusted

A suggestion's words were written by whoever sent the mail, and passed through a language model on the way.

- A suggestion **does nothing by itself**. It changes the planner only when an owner accepts it, and then only by
  making one entry or one vendor with the values shown.
- Clients show its texts as **plain text**. Nothing in a title, a passage or a sender's name is taken for a link,
  a markup or an instruction.
- The **link to Gmail is made by the server** from what it knows about the message, never taken from the mail's
  text, and the server refuses to keep one that is not an `https` address at `mail.google.com`. Clients open it as
  any other web address. It says which mailbox the message is in, so that it opens in the right Google account.
  That part of the link is its owner's: the other owner is answered the link without it (S03: the partner's
  address is never shown), and could not open a message in the other's mailbox in any case.
- The card always shows **who the mail came from and the passage**, so that the person can judge the proposal —
  above all one that asks for money.

## 6. The API

All of it needs a signed-in owner. Every response has every field; what is missing is `null`.

| Call | What it does |
|---|---|
| `GET /api/suggestions` | The suggestions that wait, the newest message first. `?state=ACCEPTED`, `REJECTED` or `ALL` for the others |
| `POST /api/suggestions/{id}/accept` | Accepts it. Without a body: as proposed. With a body: the proposal's values as the person wants them. Answers with the suggestion, which now names what was made |
| `POST /api/suggestions/{id}/reject` | Rejects it. Answers with the suggestion |

Problems, by their `type` as always: `not-found` for a suggestion that does not exist; the new `already-decided`
(409) when the other decision was made first; the validation problem with its messages per field when what would
be made is not valid.

There is **no call that adds a suggestion**. The import hands them to the suggestions module inside the server,
through an interface of that module; the tests use the same interface. The module uses `planning` and `vendors` to
make what is accepted, through interfaces of theirs, and nothing uses it back.

## 7. The phone

The scope gives the phone accepting and rejecting (S01 section 4); changing a proposal before accepting it is the
web's.

- The suggestions that wait are **loaded and kept with the rest of the planner**, so the inbox is readable without
  a connection and says from when it is. Home says how many wait and leads to the inbox; More has a row for it.
- A card says what is proposed in the planner's own words (kind, title, day, amount, vendor), then where it comes
  from (sender, subject, date), the passage as a quotation, and how sure the import is. "Open the e-mail" opens the
  message in Gmail.
- **Accept** and **Reject** take effect on the phone at once and are sent when the server can be reached, like a
  tick: the decision waits on the phone, survives the app being closed, and is sent by the app or by the
  background worker. Until it is sent it can be taken back on the phone — that is the way out of a wrong tap.
- If the other owner decided first, the phone says what they decided and lets go of its own decision. If the
  server does not take an acceptance (a vendor that is gone, an amount that is not valid), the phone says so and
  points to the web, where the proposal can be changed.
- Signing out removes the suggestions and the decisions that wait from the phone; nothing of them is in its log.

## 8. Open, and where it is tracked

| What | Where |
|---|---|
| Gmail connection and sync | WP-US-20 |
| Triage and extraction, which produce the suggestions | WP-US-21 |
| The inbox on the web, with changing a proposal before accepting | WP-US-22, a task of its own |
| Adding by itself at the threshold, with undo; whether a payment may ever be added without a person; a later message updating an earlier entry | WP-US-22, a task of its own, after WP-US-21 |
| Taking a decision back after it was sent | Not planned; to be decided if it is missed |
