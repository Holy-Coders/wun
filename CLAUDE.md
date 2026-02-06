# CLAUDE.md

## Project

Wun — a focus engine for project management. One Active card per person, everything else is On Deck.

## Authoritative docs

- `README.md` — product philosophy and how it works
- `STYLE.md` — engineering and product style (Fizzy-inspired)
- `AGENTS.md` — constraints, rules, and agent behavior guidelines

Read AGENTS.md before making any changes. Its constraints are non-negotiable.

## Stack

- Rails 8.1.2
- Ruby 4.0.0
- SQLite3
- Hotwire (Turbo + Stimulus)
- Propshaft
- Importmap
- bcrypt (has_secure_password)

## Key concepts

- **Card** — a unit of focus for one person
- **Tag** — all meaning is expressed through tags (`sys:active`, `sys:done`, user-defined)
- **Rule** — all behavior is implemented as rules reacting to events
- **Event** — append-only log: tag_added, tag_removed, comment_added, card_created, rule_executed
- **Account** — multi-tenant, always scoped in URL via slug
- **Membership** — join between users and accounts, controls access
- **ActivitySegment** — tracks focused time per card per user (started_at/stopped_at)

## Architecture

- `RuleEngine` (app/services/rule_engine.rb) — ALL business logic lives here
- `ActivityReport` (app/services/activity_report.rb) — aggregation queries for insight
- Controllers call RuleEngine methods, never create Taggings/Events directly
- Intent model: UI → RuleEngine → tag changes → events → rule execution
- Rule types: `add_tag`, `remove_tag`, `keyword_tag`
- Rules execute in `position` order (then by `id` for ties)

## Auth

- Session-based (SessionsController)
- `Current.account` and `Current.user` set per request
- Membership-based: user must be member of account to access
- Login: POST /sign_in (account_slug + email + password)

## Conventions

- Use Rails generators when possible
- Follow Fizzy style: small concepts, explicit boundaries, boring technology
- No model callbacks for business logic — use the rule engine
- No hidden state — everything is tags and events
- No backwards-compatibility hacks — this is pre-MVP
- Tenant scoping on every query
- Prefer deletion over abstraction

## Domain tables

accounts, users, memberships, cards, tags, taggings, comments, events, rules, activity_segments

## Testing

```
bin/rails test
```

## Development

```
bin/rails server
```

Seed data: `demo` account, `demo@wun.app` / `password`
