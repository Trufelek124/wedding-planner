# S03 — E-mail import: connecting Gmail and keeping up with it

**Status:** written on 2026-11-19 at the owner's request, as the second step of epic 7. The server that will run
the planner is not set up yet: everything here is built and tested against stand-ins for Google, and section 9
lists what the owner does once there is a server. **Builds on:** S01 section 6, S02, D-006. **Story:** WP-US-20.

## 1. What this covers

Each owner connects their Gmail once; from then on the server knows which messages are in that inbox and notices
new ones within minutes. This spec says how connecting and disconnecting work, what the server keeps, how it keeps
up, and what an owner sees of it.

It does **not** cover reading the messages. Deciding which message concerns the wedding and proposing entries
(WP-US-21) come next. Until they exist the sync only **notes which messages are there** — an identifier per
message and nothing of its content — and the suggestions inbox (S02) stays empty.

## 2. What an owner sees

A page **E-mail import**, reached from the signed-in block of the top bar and from the suggestions inbox.

| The server says | The page shows |
|---|---|
| The import is not set up on this server | That it is not set up, and nothing to press |
| You are not connected | What connecting means (below), and **Connect Gmail** |
| You are connected | The address that is connected, since when, when the server last looked, how many messages it has noted, and **Disconnect** |
| Google no longer lets the server in (access was withdrawn, or expired) | That it has to be connected again, and **Connect Gmail** |
| Your partner | Whether they are connected and when the server last looked — not their address |

**Before connecting the page says, in plain words:** the planner asks Google for permission to *read* your mail and
nothing else — it cannot send, change or delete anything; it looks at the last three months and then at new
mail every few minutes; of a message that does not concern the wedding it keeps only that it was looked at; and,
once the import is complete, the sender, the subject and the beginning of every message will be sent to the AI
service that decides whether it concerns the wedding (S01 section 6, "What leaves the server"). Google will show
a warning that the app is not verified (D-006); the page says so beforehand, so that nobody is surprised.

**Disconnect** asks first and says what it does (section 6).

## 3. Connecting

1. The signed-in owner presses Connect. The server makes a **one-time ticket** — random, valid for ten minutes,
   bound to this owner and, through a cookie that scripts cannot read, to this browser — and answers with Google's
   address to go to. The browser goes there.
2. Google asks the owner to choose an account and to allow **read-only access to Gmail**
   (`gmail.readonly`) — that scope and no other — with access that lasts while the owner is away.
3. Google sends the browser back to the server's **return address**, which is the one path of this feature that
   answers without a sign-in: a browser coming back from Google carries no access token. It accepts nothing but
   a ticket it issued itself, once, in time, from the browser it was issued to; anything else ends on the page
   with "that did not work", and nothing is stored. With a good ticket the server exchanges Google's code for
   its tokens, asks Google which address this is, stores the connection, and sends the browser to the page.
4. One connection per owner. Connecting again replaces it. The mailbox may be another Google account than the
   one the owner signs in with — the page shows which address is connected.

Nothing of this passes through the web app's own code: no token and no code is ever in a page's address when the
page is drawn, in a script's reach, or in the log.

## 4. What the server keeps

| What | Why | How |
|---|---|---|
| Google's long-lived token for the mailbox | To read without the owner being there | **Encrypted** with a key that is not in the database (section 8). Never in a response, never in the log |
| The connected address | To show the owner which mailbox it is | Shown to its owner only |
| Where the sync stands | To notice what is new | Google's own marker for the mailbox |
| For every message noted: Google's identifier of the message and of its thread, when it was noted, and whether it has been read by the import yet | So that no message is handled twice, also after a restart | **Nothing of the message itself**: no sender, no subject, no text |

What is kept *about* a message once it is read — the verdict, and for wedding messages the sender, the subject, the
date and the passage — is S02's and WP-US-21's.

## 5. Keeping up

- **Every five minutes** the server looks at each connected mailbox, one after the other; a look that is still
  running is not started a second time.
- **The first look** notes the received messages of the last **90 days**. Not what the owner sent, not drafts,
  not spam, not the bin, not chats.
- **Every later look** asks Google what was added since the last one. If Google no longer knows that far back
  (its marker has grown too old), the server looks through the days since its last successful look again; a
  message it has already noted is not noted twice.
- **A look counts only when it is complete.** Where the sync stands moves forward only after everything the look
  found is stored, so a look that was cut off — by a restart, by Google being slow — is simply done again.
- **When Google refuses**: too many requests or a failure on Google's side — the mailbox is left for the next
  look, with growing pauses. The permission is gone (withdrawn in the Google account, or expired) — the
  connection is marked as needing to be connected again, the sync leaves it alone, and the page says so.
- The server goes out to **Google's own addresses and nowhere else**, with limits on how long it waits.
- **The log** says that a look happened and how many messages it noted — never an address, a token, or anything
  of a message.

## 6. Disconnecting

Disconnecting tells Google to withdraw the permission (if Google cannot be reached, the rest still happens),
removes the token, where the sync stood and the list of noted messages, and stops the sync for that mailbox.
Suggestions that already exist stay: they are part of the planner (S02). Connecting again later starts with the
last 90 days; a proposal that was already made is not made twice (S02's source key).

Removing an owner's access to the planner (taking the address off the allow-list) does not by itself withdraw
Google's permission: the owner disconnects first, or withdraws it in their Google account.

## 7. Other mail later

Gmail is behind one interface — "what is new in this mailbox since …" and "give me this message" — so that
Outlook or plain IMAP can be added as a second source without touching what reads and judges messages
(S01 section 6). IMAP with an app password is also the fallback should Google's route not hold (D-006).

## 8. The API and the settings

All calls need a signed-in owner except the return address.

| Call | What it does |
|---|---|
| `GET /api/mail` | Whether the import is set up on this server; your connection (state, address, since when, last look, messages noted); your partner's state and last look |
| `POST /api/mail/gmail/connection` | Starts connecting: answers with Google's address to go to |
| `GET /api/mail/gmail/return` | Where Google sends the browser back. **Answers without a sign-in** (section 3); always ends in a redirect to the page |
| `DELETE /api/mail/gmail/connection` | Disconnects your mailbox |

The import is **off unless the server is given everything it needs**: the Google client's id and secret, the
planner's own public address (the return address is made from it), and the key that encrypts the tokens. With
any of them missing the server starts as before, the page says the import is not set up, and nothing is asked of
Google. `docs/deployment.md` names the settings.

## 9. What the owner does when there is a server

Tracked as a task of the owner's in WP-US-20; `docs/deployment.md` has the steps.

1. In the Google project that already holds the sign-in client: switch the Gmail API on, add the planner's
   return address to the web client, add the read-only Gmail scope to the consent screen, and set the consent
   screen to **In production** (D-006 says why "Testing" does not work).
2. Give the server the client's secret and a new random key for the tokens, in its environment file.
3. Connect both mailboxes and check after more than a week that both are still connected. If Google's route
   does not hold, say so: the fallback is IMAP (D-006).

## 10. Open, and where it is tracked

| What | Where |
|---|---|
| Reading and judging the messages, and what leaves the server for that | WP-US-21 |
| Connecting from the phone | Not planned: connecting is done once, on the web |
| A second mail provider | Not in the first version (S01 section 13) |
