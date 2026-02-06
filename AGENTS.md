# Agent Guidelines

This file defines how AI agents and humans should work in this repository.

CLAUDE.md references this file as the authoritative source for constraints and expectations.

## Project state

This project is pre-MVP.

- There is no requirement for backwards compatibility.
- Schemas, rule formats, APIs, and internal models may change freely.
- Prefer reshaping or deleting code over adding compatibility layers.
- Optimize for correctness and clarity, not stability.

If something doesn't fit the model, remove it.

## Non-negotiable constraints

- A person can have exactly one Active card at a time.
- Active is represented by the `sys:active` tag.
- Everything else is On Deck.
- All state is expressed via tags.
- All behavior is implemented as rules.
- There are no explicit relationships between cards.

Violating these constraints is a bug.

## The rule engine is the product

All business logic must live in the rule system.

If a behavior can be expressed as a system rule, it must be. Do not hardcode logic in the RuleEngine methods or controllers when a seeded Rule record can do the job. Hardcoded logic is invisible; rules are inspectable, testable, and explainable.

Avoid:

- hidden logic in controllers
- model callbacks
- background magic without traceability
- hardcoded behavior in RuleEngine that should be a Rule record

Behavior must always be explainable as:

**event → rules → tag changes → logs**

## Allowed triggers

- tag added
- tag removed
- comment added
- card created
- session resumed (optional)

Do not add triggers for UI noise (clicks, views, scrolls).

## Intent model

The UI does NOT directly add `sys:active` or other system tags.

The UI calls `RuleEngine` methods (`activate`, `deactivate`, `mark_done`, `add_tag`, `add_comment`).
The RuleEngine translates intent into tag changes, events, and rule execution.

This is a deliberate boundary:

- **Controllers** express user intent (activate, done, add comment)
- **RuleEngine** translates intent into tag changes + events
- **Rules** react to events and produce more tag changes

No controller should ever create a Tagging or Event directly.

## Core system rules

These rules must always hold:

- Only one Active card per user
- Removing Active ends work
- Adding Done ends work
- Activity segments never overlap

If a change threatens these rules, stop.

## Deliberate exceptions

- `Rule.active` boolean — rules are configuration, not domain objects. Their on/off state is not expressed through tags. This is intentional.

## Rule types

- `add_tag` — adds a tag to the card (config: `{ "tag": "name" }`)
- `remove_tag` — removes a tag from the card (config: `{ "tag": "name" }`)
- `keyword_tag` — adds a tag when a comment contains a keyword (config: `{ "keyword": "...", "tag": "..." }`)

All rule types support an optional `when_tag` condition in config. If present, the rule only fires when the triggering tag matches (e.g., `{ "when_tag": "sys:active", "tag": "sys:done" }`).

Rules execute in `position` order, then by `id` for ties. This ordering is tested and must remain deterministic.

## System rules (seeded)

- **Activate removes done** — trigger: `tag_added`, when_tag: `sys:active`, action: remove `sys:done`. This is how activating a done card reopens it.

## Agent behavior guidelines

When working in this repo:

1. **Start from primitives** — Cards, tags, comments, events, rules.
2. **Prefer deletion over abstraction** — If something is unclear, remove it.
3. **Add behavior via rules** — Not callbacks. Not conditionals.
4. **Keep changes small and explainable** — Every change should have a clear "why".
5. **Break things if needed** — This is pre-MVP. Backwards compatibility is not a goal.

## Deliverables

When an agent completes work, include:

- What changed
- Why it changed
- What was removed
- How to test
- Any risks or follow-ups

## Definition of done

Work is done when:

- Constraints still hold
- Behavior is explainable
- The system is simpler
- No new hidden state exists
