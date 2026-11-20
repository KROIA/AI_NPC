# Glossary

- **NPC** — the AI-controlled player-like entity driven by the trained model (`NpcBody`).
- **serve.py** — Python process that runs the trained model and sends inputs to the NPC in real time.
- **Recording** — captured gameplay of a human player (inputs + sensor frames), saved as `.jsonl.gz` under `fabric/run/ainpc_datasets/<task>/`.
- **generateTrainingset** — `/ainpc generateTrainingset` command that turns recordings into training data.
- **Arch N** — model architecture version in `training/train.py` (arch 6 = aim picks a ray or a detected object).
- **aim_obj** — arch-6 training target: which ray or object the head should turn toward.
- **Tree memory** — `ObjectSensor.Memory`: a recognised tree stays a tree until its last log is gone; one memory per viewer.
- **Viewer** — anything that consumes sensor frames: live player, NPC Brain, Theater, Replayer.
- **Stuck escape** — serve.py behaviour that breaks the NPC out when it pushes a movement key without moving.
- **Schmitt trigger (keys)** — hysteresis in serve.py: a key goes down above 0.5 and up below its `KEY_LOW`.
- **Mining lock** — serve.py keeps attack held, head still and item unchanged while a block cracks.
- **DAgger** — take-over recordings where a human corrects the NPC, used as extra training data.
- **Look gap** — model look error minus the "keep still" baseline error; the main aim metric.
