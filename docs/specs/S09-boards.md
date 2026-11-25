# S09 — Boards: one for the first dance, and a new one where it is needed

**Status:** written on 2026-11-25 at the owner's request. **Builds on:** S01 sections 4 and 7 (an inspiration
has a board; sharing from the phone asks for one). **Story:** WP-US-15, WP-US-16.

## 1. What this covers

The owner asked for another category of inspirations — **First dance** — and for a way to add categories of
one's own. The planner calls them **boards**. They could already be added, renamed and removed, but only on the
web, behind a link called "Edit the boards" that says nothing about adding; and on the phone, where most ideas
are shared, a board can only be chosen from the ones that exist.

## 2. The boards a wedding begins with

- The list a wedding is offered once — Dress, Suit, Hair and make-up, Flowers, Decor, Venue, Cake, Invitations —
  gains **First dance**, at its end.
- **A wedding that was already offered its boards gets First dance once**, at the end of its list — unless it
  has a board of that name already, in any spelling of capital and small letters. It is added once: a wedding
  that removes it does not get it back.
- Nothing else changes: the boards are the couple's own, and every one of them can be renamed or removed.

## 3. On the web

- The way from the inspirations to the boards is called by what it does: **Add or change boards**. It leads
  where "Edit the boards" led.

## 4. On the phone: a new board while sharing

- The share screen (S01 section 7) offers, after "No board" and the boards the phone knows, **New board**.
  Choosing it asks for a name, up to 60 characters. The choice is offered also when the phone knows no board
  yet.
- Saving keeps the share as before — the screen closes and the person is back where they shared from — with
  the name of the new board. While the share waits, the phone's board shows it under that name.
- When the share is sent, **the board comes first**: a board of that name that the planner already has —
  compared without regard to capital letters and to spaces around it — is used; otherwise it is made. Then the
  idea is saved on it. Sent twice, or sent again after an answer was lost, it makes no second board.
- If the server does not take the name, **nothing of the share is lost**: the idea is saved on no board.
- Renaming and removing boards stay on the web.

## 5. How it is checked

- The backend's tests: the list a new wedding gets; the migration for a wedding that has its boards, for one
  that already has "first dance" in another spelling, and for one that was never offered its boards.
- The web's tests: the link's name and where it leads.
- The phone's tests: the choice on the share screen, with and without boards; the name's limits; a share with a
  new board sent — the board made, the idea on it; a board of that name already there; without a connection;
  sent twice; the name refused; the database carried over from the older layout.
- Not checkable here: the share sheet of a real phone.
