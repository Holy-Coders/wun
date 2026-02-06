# Wun

People actually only ever work on one thing at a time.

Most project management tools are built on a polite fiction: that people can work on many things at once. They can't. They switch. Every switch costs focus, time, and clarity — and most tools quietly ignore that reality.

This system starts from a simpler truth. People work on one thing, then another. So instead of asking workers to manage statuses, timers, and reports, the system quietly observes focus. When you move to a card, work has started. When you move away, it has stopped. No extra steps. No performance theater.

The result is calmer work for individuals and unusually accurate insight for teams. Focus for workers. Truth for managers.

## What this is

This is not traditional project management.

It is a focus engine.

It enforces one core constraint:

**Each person has exactly one Active card at a time.**

Everything else is On Deck.

## How it works

### Active and On Deck

Every card is always in one of two states:

- **Active** — the thing you are working on right now
- **On Deck** — everything else waiting

There are no additional workflow states. No "in progress", no "blocked", no "priority".

Just:

- Active
- Not Active

### Cards

A card is a unit of focus for one person.

A card contains:

- a title
- optional text
- tags
- comments
- an automatically recorded activity history

There are:

- no subtasks
- no hierarchies
- no dependency graphs

The system stays flat and honest by design.

### Tags

All meaning is expressed through tags.

There are two kinds:

**System tags** — Reserved tags that drive behavior.

- `sys:active`
- `sys:done`

Users never apply these directly.

**User tags** — Everything else.

- `backend`
- `bundle:menu-component`
- `role:design`
- `blocked`

User tags describe things. They do nothing unless rules give them meaning.

### There is no "start work" button

The only real action is adding the tag `sys:active` to a card.

That is what it means to start work.

Everything else — switching, logging, stopping — is a consequence of rules reacting to that change.

### Rules, not workflows

All behavior in the system is implemented as rules.

Rules react to simple events:

- a tag was added
- a tag was removed
- a comment was added
- a card was created

The flow is always:

**event → rules → tag changes → more events**

There is no hidden logic.

### Multi-person work

If two people might work at the same time, there must be two cards.

Cards are grouped by shared tags such as `bundle:menu-component`.

An initiative is a collection of cards, not a shared task.

### Insight without reporting

Because work is inferred from focus, the system automatically knows:

- what was worked on
- when work started and stopped
- how long focus lasted
- how often work was interrupted

No timesheets. No status updates. No guesswork.

Managers see reality as it happened.

### Calm by design

The interface shows:

- one large Active card
- a smaller overlapping On Deck queue

You cannot pretend to multitask because the interface will not let you.

## Pre-MVP warning

This project is pre-MVP.

Schemas, rules, and APIs may change freely. Clarity is more important than compatibility.

## In one sentence

One card is Active, everything else is On Deck — and the system watches focus instead of asking for reports.
