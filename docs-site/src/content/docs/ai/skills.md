---
title: Skills
description: Canonical Wun task playbooks for AI agents.
---

> **TL;DR.** Skills are narrow, one-task playbooks for AI agents.
> Each is a Markdown file under `skills/` in the repo. Agents
> using the [MCP server](/ai/mcp/) can also pull them as
> `wun://skills/<name>` resources on demand.

The `skills/` directory holds narrow, single-task how-to playbooks
agents can ingest and follow mechanically. Each skill has the same
shape:

```
# Task name

## When to use this   – concrete signals this is the right skill

## Inputs you need    – what the agent should ask for

## Steps              – a numbered list, ~5–10 items max

## Verify             – commands or behaviour confirming success
```

If a task takes more than ~10 steps, it should be split.

## Available skills

| skill                                                                                                  | when to use                                       |
|--------------------------------------------------------------------------------------------------------|---------------------------------------------------|
| [add-screen-with-form](https://github.com/Holy-Coders/wun/blob/master/skills/add-screen-with-form.md)  | adding a screen that takes user input             |
| [wire-an-intent](https://github.com/Holy-Coders/wun/blob/master/skills/wire-an-intent.md)              | shipping a new intent end-to-end                  |
| [add-component-pack](https://github.com/Holy-Coders/wun/blob/master/skills/add-component-pack.md)      | building a reusable `:myapp/*` library            |
| [ship-a-breaking-change](https://github.com/Holy-Coders/wun/blob/master/skills/ship-a-breaking-change.md) | landing a change that needs a migration script   |

Each skill is also a [MCP resource](/ai/mcp/) under
`wun://skills/<name>`, so agents using the wun-mcp server can pull
them into context on demand.

## Adding a skill

Drop a new `.md` file under `skills/` matching the shape above.
Keep them narrow — one task per file. The
[skills README](https://github.com/Holy-Coders/wun/blob/master/skills/README.md)
in the repo has the full rubric.
