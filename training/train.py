"""Behaviour cloning: learn the player's actions from what the sensors saw.

    python training/train.py fabric/run/ainpc_datasets [--epochs N]   (default: until Ctrl+C)

One model for all labels: each recording's label (its folder) is the goal input, the NPC gets its goal
with /ainpc npc goal <label>. Keep the labels you care about roughly balanced, or the big ones dominate.

Blocks, items, entities, sounds, effects and damage types are id embedding + features (tags, properties; see data.py). The id embeddings start at
zero and ids are dropped at random while training (ID_DROPOUT), so the model leans on the features and a block
it never saw (birch when only spruce was recorded) behaves like the ones that share its tags.

Per tick an encoder reads vision (CNN over the ray grid), seen entities, sounds, inventory and body; a GRU
carries memory across ticks; heads predict keys/clicks, mouse look (yaw/pitch classes) and the item to hold.
Validation: whole recordings held out (every 5th) when there are 5+, else the last 20% of each recording.
Output in training/runs/<time>/, each written via a temp file so serve.py can reload at any moment:
  latest.pt  the last finished epoch, at most every 5 s (what serve.py and /ainpc npc reload take)
  model.pt   the best validation loss so far, its scores in report.json
"""
import argparse
import copy
import itertools
import json
import random
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

import data as D

REACH = D.REACH  # blocks a player can mine (survival)
WINDOW = 64  # samples per training sequence (3.2 s at 20 samples/s, 12.8 s at 5)
ID_DROPOUT = 0.2
DROPOUT = 0.2  # arch 2: before and after the memory (GRU), against memorizing the few recordings
CHUNK_TICKS = 10  # keys: a plan for this many ticks (0.5 s) per decision, see chunks()
AUX_WEIGHT = 0.3  # side tasks (D.AUX) next to the actions
SEE_WEIGHT = 0.5  # arch 4 side task: every ray's distance and hit D.SEE_TICKS later
SEE_HIT_PRIOR = 3.0  # "see" starts from "hit stays as it is now" at ~95% sure (logit 3)
RAY_DROP, ENT_DROP, DIST_NOISE = 0.15, 0.1, 0.05  # arch 4, training only: hidden rays/entities/sounds, distances off by ~5%
EMA_DECAY = 0.998  # the saved model is a moving average of the weights over ~500 steps (see main)
SPREAD = 32  # table copies per lookup, see lookup()
LATEST_EVERY_S = 5  # latest.pt at most this often


def lookup(idx, table):
    """F.embedding, with the rows spread over SPREAD copies of the table (ray i uses copy i % SPREAD).
    Same result, but backward sums the gradients of a row in SPREAD parallel parts: 99% of rays see no entity
    and one block state is often 30% of all rays, and summing millions into one row ran nearly serially
    (entity lookup 15 ms -> 1.4 ms at 2.4M rays; also more accurate than one long float32 sum)."""
    idx = idx.long()
    copy = torch.arange(idx.numel(), device=idx.device).view(idx.shape) % SPREAD
    return F.embedding(idx + copy * len(table), table.repeat(SPREAD, 1))


class State(nn.Module):
    """Block state: its block (Thing) + projected state properties (age, open, lit, facing, ...)."""

    def __init__(self, block, feats, state_block, dim):
        super().__init__()
        self.block = block
        self.register_buffer("feats", torch.as_tensor(feats))
        self.register_buffer("state_block", torch.as_tensor(state_block))
        self.proj = nn.Linear(feats.shape[1], dim)

    def forward(self, idx):
        # the finished table of all 26k states, then one lookup per ray (see Thing)
        return lookup(idx, self.block.table()[self.state_block] + self.proj(self.feats))


class Thing(nn.Module):
    """Block, item, ...: id embedding (zero at start) + projected features of the feature table."""

    def __init__(self, feats, dim):
        super().__init__()
        self.register_buffer("feats", torch.as_tensor(feats))
        self.id = nn.Embedding(len(feats), dim)
        nn.init.zeros_(self.id.weight)
        self.proj = nn.Linear(feats.shape[1], dim)

    def table(self):
        """Every entry's vector. Training: a random ID_DROPOUT of the ids read as unknown (1) for this batch,
        so the model must also recognize them by their features; nothing (0) and unknown stay as they are."""
        ids = self.id.weight
        if self.training:
            drop = torch.rand(len(ids), 1, device=ids.device) < ID_DROPOUT
            drop[:2] = False
            ids = torch.where(drop, ids[1], ids)
        return ids + self.proj(self.feats)

    def forward(self, idx):
        # built at table size (1-27k rows), then a single lookup: each extra lookup over the rays
        # (590k per batch) costs ~1 ms forward and backward
        return lookup(idx, self.table())


def embed(hidden):
    """Block/item vector width for a model size: 16 (as before) from 128 up, then halving with the size."""
    return max(4, min(16, hidden // 8))


class Policy(nn.Module):
    def __init__(self, cfg, feats):
        super().__init__()
        self.cfg = cfg
        n = cfg["vocab"]
        # model size: everything scales with the memory width H (--hidden); 512 = the models before the option.
        # E: width of a block/item vector (others half); 16 from H 128 up, smaller below (embed() in cfg)
        H, E = cfg.get("hidden", 512), cfg.get("embed", 16)
        self.block = State(Thing(feats["block"], E), feats["state"], feats["state_block"], E)
        self.entity = Thing(feats["entity"], E // 2)
        self.item = Thing(feats["item"], E)
        self.sound = Thing(feats["sound"], E // 2)
        self.effect = Thing(feats["effect"], E // 2)
        self.damage = Thing(feats["damage"], E // 2)
        self.eff = nn.Sequential(nn.Linear(3 + E // 2, 2 * E), nn.ReLU())
        self.sound_cat = nn.Embedding(len(D.SOUND_CATS) + 1, 4)
        c = 6 + len(D.FACES) + E + E // 2 + (1 if cfg.get("arch", 1) >= 5 else 0)  # arch 5: + ray in reach
        rows, cols = cfg["rows"], cfg["cols"]
        # arch 2: the view stays sharp (no downscaling), the net says WHERE things are (spatial softmax) and
        # aims by picking a ray (aim head) instead of guessing a turn angle. arch 1: the models before.
        self.arch = cfg.get("arch", 1)
        if self.arch >= 2:
            C = self.C = max(16, H // 4)
            u = (torch.arange(cols) + 0.5) / cols * 2 - 1  # -1 left .. 1 right
            w = 1 - (torch.arange(rows) + 0.5) / rows * 2  # 1 top .. -1 bottom
            grid = torch.stack(torch.meshgrid(w, u, indexing="ij"))  # (2, rows, cols): up, right
            self.register_buffer("grid", grid, persistent=False)
            self.conv = nn.Sequential(  # 3x3 each: neighbouring rays; stride 1: one output per ray
                nn.Conv2d(c + 2, C, 3, 1, 1), nn.ReLU(), nn.Conv2d(C, C, 3, 1, 1), nn.ReLU(),
                nn.Conv2d(C, C, 3, 1, 1), nn.ReLU())
            self.conv.to(memory_format=torch.channels_last)
            # spatial softmax: per channel where it is strongest (expected up/right) + how strong (max)
            self.where = nn.Sequential(nn.Linear(3 * C, H // 2), nn.ReLU())
            self.aim_q = nn.Linear(H, C)  # memory/goal -> what to look for in the rays' features
            self.aim_pos = nn.Parameter(torch.zeros(rows * cols))  # learned preference per ray (e.g. near center)
            self.drop = nn.Dropout(DROPOUT)
        if self.arch >= 4:
            # the goal tunes the vision: per channel scale and shift after the first layer (FiLM), starting neutral
            self.film = nn.Linear(E, 2 * C)
            nn.init.zeros_(self.film.weight), nn.init.zeros_(self.film.bias)
            # aim: where exactly within the chosen ray's cell (right, up in ray steps)
            self.aim_off = nn.Sequential(nn.Linear(C + H, H // 4), nn.ReLU(), nn.Linear(H // 4, 2))
            # side task "see": per ray, its distance and hit a moment later
            self.see_z, self.see = nn.Linear(H, C), nn.Linear(C, 2)
        self.cnn = None if self.arch >= 2 else nn.Sequential(
            nn.Conv2d(c, H // 8, 3, 2, 1), nn.ReLU(), nn.Conv2d(H // 8, H // 4, 3, 2, 1), nn.ReLU(),
            nn.Conv2d(H // 4, H // 4, 3, 2, 1), nn.ReLU(), nn.AdaptiveAvgPool2d((3, 4)), nn.Flatten(),
            nn.Linear(H // 4 * 12, H // 2), nn.ReLU())
        if self.cnn is not None:
            self.cnn.to(memory_format=torch.channels_last)  # the ray image is laid out (rows, cols, channels)
        # crosshair: the dedicated ray along the view (vision "cross"), past the CNN (which blurs the grid into
        # 3x4), so "a log under the crosshair within reach" is directly visible. Models before it have none.
        self.cross = None
        if cfg.get("crosshair"):
            self.reach = REACH / cfg["vision"]["range"]  # xvis[..., 0] is distance / range
            self.ent_reach = D.ENTITY_REACH / cfg["vision"]["range"]
            self.cross = nn.Sequential(nn.Linear(3 + 1 + len(D.FACES) + E + E // 2, H // 8), nn.ReLU())
        self.ent = nn.Sequential(nn.Linear(4 + E // 2, H // 8), nn.ReLU())
        # arch 5: objects the game recognised (trees, ...), also behind walls: like the entities
        self.obj_kind = nn.Embedding(len(D.OBJECT_KINDS) + 1, E // 2) if self.arch >= 5 else None
        self.obj = nn.Sequential(nn.Linear(9 + E // 2, H // 8), nn.ReLU()) if self.arch >= 5 else None
        if self.arch >= 6:  # aim: besides the rays, a recognised object can be picked (turn toward it, also behind)
            self.obj_key = nn.Linear(9 + E // 2, self.C)
            self.obj_bias = nn.Parameter(torch.zeros(()))
        self.snd = nn.Sequential(nn.Linear(5 + E // 2 + 4, H // 8), nn.ReLU())
        # the task (D.GOALS, 0 = none): one model for all labels, so walking, aiming and mining are learned
        # from every recording, and the goal picks what to do. Models saved before goals have none.
        self.goal = nn.Embedding(len(cfg["goals"]) + 1, E) if "goals" in cfg else None
        width = H // 2 + (H // 8 if self.cross is not None else 0) + H // 8 + H // 8 + E + 6 * E + 2 * E + E // 2 + cfg["scalars"] + (E if self.goal else 0) + (2 if self.arch >= 4 else 0) + (H // 8 if self.arch >= 5 else 0)
        self.fuse = nn.Sequential(nn.Linear(width, H), nn.ReLU())
        self.gru = nn.GRU(H, H, batch_first=True)
        self.K = cfg.get("chunk", 1)  # decisions per key plan; 1 = the models before (same weights)
        self.keys = nn.Linear(H, self.K * len(D.ACTION_NAMES))
        self.aux = nn.Linear(H, len(D.AUX)) if cfg.get("aux") else None
        self.look = nn.Linear(H, 2 * len(D.LOOK_BINS)) if self.arch < 2 else None
        if self.arch >= 3:  # hold: point at one of the inventory entries (or the empty hand), not 1300 items
            self.hold_q = nn.Linear(H, H // 4)
            self.hold_k = nn.Linear(E + 1, H // 4)  # item vector + log count
            self.hold_empty = nn.Linear(H, 1)
            self.hold = None
        else:
            self.hold = nn.Linear(H, n["item"])

    def heads(self, z, look, hold, h, extra=None):
        """(keys (B, T, K, actions): the plan for this and the next K-1 decisions, look/aim, hold, memory,
        extra: {"aux": side tasks, "see": arch 4 side task, "aim_off": arch 4 aim within the ray}, what the model has)."""
        B, T = z.shape[:2]
        extra = dict(extra or {})
        if self.aux is not None:
            extra["aux"] = self.aux(z)
        return self.keys(z).view(B, T, self.K, -1), look, hold, h, extra

    def hold_logits(self, z, x):
        """arch 3: one logit per inventory entry (empty ones masked out) + one for the empty hand."""
        if self.hold is not None:
            return self.hold(z)
        k = self.hold_k(torch.cat([self.item(x["inv_item"].long()), x["inv_count"].unsqueeze(-1)], -1))  # (B, T, MAX_INV, K)
        logit = (k @ self.hold_q(z).unsqueeze(-1)).squeeze(-1) / k.shape[-1] ** 0.5
        logit = logit.masked_fill(x["inv_item"] == 0, -1e4)
        return torch.cat([logit, self.hold_empty(z)], -1)

    def forward(self, x, h=None, ray=None, side=True):
        """ray: arch 4, the ray whose cell offset to give (training: the right one; None: the chosen one);
        side: also the "see" side task (serve.py: off, it only acts)."""
        B, T, R = x["face"].shape
        rows, cols = self.cfg["rows"], self.cfg["cols"]
        aug = self.training and self.arch >= 4  # disturbed inputs: no exact pattern to memorize
        faces = torch.eye(len(D.FACES), device=x["face"].device)[x["face"].long()]  # one_hot would sync the GPU
        vis = x["vis"].float()
        if aug:
            vis = torch.cat([vis[..., :1] * (1 + DIST_NOISE * torch.randn_like(vis[..., :1])), vis[..., 1:]], -1)
        v = [vis, faces, self.block(x["block"].long()), self.entity(x["vent"].long())]
        if self.arch >= 5:  # per ray: a block within mining reach / an entity within hitting reach
            rng = self.cfg["vision"]["range"]
            v.append(((vis[..., :1] * rng <= REACH - (REACH - D.ENTITY_REACH) * (x["vent"].unsqueeze(-1) > 0).float())
                      & (vis[..., 1:2] > 0)).float())
        v = torch.cat(v, -1)
        if aug:  # hidden rays read as nothing at all
            v = v * (torch.rand(B, T, R, 1, device=v.device) > RAY_DROP)
        cross = None
        if self.cross is not None:
            xv = x["xvis"]
            reach = self.reach  # hit within reach; arch 5: an entity only from 3 blocks
            if self.arch >= 5:
                reach = self.reach - (self.reach - self.ent_reach) * (x["xvent"].unsqueeze(-1) > 0).float()
            near = ((xv[..., 0:1] <= reach) & (xv[..., 1:2] > 0)).float()
            xface = torch.eye(len(D.FACES), device=xv.device)[x["xface"].long()]
            cross = self.cross(torch.cat([xv, near, xface, self.block(x["xblock"].long()), self.entity(x["xvent"].long())], -1))
        feat = None
        with torch.autocast("cuda", torch.bfloat16, enabled=v.is_cuda):  # CNN only: 2x faster on tensor cores
            img = v.view(B * T, rows, cols, -1).permute(0, 3, 1, 2)
            if self.cnn is not None:
                v = self.cnn(img).float().view(B, T, -1)
            else:
                img = torch.cat([img, self.grid.to(img.dtype).expand(B * T, -1, -1, -1)], 1)
                if self.arch >= 4:
                    gamma, beta = self.film(self.goal(x["goal"].long())).view(B * T, -1, 1, 1).chunk(2, 1)
                    first = self.conv[:2](img)
                    feat = self.conv[2:](first * (1 + gamma) + beta).float().flatten(2)  # (B*T, C, rays)
                else:
                    feat = self.conv(img).float().flatten(2)  # (B*T, C, rays)
        if feat is not None:
            att = feat.softmax(-1)  # per channel: a distribution over the rays
            pos = torch.einsum("ncr,kr->nck", att, self.grid.flatten(1))  # expected (up, right) per channel
            v = self.where(torch.cat([pos.flatten(1), feat.amax(-1)], -1)).view(B, T, -1)
        e = self.ent(torch.cat([x["ent"], self.entity(x["ent_type"].long())], -1))
        present, heard = x["ent"][..., 3:4], (x["snd_id"] > 0).unsqueeze(-1)
        if aug:
            present = present * (torch.rand_like(present) > ENT_DROP)
            heard = heard & (torch.rand(heard.shape, device=heard.device) > ENT_DROP)
        e = (e * present).amax(2)  # slot present flag masks padding
        s = self.snd(torch.cat([x["snd"], self.sound(x["snd_id"].long()), self.sound_cat(x["snd_cat"].long())], -1))
        s = (s * heard).amax(2)
        inv = (self.item(x["inv_item"].long()) * x["inv_count"].unsqueeze(-1)).sum(2)
        eq = self.item(x["equip"].long()).flatten(2)
        ef = self.eff(torch.cat([x["eff"], self.effect(x["eff_id"])], -1))
        ef = (ef * (x["eff_id"] > 0).unsqueeze(-1)).amax(2)
        z = [v] + ([cross] if cross is not None else []) + [e, s, inv, eq, ef, self.damage(x["damage"]), x["scalars"]]
        z += [self.goal(x["goal"].long())] if self.goal else []
        z += [x["breaking"]] if self.arch >= 4 else []
        if self.obj is not None:
            of = torch.cat([x["obj"], self.obj_kind(x["obj_kind"].long())], -1)
            there = x["obj"][..., 6:7]
            if aug:
                there = there * (torch.rand_like(there) > ENT_DROP)
            z += [(self.obj(of) * there).amax(2)]
        z = self.fuse(torch.cat(z, -1))
        if self.arch >= 2:
            z = self.drop(z)
        z, h = self.gru(z, h)
        if self.arch >= 2:  # aim: one logit per ray = how well its features match what the memory looks for
            z = self.drop(z)
            q = self.aim_q(z).view(B * T, 1, -1)
            aim = (q @ feat).view(B, T, -1) / self.C ** 0.5 + self.aim_pos
            extra = {}
            if self.arch >= 6:  # (B, T, rays + MAX_OBJ): absent objects can't be picked
                ko = self.obj_key(of)  # (B, T, MAX_OBJ, C)
                ao = (ko @ q.view(B, T, -1, 1)).squeeze(-1) / self.C ** 0.5 + self.obj_bias
                aim = torch.cat([aim, ao.masked_fill(x["obj"][..., 6] == 0, -1e4)], -1)  # not the dropped: may be the target
                extra["aim_rays"] = R
            if self.arch >= 4:
                fr = feat.view(B, T, self.C, R)
                r = ray if ray is not None else aim.argmax(-1).clamp(max=R - 1)  # an object: offset unused
                pick = fr.gather(3, r.view(B, T, 1, 1).expand(B, T, self.C, 1)).squeeze(-1)
                extra["aim_off"] = torch.tanh(self.aim_off(torch.cat([pick, z], -1)))
                if side:  # (B, T, R, 2) logits: distance (sigmoid = distance / range), hit
                    change = self.see(F.relu(fr.transpose(2, 3) + self.see_z(z).unsqueeze(2)))
                    # only the change is learned: on top of "it stays as it is now"
                    now = x["vis"][..., :2].float()
                    stay = torch.stack([torch.logit(now[..., 0], eps=1e-3), (now[..., 1] * 2 - 1) * SEE_HIT_PRIOR], -1)
                    extra["see"] = stay + change
            return self.heads(z, aim, self.hold_logits(z, x), h, extra)
        return self.heads(z, self.look(z).view(B, T, 2, -1), self.hold_logits(z, x), h)


def windows(files, w):
    """(file index, start, length) covering every file with half-overlapping windows."""
    out = []
    for f, (_, x, _) in enumerate(files):
        n = len(x["vis"])
        out += [(f, s, min(w, n - s)) for s in range(0, max(1, n - w // 2), w // 2)]
    return out


def to_device(files, device):
    """All recordings as tensors on the device once, so batches are cut out there (no copy per step).
    ponytail: everything in GPU memory (~13 MB per 1000 ticks); stream from the CPU if it stops fitting"""
    to = lambda d: {k: torch.from_numpy(v).to(device) for k, v in d.items() if k != "t"}
    return [(name, to(x), to(y)) for name, x, y in files]


def pack(files):
    """Files from to_device joined end to end, so a batch is one gather per input instead of one copy per
    window and input (16 x 35 tiny GPU jobs, each costing CPU time to launch)."""
    cat = lambda i: {k: torch.cat([f[i][k] for f in files]) for k in files[0][i]}
    return cat(1), cat(2), np.cumsum([0] + [len(x["vis"]) for _, x, _ in files])


def batch(packed, items, device, ahead=0):
    """Windows (file, start, length) from a pack; shorter ones repeat their last tick, mask marks the real ticks.
    ahead > 0: y["see"] = distance and hit of every ray `ahead` samples later (at most the recording's last)."""
    xs, ys, off = packed
    T = WINDOW  # always the same shape, as the CUDA graph (train_step) needs
    start = torch.tensor([off[f] + s for f, s, _ in items], device=device)
    n = torch.tensor([n for _, _, n in items], device=device)
    t = torch.arange(T, device=device)
    idx = start[:, None] + torch.minimum(t, n[:, None] - 1)
    y = {k: v[idx] for k, v in ys.items()}
    if ahead:
        end = torch.tensor([off[f + 1] - 1 for f, _, _ in items], device=device)
        y["see"] = xs["vis"][torch.minimum(idx + ahead, end[:, None]), :, :2]
    return {k: v[idx] for k, v in xs.items()}, y, t < n[:, None]


def train_step(model, opt, example, key_weight=None):
    """step(x, y, mask) -> loss parts: forward, backward and optimizer step. On the GPU recorded once as a
    CUDA graph and replayed: a step is ~600 small GPU jobs, and launching them one by one from Python took
    longer than running them. Needs fixed shapes: every batch has `example`'s size (the loop skips the last,
    smaller one; batches are shuffled anew each epoch, so no window is left out for good)."""

    def eager(x, y, mask):
        loss, parts = loss_fn(model(x, ray=y["aim"]), y, mask, key_weight)
        opt.zero_grad(set_to_none=False)
        loss.backward()
        nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        return parts

    if not example[2].is_cuda:
        return eager
    keep = copy.deepcopy(model.state_dict()), copy.deepcopy(opt.state_dict())
    sx, sy, smask = example
    side = torch.cuda.Stream()
    side.wait_stream(torch.cuda.current_stream())
    with torch.cuda.stream(side):  # warm-up (cuDNN picks its algorithms, gradients get allocated) ...
        for _ in range(3):
            eager(sx, sy, smask)
    torch.cuda.current_stream().wait_stream(side)
    model.load_state_dict(keep[0])  # ... then undone, so the warm-up steps don't train on one batch
    opt.load_state_dict(keep[1])
    graph = torch.cuda.CUDAGraph()
    with torch.cuda.graph(graph):
        parts = eager(sx, sy, smask)

    def replay(x, y, mask):
        for static, new in ((sx, x), (sy, y)):
            for k in static:
                static[k].copy_(new[k])
        smask.copy_(mask)
        graph.replay()
        return {k: v.clone() for k, v in parts.items()}

    return replay


def key_weights(files):
    """Per key: how much more a press counts than a release in training, sqrt(released / pressed) capped
    at 1..5. Without it rare keys (jump, left, use: a few % of the ticks) are never pressed: "not pressed"
    is almost always right, so the net learns nothing else. Square root and cap: pressed a bit too often
    rather than all the time."""
    k = np.concatenate([y["keys"] for _, _, y in files])
    rate = k.mean(0)
    return np.clip(np.sqrt((1 - rate) / np.maximum(rate, 1e-6)), 1, 5).astype(np.float32)


def press_samples(files):
    """Per key: how many samples a press lasts on average (serve.py starts presses that rarely)."""
    down, starts = 0, 0
    for _, _, y in files:
        k = y["keys"] > 0
        down, starts = down + k.sum(0), starts + (k & ~np.vstack([k[:1] & False, k[:-1]])).sum(0)
    return [round(float(d / max(s, 1)), 1) if s else 1.0 for d, s in zip(down, starts)]


def loss_fn(out, y, mask, key_weight=None):
    """key_weight: training only (see key_weights); validation scores without, so runs stay comparable."""
    keys, look, hold, _, extra = out
    aux = extra.get("aux")
    m = mask.float()
    # the whole plan: step i = the decision i later (y["plan"] from chunks(), plan_mask: not past the end)
    lk = F.binary_cross_entropy_with_logits(keys, y["plan"], reduction="none", pos_weight=key_weight).mean(-1)
    pm = y["plan_mask"]
    lk = ((lk * pm).sum(-1) / pm.sum(-1).clamp(min=1) * m).sum()
    if look.dim() == 4:  # arch 1: turn per tick as bins
        ll = (F.cross_entropy(look.flatten(0, 2), y["look"].flatten(), reduction="none").view(m.shape + (2,)).mean(-1) * m).sum()
    obj = "aim_rays" in extra and "aim_obj" in y  # arch 6: the target can be an object (index after the rays)
    if look.dim() == 3:  # arch 2: aim logits (B, T, rays) -> the ray looked at 0.5 s later
        tgt = torch.where(y["aim_obj"] >= 0, extra["aim_rays"] + y["aim_obj"], y["aim"]) if obj else y["aim"]
        ll = (F.cross_entropy(look.flatten(0, 1), tgt.flatten(), reduction="none").view(m.shape) * m).sum()
    target = y["hold_slot"] if hold.shape[-1] == D.MAX_INV + 1 else y["hold"]  # arch 3: inventory entry
    lh = (F.cross_entropy(hold.flatten(0, 1), target.flatten(), reduction="none").view(m.shape) * m).sum()
    n = m.sum()
    la = torch.zeros((), device=m.device)
    if aux is not None:  # side tasks: two yes/no, one distance
        a = y["aux"]
        la = ((F.binary_cross_entropy_with_logits(aux[..., :2], a[..., :2], reduction="none").sum(-1)
               + F.mse_loss(torch.sigmoid(aux[..., 2]), a[..., 2], reduction="none") * 4) * m).sum()
    lo, ls = torch.zeros((), device=m.device), torch.zeros((), device=m.device)
    if "aim_off" in extra:  # arch 4: where within the ray's cell (an action: counts in validation too)
        lo = ((extra["aim_off"] - y["aim_off"]).pow(2).sum(-1) * (m * (y["aim_obj"] < 0) if obj else m)).sum()
    if "see" in extra and "see" in y:  # arch 4 side task: every ray a moment later
        p, t = extra["see"], y["see"].float()
        # plain distance error (squared would hedge between near and far on unsure rays, see the report)
        ls = ((F.l1_loss(torch.sigmoid(p[..., 0]), t[..., 0], reduction="none") * 4
               + F.binary_cross_entropy_with_logits(p[..., 1], t[..., 1], reduction="none")).mean(-1) * m).sum()
    # parts stay on the GPU (read once per epoch): .item() every step would make the CPU wait for the GPU
    # validation scores without the side tasks (key_weight None): comparable with runs that have none
    total = lk + ll + lh + lo + (AUX_WEIGHT * la + SEE_WEIGHT * ls if key_weight is not None else 0)
    return total / n, {k: (v / n).detach() for k, v in
                       {"keys": lk, "look": ll, "hold": lh, "aux": la, "off": lo, "see": ls}.items()}


@torch.no_grad()
def evaluate(model, files, device):
    """Whole sequences, memory carried from the first tick like the live NPC."""
    model.eval()
    P, Y, L, LY, H, HY, total, ticks = [], [], [], [], [], [], 0.0, 0
    AX, AY = [], []  # side tasks: predicted, true
    ERR, ERR0 = [], []  # arch 2+: angle to where the player looked 0.5 s later, model / "keep looking"
    SEE = []  # arch 4 "see": (distance error, its baseline, hit right, its baseline) per tick
    OBJ = []  # arch 6: (picked an object, the target was one) per tick
    rng, cols = model.cfg["vision"]["range"], model.cfg["cols"]
    ahead = max(1, D.SEE_TICKS // model.cfg.get("every", 1))
    unit = lambda v: v / np.linalg.norm(v, axis=-1, keepdims=True).clip(1e-9)
    angle = lambda a, b: np.degrees(np.arccos(np.clip((unit(a) * unit(b)).sum(-1), -1, 1)))
    for f in range(len(files)):
        n = len(files[f][1]["vis"])
        _, x, y = files[f]
        x, y = {k: v[None] for k, v in x.items()}, {k: v[None] for k, v in y.items()}
        if model.arch >= 4:
            y["see"] = x["vis"][:, torch.clamp(torch.arange(n, device=device) + ahead, max=n - 1), :, :2]
        mask = torch.ones(1, n, dtype=torch.bool, device=device)
        out = model(x)
        total += loss_fn(out, y, mask)[0].item() * n
        ticks += n
        extra = out[4]
        P.append(torch.sigmoid(out[0][0, :, 0]).cpu()), Y.append(y["keys"][0].cpu())
        if "aux" in extra:
            AX.append(torch.sigmoid(extra["aux"][0]).cpu()), AY.append(y["aux"][0].cpu())
        L.append(out[1][0].argmax(-1).cpu())
        if out[1].dim() == 3:
            R = x["vis"].shape[2]
            obj = "aim_rays" in extra
            ay = y["aim"][0]
            LY.append(torch.where(y["aim_obj"][0] >= 0, R + y["aim_obj"][0], ay).cpu() if obj else ay.cpu())
            dirs = x["vis"][0, :, :, 2:5].float().cpu().numpy()
            pick = out[1][0].argmax(-1).cpu().numpy()
            s = x["scalars"][0].cpu().numpy()
            now = D.view_axes(s)[0]  # keep looking: the view direction now
            truth = now[np.minimum(np.arange(n) + max(1, D.AIM_TICKS // model.cfg.get("every", 1)), n - 1)]  # 0.5 s later
            d, rt, up = D.cell_axes(dirs, np.minimum(pick, R - 1), cols)
            mine = d  # arch 4: plus where within the ray's cell
            if "aim_off" in extra:
                o = extra["aim_off"][0].cpu().numpy()
                mine = d + o[:, :1] * rt + o[:, 1:] * up
            if obj:  # arch 6: picked an object
                od = D.obj_dirs(s, x["obj"][0].cpu().numpy())
                po = pick >= R
                mine[po] = od[np.arange(n)[po], pick[po] - R]
                OBJ.append(np.stack([po, y["aim_obj"][0].cpu().numpy() >= 0], -1))
            ERR.append(angle(mine, truth)), ERR0.append(angle(now, truth))
        else:
            LY.append(y["look"][0].cpu())
        if "see" in extra:
            p, t = extra["see"][0].float(), y["see"][0].float()
            dist = lambda a: ((a - t[..., 0]).abs().mean(-1) * rng).cpu()
            hit = lambda a: (a == (t[..., 1] > 0.5)).float().mean(-1).cpu()
            SEE.append(torch.stack([dist(torch.sigmoid(p[..., 0])), dist(x["vis"][0, :, :, 0].float()),
                                    hit(p[..., 1] > 0), hit(x["vis"][0, :, :, 1] > 0.5)], -1))
        H.append(out[2][0].argmax(-1).cpu())
        HY.append((y["hold_slot"] if out[2].shape[-1] == D.MAX_INV + 1 else y["hold"])[0].cpu())
    P, Y, L, LY, H, HY = map(torch.cat, (P, Y, L, LY, H, HY))
    pred = P > 0.5
    keys = {}
    for i, name in enumerate(D.ACTION_NAMES):
        tp = (pred[:, i] & (Y[:, i] > 0)).sum().item()
        pos, said = (Y[:, i] > 0).sum().item(), pred[:, i].sum().item()
        keys[name] = {"rate": round(pos / ticks, 3), "precision": round(tp / said, 3) if said else None,
                      "recall": round(tp / pos, 3) if pos else None}
    if ERR:  # aim vs. "keep looking where you look now", in degrees from where the player looked 0.5 s later
        ERR, ERR0 = np.concatenate(ERR), np.concatenate(ERR0)
        step = 1.5 * D.ray_step(model.cfg["vision"])
        look = {"accuracy": round((L == LY).float().mean().item(), 3),
                "mean_error_deg": round(float(ERR.mean()), 1), "mean_error_deg_if_still": round(float(ERR0.mean()), 1),
                "within_one_ray": round(float((ERR <= step).mean()), 3),
                "within_one_ray_if_still": round(float((ERR0 <= step).mean()), 3)}
        if OBJ:  # how often the target is an object (turning toward a tree out of view) and the model agrees
            o = np.concatenate(OBJ)
            look |= {"object_target_rate": round(float(o[:, 1].mean()), 3),
                     "object_picked_rate": round(float(o[:, 0].mean()), 3),
                     "object_recall": round(float((o[:, 0] & o[:, 1]).sum() / max(1, o[:, 1].sum())), 3)}
    else:
        still = D.look_bin(0)
        deg = np.abs(D.LOOK_BINS[L.numpy()] - D.LOOK_BINS[LY.numpy()])
        look = {"accuracy": round((L == LY).float().mean().item(), 3),
                "accuracy_if_always_still": round((LY == still).float().mean().item(), 3),
                "mean_error_deg": [round(float(d), 2) for d in deg.mean(0)]}
    side = None
    if AX:  # yes/no tasks: how often right vs. always saying the more common answer; distance: mean error
        AX, AY = torch.cat(AX), torch.cat(AY)
        side = {}
        for i, name in enumerate(D.AUX[:2]):
            rate = AY[:, i].mean().item()
            side[name] = {"accuracy": round(((AX[:, i] > 0.5) == (AY[:, i] > 0.5)).float().mean().item(), 3),
                          "always_" + ("yes" if rate > 0.5 else "no"): round(max(rate, 1 - rate), 3)}
        side[D.AUX[2]] = {"mean_error_blocks": round(((AX[:, 2] - AY[:, 2]).abs().mean() * rng).item(), 2)}
    if SEE:  # every ray a moment later vs. "it stays as it is now"
        m = torch.cat(SEE).mean(0).tolist()
        side = dict(side or {}, see={"distance_error_blocks": round(m[0], 2), "if_unchanged": round(m[1], 2),
                                     "hit_accuracy": round(m[2], 3), "hit_if_unchanged": round(m[3], 3)})
    model.train()
    return {"loss": total / ticks, "ticks": ticks, "keys": keys, "look": look, "side_tasks": side,
            "hold_accuracy": round((H == HY).float().mean().item(), 3)}


def chunks(files, K):
    """y["plan"]: the keys of this and the next K-1 decisions per row; plan_mask: 0 past the recording's end."""
    out = []
    for name, x, y in files:
        k = y["keys"]
        plan, pm = np.zeros((len(k), K, k.shape[1]), np.float32), np.zeros((len(k), K), np.float32)
        for i in range(K):
            plan[:len(k) - i, i], pm[:len(k) - i, i] = k[i:], 1
        out.append((name, x, dict(y, plan=plan, plan_mask=pm)))
    return out


def split(files):
    if len(files) >= 5:
        return [f for i, f in enumerate(files) if i % 5], [f for i, f in enumerate(files) if not i % 5]
    train, val = [], []
    for name, x, y in files:
        cut = int(len(x["vis"]) * 0.8)
        train.append((name, {k: v[:cut] for k, v in x.items()}, {k: v[:cut] for k, v in y.items()}))
        val.append((name + "#last20%", {k: v[cut:] for k, v in x.items()}, {k: v[cut:] for k, v in y.items()}))
    return train, val


def new_run_dir():
    """runs/<time>, or <time>-2, -3, ... when several runs start in the same second (train_gui.py: several seeds)."""
    base = Path(__file__).parent / "runs" / time.strftime("%Y%m%d-%H%M%S")
    for i in itertools.count(1):
        d = base if i == 1 else base.with_name(f"{base.name}-{i}")
        try:
            d.mkdir(parents=True)
            return d
        except FileExistsError:
            pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data", nargs="+", help="dataset files or folders")
    ap.add_argument("--epochs", type=int, default=0, help="0: until Ctrl+C")
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--hidden", type=int, default=128,
                    help="model size (memory width, multiple of 8): 1024 = 11M parameters, 768 = 6.7M, 512 = 3.3M, 384 = 2.0M, 256 = 1.1M, 192 = 0.72M, 128 = 0.43M, 96 = 0.29M, "
                         "64 = 0.17M, 48 = 0.12M, 32 = 76K, 24 = 60K, 16 = 45K, 8 = 31K; "
                         "fewer overfit later on little data. A continued run keeps its size")
    ap.add_argument("--rate", type=int, default=20, choices=[20, 10, 5, 4, 2, 1],
                    help="samples (NPC decisions) per second, the recordings have 20: fewer = each sample a bigger step, "
                         "faster training, longer memory per window, coarser reactions. A continued run keeps its rate")
    ap.add_argument("--resume", help="a run folder (training/runs/<time>): continue it from its latest.pt")
    ap.add_argument("--shake-from", help="a run folder: a new run starting from its latest.pt with --shake noise on the weights")
    ap.add_argument("--shake", type=float, default=0.2,
                    help="noise for --shake-from: half of the weights (random) get a random change of about this "
                         "share of their tensor's spread (0.2 = 20%%)")
    ap.add_argument("--from-best", action="store_true",
                    help="with --shake-from: start from its best epoch (model.pt) instead of its last (latest.pt)")
    ap.add_argument("--copy-only", action="store_true",
                    help="with --shake-from: only save the shaken copy (latest.pt), no training (train_gui.py: CreateChilds)")
    ap.add_argument("--arch", type=int, choices=[1, 2, 3, 4, 5, 6], default=6,
                    help="new run: 6 = 5 + aim can also pick a recognised object (turn toward a tree, also behind); "
                         "5 = 4 + objects the game recognised (trees, ...) as input; 4 = 3 + disturbed inputs in training, the goal tunes the vision, aim within the "
                         "ray, side task 'see a moment ahead', block breaking progress as input; 3 = 2 + hold by "
                         "picking an inventory entry; 2 = sharp view, spatial softmax, aim by picking a ray, "
                         "dropout; 1 = the models before")
    ap.add_argument("--wd", type=float, default=0.05, help="weight decay (pulls weights to 0 against memorizing)")
    ap.add_argument("--no-mirror", action="store_true",
                    help="don't add the left/right mirrored copy of every training recording")
    ap.add_argument("--stop-file", help="stop (and save) after the epoch in which this file appears (train_gui.py: pause)")
    a = ap.parse_args()
    random.seed(a.seed), torch.manual_seed(a.seed)
    device = "cuda" if torch.cuda.is_available() else "cpu"

    paths = D.find(a.data)
    other = [p for p in paths if p.parent.name not in D.GOALS]  # the label folder (ainpc_datasets/<label>/)
    if other:
        print("skipped, not in a goal folder:", ", ".join(f"{p.parent.name}/{p.name}" for p in other))
        paths = [p for p in paths if p not in other]
    visions = {}  # /ainpc vision may have changed between /ainpc generateTrainingset runs
    for p in paths:
        with D.gzip.open(p, "rt") as fh:
            visions.setdefault(json.dumps(json.loads(fh.readline())["vision"], sort_keys=True), []).append(p)
    if len(visions) > 1:
        raise SystemExit("the datasets were made with different vision settings, run /ainpc generateTrainingset again:\n"
                         + "\n".join(f"  {v}: {len(ps)} files, e.g. {ps[0].parent.name}/{ps[0].name}" for v, ps in visions.items()))
    table = D.find_table(a.data)
    vocabs = D.new_vocabs(table=table)
    feats = D.features(table, vocabs)
    every = 20 // a.rate  # ticks per sample
    src = a.resume or a.shake_from  # the run whose weights we start from
    if src:  # its own size and rate, not --hidden / --rate
        start = "model.pt" if a.shake_from and a.from_best else "latest.pt"
        old = torch.load(Path(src) / start, map_location="cpu", weights_only=False)["cfg"]
        a.hidden, every = old.get("hidden", 512), old.get("every", 1)
        a.embed, a.crosshair, a.arch = old.get("embed", 16), old.get("crosshair", False), old.get("arch", 1)
        a.chunk, a.aux = old.get("chunk", 1), old.get("aux", False)
    t0 = time.time()
    files = D.load(paths, vocabs, every)
    with D.gzip.open(paths[0], "rt") as fh:
        header = json.loads(fh.readline())
    vision = header["vision"]
    train, val = split(files)
    if not a.no_mirror:  # training only: validation stays the real recordings
        train += [(n + " (mirrored)", *D.mirror(x, y, vision["cols"], vision["rows"])) for n, x, y in train]
    K = a.chunk if src else max(1, CHUNK_TICKS // every)
    train, val = chunks(train, K), chunks(val, K)
    kw = key_weights(train)
    print("key weights", " ".join(f"{n} {w:.1f}" for n, w in zip(D.ACTION_NAMES, kw) if w > 1.05))
    key_weight = torch.from_numpy(kw).to(device)
    train, val = to_device(train, device), to_device(val, device)
    packed = pack(train)
    print(f"{len(paths)} recordings, {sum(len(x['vis']) for _, x, _ in files)} samples ({20 // every}/s), loaded in {time.time() - t0:.1f}s; "
          f"train {len(train)} / val {len(val)} on {device}")

    cfg = {"rows": vision["rows"], "cols": vision["cols"], "vision": vision, "hearing": header["hearing"],
           "scalars": files[0][1]["scalars"].shape[-1], "vocab": {k: len(v) for k, v in vocabs.items()},
           "goals": D.GOALS, "hidden": a.hidden, "embed": a.embed if src else embed(a.hidden), "every": every,
           "crosshair": a.crosshair if src else True, "arch": a.arch,
           "chunk": a.chunk if src else max(1, CHUNK_TICKS // every), "aux": a.aux if src else True,
           "key_weight": [round(float(w), 3) for w in kw], "press_samples": press_samples(files)}
    torch.backends.cudnn.benchmark = True  # fastest convolution method for these shapes
    model = Policy(cfg, feats).to(device)
    vis_part = model.conv if model.arch >= 2 else model.cnn
    print(f"model size {cfg['hidden']}, arch {model.arch}: {sum(p.numel() for p in model.parameters()) / 1e6:.2f}M parameters, "
          f"vision CNN {sum(p.numel() for p in vis_part.parameters()) / 1e3:.1f}K", flush=True)
    # fused: one GPU job, not hundreds; capturable: may run inside the CUDA graph (train_step)
    gpu = device == "cuda"
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=a.wd, fused=gpu, capturable=gpu)
    best, first = None, 1
    if src:  # weights and optimizer from latest.pt; new recordings in the data are fine
        out_dir = Path(src)
        ck = torch.load(out_dir / start, map_location=device, weights_only=False)  # model.pt: no optimizer state
        try:
            model.load_state_dict(ck.get("raw", ck["state"]))  # runs before the averaging: only "state"
        except RuntimeError as e:  # features.json or the model changed since that run
            raise SystemExit(f"cannot continue {out_dir.name}, the model inputs changed; start a new run\n{e}")
        if "opt" in ck:
            opt.load_state_dict(ck["opt"])
            for g in opt.param_groups:  # runs saved before the CUDA graph had other settings
                g.update(fused=gpu, capturable=gpu, weight_decay=a.wd)
            for prm, s in opt.state.items():  # step on the GPU; moments in the weight's layout (CNN: channels_last)
                s["step"] = s["step"].to(device)
                for k in ("exp_avg", "exp_avg_sq"):
                    s[k] = torch.empty_like(prm).copy_(s[k])
        first = ck["epoch"] + 1
        report = out_dir / "report.json"
        if a.resume and report.exists():  # its best only counts if it was scored on the same recordings
            b = json.loads(report.read_text())
            names = lambda ns: [Path(n).name for n in ns]  # by file name: the folder may be spelled differently
            best = b if names(b.get("validation", [])) == names(n for n, _, _ in val) else None
        if a.resume:
            print(f"continuing {out_dir.name} at epoch {first}" + ("" if best else " (validation set changed: best starts over)"))
    if not a.resume:
        out_dir = new_run_dir()
    if a.shake_from:
        with torch.no_grad():
            for prm in model.parameters():
                spread = prm.std() if prm.numel() > 1 else prm.abs().mean()
                hit = torch.rand_like(prm) < 0.5
                prm.add_(hit * torch.randn_like(prm) * spread * a.shake)
        cfg["from"] = f"{Path(a.shake_from).name} {'best ' if a.from_best else ''}epoch {first - 1}, shake {a.shake}"
        print(f"shaken copy of {cfg['from']}", flush=True)
    # averaged weights (EMA): after each step they move EMA_DECAY of the way toward the trained ones. They
    # smooth out the last steps' random swings and usually do better on new situations; scored, saved
    # and used by the NPC. A continued run goes on with both.
    ema = copy.deepcopy(model).requires_grad_(False)
    if a.resume and "raw" in ck:
        ema.load_state_dict(ck["state"])
    ema_p, model_p = list(ema.parameters()), list(model.parameters())
    ema_steps = 1000 if a.resume else 0  # a new run: short average at first (else the start weights linger)
    print("run", out_dir, flush=True)

    def save(name, epoch, loss):
        # the feature tables are buffers in the state, so the model needs no features.json at inference;
        # via a temp file: serve.py may reload at any moment and must never read half a file
        torch.save({"state": ema.state_dict(), "cfg": cfg, "epoch": epoch, "val_loss": loss,
                    "vocab": {k: v.ids for k, v in vocabs.items()},
                    **({"opt": opt.state_dict(), "raw": model.state_dict()} if name == "latest.pt" else {})},
                   out_dir / "save.tmp")
        (out_dir / "save.tmp").replace(out_dir / name)

    if a.copy_only:
        save("latest.pt", first - 1, None)
        print("copy saved", flush=True)
        return

    items = windows(train, WINDOW)
    bs = min(a.batch, len(items))
    steps = len(items) // bs  # whole batches only (CUDA graph: fixed shape)
    ahead = max(1, D.SEE_TICKS // every) if model.arch >= 4 else 0
    step = train_step(model, opt, batch(packed, items[:bs], device, ahead), key_weight)
    saved_at, done, saved = 0.0, None, None  # done: (epoch, val loss) of the last finished epoch
    print(f"training, {steps} batches per epoch: first epoch running...", flush=True)
    try:
        for epoch in range(first, a.epochs + 1) if a.epochs else itertools.count(first):
            random.shuffle(items)
            parts = {}
            for i in range(0, steps * bs, bs):
                p = step(*batch(packed, items[i:i + bs], device, ahead))
                ema_steps += 1
                with torch.no_grad():
                    torch._foreach_lerp_(ema_p, model_p, 1 - min(EMA_DECAY, (1 + ema_steps) / (10 + ema_steps)))
                for k in p:
                    parts[k] = parts.get(k, 0.0) + p[k] / steps
            r = evaluate(ema, val, device)
            done = epoch, r["loss"]
            if time.time() - saved_at >= LATEST_EVERY_S:  # a save (~77 MB) costs as much as a short epoch
                save("latest.pt", *done)
                saved, saved_at = epoch, time.time()
            mark = ""
            if best is None or r["loss"] < best["loss"]:
                best = dict(r, epoch=epoch, hidden=cfg["hidden"], rate=20 // every, origin=cfg.get("from"),
                            recordings=[str(p) for p in paths], validation=[n for n, _, _ in val])
                save("model.pt", epoch, r["loss"])
                (out_dir / "report.json").write_text(json.dumps(best, indent=1))
                mark = " * best"
            print(f"epoch {epoch:3d} train keys {parts['keys']:.3f} look {parts['look']:.3f} hold {parts['hold']:.3f}"
                  f" | val loss {r['loss']:.3f} look acc {r['look']['accuracy']:.2f} hold acc {r['hold_accuracy']:.2f}"
                  + (f" side {parts['aux']:.3f}" if model.aux is not None else "")
                  + (f" offset {parts['off']:.3f} see {parts['see']:.3f}" if model.arch >= 4 else "") + mark,
                  flush=True)
            if a.stop_file and Path(a.stop_file).exists():
                print("paused", flush=True)
                break
    except KeyboardInterrupt:
        print("\nstopped")
    if done and done[0] != saved:  # the end state, so a continued run goes on from here
        # ponytail: after Ctrl+C mid-epoch the weights include part of the next epoch; labelled the finished one
        save("latest.pt", *done)
    if best is None:
        return
    print(f"\nbest epoch {best['epoch']} (val loss {best['loss']:.3f}) -> {out_dir}")
    print(f"{'action':12s} {'rate':>6s} {'precision':>9s} {'recall':>7s}")
    for k, m in best["keys"].items():
        print(f"{k:12s} {m['rate']:6.3f} {str(m['precision']):>9s} {str(m['recall']):>7s}")
    print("look:", best["look"], "| hold accuracy:", best["hold_accuracy"])
    if best.get("side_tasks"):
        print("side tasks:", best["side_tasks"])


if __name__ == "__main__":
    main()
