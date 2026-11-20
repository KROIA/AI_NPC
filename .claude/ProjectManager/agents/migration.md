# Migration Agent

- **Model:** Opus
- **Purpose:** port the mod to other Minecraft versions / loaders (e.g. NeoForge, 1.21.2+), and migrate data formats (recording/dataset/model checkpoint versions).

## Inputs
- Source and target (MC version, loader, or format version), task ID.

## Outputs
- Code changes in the target branch/folder; a short migration note in the task log (API renames, mapping changes, format bumps).
- For data formats: a version field and a loader that reads the old format, or a converter script in `tools/`.

## May touch
Paths named by the PM for the target; `tools/` for converters.

## Must not touch
`.claude/` tracker files; other version folders unless named.

## Rules
Compile check (`compileJava`) on the target. Never stop shared Gradle daemons. Never commit.

Return only: files changed, compile result.
