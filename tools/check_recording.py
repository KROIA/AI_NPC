"""Validate and summarize AI-NPC files: raw recordings (ainpc_recordings) and replay datasets (ainpc_datasets).

Usage: python tools/check_recording.py <file.jsonl.gz> [...]
Raises (non-zero exit) if a file is malformed.
"""
import collections
import gzip
import json
import sys

RAY_KEYS = ("d", "dx", "dy", "dz", "block", "face", "entity", "light")


def load(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        lines = [json.loads(line) for line in f]
    assert lines and lines[0]["type"] == "header", "first line must be the header"
    ticks = [l for l in lines if l["type"] == "tick"]
    ended = lines[-1]["type"] == "end"
    if ended:
        assert lines[-1]["ticks"] == len(ticks), "end line tick count mismatch"
    assert ticks, "no ticks"
    for i, t in enumerate(ticks):
        assert t["t"] == i, f"tick index gap at {i}"
    assert "inventory" in ticks[0], "first tick must carry the inventory"
    return lines[0], ticks, ended


def check_recording(header, ticks):
    assert header["format"] == 4, f"unsupported recording format {header['format']}"
    sections = header["height"] // 16
    chunks, block_changes, light_sections, states = set(), 0, 0, collections.Counter()
    entities, sounds = collections.Counter(), collections.Counter()
    for t in ticks:
        for c in t["chunks"]:
            chunks.add((c["x"], c["z"]))
            for s in c["s"]:
                assert 0 <= s["i"] < sections, "section index out of range"
                assert sum(s["r"][1::2]) == 4096, "section run lengths must cover 4096 cells"
                assert max(s["r"][0::2]) < len(s["p"]), "run refers past the palette"
                states.update(s["p"])
        for l in t["light"]:
            assert sum(l["r"][1::2]) == 4096, "light run lengths must cover 4096 cells"
            assert all(0 <= v < 256 for v in l["r"][0::2]), "light value out of range"
        light_sections += len(t["light"])
        block_changes += len(t["blocks"])
        states.update(b[3] for b in t["blocks"])
        entities.update(e["type"] for e in t["entities"])
        sounds.update(s["id"] for s in t["sounds"])
    keys = collections.Counter(k for t in ticks for k, down in t["actions"]["keys"].items() if down)
    clicks = {k: sum(t["actions"][k] for t in ticks) for k in ("attacks", "uses", "picks")}
    print(f"  chunks={len(chunks)} blockChanges={block_changes} lightSections={light_sections} distinctStates={len(states)}")
    print(f"  clicks: {clicks}  dayTime {ticks[0]['world']['dayTime']}..{ticks[-1]['world']['dayTime']}")
    print(f"  entity-ticks by type: {entities.most_common(6)}")
    print(f"  sounds:   {sounds.most_common(6)}")
    print(f"  key ticks: {dict(keys)}")


def check_dataset(header, ticks):
    rays = header["vision"]["cols"] * header["vision"]["rows"]
    blocks, sounds, seen = collections.Counter(), collections.Counter(), collections.Counter()
    for i, t in enumerate(ticks):
        v = t["vision"]
        for k in RAY_KEYS:
            assert len(v[k]) == rays, f"tick {i}: vision.{k} has {len(v[k])} entries, expected {rays}"
        for dx, dy, dz in zip(v["dx"], v["dy"], v["dz"]):
            assert abs(dx * dx + dy * dy + dz * dz - 1) < 0.01, f"tick {i}: ray direction not unit length"
        assert all(0 <= l <= 15 for l in v["light"]), f"tick {i}: light out of range"
        blocks.update(b for b in v["block"] if b)
        seen.update(e["type"] for e in v["seen"])
        sounds.update(s["id"] for s in t["sounds"] if s["age"] == 0)
    lights = [l for t in ticks for l in t["vision"]["light"]]
    print(f"  ray brightness: min {min(lights)} max {max(lights)} mean {sum(lights) / len(lights):.1f}")
    print(f"  top blocks seen: {blocks.most_common(6)}")
    print(f"  entities seen:   {seen.most_common(6)}")
    print(f"  sounds started:  {sounds.most_common(6)}")


def check(path):
    header, ticks, ended = load(path)
    print(f"{path}\n  kind={header['kind']} label={header['label']} ticks={len(ticks)} "
          f"({len(ticks) / 20:.1f}s) ended={ended}")
    {"recording": check_recording, "dataset": check_dataset}[header["kind"]](header, ticks)


if __name__ == "__main__":
    for p in sys.argv[1:]:
        check(p)
