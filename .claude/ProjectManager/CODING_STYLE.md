# Coding Style (observed in the code)

Match the surrounding code. Keep changes small; this codebase prefers direct code over abstraction.

## Shared (Java and Python)
- Indentation: 4 spaces, no tabs. Long lines are fine (~120-150 chars).
- Comments explain *why* and the domain meaning, in plain English sentences, often with units and
  examples ("0.5 s", "age 7/7 = 1.0", "logs, mineable/axe"). No author tags, no TODO lists.
- `ponytail:` comments mark deliberate shortcuts with their limit and the upgrade path
  (e.g. `ponytail: one NPC at a time, ...`). Keep them accurate; add one when cutting a corner.
- Formats are versioned: `format` in recording/dataset headers, `DATASET_FORMAT` and `ENCODING` in data.py.
  Bump the version when the output changes (ENCODING: whenever encode_ticks' output changes).
- Constants shared across languages (KEYS, LABELS/GOALS, protocol fields) are duplicated by hand.
  Change Java and Python together.
- File outputs that another process may read are written to a temp file and moved into place.

## Java
- Package `net.kroia.ainpc`, subpackages by role: `client`, `mixin`, `npc`, `replay`, `sensor`.
  Loader code only in `fabric/` (`net.kroia.ainpc.fabric`), which just calls `AiNpc.init()` / `AiNpcClient.init()`.
- Naming: UpperCamelCase classes, lowerCamelCase members, UPPER_SNAKE constants; short local names (`p`, `s`,
  `src`, `o`, `l`). Command handlers named `xxxCommand(CommandSourceStack src, ...)` returning int.
- Classes: `public final class` with `private` constructor for static-only holders; `register()` static method
  hooks Architectury events. Plain data are `record`s (frames, states, payloads), nested records allowed.
  Static mutable state (`current`, `listener`) for singletons; no DI, no interfaces with one implementation.
- Every class has a Javadoc block saying what it is and how it fits the pipeline; important fields and methods
  get `/** ... */` one-liners. HTML entities (`&lt;`) in Javadoc paths. Inline `//` comments at line end.
- Braces: K&R; single-statement `if` without braces on one line is common
  (`if (l != null && ...) l.onChunkReplaced(self);`). `var` used for obvious local types.
- Fully qualified names inline (`net.kroia.ainpc.npc.Brain.start(...)`, `java.nio.file.Files`) are common
  instead of imports for one-off uses. Either is acceptable; follow the file.
- Logging: SLF4J. `AiNpc.LOGGER` (common), `AiNpcClient.LOGGER` (client). Parameterized messages
  (`LOGGER.error("Uploading {} failed", name, e)`), exception as last arg.
- User feedback: `src.sendSuccess(() -> Component.literal(...), true)` / `src.sendFailure(...)`,
  `player.sendSystemMessage(...)`. Plain English, no translation keys (except key bindings in en_us.json).
- Error handling: catch `IOException` at the boundary, log or report to the player, clean up (close socket,
  remove NPC body, delete partial file). `catch (... ignored)` only for close/cleanup or expected failures,
  with a comment why. Validate anything from the client (e.g. upload names against `LABELS` + regex).
- Side safety: common code must not reference client classes. Client-only work goes through listener slots
  (`BlockChangeHook.listener`) set by client code. Server-only payload types are registered under
  `Platform.getEnvironment() == Env.SERVER`; client registers receivers (which also register the type).
- Networking: Architectury `NetworkManager` with `CustomPacketPayload` records holding `TYPE` and a
  `StreamCodec CODEC` built with `StreamCodec.of(...)`; handlers queue onto the main thread (`ctx.queue`).
- Commands: one tree in `ReplayCommand.register` under `/ainpc` (permission 2); client command `/ainpcrec`.
- Sensors must read only recorded state (`PlayerState`, `EntityState`, a `BlockGetter`) so live and replay
  produce identical output. Do not read live game objects inside a sensor.

### Mixins
- All in `net.kroia.ainpc.mixin`, listed in `common/src/main/resources/ainpc.mixins.json`
  (`mixins` = both sides, `client` = client only). `defaultRequire: 1`.
- Class name `<Target>Mixin`; accessors are interfaces named `<Target>Access` with `@Accessor`.
- Injected method names prefixed `ainpc$` (`ainpc$onSetBlockState`), accessor methods too.
- Keep mixins thin: forward to a static method elsewhere (`AiNpcClient.onClick(0)`, `BlockChangeHook`).
- Javadoc on each mixin explains why the hook point was chosen.
- Prefer `@Inject` at HEAD/RETURN/TAIL; no `@Overwrite` or `@Redirect` in use.

### Tests
- JUnit 5 in `common/src/test/java`, same package as the class under test (`ReplayRoundTripTest`,
  `ObjectSensorTest`, `VisionSensorTest`).

## Python
- Files start with a module docstring: what it does, usage line(s) (`python training/x.py ...`), and protocol
  or format notes. Read and update it when behaviour changes.
- Imports: stdlib first, blank line, third-party (`numpy as np`, `torch`, `torch.nn as nn`,
  `torch.nn.functional as F`), blank line, local (`import data as D`, `from train import Policy`).
- Naming: snake_case functions/variables, PascalCase classes (`Policy`, `Driver`, `Vocab`), UPPER_SNAKE
  module constants with an end-of-line comment explaining value and unit. Private helpers start with `_`.
- Style: double quotes, f-strings, flat functions over class hierarchies; `pathlib.Path` for paths;
  `argparse` for CLIs with `help=` text, `main()` + `if __name__ == "__main__": main()`.
- No type hints in use; do not add them in passing.
- Output: `print(..., flush=True)` for progress (consumed by train_gui / consoles). No logging module.
- Errors: `raise SystemExit("message")` for user-facing fatal errors, `ValueError`/`FileNotFoundError` with a
  message that says what to do ("run /ainpc generateTrainingset again"). Catch narrowly
  (`except (ConnectionError, OSError)`), at the connection or process boundary.
- Checks: self-check via `__main__` (`python training/data.py`) or small standalone check scripts; no test framework.
- Launchers: `.bat` files with `@echo off`, a `rem` header explaining usage, `python -X utf8 "%~dp0script.py" %*`.

## Rules for subagents
- Do not run `./gradlew --stop` or kill java processes; compile with `./gradlew compileJava` only when asked.
- When changing what sensors produce: bump formats as above and note that datasets need regenerating.
- When changing the socket protocol: update `Brain` and `serve.py` and the serve.py docstring together.
