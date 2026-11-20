"""Dataset files (/ainpc generateTrainingset, format 2) -> numpy arrays for training.

Alignment: the observation of tick t-1 (what the player saw before acting) predicts the actions of tick t.
Recorded keys/rotation of tick t are the inputs used during tick t, and tick t's vision already shows their result.

Blocks, items, entities, sounds, effects and damage types: the id plus what it is, from features.json (written by /ainpc generateTrainingset next to the datasets):
its tags (logs, mineable/axe, ...) and physical properties. So the vocab of blocks/items is the whole game
registry, and a block never seen in a recording is still understood through its features.
Vision rays see block states (wheat[age=7], furnace[lit=true]): the state vocab is every state of every block;
a state's features are its properties, shared by name across blocks: numbers scaled to the block's range (age
7/7 = 1.0 for wheat and 3/3 for beetroot alike), booleans 0/1, named values one-hot (facing=north).

Run `python training/data.py` for the self-check.
"""
import gzip
import hashlib
import json
import itertools
import math
import os
from pathlib import Path

import numpy as np

DATASET_FORMAT = 2
# Version of encode_ticks' output (the training cache, see load): raise it whenever encode_ticks or the
# constants it uses change what it produces, so old caches are re-encoded. Not raised for comments or other code.
ENCODING = 5
KEYS = ["forward", "back", "left", "right", "jump", "sneak", "sprint", "attack", "use", "drop", "swapHands"]
CLICKS = ["attacks", "uses"]  # clicked this tick (count > 0)
ACTION_NAMES = KEYS + ["click_" + c for c in CLICKS]
# Mouse look per tick in degrees, as classes: mouse moves are multimodal (still / small aim / fast turn).
LOOK_BINS = np.array([-90, -45, -20, -10, -5, -2.5, -1, -0.3, 0, 0.3, 1, 2.5, 5, 10, 20, 45, 90], dtype=np.float32)
FACES = [None, "down", "up", "north", "south", "west", "east"]
SOUND_CATS = ["master", "music", "record", "weather", "block", "hostile", "neutral", "player", "ambient", "voice"]
MAX_ENT, MAX_SND, MAX_INV, MAX_EFF = 16, 16, 36, 8
# objects the game recognised (Java ObjectSensor.RULES): append new kinds, the index is the model's input
OBJECT_KINDS = ["tree"]
MAX_OBJ = 16
REACH, ENTITY_REACH = 4.5, 3.0  # blocks a player can mine / an entity he can hit from (survival)
THINGS = ["block", "item", "entity", "sound", "effect", "damage"]  # looked up in the feature table
BODY = ["health", "maxHealth", "absorption", "healthDelta", "food", "saturation", "armor", "armorToughness",
        "air", "maxAir", "onGround", "inWater", "inLava", "onFire", "frozen", "fallDistance"]
# scale per BODY entry, so inputs are roughly 0..1
BODY_SCALE = [20, 20, 20, 20, 20, 20, 20, 20, 300, 300, 1, 1, 1, 1, 1, 10]
# The task an NPC is given = the recording label. Same list as RecordingFormat.LABELS in the mod.
# Append only: a trained model knows a goal by its position here.
GOALS = ["gather_wood", "mine_stone", "mine_ore", "hunt", "eat", "craft", "build", "farm", "explore", "swim",
         "fight", "flee", "free_play"]


def goal_index(label):
    """1.. for a goal in GOALS, 0 for none/unknown (an unknown label never trains a goal of its own)."""
    return GOALS.index(label) + 1 if label in GOALS else 0


class Vocab:
    """String id -> index. 0 = nothing, 1 = unknown (seen only after the vocab was frozen)."""

    def __init__(self, ids=None):
        self.ids = ids or [None, "<unk>"]
        self.index = {s: i for i, s in enumerate(self.ids)}
        self.frozen = ids is not None

    def __call__(self, s):
        if s is None:
            return 0
        i = self.index.get(s)
        if i is None:
            if self.frozen:
                return 1
            i = self.index[s] = len(self.ids)
            self.ids.append(s)
        return i

    def __len__(self):
        return len(self.ids)


def new_vocabs(saved=None, table=None):
    """Every vocab is the game's registry from the feature table (frozen)."""
    v = {n: Vocab(saved[n] if saved else [None, "<unk>"] + sorted(_entries(table, n))) for n in THINGS}
    v["state"] = Vocab(saved["state"] if saved else [None, "<unk>"] + [k for k, _ in _all_states(table)])
    return v


def state_key(state):
    """Canonical block state: properties sorted by name. None stays None (ray hit nothing)."""
    if state is None or "[" not in state:
        return state
    block, props = state[:-1].split("[", 1)
    return block + "[" + ",".join(sorted(props.split(","))) + "]"


def _all_states(table):
    """(state key, {prop: value}) of every block state in the game, blocks sorted by id."""
    for block in sorted(table["blocks"]):
        props = table["blocks"][block]["props"]
        names = sorted(props)
        for values in itertools.product(*(props[n] for n in names)):
            p = dict(zip(names, values))
            yield (block + "[" + ",".join(f"{n}={p[n]}" for n in names) + "]" if names else block), p


def _prop_kind(values):
    if set(values) == {"true", "false"}:
        return "bool"
    return "int" if all(v.lstrip("-").isdigit() for v in values) else "enum"


def find_table(paths):
    """features.json in a given folder or one of its parents."""
    for p in map(Path, paths):
        for d in [p if p.is_dir() else p.parent, *p.resolve().parents]:
            if (d / "features.json").exists():
                return json.loads((d / "features.json").read_text(encoding="utf-8"))
    raise FileNotFoundError("features.json not found next to the datasets (run /ainpc generateTrainingset once)")


def _entries(table, n):
    """id -> {"tags": [...], ...}; categorical values become tags too (category:monster, kind:animal)."""
    if n == "sound":  # no tags in the game: the parts of the id (entity.zombie.hurt -> entity, zombie, hurt)
        return {s: {"tags": ["part:" + p for p in s.split(":")[-1].split(".")]} for s in table["sounds"]}
    src = table[{"entity": "entities", "damage": "damage"}.get(n, n + "s")]
    out = {}
    for id_, e in src.items():
        tags = list(e.get("tags", []))
        tags += ["category:" + e["category"]] if "category" in e else []
        tags += ["kind:" + k for k in e.get("kind", [])]
        tags += ["scaling:" + e["scaling"]] if "scaling" in e else []
        out[id_] = dict(e, tags=tags)
    return out


def _tag_list(entries):
    """Tags with 2+ members: a tag on one entry says nothing the id does not."""
    count = {}
    for e in entries.values():
        for t in e["tags"]:
            count[t] = count.get(t, 0) + 1
    return sorted(t for t, n in count.items() if n >= 2)


def _block_props(e):
    h = e["hardness"]
    return [h < 0, math.log1p(max(h, 0)) / 4, math.log1p(e["resistance"]) / 8, e["needsTool"], e["solid"],
            e["blocksMotion"], e["liquid"], e["replaceable"], e["light"] / 15, *[c / 255 for c in e["color"]],
            e["friction"], e["speed"], e["jump"]]


def _item_props(e):
    return [math.log1p(e["maxStack"]) / 4.2, math.log1p(e["maxDamage"]) / 8, e["food"] / 20, e["saturation"] / 20,
            e["tool"], e["block"], e["attackDamage"] / 10, e["attackSpeed"] / 4]


def _entity_props(e):
    return [e["width"] / 2, e["height"] / 2, e["fireImmune"], e["summonable"], math.log1p(e["maxHealth"]) / 6,
            e["attackDamage"] / 10, e["speed"] * 2, e["armor"] / 20, e["followRange"] / 64]


PROPS = {"block": _block_props, "item": _item_props, "entity": _entity_props, "sound": lambda e: [],
         "effect": lambda e: [*[c / 255 for c in e["color"]], e["instant"]],
         "damage": lambda e: [e["exhaustion"] * 10]}


def features(table, vocabs):
    """Per block/item vocab index: tag multi-hot + properties (rows 0 = nothing and 1 = unknown are zeros)."""
    out = {}
    for n in THINGS:
        props, entries = PROPS[n], _entries(table, n)
        tags = _tag_list(entries)
        col = {t: i for i, t in enumerate(tags)}
        width = len(tags) + len(props(next(iter(entries.values()))))
        m = np.zeros((len(vocabs[n]), width), np.float32)
        for i, id_ in enumerate(vocabs[n].ids):
            e = entries.get(id_)
            if e is None:
                continue
            for t in e["tags"]:
                if t in col:
                    m[i, col[t]] = 1
            m[i, len(tags):] = props(e)
        out[n] = m
    # block states: the block of each state + property features
    blocks, names, pairs = table["blocks"], set(), set()
    for e in blocks.values():
        for n, vals in e["props"].items():
            names.add(n)
            if _prop_kind(vals) == "enum":
                pairs.update(f"{n}={v}" for v in vals)
    names, pairs = sorted(names), sorted(pairs)
    col = {n: i for i, n in enumerate(names)}  # value slot; presence slot at len(names) + i
    pcol = {p: 2 * len(names) + i for i, p in enumerate(pairs)}
    sm = np.zeros((len(vocabs["state"]), 2 * len(names) + len(pairs)), np.float32)
    sb = np.zeros(len(vocabs["state"]), np.int64)
    sb[1] = 1
    for key, p in _all_states(table):
        i = vocabs["state"].index[key]
        block = key.split("[", 1)[0]
        sb[i] = vocabs["block"](block)
        for n, v in p.items():
            vals, kind = blocks[block]["props"][n], _prop_kind(blocks[block]["props"][n])
            sm[i, len(names) + col[n]] = 1
            if kind == "bool":
                sm[i, col[n]] = v == "true"
            elif kind == "int":
                nums = [int(x) for x in vals]
                sm[i, col[n]] = (int(v) - min(nums)) / max(1, max(nums) - min(nums))
            else:
                sm[i, pcol[f"{n}={v}"]] = 1
    out["state"], out["state_block"], out["state_columns"] = sm, sb, names + ["has:" + n for n in names] + pairs
    return out


def look_bin(delta):
    return int(np.abs(LOOK_BINS - np.clip(delta, -90, 90)).argmin())


def wrap(deg):
    return (deg + 180) % 360 - 180


def read_ticks(path):
    with gzip.open(path, "rt", encoding="utf-8") as f:
        header = json.loads(f.readline())
        if header.get("kind") != "dataset" or header.get("format") != DATASET_FORMAT:
            raise ValueError(f"{path}: not a format {DATASET_FORMAT} dataset (run /ainpc generateTrainingset again)")
        ticks = [o for o in map(json.loads, f) if o["type"] == "tick"]
    return header, ticks


def encode(path, vocabs):
    """One dataset file -> dict of arrays, one row per tick (inputs) and per tick (targets), not yet shifted."""
    return encode_ticks(*read_ticks(path), vocabs)


def encode_ticks(header, ticks, vocabs):
    """Ticks in dataset format -> arrays. Ticks without "actions" (live NPC observations) leave targets at 0."""
    rng = header["vision"]["range"]
    memory = header["hearing"]["memoryTicks"]
    T, R = len(ticks), header["vision"]["cols"] * header["vision"]["rows"]
    center = header["vision"]["rows"] // 2 * header["vision"]["cols"] + header["vision"]["cols"] // 2
    V = vocabs
    a = dict(
        vis=np.zeros((T, R, 6), np.float16), face=np.zeros((T, R), np.int8),
        block=np.zeros((T, R), np.int32), vent=np.zeros((T, R), np.int16),
        # crosshair ray (vision "cross"): distance / range, hit, light / 15; its face, block state, entity
        xvis=np.zeros((T, 3), np.float32), xface=np.zeros(T, np.int8), xblock=np.zeros(T, np.int32),
        xvent=np.zeros(T, np.int16),
        ent=np.zeros((T, MAX_ENT, 4), np.float32), ent_type=np.zeros((T, MAX_ENT), np.int16),
        # recognised objects (vision "objects", nearest first): distance / range, yaw / 180, pitch / 90 to its
        # anchor, height / 16, log2(parts + 1) / 8, seen by a ray, present, distance to its nearest part / range,
        # a part within reach; kind (OBJECT_KINDS index + 1)
        obj=np.zeros((T, MAX_OBJ, 9), np.float32), obj_kind=np.zeros((T, MAX_OBJ), np.int8),
        snd=np.zeros((T, MAX_SND, 5), np.float32), snd_id=np.zeros((T, MAX_SND), np.int16),
        snd_cat=np.zeros((T, MAX_SND), np.int8),
        inv_item=np.zeros((T, MAX_INV), np.int16), inv_count=np.zeros((T, MAX_INV), np.float32),
        equip=np.zeros((T, 6), np.int16),  # hand, offhand, armor feet..head
        eff_id=np.zeros((T, MAX_EFF), np.int16), eff=np.zeros((T, MAX_EFF, 3), np.float32),
        damage=np.zeros(T, np.int16), goal=np.zeros(T, np.int8),
        breaking=np.zeros((T, 2), np.float32),  # block breaking progress 0..1, known (recordings before it: 0, 0)
        mining=np.zeros(T, bool),  # side task only (aux_targets): the game was breaking a block
        scalars=np.zeros((T, len(BODY) + 3 + 3 + 3 + 4), np.float32),
        keys=np.zeros((T, len(ACTION_NAMES)), np.float32), look=np.zeros((T, 2), np.int64),
        hold=np.zeros(T, np.int64), screen=np.zeros(T, bool), t=np.zeros(T, np.int64),
        _cols=np.array(header["vision"]["cols"]),  # grid width for shifted() (not an input, dropped there)
    )
    inv = {"items": {}, "armor": [None] * 4, "offhand": None}
    for i, o in enumerate(ticks):
        a["t"][i] = o["t"]
        a["goal"][i] = goal_index(o.get("goal", header.get("label")))  # live NPC: per tick; dataset: its label
        v = o["vision"]
        d = np.asarray(v["d"], np.float32)
        a["vis"][i] = np.stack([d / rng, np.array([b is not None for b in v["block"]], np.float32),
                                v["dx"], v["dy"], v["dz"], np.asarray(v["light"], np.float32) / 15], -1)
        a["face"][i] = [FACES.index(f) for f in v["face"]]
        a["block"][i] = [V["state"](state_key(b)) for b in v["block"]]  # block state index
        a["vent"][i] = [V["entity"](e) for e in v["entity"]]
        # datasets made before the crosshair ray: the grid ray nearest to the view center instead
        c = v.get("cross") or {k: v[k][center] for k in ("d", "block", "face", "entity", "light")}
        a["xvis"][i] = [c["d"] / rng, c["block"] is not None or c["entity"] is not None, c["light"] / 15]
        a["xface"][i] = FACES.index(c["face"])
        a["xblock"][i] = V["state"](state_key(c["block"]))
        a["xvent"][i] = V["entity"](c["entity"])
        for j, e in enumerate(v["seen"][:MAX_ENT]):
            a["ent"][i, j] = [e["d"] / rng, e["yaw"] / 180, e["pitch"] / 90, 1]
            a["ent_type"][i, j] = V["entity"](e["type"])
        objs = [b for b in v.get("objects", []) if b["kind"] in OBJECT_KINDS]  # datasets before it: none
        for j, b in enumerate(objs[:MAX_OBJ]):
            a["obj"][i, j] = [b["d"] / rng, b["yaw"] / 180, b["pitch"] / 90, b["h"] / 16,
                              math.log2(b["parts"] + 1) / 8, b["seen"], 1, b["near"] / rng, b["near"] <= REACH]
            a["obj_kind"][i, j] = OBJECT_KINDS.index(b["kind"]) + 1
        for j, s in enumerate(o["sounds"][:MAX_SND]):
            a["snd"][i, j] = [s["i"], s["d"] / 16, s["yaw"] / 180, s["pitch"] / 90, s["age"] / memory]
            a["snd_id"][i, j] = V["sound"](s["id"])
            a["snd_cat"][i, j] = SOUND_CATS.index(s["cat"]) + 1 if s["cat"] in SOUND_CATS else 0
        if "inventory" in o:
            inv = o["inventory"]
        for j, (item, n) in enumerate(list(inv["items"].items())[:MAX_INV]):
            a["inv_item"][i, j], a["inv_count"][i, j] = V["item"](item), math.log1p(n)
        a["equip"][i] = [V["item"](x) for x in [o["hand"], inv["offhand"], *inv["armor"]]]
        b, w, (yaw, pitch) = o["body"], o["world"], o["rot"]
        hurt = b["damageYaw"] is not None
        a["damage"][i] = V["damage"](b["damageType"])
        for j, (eid, amp, dur) in enumerate(b["effects"][:MAX_EFF]):
            a["eff_id"][i, j] = V["effect"](eid)
            a["eff"][i, j] = [(amp + 1) / 4, math.log1p(max(dur, 0)) / 10, dur < 0]
        a["scalars"][i] = [float(b[k] or 0) / s for k, s in zip(BODY, BODY_SCALE)] + \
            [x / 2 for x in b["velocity"]] + \
            [hurt, (b["damageYaw"] or 0) / 180, (b["damagePitch"] or 0) / 90] + \
            [math.sin(math.radians(yaw)), math.cos(math.radians(yaw)), pitch / 90] + \
            [math.sin(w["dayTime"] / 24000 * 2 * math.pi), math.cos(w["dayTime"] / 24000 * 2 * math.pi),
             w["skyDarken"] / 15, max(w["rain"], w["thunder"])]
        did = o.get("actions", {}).get("did", {})
        br = o.get("breaking", did.get("breaking"))  # live NPC: top level; dataset: what the recording did
        if br is not None:
            a["breaking"][i] = [min(float(br), 1.0), 1]
        if "actions" not in o:
            continue
        act = o["actions"]
        a["mining"][i] = did.get("mining", False)
        a["keys"][i] = [act["keys"].get(k, False) for k in KEYS] + [act[c] > 0 for c in CLICKS]
        a["hold"][i] = V["item"](o["hold"])
        a["screen"][i] = act["screen"] is not None
        if i > 0:
            prev = ticks[i - 1]["rot"]
            a["look"][i] = [look_bin(wrap(yaw - prev[0])), look_bin(pitch - prev[1])]
        else:
            a["look"][i] = [look_bin(0)] * 2
    return a


_EAST, _WEST = FACES.index("east"), FACES.index("west")
_LEFT, _RIGHT = KEYS.index("left"), KEYS.index("right")


def mirror(x, y, cols, rows):
    """A sample mirrored left/right: the world flipped along x (east <-> west), so the view, the head's yaw,
    turns and the left/right keys flip with it. A free second recording for every recording.
    ponytail: block states keep their facing (stairs facing=east stay east), a small inconsistency; mirror
    them through the state vocab if that turns out to matter."""
    x, y = {k: v.copy() for k, v in x.items()}, {k: v.copy() for k, v in y.items()}
    T = len(x["vis"])
    for k in ("vis", "face", "block", "vent"):  # grid columns reversed (row-major from the top-left)
        v = x[k].reshape(T, rows, cols, *x[k].shape[2:])
        x[k] = v[:, :, ::-1].reshape(x[k].shape).copy()
    x["vis"][..., 2] *= -1  # ray direction x (world)
    for k in ("face", "xface"):
        f = x[k]
        e, w = f == _EAST, f == _WEST
        f[e], f[w] = _WEST, _EAST
    x["ent"][..., 1] *= -1  # yaw relative to the head
    x["obj"][..., 1] *= -1
    x["snd"][..., 2] *= -1
    s = x["scalars"]
    s[:, len(BODY)] *= -1  # velocity x
    s[:, len(BODY) + 4] *= -1  # damage yaw
    s[:, ROT] *= -1  # sin yaw: yaw -> -yaw
    k = y["keys"]
    k[:, [_LEFT, _RIGHT]] = k[:, [_RIGHT, _LEFT]]
    y["look"][:, 0] = len(LOOK_BINS) - 1 - y["look"][:, 0]  # the bins are symmetric around 0
    for k in ("aim", "aim0"):  # ray index row * cols + col -> mirrored column
        y[k] = y[k] // cols * cols + (cols - 1 - y[k] % cols)
    y["aim_off"][:, 0] *= -1  # within the cell: right <-> left
    return x, y


def _cache_file(p):
    return p.parent / ".cache" / (p.name + ".npz")


def _cached(p, key):
    """The cached arrays of dataset p, None when missing or stale."""
    cache = _cache_file(p)
    if not cache.exists():
        return None
    with np.load(cache) as z:
        return {k: z[k] for k in z.files if k != "_key"} if z["_key"] == np.array(key) else None


def _encode_to_cache(p, vocabs, key):
    """Worker process: encode one dataset into its cache file."""
    a = encode(p, vocabs)
    cache = _cache_file(p)
    cache.parent.mkdir(exist_ok=True)
    tmp = cache.with_name(cache.name + f".{os.getpid()}.tmp")
    with open(tmp, "wb") as f:
        np.savez(f, _key=np.array(key), **a)
    tmp.replace(cache)
    return f"{p.parent.name}/{p.name}"


TARGETS = ("keys", "look", "hold", "screen", "t", "aim", "aim0", "aim_off", "aim_obj", "hold_slot", "aux", "mining")
AUX = ["pickup_1s", "break_1s", "cross_dist_0.5s"]  # side tasks, see aux_targets()
SEE_TICKS = 10  # side task "see": every ray's distance and hit this many ticks later (train.py)
AIM_TICKS = 10  # aim target: where the player looks this many ticks (0.5 s) after the decision
# pressed for one moment rather than held: kept if pressed in any tick of a sample (held keys: in most of them)
TAPS = [KEYS.index(k) for k in ("jump", "drop", "swapHands")] + [len(KEYS) + i for i in range(len(CLICKS))]
ROT = len(BODY) + 6  # scalars columns: sin yaw, cos yaw, pitch / 90


def ray_step(vision):
    """Degrees between neighbouring rays (the larger of across and up); with foveation: in the view center."""
    return max(vision["hFov"] / vision["cols"], vision["vFov"] / vision["rows"]) * (1 - vision.get("foveation", 0))


def look_at(a, at):
    """Unit view vectors at ticks `at` (Minecraft's: -sin yaw cos pitch, -sin pitch, cos yaw cos pitch)."""
    s = a["scalars"]
    yaw, pitch = np.arctan2(s[at, ROT], s[at, ROT + 1]), np.radians(s[at, ROT + 2] * 90)
    return np.stack([-np.sin(yaw) * np.cos(pitch), -np.sin(pitch), np.cos(yaw) * np.cos(pitch)], -1)


def view_axes(scalars):
    """Head basis per tick from the scalars' yaw/pitch: forward, up, right (like the mod's Head.of)."""
    yaw, pitch = np.arctan2(scalars[..., ROT], scalars[..., ROT + 1]), np.radians(scalars[..., ROT + 2] * 90)
    fwd = np.stack([-np.sin(yaw) * np.cos(pitch), -np.sin(pitch), np.cos(yaw) * np.cos(pitch)], -1)
    up = np.stack([-np.sin(yaw) * np.sin(pitch), np.cos(pitch), np.cos(yaw) * np.sin(pitch)], -1)
    return fwd, up, np.cross(fwd, up)


def obj_dirs(scalars, obj):
    """World unit vectors (..., MAX_OBJ, 3) from the eye toward each recognised object (its yaw/pitch offsets
    relative to the head, see encode_ticks)."""
    fwd, up, right = (v[..., None, :] for v in view_axes(scalars))
    oy, op = obj[..., 1:2] * np.pi, obj[..., 2:3] * np.pi / 2
    return np.cos(op) * np.sin(oy) * right + np.sin(op) * up + np.cos(op) * np.cos(oy) * fwd


def aim_rays(a, idx, at):
    """Per decision tick idx: the ray (of that tick's view) closest to where the player looks at tick `at`."""
    return np.einsum("nrc,nc->nr", a["vis"][idx, :, 2:5].astype(np.float32), look_at(a, at)).argmax(1).astype(np.int64)


def cell_axes(dirs, r, cols):
    """dirs (N, rays, 3), r (N,) ray index -> the ray's direction, and the steps to its right and upper
    neighbour ray (from the other side at the grid's edge; zero for a grid one ray wide/high)."""
    dirs = dirs.astype(np.float32)
    n, rows = np.arange(len(r)), dirs.shape[1] // cols
    col, row = r % cols, r // cols
    d = dirs[n, r]

    def step(ok, nb, other):
        s = np.where(ok, 1.0, -1.0)[:, None]
        return (dirs[n, np.where(ok, nb, other)] - d) * s

    right = step(col < cols - 1, r + 1, r - 1) if cols > 1 else np.zeros_like(d)
    up = step(row > 0, r - cols, r + cols) if rows > 1 else np.zeros_like(d)
    return d, right, up


def cell_offset(dirs, r, cols, look):
    """Where `look` lies within ray r's cell: (right, up) in ray steps, about -0.5..0.5 (clipped to -1..1)."""
    d, right, up = cell_axes(dirs, r, cols)
    off = [((look - d) * ax).sum(-1) / np.maximum((ax * ax).sum(-1), 1e-9) for ax in (right, up)]
    return np.clip(np.stack(off, -1), -1, 1).astype(np.float32)


def _aim(a, idx):
    """aim: the ray looked at AIM_TICKS later (what to aim at), aim_off: where exactly within that ray's cell;
    aim0: the ray looked at now (the baseline)."""
    last = len(a["t"]) - 1
    at = np.minimum(idx + AIM_TICKS, last)
    aim = aim_rays(a, idx, at)
    look = look_at(a, at)
    # aim_obj (arch 6): the recognised object (index into obj) when it lies closer to where the player looked
    # than the best ray (mostly: turning toward a tree out of view), else -1
    near = np.einsum("nrc,nc->nr", a["vis"][idx, :, 2:5].astype(np.float32), look).max(1)
    o = np.einsum("noc,nc->no", obj_dirs(a["scalars"][idx], a["obj"][idx]), look)
    o = np.where(a["obj"][idx, :, 6] > 0, o, -2)
    aim_obj = np.where(o.max(1) > near, o.argmax(1), -1).astype(np.int64)
    return {"aim": aim, "aim0": aim_rays(a, idx, idx), "aim_off": cell_offset(a["vis"][idx, :, 2:5], aim, int(a["_cols"]), look),
            "aim_obj": aim_obj}


def aux_targets(a, idx):
    """Side tasks: answers the recording itself gives, learned next to the actions so the net has to understand
    the scene (training only): will the inventory grow within 1 s (got something), and what will the crosshair
    hit in 0.5 s (anything / how far)."""
    last = len(a["t"]) - 1
    total = np.expm1(a["inv_count"].astype(np.float64)).sum(1)
    later, soon = np.minimum(idx + 20, last), np.minimum(idx + 10, last)
    # a block broke: the game stopped mining while attack was still held (letting go also stops it)
    m = a["mining"]
    broke = np.concatenate([[0], np.cumsum(m[:-1] & ~m[1:] & (a["keys"][1:, KEYS.index("attack")] > 0))])
    return np.stack([total[later] > total[idx] + 0.5, broke[later] > broke[idx], a["xvis"][soon, 0]], -1).astype(np.float32)


def hold_slots(inv_item, hold):
    """The held item as an entry of the inventory list the decision saw (inv_item rows), MAX_INV = empty hand
    (or an item that is not in it, e.g. just picked up)."""
    eq = (inv_item == hold[:, None]) & (hold[:, None] > 0)
    return np.where(eq.any(1), eq.argmax(1), MAX_INV).astype(np.int64)


def shifted(a, every=1):
    """Per-tick arrays -> (x, y): one row per decision, every `every` ticks (20 / every samples per second).
    Row j: x = inputs at tick j*every, y = what the player did until the next decision (ticks j*every+1 ..
    (j+1)*every): look = the whole head turn, taps if in any tick, held keys if in most, hold/t at its end.
    every=1: x = ticks 0..T-2, y = ticks 1..T-1, as recorded."""
    skip = TARGETS + ("_cols",)
    if every == 1:
        return ({k: v[:-1] for k, v in a.items() if k not in skip},
                {k: a[k][1:] for k in TARGETS if k in a and k != "mining"} | _aim(a, np.arange(len(a["t"]) - 1))
                | {"hold_slot": hold_slots(a["inv_item"][:-1], a["hold"][1:]), "aux": aux_targets(a, np.arange(len(a["t"]) - 1))})
    r = np.arange((len(a["t"]) - 1) // every) * every
    win, end = r[:, None] + np.arange(1, every + 1), r + every
    keys = a["keys"][win]
    k = keys.mean(1) >= 0.5
    k[:, TAPS] = keys[:, :, TAPS].max(1) > 0
    s = a["scalars"]
    yaw, pitch = np.degrees(np.arctan2(s[:, ROT], s[:, ROT + 1])), s[:, ROT + 2] * 90
    look = [[look_bin(wrap(yaw[e] - yaw[b])), look_bin(pitch[e] - pitch[b])] for b, e in zip(r, end)]
    return ({k_: v[r] for k_, v in a.items() if k_ not in skip},
            dict(keys=k.astype(np.float32), look=np.array(look, np.int64).reshape(-1, 2), hold=a["hold"][end],
                 screen=a["screen"][win].any(1), t=a["t"][end], **_aim(a, r),
                 hold_slot=hold_slots(a["inv_item"][r], a["hold"][end]), aux=aux_targets(a, r)))


def load(paths, vocabs, every=1):
    """Encoded files as (name, x, y), see shifted()."""
    out = []
    # cache of the encoded arrays (decoding the json: ~400 ticks/s); stale when the dataset, the vocab
    # (features.json) or the encoding (ENCODING) changes. Misses are encoded in parallel, one file per process.
    vkey = hashlib.sha1(json.dumps({k: v.ids for k, v in vocabs.items()}).encode()).hexdigest()[:12]
    paths = list(map(Path, paths))
    keys = {p: f"{p.stat().st_mtime_ns}-{ENCODING}-{vkey}" for p in paths}
    missing = [p for p in paths if _cached(p, keys[p]) is None]
    if missing:
        import concurrent.futures as cf
        print(f"encoding {len(missing)} of {len(paths)} recordings (not cached yet)...", flush=True)
        workers = max(1, min(len(missing), (os.cpu_count() or 2) - 1))
        with cf.ProcessPoolExecutor(workers) as ex:
            jobs = [ex.submit(_encode_to_cache, p, vocabs, keys[p]) for p in missing]
            for n, j in enumerate(cf.as_completed(jobs), 1):
                print(f"encoded {n}/{len(missing)}: {j.result()}", flush=True)
    for p in paths:
        out.append((str(p), *shifted(_cached(p, keys[p]), every)))
    return out


def find(paths):
    files = []
    for p in map(Path, paths):
        files += sorted(p.rglob("*.jsonl.gz")) if p.is_dir() else [p]
    return files


if __name__ == "__main__":  # self-check on the real or synthetic dataset
    root = Path(__file__).resolve().parent.parent / "fabric/run/ainpc_datasets"
    files = find([root])
    assert files, f"no datasets under {root}"
    table = find_table([root])
    V = new_vocabs(table=table)
    fe = features(table, V)
    _, x, y = load(files[:1], V)[0]
    _, ticks = read_ticks(files[0])
    # targets of row i are tick i+1, inputs of row i are tick i
    assert y["t"][0] == ticks[1]["t"] and len(y["t"]) == len(x["vis"]) == len(ticks) - 1
    assert x["vis"][5, 0, 0] == np.float16(ticks[5]["vision"]["d"][0] / 48)
    if "cross" in ticks[5]["vision"]:  # the crosshair ray (datasets from before it: the center grid ray)
        c = ticks[5]["vision"]["cross"]
        assert x["xvis"][5, 0] == np.float32(c["d"] / 48) and V["state"].ids[x["xblock"][5]] == state_key(c["block"])
    k = ticks[1]["actions"]["keys"]
    assert list(y["keys"][0, :len(KEYS)]) == [float(k.get(n, False)) for n in KEYS]
    assert LOOK_BINS[look_bin(0.31)] == 0.3 and LOOK_BINS[look_bin(-400)] == -90 and wrap(350 - (-5)) == -5
    assert V["item"].ids[y["hold"][0]] == ticks[1]["hold"]
    # 5 samples/s: row 1 = tick 4, its look = the head turn over ticks 5..8, jump if in any of them
    _, x4, y4 = load(files[:1], V, every=4)[0]
    assert len(y4["t"]) == (len(ticks) - 1) // 4 and y4["t"][1] == ticks[8]["t"] and (x4["vis"][1] == x["vis"][4]).all()
    turn = [wrap(ticks[8]["rot"][0] - ticks[4]["rot"][0]), ticks[8]["rot"][1] - ticks[4]["rot"][1]]
    assert list(y4["look"][1]) == [look_bin(turn[0]), look_bin(turn[1])]
    j = KEYS.index("jump")
    assert all(y4["keys"][r, j] == max(y["keys"][r * 4: r * 4 + 4, j]) for r in range(len(y4["t"])))
    header, _ = read_ticks(files[0])
    assert (x["goal"] == goal_index(header["label"])).all() and goal_index("gather_wood") == 1 and goal_index("x") == 0
    tags = _tag_list(_entries(table, "block"))
    logs = fe["block"][:, tags.index("minecraft:logs")]
    assert logs[V["block"]("minecraft:birch_log")] == 1 and logs[V["block"]("minecraft:stone")] == 0
    assert fe["block"][0].sum() == 0 and fe["item"][V["item"]("minecraft:bread")][-6] == 5 / 20  # food
    sc, st = fe["state_columns"], V["state"]
    assert fe["state"][st(state_key("minecraft:wheat[age=7]")), sc.index("age")] == 1
    assert fe["state"][st(state_key("minecraft:beetroots[age=1]")), sc.index("age")] == 1 / 3
    assert fe["state"][st(state_key("minecraft:furnace[lit=true,facing=east]")), sc.index("lit")] == 1
    assert fe["state"][st(state_key("minecraft:furnace[lit=true,facing=east]")), sc.index("facing=east")] == 1
    assert V["block"].ids[fe["state_block"][st("minecraft:stone")]] == "minecraft:stone"
    assert all(s == 0 or V["state"].ids[s] is not None for s in x["block"][0])
    assert (x["block"] != 1).all(), "a recorded block state is missing from features.json"
    ents = _entries(table, "entity")
    animal = _tag_list(ents).index("kind:animal")
    assert fe["entity"][V["entity"]("minecraft:cow"), animal] == 1 and fe["entity"][V["entity"]("minecraft:zombie"), animal] == 0
    hurt = _tag_list(_entries(table, "sound")).index("part:hurt")
    assert fe["sound"][V["sound"]("minecraft:entity.zombie.hurt"), hurt] == 1
    # aim: the ray looked at now is the one closest to the view center; later = the head turn's target
    cols, rows = header["vision"]["cols"], header["vision"]["rows"]
    yaws = [abs(((y["aim0"] % cols) + 0.5) / cols * 2 - 1)]
    assert (yaws[0] <= 1.5 / cols + 1e-6).all(), "aim0 is not next to the view center"
    turned = np.abs([wrap(ticks[min(i + 1 + AIM_TICKS, len(ticks) - 1)]["rot"][0] - ticks[i]["rot"][0]) for i in range(len(y["aim"]))])
    assert (y["aim"] != y["aim0"]).mean() > 0 and (turned[y["aim"] == y["aim0"]].mean() < turned[y["aim"] != y["aim0"]].mean())
    # hold as an inventory entry: that entry is the held item; MAX_INV = nothing found (empty hand)
    hs = y["hold_slot"]
    assert all(x["inv_item"][i, s] == y["hold"][i] for i, s in enumerate(hs) if s < MAX_INV)
    assert all(y["hold"][i] == 0 or y["hold"][i] not in x["inv_item"][i] for i, s in enumerate(hs) if s == MAX_INV)
    assert y["aux"].shape == (len(y["t"]), len(AUX)) and 0 <= y["aux"][:, 1].mean() < 1
    # aim offset: the ray's direction + offset * its neighbour steps points (nearly) where the player looked
    d, rt, up = cell_axes(x["vis"][:, :, 2:5], y["aim"], cols)
    est = d + y["aim_off"][:, :1] * rt + y["aim_off"][:, 1:] * up
    at = np.minimum(np.arange(len(y["aim"])) + 1 + AIM_TICKS, len(ticks) - 1)
    true = np.array([[-math.sin(math.radians(ticks[i]["rot"][0])) * math.cos(math.radians(ticks[i]["rot"][1])),
                      -math.sin(math.radians(ticks[i]["rot"][1])),
                      math.cos(math.radians(ticks[i]["rot"][0])) * math.cos(math.radians(ticks[i]["rot"][1]))] for i in at])
    err = lambda v: np.degrees(np.arccos(np.clip((v / np.linalg.norm(v, axis=1, keepdims=True) * true).sum(1), -1, 1)))
    assert np.median(err(est)) < 0.5 * np.median(err(d)) + 0.05, (np.median(err(est)), np.median(err(d)))
    # mirrored: twice = the original; the left column becomes the right one, left/right keys and turns swap
    cols, rows = header["vision"]["cols"], header["vision"]["rows"]
    mx, my = mirror(x, y, cols, rows)
    bx, by = mirror(mx, my, cols, rows)
    assert all((bx[k] == x[k]).all() for k in x) and all((by[k] == y[k]).all() for k in y)
    assert (mx["vis"][:, cols - 1, 0] == x["vis"][:, 0, 0]).all() and (mx["vis"][:, 0, 2] == -x["vis"][:, cols - 1, 2]).all()
    assert (my["keys"][:, KEYS.index("left")] == y["keys"][:, KEYS.index("right")]).all()
    assert (LOOK_BINS[my["look"][:, 0]] == -LOOK_BINS[y["look"][:, 0]]).all() and (my["look"][:, 1] == y["look"][:, 1]).all()
    assert (mx["scalars"][:, ROT] == -x["scalars"][:, ROT]).all() and (mx["scalars"][:, ROT + 1] == x["scalars"][:, ROT + 1]).all()
    print(f"ok: {files[0].name}, {len(ticks)} ticks, vocab sizes", {n: len(v) for n, v in V.items()},
          "feature widths", {n: m.shape[1] for n, m in fe.items() if n in THINGS + ["state"]})
