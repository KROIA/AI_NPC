# Pending tasks (state of 2026-11-18)

The project is not under git. Everything below is in the working tree and not yet confirmed in game.

## Done, not yet tested in game
- **Arch 6 (training/train.py, data.py, serve.py; now the default):** the aim picks one of the rays or a detected
  object (tree). Picking a tree turns the head exactly toward it, also when it's behind.
  - Training target: `aim_obj`.
  - The report shows `object_target_rate`, `object_picked_rate` and `object_recall`.
  - Look error is now measured against the real view 0.5 s later. Compare the gap against the arch-5 baseline
    (model 24.6 deg vs keeping still 26.1 deg, a gap of 1.5 deg).
- **Tree memory (ObjectSensor.Memory, Java):** a recognised tree stays a tree until its last log is gone.
  - Each viewer (live player, NPC Brain, Theater, Replayer) has its own memory. Starting a recording clears it.
  - Needs: the new jar, `/ainpc generateTrainingset`, then a New run.
- **serve.py behaviour:**
  - **Stuck escape:** about 3 s pressing a movement key, moved < 1 block and not mining triggers a 2 s escape.
    The escape turns toward a sampled non-favourite aim and samples keys at temperature 2; attack stays held
    once pressed. Prints `stuck (Nx, rate/min)`.
  - **Schmitt trigger for keys:** a key goes down above 0.5 and up below its `KEY_LOW`. Left and right use 0.3;
    other keys have no band (forward walked too long with one); mouse buttons use 0.2.
  - **Mining lock:** while a block cracks, attack stays down, the head stays still and the held item stays.
    The model lets go deliberately when attack is below 0.1 for 5 ticks.
- **NpcBody.startDestroy:** checks `level.mayInteract` (spawn protection, world border) like the server.
  - jar: `fabric/build/libs/ainpc-fabric-1.21.1-0.1.0.jar` (03:22). The user deploys it.
- **Server:** `server.properties` now has `spawn-protection=0` (was 16). This caused "Mismatch in destroy block
  pos 0,0,0": the NPC could not break near spawn 448/288. Takes effect after a server restart.

## Next steps
1. Deploy the jar, restart the server, restart serve.py. Check in game that the NPC breaks logs near spawn.
2. Run `generateTrainingset`, start a New run (arch 6), compare the look gap and the object_* metrics.
3. If it still gets stuck (leaves etc.): take-over recordings (DAgger), plus inputs for "moved in the last 2 s"
   and "time since the last block broke" (options 4 and 3 from the stuck discussion).
4. Open idea: self-imitation fine-tuning in game (proposed earlier, not started).

## Scratchpad checks (temporary, may be gone)
These are in the session scratchpad: arch6_check.py, serve6_check.py, stuck_check.py, stop_check.py,
schmitt_check.py, mine_reset_check.py and leaf_mining.py. Copies of the arch-5 and arch-6 models are in a5copy/ and a6copy/.
