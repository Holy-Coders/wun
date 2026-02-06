# Style Guide

This project follows the spirit of 37signals' Fizzy style guide: small concepts, explicit boundaries, boring technology, and code that explains itself.

Clarity beats cleverness.
Deletion beats abstraction.
Constraints beat options.

## Product philosophy

- People work on one thing at a time.
- The system should reflect reality, not aspiration.
- Focus is protected by design, not discipline.
- Insight should be a byproduct of work, not an extra task.

If a feature weakens these ideas, it does not belong.

## Naming

Use simple, literal language.

Preferred terms:

- Card
- Active
- On Deck
- Tag
- Rule
- Event

Avoid:

- ticket
- issue
- status
- workflow
- sprint
- board

This is not Jira. Do not borrow its vocabulary.

## Tags over structure

- There are no hierarchies.
- There are no dependencies.
- There are no statuses beyond Active / Not Active.

If you think you need structure, try a tag first.

## Rules over callbacks

All behavior belongs in rules.

Avoid:

- model callbacks
- controller conditionals encoding business logic
- implicit side effects

Rules make behavior:

- explicit
- testable
- explainable

If you can't explain behavior as "event → rule → outcome", it's wrong.

## Multi-tenancy

- Tenancy is explicit.
- Tenant is always visible in the URL.
- Every record belongs to an account.
- Cross-tenant access is a bug, not an edge case.

## UI principles

- One primary action at a time.
- No dashboards shouting for attention.
- Fewer controls is a feature.
- Calm beats powerful.

If the UI encourages multitasking, it is broken.

## Data principles

- Prefer append-only logs.
- Prefer derived state over stored state.
- Prefer deleting data to preserving the wrong abstraction.

This is pre-MVP. Do not design for permanence.

## What we don't do

- No dependency graphs
- No workflow builders
- No status matrices
- No backward-compatibility hacks
- No clever abstractions "for later"

Solve today's problem clearly.

## A good change

A change is good if:

- the system is simpler after it
- the rules are easier to explain
- the UI is calmer
- fewer concepts are required to understand the app
