# S08 — The inspirations canvas

**Status:** written on 2026-11-24 at the owner's request. **Builds on:** S01 section 4 (inspirations), D-001 (what
we draw ourselves), D-015. **Story:** WP-US-26.

## 1. What this covers

The saved ideas — a post shared from Instagram, a link, pictures of one's own — are shown as a grid, narrowed
down by board and tag. Boards and tags ask for a name at the moment something is saved, and most ideas have no
name for where they belong yet. The owner asked for **a canvas**: one large surface on which every saved thing
lies as a card that can be **moved freely**, so that what belongs together lies together, in loose groups that
need no name.

This spec adds the canvas as **a second view of the inspirations page on the web**. The grid, the boards, the
tags and an idea's own page stay as they are; nothing is taken away.

Not covered, and said so that it is a decision: the canvas on the phone (the phone keeps its board; what is
shared there arrives on the canvas, section 2); words or frames of one's own on the canvas; cards of other
sizes; several canvases; two people seeing each other move things while they do.

## 2. A place for every idea

- There is **one canvas for the wedding**, the same for both owners.
- Every inspiration has **a place** on it: two whole numbers, `x` and `y`, the upper left corner of its card in
  the canvas's own units — one unit is one pixel when the canvas is shown at its natural size. `x` grows to the
  right, `y` downwards. A place lies between −100,000 and 100,000 either way.
- A card is 200 units wide; how tall it is follows from its picture and its name, and is the page's business.
  Where the server has to keep cards apart it reckons with **a slot of 220 by 260 units**.
- **A place changes only when an owner moves the card.** Changing an idea's name, board, tags or pictures does
  not move it; removing an idea frees its place.
- **What is saved newly arrives at the end of the lowest row**: one slot to the right of the card that lies
  lowest (of several equally low, the right-most) — unless that would be more than six slots from the left edge
  of everything on the canvas, or would lie on another card's slot; then it begins a new row below everything,
  at the left edge. The first idea of all lies at 0, 0. So new things collect in one place, in the order they
  came, and never cover anything — whether they were added on the web or shared on the phone.
- **The ideas that exist** when the canvas arrives are laid out by the same rule, in the order they were saved.
- **The card that was moved last lies on top** of the ones it overlaps.

## 3. Moving

- A card is moved by **dragging** it. It follows the pointer at once; when it is let go its new place is saved —
  one request for one move. If saving fails, the card goes back to where it was and the page says so.
- **The keyboard does the same**: the card that has the keyboard moves with the arrow keys, 20 units a press and
  100 with Shift, and its place is saved when the keys rest. Tab goes from card to card in reading order, by
  rows from the top.
- **The last move wins.** A place has no version: if both owners move the same card, it lies where the later one
  put it. Moving a card is not a change of the idea — its version and "changed at" stay — so somebody who has
  the idea's page open at that moment can still save it.
- The other owner sees a move when their page reads the inspirations again; nothing is pushed.

## 4. The view

- The inspirations page has two views, **Grid** and **Canvas**, as the plan has its views. The canvas has an
  address of its own.
- The surface fills the page below the page's head. It is **moved** by dragging where no card is, by the wheel
  or two fingers, and with the arrow keys when the surface itself has the keyboard; it is **made larger and
  smaller** with buttons (larger, smaller, natural size, show everything), with the wheel while Ctrl or ⌘ is
  held, and with two fingers — between a quarter and twice its natural size. It opens showing everything.
- **A card** shows what the grid's card shows of an idea, small: its picture — or the words that stand for one
  where there is none to show — its name, and where it comes from. A click or a tap that moves nothing, or
  Enter, opens the idea, which returns to the canvas.
- **The filters by board and by tag** work as on the grid: an idea that does not match is not shown, and keeps
  its place.
- Adding, changing and removing an idea stay where they are: the page's button and the idea's own page.
- **What a screen reader gets**: the surface is named and says how it is used; every card is one thing to go to,
  named by the idea's name and where it comes from; a move is said when it is done ("moved"). The grid remains
  the view in which the ideas are a plain list.
- On a narrow screen and with touch the same holds: a finger on a card moves the card, a finger on the surface
  moves the surface, two fingers make it larger or smaller.
- Where a card lies on the screen — from its place, how far the surface was moved and how large it is shown —
  **is arithmetic**, as on the chart of the plan (D-001): nothing is measured on the page, and a test can drag
  by a known distance.

## 5. What is kept, and the API

- With an inspiration: its place and the moment it was put there. Nothing else is new.
- An inspiration's answer gains `x`, `y` and `placedAt`, always present. A new inspiration cannot say where it
  wants to lie: the server places it (section 2).
- `PUT /api/inspirations/{id}/place` with `{ "x", "y" }` moves it, for either owner of the wedding, and answers
  the inspiration. Whole numbers within the limits; anything else is refused with words beside the field. An
  inspiration that is gone is "not found".
- A phone app that is already installed keeps working: it ignores what is new.

## 6. How it is checked

- The backend's tests: the migration's layout of what exists; where a new one arrives — beside the lowest, a
  new row after six, a new row where the slot is taken, after a card was moved far away; moving, by either
  owner; the limits; the version untouched; the order of drawing; an inspiration shared from the phone arrives
  like one from the web.
- The web's tests: the arithmetic without a screen; the page with the stand-in server — the cards at their
  places, a drag by a known distance and what is sent, the keys, a failed save going back, the filters, opening
  an idea and the way back, the surface moved and scaled.
- It was looked at as rendered pictures, wide and narrow, light and dark.
- Not checkable here: a real touch screen, and what forty real pictures feel like to move.
