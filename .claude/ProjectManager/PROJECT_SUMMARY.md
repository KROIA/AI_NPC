# Project Summary: ainpc (AI NPC)

Scanned 2026-11-20. Current state lives in `PENDING_TASKS.md` (read it first).

## Purpose
A Minecraft 1.21.1 mod plus Python ML tooling. Players record their gameplay with a goal label
(gather_wood, mine_stone, ...). The server replays those recordings through the mod's sensors to build
training datasets. A Python imitation-learning (behaviour cloning) model learns the player's actions from
what the sensors saw. `serve.py` then runs the model and drives a server-side NPC player tick by tick.

## Tech stack and versions
- Minecraft 1.21.1, Java 21, Mojang official mappings.
- Architectury layout (common + fabric; only Fabric enabled, `enabled_platforms = fabric`).
  - architectury-plugin 3.4-SNAPSHOT, dev.architectury.loom 1.7-SNAPSHOT, shadow 8.1.1.
  - Architectury API 13.0.8, Fabric Loader 0.16.9, Fabric API 0.114.0+1.21.1.
- JUnit 5.10.2 tests in `common/src/test` (3 classes, 13 tests).
- Mod version: `gradle.properties` -> `mod_version = 1.21.1-0.1.0`. Mod id `ainpc`, group `net.kroia`.
- Python 3 with PyTorch and numpy; tkinter for the training GUI. No requirements.txt.
- Git repo, branch `mc_1.21.1`, remote `origin` = github.com/KROIA/AI_NPC (since 2026-11-20).

## Directory map
- `common/` - shared mod code (both sides), mixins, lang file, unit tests.
- `fabric/` - Fabric entrypoints and `fabric.mod.json`; builds the jar. `fabric/run/` = dev run dir (ignore).
- `training/` - Python training and model server; `training/runs/<time>/` = training outputs (ignore).
- `tools/` - small Python helper scripts for recordings.
- `gradle/`, `build.gradle`, `settings.gradle`, `gradle.properties` - build.
- `.claude/` - PM artifacts.

### Java packages (`common/src/main/java/net/kroia/ainpc/`)
- `AiNpc` - common entry point: registers commands, Theater, Brain, RecordingUpload, VisionSetting, payload types.
- `client/` - client only: `AiNpcClient` (keybinds F6 debug overlay, F7 record, F8 next label; `/ainpcrec deletelast`;
  upload queue), `Recorder` (writes raw recordings), `SensorDebugRenderer` (gizmo drawing).
- `mixin/` - `LevelChunkMixin` (block-change log for recording), `MinecraftMixin` (click counting),
  `MultiPlayerGameModeMixin` (container clicks), `MultiPlayerGameModeAccess` (accessor: destroy progress).
- `npc/` - `Brain` (socket client to serve.py, per-tick observe/act loop, time budget), `NpcBody`
  (server-side fake player that executes Actions like the vanilla client), `Actions` (one tick of inputs).
- `replay/` - `RecordingFormat` (format 2 reader/writer + LABELS), `RecordingUpload` (chunked C2S upload),
  `ReplayCommand` (`/ainpc ...` command tree), `Replayer` (recording -> dataset, no game ticking),
  `ReplayWorld` (BlockGetter rebuilt from snapshots), `Theater` (playback in a real world, spectator view),
  `FeatureTable` (features.json: tags/properties of blocks, items, entities...), `VisionSetting`
  (`/ainpc vision`, stored in `config/ainpc_vision.json`), `BlockChangeHook` (listener slot used by the mixin).
- `sensor/` - `VisionSensor`/`VisionFrame` (ray grid), `ObjectSensor` (+ `Memory`, recognised trees),
  `HearingSensor`/`HearingFrame`, `BodySensor`/`BodyFrame`, `PlayerState`/`EntityState` (recorded snapshots),
  `Head`, `LightSource`, `SensorGizmos` (debug overlay shapes, also an S2C payload).
- `fabric/src/main/java/net/kroia/ainpc/fabric/` - `AiNpcFabric`, `AiNpcFabricClient` (calls common init,
  hooks `WorldRenderEvents.BEFORE_DEBUG_RENDER`).

### Python files
- `training/data.py` - dataset (format 2) -> numpy arrays; vocabularies from features.json; encoding cache
  (`ENCODING` version); targets/aux targets; `python training/data.py` = self-check.
- `training/train.py` - `Policy` model (CNN over rays + entities/sounds/inventory/body encoders, GRU, heads for
  keys, look, hold, aim...), training loop, EMA, evaluation, `--arch 1..6` (6 default). Writes
  `runs/<time>/latest.pt`, `model.pt`, `report.json`.
- `training/serve.py` - TCP model server; `Driver` turns model output into actions (sampling, Schmitt trigger
  keys, stuck escape, mining lock).
- `training/train_gui.py` - tkinter control window: pause/continue/new run/shake/CreateChilds/"Use for NPC"
  (writes `runs/npc_run.txt`), loss curves; runs train.py as subprocesses, pauses via `runs/STOP` file.
- `training/train.bat` - opens train_gui with the data server's datasets
  (`%APPDATA%\.minecraft\server\1.21.1\fabric_AI\ainpc_datasets`) or given args.
- `training/serve.bat` - starts serve.py with given args.
- `tools/check_recording.py` - validates recordings/datasets (non-zero exit on malformed file).
- `tools/make_test_recording.py` - writes a small synthetic recording for testing replay.

## Key data flow
1. **Record (client):** F7 starts `Recorder`. It writes raw state only (player, inputs, entities, sounds,
   chunk snapshots, block-change log) to `<game dir>/ainpc_recordings/<label>/<timestamp>.jsonl.gz`.
2. **Upload:** when finished, the client sends it in 30000-byte chunks (`RecordingUpload`, C2S payload). The
   server validates the name and stores `<server dir>/ainpc_recordings/<label>/<player>-<timestamp>.jsonl.gz`.
3. **Dataset:** `/ainpc generateTrainingset [all] [filter]` runs `Replayer`: rebuilds the world (`ReplayWorld`)
   and runs the current sensors per tick -> `<server dir>/ainpc_datasets/...` plus `features.json`.
   Sensors can change later; regenerate datasets from the same recordings.
4. **Train:** `train.bat` / `train.py <datasets>` loads datasets (observation t-1 predicts actions t), trains,
   saves `latest.pt` (every epoch, at most every 5 s) and `model.pt` (best validation loss).
5. **Serve:** `serve.py [model.pt] [--port 5571] [--greedy] [--device cpu|cuda]` listens on 127.0.0.1.
   Default model: latest.pt of the newest run, or the run in `runs/npc_run.txt`.
6. **NPC control:** `/ainpc npc start [port]` spawns an `NpcBody` and `Brain` connects. Each server tick: sense,
   send a dataset-style tick, read actions, apply them. `/ainpc npc reload [latest|best]`, `goal <label>`, `stop`.
- Playback for inspection: `/ainpc playback load|start|pause|stop|setting ...` (`Theater`, overwrites chunks).

## Java <-> Python communication
- **NPC control: localhost TCP socket**, port 5571 default (`Brain.DEFAULT_PORT`). Java is the client
  (`Socket` to 127.0.0.1, 3 s timeout, 30 s for reload, TCP_NODELAY); `serve.py` is the server
  (`socket.create_server`). Newline-delimited JSON, one object per line each way:
  - `{"type":"hello"}` -> `{"vision":{...}}` (NPC adopts the model's training vision settings).
  - observation tick (dataset tick without "actions") -> `{"keys":{...},"attacks":n,"uses":n,"yaw","pitch","hold"}`.
  - `{"type":"reload","which":"latest"|"best"}` -> `{"model","epoch","val_loss","vision"}`.
- **Training data: files.** Java writes gzipped JSONL recordings/datasets and `features.json`; Python reads them.
- **GUI <-> serve.py:** files in `training/runs/` (`npc_run.txt`, `STOP`).
- Shared constants are duplicated by hand: key names, labels/goals (`RecordingFormat.LABELS` vs
  `data.py GOALS`), dataset format number. Change both sides together.

## Build / run
- Build jar: `./gradlew build` -> `fabric/build/libs/ainpc-fabric-1.21.1-0.1.0.jar` (user deploys it).
- Compile only: `./gradlew compileJava`. Tests: `./gradlew :common:test`.
- Dev client/server: `./gradlew :fabric:runClient` / `runServer` (client user name fixed to "Dev").
- Python: `python -X utf8 training/train_gui.py <datasets>`, `python training/train.py <datasets>`,
  `python training/serve.py`, `python training/data.py` (self-check).
- Shared machine: never stop Gradle daemons (see mods/CLAUDE.md).

## External dependencies
- Gradle plugins and Maven repos: maven.fabricmc.net, maven.architectury.dev, maven.neoforged.net.
- Runtime: Fabric API, Architectury API (required in fabric.mod.json).
- Python: torch, numpy, tkinter (stdlib). Optional CUDA.
- The data server lives at `%APPDATA%\.minecraft\server\1.21.1\fabric_AI` (needs `spawn-protection=0`).

## Known gaps
- Arch 6, tree memory, serve.py stuck escape / Schmitt trigger / mining lock, `NpcBody.startDestroy`
  mayInteract check: implemented, not yet tested in game (PENDING_TASKS.md).
- Still gets stuck (leaves); ideas: DAgger take-over recordings, extra inputs "moved in last 2 s",
  "time since last block broke"; self-imitation fine-tuning not started.
- `ponytail:` markers (18) record known shortcuts, e.g. one NPC at a time sharing the playback NPC's profile;
  NPC's own sounds are not heard; goal set by hand; upload has per-file 1 GiB cap only, no per-player quota.
- Only Fabric is built; no NeoForge module.
- No git, no CI, no requirements.txt, no Python tests besides `data.py` self-check and ad-hoc scratchpad checks.
- `tools/make_test_recording.py` docstring says "format-3"; `RecordingFormat` is format 2. Possibly stale.
- Constants duplicated across Java and Python can drift (see above).
