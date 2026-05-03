---
title: Working with agents
description: How Wun integrates with Claude, Cursor, Cline, and other LLM coding agents.
---

> **TL;DR.** Wun ships three first-class surfaces for AI coding
> agents: a repo-root `CLAUDE.md` / `AGENTS.md` that orients them
> on the framework's three macros, a `skills/` directory of
> narrow playbooks, and an MCP server exposing the dev CLI to
> tools like Claude Desktop and Cursor.

Wun treats LLM coding agents as a first-class user. The repo ships
three surfaces that orient agents the moment they land:

1. **`CLAUDE.md` / `AGENTS.md`** at repo root — project orientation.
2. **`skills/`** — narrow how-to playbooks for canonical Wun tasks.
3. **`mcp/`** — a Model Context Protocol server exposing Wun
   developer tools.

Together they let an agent inspect a Wun project and run scaffolds
without you copy-pasting CLI commands.

## CLAUDE.md / AGENTS.md

The repo-root `CLAUDE.md` covers:

- What Wun is + the three macros.
- The mental model: server is source of truth, capability negotiation,
  optimistic morphs.
- The "use `wun add` instead of hand-writing the cross-platform
  plumbing" rule.
- Common gotchas (hyphenated names breaking Kotlin packages, wire
  format changes affecting all four platforms).
- How to verify a change didn't break any platform.

`AGENTS.md` is a one-line pointer for runtimes that look for that
filename instead (Cline, OpenAI Codex). Same content.

Cursor / Continue / Aider users: drop `CLAUDE.md` (or
`AGENTS.md`) into the project root of any wun-app you scaffold;
the agent picks it up automatically.

## Skills

`skills/` holds one-file how-to playbooks an agent can ingest
end-to-end and follow mechanically. Each skill is narrow — one
task per file — and verifiable. Current list:

| skill                              | when to use                                       |
|------------------------------------|---------------------------------------------------|
| `add-screen-with-form.md`          | new screen with user input                        |
| `wire-an-intent.md`                | intent end-to-end                                 |
| `add-component-pack.md`            | reusable `:myapp/*` library                       |
| `ship-a-breaking-change.md`        | landing a change that needs a migration script    |

Browse them in the [skills directory on GitHub](https://github.com/Holy-Coders/wun/tree/master/skills).

## MCP server

The MCP server (see [MCP server](/ai/mcp/)) exposes five tools to
LLM clients:

- `wun_status` — coverage matrix
- `wun_doctor` — env check
- `wun_add_component` / `wun_add_screen` / `wun_add_intent` — scaffolding

Plus read-only resources for `CLAUDE.md`, the architecture docs,
and every file under `skills/`. An agent can pull those into context
on demand.

## Patterns we like

- **Ask the agent to use `wun add` first.** Most cross-platform
  plumbing is generator-shaped. If the agent volunteers to write a
  component renderer in 5 places by hand, redirect.

- **Show it `wun status` early in long sessions.** It's a one-shot
  view of what's implemented vs. fallback, which keeps the agent
  oriented when it's deciding whether something needs platform work.

- **Lean on the architecture docs for hard questions.** The
  `docs/architecture/` files (also in this site under
  [Architecture](/architecture/head-and-cache/)) are heavily
  commented; the agent does well when pointed at them rather than
  re-deriving the design.

- **Use migrations for breaking changes.** Don't let the agent
  ad-hoc rewrite caller code; ship a codemod under `migrations/`
  and let downstream consumers run it deliberately.
