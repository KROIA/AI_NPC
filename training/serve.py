"""Drives the NPC with a trained model: the mod sends one observation per tick, this answers with actions.

    python training/serve.py [training/runs/<run>/model.pt] [--port 5571] [--greedy]
    in game: /ainpc npc start [port]

Protocol (localhost TCP, one JSON object per line each way):
  mod -> here: a dataset tick without "actions" (world, rot, hand, inventory, vision, sounds, body)
  here -> mod: {"keys":{forward:..}, "attacks":n, "uses":n, "yaw":deg, "pitch":deg, "hold":item id or null}
  mod -> here: {"type":"reload","which":"latest"|"best"} (/ainpc npc reload [latest|best]): latest.pt (last
               epoch) or model.pt (best validation loss so far) of the newest run (or re-read the given file),
               answered with {"model": "<run>/<file>", "epoch": n, "val_loss": x, "vision": {...}}; the NPC's memory starts fresh
  mod -> here: {"type":"hello"} once on connect, answered with {"vision": {...}}: the NPC then sees like the
               model's training data did (cols, rows, hFov, vFov, projection), whatever /ainpc vision is set to
Default model: latest.pt of the newest run, so a running training can be tried live.
With a model trained at fewer samples per second (train.py --rate) it decides every few ticks and repeats
the decision in between, like its training samples.
The GRU memory runs on from decision to decision and starts fresh with every connection (a newly spawned NPC).
Actions are sampled from the model's probabilities (--greedy: always the most likely), like the player who
sometimes does the less likely thing; greedy policies tend to get stuck repeating one move.
"""
import argparse
import math
import json
import socket
from pathlib import Path

import numpy as np
import torch

import data as D
from train import Policy



def load_model(path, device):
    ck = torch.load(path, map_location=device, weights_only=False)
    st = ck["state"]
    feats = {"block": st["block.block.feats"], "state": st["block.feats"], "state_block": st["block.state_block"]}
    for n in ("item", "entity", "sound", "effect", "damage"):
        feats[n] = st[n + ".feats"]
    model = Policy(ck["cfg"], feats).to(device)
    model.load_state_dict(st)
    model.eval()
    model.epoch = ck.get("epoch")  # None for models saved before epochs were recorded
    model.val_loss = ck.get("val_loss")
    return model, ck["cfg"], D.new_vocabs(saved=ck["vocab"])


class Driver:
    """One NPC: observation in, actions out, memory carried between ticks."""

    def __init__(self, model, cfg, vocabs, device, greedy=False):
        self.model, self.vocabs, self.device, self.greedy = model, vocabs, device, greedy
        self.header = {"vision": cfg["vision"], "hearing": cfg.get("hearing", {"memoryTicks": 20})}
        self.h = None
        self.held = None
        self.every = cfg.get("every", 1)  # ticks per decision (train.py --rate)
        self.tick, self.plan = 0, None
        self.recent = []  # per tick: (velocity x, z, mining, a movement key held), the last STUCK_TICKS
        self.escape, self.escape_dir, self.stuck_count = 0, None, 0
        self.low = torch.tensor([KEY_LOW.get(n, KEY_HIGH) for n in D.ACTION_NAMES])
        self.low[MOUSE], self.low[len(D.KEYS):] = MOUSE_LOW, KEY_HIGH
        self.stop_ticks, self.last_hold = 0, None  # mining: ticks the model wants to stop; the item it mines with
        # press starts as often as the player: see decide(). ponytail: models from before train.py saved these
        # get the gather_wood numbers of 2026-11-18 (weight, press length in ticks per key; clicks unchanged)
        old_w = dict(forward=1.55, back=5, left=4.64, right=4.69, jump=4.88, sneak=5, sprint=5, use=5, drop=5, swapHands=5)
        old_len = dict(forward=13.2, back=4.5, left=5.9, right=5.8, jump=6.0, sneak=66, sprint=3, attack=60.6, use=7.4)
        self.weight = torch.tensor(cfg.get("key_weight") or [old_w.get(n, 1.0) for n in D.ACTION_NAMES])
        self.press = torch.tensor(cfg.get("press_samples")
                                  or [max(1.0, old_len.get(n, 1.0) / self.every) for n in D.ACTION_NAMES]).clamp(min=1)

    def act(self, obs):
        """Every `every` ticks a decision; the ticks in between repeat it: held keys stay, taps and clicks
        only in its first tick, the head turn spread evenly."""
        self.watch(obs)
        if self.tick % self.every == 0:
            self.plan = self.decide(obs)
        self.tick += 1
        keys, attacks, uses, dyaw, dpitch, hold = self.plan
        first = (self.tick - 1) % self.every == 0
        if not first:
            keys = {k: v and k not in ("drop", "swapHands") for k, v in keys.items()}
        yaw, pitch = obs["rot"]
        return {"keys": keys, "attacks": attacks if first else 0, "uses": uses if first else 0,
                "yaw": float(yaw + dyaw / self.every), "pitch": float(np.clip(pitch + dpitch / self.every, -90, 90)),
                "hold": hold}

    def watch(self, obs):
        """Starts an escape when stuck (see STUCK_TICKS)."""
        if self.escape:
            self.escape -= 1
            return
        vx, _, vz = obs.get("body", {}).get("velocity", (0, 0, 0))
        moving = self.held is not None and bool(self.held[MOVE_KEYS].any())
        self.recent = (self.recent + [(vx, vz, obs.get("breaking", 0) > 0, moving)])[-STUCK_TICKS:]
        r = np.array(self.recent, np.float32)
        if len(r) == STUCK_TICKS and np.hypot(*r[:, :2].sum(0)) < STUCK_MOVE and not r[:, 2].any() and r[:, 3].mean() > 0.5:
            self.escape, self.escape_dir, self.recent = ESCAPE_TICKS, None, []
            self.held[ATTACK] = False  # the escape starts with attack up (not mining, see above)
            self.stuck_count += 1
            print(f"stuck ({self.stuck_count}x, {self.stuck_count * 1200 / max(1, self.tick):.2f}/min): trying something else", flush=True)

    @torch.no_grad()
    def decide(self, obs):
        v = self.header["vision"]
        if len(obs["vision"]["d"]) != v["cols"] * v["rows"]:
            raise ValueError(f"this model was trained with {v['cols']}x{v['rows']} rays ({v.get('hFov')}x{v.get('vFov')} deg), "
                             f"the mod sends {len(obs['vision']['d'])}: train a new run on regenerated datasets")
        a = D.encode_ticks(self.header, [obs], self.vocabs)
        x = {k: torch.from_numpy(v[None]).to(self.device) for k, v in a.items() if k not in D.TARGETS and k != "_cols"}
        keys, look, hold, self.h, extra = self.model(x, self.h, side=False)
        p = self.blend(torch.sigmoid(keys[0, 0]))
        # sampled to press, then held while the model is >50% sure: re-sampling every tick would let go of
        # attack now and then, and letting go restarts mining the block.
        # Starting a press: p is "the key is down now", made higher in training for rare keys (key_weights);
        # undone and spread over the press's length, else a 5% key (jump, held 6 ticks) started ~every second
        # instead of every ~7 s like the player.
        real = p / (p + self.weight * (1 - p))
        start = torch.rand_like(p) < real / self.press
        on = (p > KEY_HIGH) if self.greedy or self.held is None else torch.where(self.held, p > self.low, start | (p > KEY_HIGH))
        if self.escape:  # stuck: the less likely keys too, sampled anew each decision
            on = torch.rand_like(p) < torch.sigmoid(torch.logit(real.clamp(1e-4, 1 - 1e-4)) / KEY_TEMP)
            on[ATTACK] |= bool(self.held[ATTACK])  # attack once pressed held to the end: else the leaf in the way never breaks
        cracking = self.held is not None and bool(self.held[ATTACK]) and obs.get("breaking", 0) > 0
        self.stop_ticks = self.stop_ticks + self.every if cracking and p[ATTACK] < MINE_STOP else 0
        mining = cracking and self.stop_ticks < MINE_STOP_TICKS
        if mining:
            on[ATTACK] = True
        elif cracking:
            on[ATTACK] = False
        self.held = on
        if look.dim() == 3:  # arch 2: the ray to look at (the most likely: sampling would make the head jitter)
            off = extra["aim_off"][0, 0].tolist() if "aim_off" in extra else (0.0, 0.0)  # arch 4: within the ray
            pick, R = int(look[0, 0].argmax()), len(obs["vision"]["d"])
            if self.escape and self.escape_dir is None:  # stuck: another aim than the favourite, held to the end
                other = torch.distributions.Categorical(logits=look[0, 0].index_fill(0, torch.tensor([pick]), -1e9)).sample()
                pick = int(other)
                self.escape_dir = (D.obj_dirs(a["scalars"][0], a["obj"][0])[pick - R] if pick >= R else
                                   self.ray_dir(obs, pick, (0.0, 0.0)))
            if self.escape:
                dyaw, dpitch = self.turn(obs, self.escape_dir)
            elif pick >= R:  # arch 6: a recognised object (e.g. a tree behind): turn toward it
                dyaw, dpitch = self.turn(obs, D.obj_dirs(a["scalars"][0], a["obj"][0])[pick - R])
            else:
                dyaw, dpitch = self.aim(obs, pick, off)
        else:
            lb = look[0, 0].argmax(-1) if self.greedy else torch.distributions.Categorical(logits=look[0, 0]).sample()
            dyaw, dpitch = D.LOOK_BINS[int(lb[0])], D.LOOK_BINS[int(lb[1])]
        if mining:
            dyaw = dpitch = 0.0
        # holding is not sampled: no random switching
        if hold.shape[-1] == D.MAX_INV + 1:  # arch 3: an entry of the inventory list it saw (encode_ticks' order)
            j, inv = int(hold[0, 0].argmax()), list(obs.get("inventory", {}).get("items", {}))[:D.MAX_INV]
            hold_id = inv[j] if j < len(inv) else None
        else:
            hold_id = self.vocabs["item"].ids[int(hold[0, 0].argmax())]
        hold_id = self.last_hold if mining else hold_id if hold_id != "<unk>" else None
        self.last_hold = hold_id
        on = on.tolist()
        return ({k: bool(on[i]) for i, k in enumerate(D.KEYS)}, int(on[len(D.KEYS)]), int(on[len(D.KEYS) + 1]),
                dyaw, dpitch, hold_id)

    def blend(self, plan):
        """Keys from the plans (K decisions each) of the last K decisions: each one's step for now, the newest
        counting most (weight halves per decision of age). Smoother and more purposeful than one decision's
        guess; a model without plans (K = 1) just uses its own."""
        self.plans = ([plan] + getattr(self, "plans", []))[:len(plan)]
        w = torch.tensor([0.5 ** age for age in range(len(self.plans))])
        return sum(wi * pl[age] for age, (wi, pl) in enumerate(zip(w, self.plans))) / w.sum()

    def aim(self, obs, ray, off=(0.0, 0.0)):
        """Head turn for this decision toward the chosen ray (+ off: right, up within its cell in ray steps):
        trained as "where the player looks AIM_TICKS later", so this decision covers every / AIM_TICKS of the
        way (re-aimed at every decision)."""
        return self.turn(obs, self.ray_dir(obs, ray, off))

    def ray_dir(self, obs, ray, off):
        v = obs["vision"]
        d, right, up = D.cell_axes(np.stack([v["dx"], v["dy"], v["dz"]], -1)[None], np.array([ray]), self.header["vision"]["cols"])
        return (d + off[0] * right + off[1] * up)[0]

    def turn(self, obs, direction):
        """Head turn for this decision toward a world direction (see aim)."""
        dx, dy, dz = direction / np.linalg.norm(direction)
        yaw, pitch = obs["rot"]
        to_yaw = math.degrees(math.atan2(-dx, dz))  # Minecraft: look vector (-sin yaw, ..., cos yaw)
        to_pitch = math.degrees(-math.asin(max(-1.0, min(1.0, dy))))
        k = min(1.0, self.every / D.AIM_TICKS)
        return D.wrap(to_yaw - yaw) * k, (to_pitch - pitch) * k


# Stuck (e.g. jumping against a wall forever): a movement key held most of STUCK_TICKS, moved less than
# STUCK_MOVE blocks in that time and not mining. Then for ESCAPE_TICKS the NPC tries the model's other ideas:
# it turns toward a sampled aim other than its favourite (kept fixed, else the head jitters) and samples its
# keys flattened by KEY_TEMP (a 5% key gets ~19%, a 95% one ~81%). Still stuck after: again, with a new sample.
STUCK_TICKS, STUCK_MOVE, ESCAPE_TICKS, KEY_TEMP = 60, 1.0, 40, 2.0
MOVE_KEYS = [D.KEYS.index(k) for k in ("forward", "back", "left", "right")]
ATTACK = D.KEYS.index("attack")
# Schmitt trigger per key: down when the model is above KEY_HIGH (or sampled, see decide), up again only below its
# KEY_LOW (default KEY_HIGH: no band). Measured open loop on gather_wood (2026-11-18): left/right were let go after
# ~2 ticks (player ~8), with 0.3 ~5; forward with any band held far longer than the player, so none. Mouse buttons:
# letting go of attack for one tick restarts the crack. Clicks are one-shot. Attack also stays down while the block cracks.
KEY_HIGH, MOUSE_LOW = 0.5, 0.2
KEY_LOW = {"left": 0.3, "right": 0.3}
MOUSE = [ATTACK, D.KEYS.index("use")]
# while a block cracks the head stays still and the item in hand stays (either change restarts the crack).
# Stopping on purpose (a mob, the wrong block): the model below MINE_STOP for MINE_STOP_TICKS in a row lets go.
MINE_STOP, MINE_STOP_TICKS = 0.1, 5


NPC_RUN = Path(__file__).parent / "runs" / "npc_run.txt"  # train_gui.py: "<run>" (Use for NPC) or "<run>/model.pt" (Best for NPC)


def newest_model(which="latest"):
    """latest.pt (last finished epoch) or model.pt (best so far) of the run chosen in train_gui.py ("Use for NPC"),
    else of the newest run; older runs only have model.pt."""
    runs = sorted((d for d in (Path(__file__).parent / "runs").glob("*") if d.is_dir()), reverse=True)
    chosen = NPC_RUN.read_text().strip() if NPC_RUN.exists() else None
    if chosen and "/" in chosen and (NPC_RUN.parent / chosen).exists():  # a fixed file: for reload latest and best
        return NPC_RUN.parent / chosen
    runs = [r for r in runs if r.name == chosen] + runs if chosen else runs
    for run in runs:
        for name in (("model.pt",) if which == "best" else ("latest.pt", "model.pt")):
            if (run / name).exists():
                return run / name
    raise SystemExit("no trained model under training/runs (run train.py first)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("model", nargs="?", help="model.pt (default: the newest run)")
    ap.add_argument("--port", type=int, default=5571)
    ap.add_argument("--greedy", action="store_true")
    ap.add_argument("--device", choices=["cpu", "cuda"], default="cpu",
                    help="cpu (default): one decision per tick is fast there and doesn't wait for a GPU busy training")
    a = ap.parse_args()
    device = a.device if torch.cuda.is_available() else "cpu"
    path = Path(a.model) if a.model else newest_model()
    loaded = load_model(path, device)
    srv = socket.create_server(("127.0.0.1", a.port))
    print(f"serving {path} (epoch {loaded[0].epoch}, {20 // loaded[1].get('every', 1)} decisions/s) on 127.0.0.1:{a.port} ({device}); in game: /ainpc npc start {a.port}", flush=True)
    while True:
        conn, _ = srv.accept()
        driver = Driver(*loaded, device, a.greedy)
        print("NPC connected", flush=True)
        ticks = 0
        with conn, conn.makefile("r", encoding="utf-8") as rf, conn.makefile("w", encoding="utf-8") as wf:
            try:
                for line in rf:
                    msg = json.loads(line)
                    if msg.get("type") == "hello":  # the NPC asks which vision the model was trained with
                        wf.write(json.dumps({"vision": loaded[1]["vision"]}) + "\n")
                        wf.flush()
                        continue
                    if msg.get("type") == "reload":
                        path = Path(a.model) if a.model else newest_model(msg.get("which", "latest"))
                        loaded = load_model(path, device)
                        driver = Driver(*loaded, device, a.greedy)
                        print(f"reloaded {path} (epoch {loaded[0].epoch})", flush=True)
                        wf.write(json.dumps({"model": f"{path.parent.name}/{path.name}", "epoch": loaded[0].epoch,
                                             "val_loss": loaded[0].val_loss, "vision": loaded[1]["vision"]}) + "\n")
                        wf.flush()
                        continue
                    wf.write(json.dumps(driver.act(msg)) + "\n")
                    wf.flush()
                    ticks += 1
            except (ConnectionError, OSError) as e:
                print("connection lost:", e)
        print(f"NPC disconnected after {ticks} ticks, stuck {driver.stuck_count}x", flush=True)


if __name__ == "__main__":
    main()
