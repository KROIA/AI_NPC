"""Writes a small synthetic format-3 recording for testing replay/playback without a client:
a stone platform chunk at chunk (0,0), a cow walking past, a block change and a sound.

Usage: python tools/make_test_recording.py <output.jsonl.gz>
"""
import gzip
import json
import sys

TICKS = 60


def player(t):
    return {"pos": [8.5, -60.0, 4.5], "rot": [0.0, 10.0], "eyeHeight": 1.62, "pose": "STANDING", "size": [0.6, 1.8],
            "vel": [0, 0, 0], "onGround": True, "inWater": False, "inLava": False, "onFire": False, "frozen": False,
            "fall": 0, "health": 20, "maxHealth": 20, "absorption": 0, "food": 20, "saturation": 5, "armor": 0,
            "toughness": 0, "air": 300, "maxAir": 300, "hurtTime": 0, "damageType": None, "damageFrom": None,
            "slot": 0, "mainHand": "minecraft:iron_axe", "offHand": None, "xp": 0}


def main(out):
    lines = [{"type": "header", "kind": "recording", "format": 4, "label": "test", "mc": "1.21.1", "seed": None,
              "dimension": "minecraft:overworld", "minY": -64, "height": 384, "entityRadius": 72, "chunkRadius": 2}]
    # Section 0 (y -64..-49): stone floor at y=-61 (local y 3) under air, a log on it at local (8, 4, 12).
    blocks_runs = [0, 3 * 256, 1, 256, 0, 12 * 16 + 8, 2, 1, 0, 4096 - 4 * 256 - 12 * 16 - 8 - 1]
    chunk = {"x": 0, "z": 0, "s": [{"i": 0, "p": ["minecraft:air", "minecraft:stone", "minecraft:oak_log[axis=x]"], "r": blocks_runs}]}
    # Light of section 0: dark (sky 0) below the floor, sky 15 above, a "torch" glow of 14 around the log.
    cells = []
    for i in range(4096):
        x, z, y = i & 15, (i >> 4) & 15, i >> 8
        sky = 15 if y >= 4 else 0
        block = max(0, 14 - abs(x - 8) - abs(z - 12) - abs(y - 4)) if y >= 4 else 0
        cells.append(sky << 4 | block)
    runs = []
    for v in cells:
        if runs and runs[-2] == v:
            runs[-1] += 1
        else:
            runs += [v, 1]
    light = [{"x": 0, "z": 0, "i": 0, "r": runs}] + [
        {"x": 0, "z": 0, "i": i, "r": [15 << 4, 4096]} for i in range(1, 24)]
    for t in range(TICKS):
        cow = {"id": 7, "type": "minecraft:cow", "pos": [2.0 + t * 0.2, -60.0, 10.5], "rot": [270.0, 0.0, 270.0],
               "size": [0.9, 1.4, 1.3]}
        tick = {"type": "tick", "t": t, "gameTime": 1000 + t, "player": player(t),
                "actions": {"keys": {"attack": t >= 30}, "did": {}, "screen": None, "target": None, "clicks": [],
                            "attacks": 1 if t % 10 == 5 else 0, "uses": 0, "picks": 0},
                "world": {"dayTime": 18000 + t, "skyDarken": 11, "rain": 0.0, "thunder": 0.0},  # midnight
                "light": light if t == 0 else [],
                "entities": [cow], "sounds": [], "blocks": [], "chunks": [chunk] if t == 0 else []}
        if t == 0:
            tick["inventory"] = [None] * 41
        if t == 40:  # log broken
            tick["blocks"] = [[8, -60, 12, "minecraft:air"]]
            tick["sounds"] = [{"id": "minecraft:block.wood.break", "cat": "blocks", "pos": [8.5, -59.5, 12.5],
                               "vol": 1.0, "range": 16.0, "gt": 1040}]
        lines.append(tick)
    lines.append({"type": "end", "ticks": TICKS})
    with gzip.open(out, "wt", encoding="utf-8") as f:
        for line in lines:
            f.write(json.dumps(line) + "\n")


if __name__ == "__main__":
    main(sys.argv[1])
