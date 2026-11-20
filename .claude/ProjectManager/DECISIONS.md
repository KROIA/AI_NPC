# Decisions

Architectural and process choices, with the why.

## 2026-11-20 — Repo root is `mc_1.21.1_AI_NPC/`, branch `mc_1.21.1`
Matches the sibling mods (BankSystem, StockMarket, ModUtilities): one folder and branch per Minecraft version, so later ports can follow the same `/propagate` pattern.

## 2026-11-20 — PM files inside the repo, CLAUDE.md outside
`.claude/` sits in the repo so work state is versioned. The lean `CLAUDE.md` stays in `AI_NPC/` so it loads for sessions started in either folder. Cost: CLAUDE.md is not versioned; the full spec is kept in `ProjectManager/SETUP_SPEC.md`.

## 2026-11-20 — Ignore trained models and run data
`training/runs/` (model `.pt` files, ~119 MB) and `fabric/run/` (datasets, worlds) are gitignored: large, regenerable, and would bloat history.

## 2026-11-20 — No tool attribution in commits
User rule. Commit messages carry no `Co-Authored-By` / Claude lines.
