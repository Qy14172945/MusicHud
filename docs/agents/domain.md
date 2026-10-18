# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

**Layout: single-context.** One repository, one shared vocabulary. There is a single
`GLOSSARY.md` at the repo root and a single `docs/adr/` directory; the `common/`,
`fabric/` and `forge/` modules are loader adapters over the same domain, not separate
contexts with their own language, so they do **not** get per-module glossaries.

## Before exploring, read these

- **`GLOSSARY.md`** at the repo root.
- **`docs/adr/`**: read the ADRs that touch the area you're about to work in.

If either doesn't exist yet, **proceed silently**. Don't flag its absence; don't
suggest creating it upfront. The `/domain-modeling` skill (reached via
`/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily, the first
time a term or a decision actually gets resolved.

## File structure

Single-context (this repo):

```
/
├── GLOSSARY.md
├── docs/
│   ├── adr/
│   │   ├── 0001-....md
│   │   └── 0002-....md
│   └── agents/            ← this config (issue tracker, labels, domain docs)
├── common/                ← shared domain + loaders-agnostic code
├── fabric/                ← Fabric adapter
└── forge/                 ← Forge 1.20.1 adapter
```

This repo is deliberately **not** multi-context: there is no `GLOSSARY-MAP.md` and no
per-module `GLOSSARY.md`. If that ever changes, that is the moment to introduce
multi-context — until then, keep one glossary.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a
hypothesis, a test name), use the term as defined in `GLOSSARY.md`. Don't drift to
synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're
inventing language the project doesn't use (reconsider) or there's a real gap (note it
for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently
overriding:

> _Contradicts ADR-0007 (event-sourced orders), but worth reopening because…_
