# Preferences

Resolved during PM init on 2026-11-20 (setup mode: **defaults**). The user can change any of these at any time; the latest setting wins.

## Setup
- setup_mode: defaults
- setup_spec: `.claude/ProjectManager/SETUP_SPEC.md` (copy of the original full CLAUDE.md spec)
- manifest: https://raw.githubusercontent.com/KROIA/ClaudeCodeTemplate/main/manifest.json (fetched 2026-11-20)

## File layout
- Repo root: `mc_1.21.1_AI_NPC/` (git). PM files live in `mc_1.21.1_AI_NPC/.claude/`.
- `CLAUDE.md` (lean runtime briefing) lives one level up in `AI_NPC/`, outside the repo, so it loads whether a session starts in `AI_NPC/` or in the repo.
- Top-level `.claude/`: PROJECT_STATUS.md, ISSUES.md, TASKS.md, settings.json, CHANGELOG.md, changelogs/
- `.claude/ProjectManager/`: PROJECT_SUMMARY.md, CODING_STYLE.md, FINISHED_TASKS.md, DECISIONS.md, GLOSSARY.md, PREFERENCES.md, SETUP_SPEC.md, agents/, templates/

## Modules
- changelog: manifest variant `default` → `templates/changelog.md`
- version_control: manifest variant `default` → `templates/version_control.md`

## Changelog
- enabled: true
- location: `.claude/changelogs/` (per-version files), index `.claude/CHANGELOG.md`

## Workflow gates
- manual_review_gate: off
- unit_testing: skipped (enable later on request)
- sprint_visualization: off

## Version control
- engaged: true (git)
- commit_permission: none — user must explicitly grant (the initial commit was made on explicit user command)
- push_permission: none — user must explicitly grant
- branch_policy: work directly on the current branch (`mc_1.21.1`). Note: the VC template assumes `main`; this project uses per-MC-version branches like its sibling mods.
- artifact_policy: commit `.claude/` (CLAUDE.md is outside the repo, see layout)
- remote: `origin` = https://github.com/KROIA/AI_NPC.git (GitHub repo not yet created by the user; nothing pushed)
- commit_attribution: **never** add `Co-Authored-By` / Claude attribution lines (user rule, 2026-11-20)

## Version source
- `gradle.properties` → `mod_version` (currently `1.21.1-0.1.0`)

## User
- name: (not set)

## workflow_overrides
(none)

## conventions
- Shared rules in `E:/Dokumente/Minecraft/mods/CLAUDE.md` apply (no killing shared Gradle daemons/Java processes, AFK via Telegram, crash-debugging protocol).

## notes
(none)
