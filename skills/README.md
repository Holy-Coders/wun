# Wun skills

One-file how-to playbooks for common Wun tasks. Each skill is a
markdown document an LLM agent can ingest end-to-end and follow
mechanically.

The shape:

```markdown
# <Task name>

## When to use this
…concrete signals that this is the skill the user actually needs.

## Inputs you need
…what the agent should ask the user for *before* starting.

## Steps
1. …
2. …
3. …

## Verify
…concrete commands or behaviour to confirm the change worked.
```

Skills are deliberately narrow — one task per file. If a task takes
longer than ~10 steps, it should be split.

## Available skills

| skill                              | when to use                                       |
|------------------------------------|---------------------------------------------------|
| `add-screen-with-form.md`          | adding a new screen that takes user input         |
| `wire-an-intent.md`                | shipping a new intent end-to-end                  |
| `add-component-pack.md`            | building a reusable `:myapp/*` library            |
| `ship-a-breaking-change.md`        | landing a change that needs a migration script    |
