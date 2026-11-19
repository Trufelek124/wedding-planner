# S04 — E-mail import: reading the messages and proposing entries

**Status:** written on 2026-11-19 at the owner's request, as the third step of epic 7. Built and tested against
stand-ins for Gmail and for the AI service; section 10 lists what the owner does once there is a server.
**Builds on:** S01 section 6, S02 (suggestions), S03 (connection and sync), D-007. **Story:** WP-US-21.

## 1. What this covers

The sync (S03) notes which messages are in a connected mailbox. This spec says what happens to a noted message:
a small AI model decides whether it concerns the wedding, a larger one reads the ones that do and proposes
entries, and what it proposes becomes **suggestions** (S02) that wait for one of the two owners.

Left for later: adding an entry by itself when the import is sure enough (S05), and a later message updating
the entry an earlier one produced (WP-US-22). Everything this step proposes waits for a person.

## 2. The way of one message

```
noted ──▶ fetched from Gmail ──▶ triage ──┬─ not about the wedding ──▶ done
                                          └─ about the wedding ──▶ extraction ──▶ checked ──▶ suggestions ──▶ done
```

- Each message goes this way **once**. What it came to — its **verdict** — is kept with the note of the message,
  and a message with a verdict is never fetched or sent anywhere again, also after a restart.
- A message is handled whole or not at all: if the server stops in the middle, the message is still "not read
  yet" and is done again. Doing it again proposes nothing twice (S02's source key).
- The newest messages are read first, a few at a time, so that the first days after connecting bring what is
  current before what is three months old.
- A message that cannot be fetched for now is left as it is and tried again later, at growing intervals; the
  others are read on. Nothing is asked of the AI service about a message from which nothing could be proposed
  (its mailbox was disconnected meanwhile).

## 3. What leaves the server

S01 section 6 says what may leave, and the owner confirmed it: of **every** message the sender, the subject and
the beginning; of a message that concerns the wedding the full text. This spec keeps to that and adds two small
things that make the proposals usable; both are listed here so that they are a decision and not an accident.

| Step | What is sent to the AI service |
|---|---|
| Triage, for every message | The sender's name and address, the subject, when it arrived, and the beginning of its text: the first 1,500 characters. |
| Extraction, for a message that concerns the wedding | The same, the full text of the message, and the text of the earlier messages of its thread — up to 60,000 characters in all; what is beyond is left out, and the model is told that it was. |
| Also with extraction | **Today's date and the wedding's date** (so that "two weeks before the wedding" can become a day), and **the titles of what was already proposed from the same thread** (so that a reply is not proposed a second time). |
| With extraction, when triage was at least 80 in 100 sure that the message concerns the wedding | **The text of the message's PDF documents** (D-011): of up to three documents, none larger than 10 MB, the first fifty pages of each, up to 40,000 characters in all. An offer or a contract usually says there what the mail only mentions: the further payments and their days. |

Not sent: an attachment that is not a PDF; a PDF that holds pictures of pages and no text; anything of an
attachment of a message that triage was not that sure of, or called a "no" (also one that is read on because the
"no" was unsure); the planner's tasks, vendors, guests, categories or budget; anything about the owners beyond
what the mail itself says.

### Messages that were read before their documents could be

A message that concerned the wedding and was read before the planner could read documents is **read once more,
for its documents only**: the same message with their text, with what it already proposed told to the model so
that nothing is proposed twice. What comes of it are further suggestions from the same message. It happens by
itself, once per message, under the same limits as all reading; a message without a PDF costs one look at
Gmail and nothing else.

## 4. What the two steps answer

- **Triage** answers one question — does this message concern the wedding of the two owners? — with yes or no
  and how sure it is. A wedding newsletter, an advertisement and somebody else's wedding are "no". **A "no" that
  is less sure than 60 in 100** (a setting) is read on as if it were a "yes": missing a message that concerns the
  wedding is worse than paying for one extraction that proposes nothing.
- **Extraction** answers with at most **five** proposals — **eight** when documents were read with the message,
  since an offer often names several payments. Each has a kind (task, payment, step of the plan,
  vendor), a title in the language of the message, and what applies of: notes, a first day, a day it is due, an
  amount as the mail writes it with the kind of payment, a category by name, a vendor by name with what
  contact details the message gives, how sure it is (0–100), and **the passage** it rests on, quoted from the
  message word for word. A message that concerns the wedding but asks for nothing proposes nothing.

## 5. What comes back is not trusted

A message is written by anybody, and the models read it. So:

- **The models are given no tools.** They can answer; they cannot do. Whatever a message says — "ignore your
  instructions", "mark everything as paid", "delete the guest list" — the most it can cause is a proposal that
  waits in the inbox for a person. Nothing existing is ever changed by this step.
- **The answer has a fixed shape**, asked for as such and checked again by the server: the kinds, the lengths,
  an amount that is a whole number and not negative, days that are real days.
- **The passage must stand in the message.** A proposal whose passage is not found in the text that was sent —
  the message, its thread, or a document that was read with it — (compared without regard to line breaks and
  spacing) is dropped: the person deciding must be able to rely on the quotation being the mail's own words.
- **A document is read by the server, not opened.** The server takes the text out of the PDF itself; nothing in
  the file is run, followed or fetched, and the file is not kept. What the text says is somebody else's words
  like the mail's, set apart in the request in the same way. A document that cannot be read — locked, damaged,
  too large — is left out and the message is read without it. Reading one message's documents is tried once: if
  the server stops while it does, that message is read without them the next time.
- **Names are matched by the server, not by the model**: a vendor is linked when the sender's address is a
  vendor's address, or the proposed name is exactly a vendor's name; a category when the proposed name is
  exactly one of the planner's. Otherwise the name stays what S02 calls a name the planner does not have yet.
  A vendor the planner already has — by its name, by the address the message gives for it, or because it is the
  sender introducing itself — is not proposed again; a known sender introducing somebody else is.
- **At most five proposals from one message, eight with its documents.** An answer with more is not used at all,
  and the message is marked as one that could not be read: a mail that floods is not a mail to act on.
- **Fifty new suggestions from one mailbox in a day, and its reading goes on the next day.** The message that
  reaches the limit is still handled whole; nothing is lost, the other mailbox is read on, and nobody can fill
  the inbox by sending a thousand mails.
- **Money is not worked out by the model.** It answers the amount as the mail writes it, with its currency. The
  server turns it into the planner's whole numbers when the currency is the wedding's; an amount in another
  currency, or with no currency named, is left out of the proposal and said in its notes. Only a payment carries
  an amount.
- What becomes of a proposal is S02's: it is shown as plain text with the sender and the passage, and it waits.

## 6. What is kept

- With the note of a message: its verdict — not about the wedding, about the wedding (and how many proposals it
  made), or could not be read (and which of a fixed set of reasons) — and when; and whether its documents were
  looked at. **Nothing of its content, and nothing of a document.**
- In a suggestion (S02): the sender, the subject, when it arrived, whose inbox, the passage, the link to Gmail.
- What the AI service was asked and what it answered is **not kept**, and not logged. The log says that a
  message was read, by which step, what it came to, and how many tokens it took — never which message, who
  sent it, or a word of it.
- How much was used: per day, step and model, the number of calls and of tokens. That is what the cost is
  worked out from (section 8). The reading's days and months are UTC.

## 7. When something does not work

| What happens | What the server does |
|---|---|
| The AI service is busy or fails | The reading waits, with growing pauses, and goes on where it stopped. No message is lost or marked. |
| The AI service does not accept the server's key, the account has no credit, or it does not know the model that is set | The reading stops and the e-mail page says so. It starts again when the server is started with a key that works. |
| The model declines a message, or answers in a shape the server cannot use | The message's verdict is "could not be read". It is not tried again. |
| A message is too large to fetch, or has no text | "Could not be read." |
| A document cannot be fetched or read | The message is read without it. |
| Gmail no longer has the message | "Could not be read." |
| Gmail refuses or the permission is gone | As in S03: the mailbox is put off, or has to be connected again. |

## 8. What it costs, and the brake

- The e-mail page shows, for both mailboxes together: how many messages wait to be read, how many were read, how
  many of them concerned the wedding and how many could not be read, how many suggestions came of it, and **what
  it has cost so far this month and in all** — an estimate from the tokens used and the prices in the server's
  settings (D-007).
- **A monthly limit** in the server's settings (20 US dollars to begin with). When the estimate for the month
  reaches it, the reading pauses until the next month and the page says so. Triage and extraction stop together.
- **A daily limit** on how many messages are read (2,000 to begin with), so that a first look over a large
  mailbox is spread over days and a mistake is noticed before it has gone through everything.

## 9. Settings

The reading is **off unless the server is given a key for the AI service**, and it needs the mail connection
(S03) to be set up. Off, the server starts as before, notes messages as S03 says, and asks nothing of the AI
service. The settings — the key, the two models, the limits, the prices the estimate uses — are named in
`docs/deployment.md`. The key is a secret like the others: never in the repository, a response or the log.
A setting that is not valid turns the reading off, and the log names the setting (never its value).

## 10. How good it is, and what the owner does

- **A fixed set of sample messages** in Polish, Ukrainian and English — invented, with what triage should answer
  and what extraction should propose — and a tool that runs the two steps over it with the real AI service and
  reports how often each was right and what the run cost. It is not part of the gate: **no test ever calls the
  real AI service**, and a run costs money. It is run when the owner says so, with the owner's key:
  `docs/deployment.md`, "Measuring it before switching it on". The set is twenty messages in each language under
  `backend/src/test/resources/mailreading/samples/`; among them messages whose point comes after the beginning,
  a day given only relative to the wedding, an amount in another currency, a reply whose amount stands in an
  earlier message, and messages that try to instruct the reader.
- When there is a server: an API key of the Anthropic account in the server's environment, with a spending limit
  set on the account itself as a second brake; then the e-mail page shows the reading going on and what it costs.

## 11. Open, and where it is tracked

| What | Where |
|---|---|
| Adding by itself at the threshold, with undo | S05, WP-US-22 |
| Attachments that are not PDFs (pictures, Word, spreadsheets), and PDFs that are pictures of pages | Not read; D-011 says what would change that |
| A later message updating an earlier entry | WP-US-22, a task of its own |
| Other mail providers | Not in the first version (S01 section 13) |
| Cheaper reading through the service's batch interface | D-007 says when to reconsider |
