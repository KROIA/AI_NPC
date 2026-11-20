"""Training control window (opened by train.bat): pause, continue, new runs, shaken copies, with the loss curves.

    python training/train_gui.py <dataset folders...> [train.py options]

Pause: every running train.py stops after its current epoch and saves latest.pt (via a stop file).
Continue: the selected run goes on from its latest.pt, with the datasets loaded again, so new recordings count.
New run: starts from scratch, "runs at once" times with different random starts, side by side in the chart.
Shake: copies of the selected run with random noise on the weights, trained on ("runs at once" copies, each
    with other noise), to see whether a jolt gets it somewhere better.
CreateChilds (paused): keeps the selected run, deletes the other runs of the group, and makes "runs at once" - 1
    shaken copies of its best epoch (model.pt); Continue then trains the selected run and its children side by side.
Continue with a run of the group selected: the whole group goes on, else only the selected run.
Use for NPC: serve.py (and /ainpc npc reload) take the selected run instead of the newest one.
"""
import json
import queue
import shutil
import random
import re
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, scrolledtext

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"
STOP = RUNS / "STOP"
NPC_RUN = RUNS / "npc_run.txt"  # read by serve.py
DATA_FILE = RUNS / "gui_data.txt"  # the last training data folder chosen in the window
RUN_NAME = re.compile(r"\d{8}-\d{6}(-\d+)?")  # train.py's run folders; nothing else in runs/ is ever deleted
SIZES = ["1024 (11M)", "768 (6.7M)", "512 (3.3M)", "384 (2.0M)", "256 (1.1M)", "192 (0.72M)", "128 (0.43M)",
         "96 (0.29M)", "64 (0.17M)", "48 (0.12M)", "32 (76K)", "24 (60K)", "16 (45K)", "8 (31K)"]
RATES = ["20/s", "10/s", "5/s"]  # samples (NPC decisions) per second, train.py --rate
COUNTS = ["1", "2", "3", "4", "6", "8"]
SHAKES = ["5%", "10%", "20%", "50%", "100%"]
COLORS = ["#d03030", "#3070d0", "#20a040", "#c08000", "#9040c0", "#00a0a0", "#e06090", "#606060"]
EPOCH = re.compile(r"epoch\s+(\d+) train keys ([\d.]+) look ([\d.]+) hold ([\d.]+) \| val loss ([\d.]+)")


def runs():
    """Runs with a latest.pt, newest first."""
    return sorted((d for d in RUNS.glob("*") if (d / "latest.pt").exists()), key=lambda d: d.name, reverse=True)


def describe(run):
    """'<name>  size 128, 20/s, best val 1.830 (epoch 85)' from its report.json."""
    try:
        b = json.loads((run / "report.json").read_text())
    except (OSError, ValueError):
        return run.name
    size = f"size {b['hidden']}, {b['rate']}/s, " if "hidden" in b else ""
    origin = f", shaken from {b['origin']}" if b.get("origin") else ""
    return f"{run.name}  {size}best val {b['loss']:.3f} (epoch {b['epoch']}){origin}"


class Job:
    """One train.py process and its loss points (epoch, train, val, best?)."""

    def __init__(self, idx, cmd):
        self.idx, self.cmd, self.proc, self.run, self.points = idx, cmd, None, None, []
        self.color = COLORS[idx % len(COLORS)]

    def label(self):
        return self.run or f"#{self.idx + 1}"


class App:
    def __init__(self, root, args):
        self.root, self.lines, self.jobs = root, queue.Queue(), []
        # the training data folder: the last one chosen here, else the command line's (train.bat: all datasets)
        given = [x for x in args if not x.startswith("-")][:1]
        self.args = [x for x in args if x not in given]  # further train.py options
        root.title("AI NPC training")
        bar = tk.Frame(root)
        bar.pack(fill="x", padx=6, pady=(6, 0))
        self.b_cont = tk.Button(bar, text="Continue", width=10, command=self.cont)
        self.b_new = tk.Button(bar, text="New run", width=10, command=self.new)
        self.b_shake = tk.Button(bar, text="Shake", width=10, command=self.shake)
        self.b_pause = tk.Button(bar, text="Pause", width=10, command=self.pause)
        self.b_child = tk.Button(bar, text="CreateChilds", width=12, command=self.create_childs)
        for b in (self.b_cont, self.b_new, self.b_shake, self.b_pause, self.b_child):
            b.pack(side="left", padx=3)
        self.status = tk.Label(bar, anchor="w")
        self.status.pack(side="left", fill="x", expand=True, padx=8)

        src = tk.Frame(root)
        src.pack(fill="x", padx=6, pady=(4, 0))
        tk.Label(src, text="training data:").pack(side="left")
        last = DATA_FILE.read_text().strip() if DATA_FILE.exists() else ""
        self.data = tk.StringVar(value=last or (given[0] if given else ""))
        tk.Entry(src, textvariable=self.data).pack(side="left", fill="x", expand=True, padx=3)
        tk.Button(src, text="Browse...", command=self.browse).pack(side="left", padx=3)
        tk.Button(src, text="All datasets", command=lambda: self.data.set(given[0]) if given else None).pack(side="left", padx=3)

        opts = tk.Frame(root)
        opts.pack(fill="x", padx=6, pady=(4, 0))
        # a new run's size and samples per second (train.py --hidden, --rate); continued and shaken runs keep theirs
        self.size = self.menu(opts, "new run size:", SIZES, SIZES[6])
        self.rate = self.menu(opts, "samples:", RATES, RATES[0])
        self.count = self.menu(opts, "runs at once:", COUNTS, COUNTS[0])  # New run / Shake: this many in parallel
        self.strength = self.menu(opts, "shake strength:", SHAKES, SHAKES[2])

        sel = tk.Frame(root)
        sel.pack(fill="x", padx=6, pady=(4, 0))
        tk.Label(sel, text="run:").pack(side="left")
        self.selected = tk.StringVar()
        self.run_menu = tk.OptionMenu(sel, self.selected, "")
        self.run_menu.pack(side="left", fill="x", expand=True)
        tk.Button(sel, text="Use for NPC", command=self.use_for_npc).pack(side="left", padx=3)
        tk.Button(sel, text="Best for NPC", command=self.best_for_npc).pack(side="left", padx=3)
        tk.Button(sel, text="NPC: newest", command=self.npc_newest).pack(side="left", padx=3)
        self.npc = tk.Label(sel, anchor="w")
        self.npc.pack(side="left", padx=6)

        self.chart = tk.Canvas(root, height=240, bg="white")
        self.chart.pack(fill="x", padx=6, pady=(6, 0))
        self.chart.bind("<Configure>", lambda e: self.draw())
        self.log = scrolledtext.ScrolledText(root, height=16, font=("Consolas", 9))
        self.log.pack(fill="both", expand=True, padx=6, pady=6)
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.idle()
        self.poll()

    @staticmethod
    def menu(parent, text, values, default):
        tk.Label(parent, text=text).pack(side="left", padx=(8, 2))
        var = tk.StringVar(value=default)
        tk.OptionMenu(parent, var, *values).pack(side="left")
        return var

    # --- run list and NPC choice ---
    def refresh_runs(self):
        names = [describe(r) for r in runs()]
        m = self.run_menu["menu"]
        m.delete(0, "end")
        for n in names:
            m.add_command(label=n, command=lambda v=n: self.selected.set(v))
        cur = self.selected_run()
        self.selected.set(next((n for n in names if n.split()[0] == cur), names[0] if names else ""))
        chosen = self.npc_run()
        self.npc.config(text=f"NPC uses: {chosen}" if chosen else "NPC uses: the newest run")
        self.draw()

    @staticmethod
    def npc_run():
        """The npc_run.txt entry: "<run>" (its latest epoch) or "<run>/model.pt" (its best epoch), None = newest."""
        return NPC_RUN.read_text().strip() if NPC_RUN.exists() else None

    def selected_run(self):
        v = self.selected.get().split()
        return v[0] if v else None

    def use_for_npc(self):
        if self.selected_run():
            NPC_RUN.write_text(self.selected_run())
            self.refresh_runs()

    def best_for_npc(self):
        """The lowest validation loss of all epochs of the runs started together last (or of all runs): its model.pt.
        ponytail: compares val loss only, which is fair for runs on the same recordings and samples per second."""
        names = [j.run for j in self.jobs if j.run] or [r.name for r in runs()]
        scored = []
        for n in names:
            try:
                scored.append((json.loads((RUNS / n / "report.json").read_text())["loss"], n))
            except (OSError, ValueError, KeyError):
                pass
        if not scored:
            self.status.config(text="No run with a best epoch yet")
            return
        loss, name = min(scored)
        NPC_RUN.write_text(f"{name}/model.pt")
        self.refresh_runs()
        self.status.config(text=f"NPC uses the best epoch of {name} (val {loss:.3f}); in game: /ainpc npc reload")

    def npc_newest(self):
        NPC_RUN.unlink(missing_ok=True)
        self.refresh_runs()

    # --- starting and stopping ---
    def idle(self, msg=None):
        self.refresh_runs()
        has = bool(self.selected_run())
        for b in (self.b_cont, self.b_shake, self.b_child):
            b.config(state="normal" if has else "disabled")
        self.b_new.config(state="normal")
        self.b_pause.config(state="disabled", text="Pause")
        self.status.config(text=msg or ("Stopped." if has else "No run yet: New run"))

    def browse(self):
        d = filedialog.askdirectory(initialdir=self.data.get() or None, title="Training data: a dataset folder or one label in it")
        if d:
            self.data.set(str(Path(d)))

    def base(self):
        """train.py with the chosen data folder (a label folder like ainpc_datasets/gather_wood = that task only)."""
        data = self.data.get().strip()
        RUNS.mkdir(exist_ok=True)
        DATA_FILE.write_text(data)
        return [sys.executable, "-u", "-X", "utf8", str(HERE / "train.py"), data, *self.args, "--stop-file", str(STOP)]

    def group(self):
        """Run names of the runs started (or created) together last."""
        return [j.run for j in self.jobs if j.run and (RUNS / j.run / "latest.pt").exists()]

    def cont(self):
        sel = self.selected_run()
        names = self.group() if sel in self.group() else [sel]
        names = [sel] + [n for n in names if n != sel]  # the selected one first (it gets color 1)
        self.launch([self.base() + ["--resume", str(RUNS / n)] for n in names], f"continue {', '.join(names)}")

    def create_childs(self):
        """Keep the selected run, delete the rest of the group, make N-1 shaken copies of its best epoch (no training yet)."""
        sel, n = self.selected_run(), int(self.count.get())
        others = [r for r in self.group() if r != sel]
        if others and not messagebox.askyesno(
                "CreateChilds", f"Keep {sel} and DELETE these runs of the group?\n\n" + "\n".join(others)):
            return
        for r in others:
            shutil.rmtree(RUNS / r)
            if (self.npc_run() or "").split("/")[0] == r:
                NPC_RUN.unlink(missing_ok=True)
        s, seed = int(self.strength.get().rstrip("%")) / 100, random.randrange(10 ** 6)
        cmds = [self.base() + ["--shake-from", str(RUNS / sel), "--from-best", "--shake", str(s), "--copy-only", "--seed", str(seed + i)]
                for i in range(n - 1)]
        if not cmds:
            self.status.config(text="runs at once is 1: no children to create")
            return
        self.parent = sel
        self.launch(cmds, f"{n - 1} children of {sel} ({s:.0%} shake)")

    parent = None  # CreateChilds: the run the children were made from, joins the group when they are saved

    def new(self):
        """Deletes all earlier runs (after asking), except the one the NPC uses, then starts fresh ones."""
        keep = (self.npc_run() or "").split("/")[0]
        old = [d for d in RUNS.iterdir() if d.is_dir() and RUN_NAME.fullmatch(d.name) and d.name != keep]
        if old:
            if not messagebox.askyesno("New run", f"DELETE all {len(old)} earlier run(s) from disk?"
                                       + (f"\n\nKept: {keep} (used by the NPC)" if keep else "")):
                return
            failed = []
            for d in old:
                try:
                    shutil.rmtree(d)
                except OSError:
                    failed.append(d.name)
            if failed:
                messagebox.showwarning("New run", "Could not delete (in use?):\n" + "\n".join(failed))
            self.refresh_runs()
        seed, n = random.randrange(10 ** 6), int(self.count.get())
        opts = ["--hidden", self.size.get().split()[0], "--rate", self.rate.get().split("/")[0]]
        self.launch([self.base() + opts + ["--seed", str(seed + i)] for i in range(n)], f"{n} new run(s)")

    def shake(self):
        seed, n, src = random.randrange(10 ** 6), int(self.count.get()), self.selected_run()
        s = int(self.strength.get().rstrip("%")) / 100
        self.launch([self.base() + ["--shake-from", str(RUNS / src), "--shake", str(s), "--seed", str(seed + i)]
                     for i in range(n)], f"{n} shaken cop{'y' if n == 1 else 'ies'} of {src} ({s:.0%})")

    def launch(self, cmds, what):
        STOP.unlink(missing_ok=True)
        self.jobs = [Job(i, c) for i, c in enumerate(cmds)]
        self.draw()
        self.write(f"\n=== {what} ===\n")
        # the first one alone: it encodes the datasets that are not cached yet, the others then load the cache
        self.start(self.jobs[0])
        for b in (self.b_cont, self.b_new, self.b_shake):
            b.config(state="disabled")
        self.b_pause.config(state="normal")
        self.status.config(text="Loading datasets...")

    def start(self, job):
        job.proc = subprocess.Popen(job.cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                    encoding="utf-8", errors="replace", cwd=HERE.parent)
        threading.Thread(target=self.read, args=(job, job.proc), daemon=True).start()

    def read(self, job, proc):
        for line in proc.stdout:
            self.lines.put((job, line))
        self.lines.put((job, None))  # finished

    def pause(self):
        STOP.touch()
        self.b_pause.config(state="disabled", text="Pausing...")
        self.status.config(text="Pausing after this epoch...")

    closing = False

    def close(self):
        if not any(j.proc for j in self.jobs):
            self.root.destroy()
            return
        self.closing = True  # pause, wait for the saves, then close
        self.pause()

    # --- output ---
    def poll(self):
        while not self.lines.empty():
            job, line = self.lines.get()
            if line is None:
                code = job.proc.wait()
                job.proc = None
                if code:
                    self.write(f"[{job.label()}] train.py exited with code {code}\n")
                if any(j.proc for j in self.jobs):
                    continue
                STOP.unlink(missing_ok=True)
                msg = None if code == 0 else f"train.py exited with code {code}, see the log"
                if self.parent:  # CreateChilds done: the group is the parent and its children
                    kids = [j.run for j in self.jobs if j.run]
                    self.jobs = [Job(i, None) for i in range(1 + len(kids))]
                    for j, r in zip(self.jobs, [self.parent] + kids):
                        j.run = r
                    self.selected.set(describe(RUNS / self.parent))
                    msg = msg or f"{self.parent} + {len(kids)} children ready: Continue trains them side by side"
                    self.parent = None
                self.idle(msg)
                if self.closing:
                    self.root.destroy()
                    return
                continue
            m = re.match(r"run (.+)", line.strip())
            if m:
                job.run = Path(m[1]).name
                if job.idx == 0 and not STOP.exists():  # datasets cached now: start the others
                    for j in self.jobs[1:]:
                        self.start(j)
            self.write(f"[{job.label()}] {line}" if len(self.jobs) > 1 else line)
            m = EPOCH.search(line)
            if m:
                ep, keys, look, hold, val = int(m[1]), *map(float, m.group(2, 3, 4, 5))
                job.points.append((ep, keys + look + hold, val, "* best" in line))
                self.show_status()
                self.draw()
            elif not any(j.points for j in self.jobs) and line.strip():  # before the first epoch: loading progress
                self.status.config(text=line.strip()[:140])
        self.root.after(100, self.poll)

    def show_status(self):
        parts = []
        for j in self.jobs:
            if j.points:
                best = min(j.points, key=lambda p: p[2])
                parts.append(f"ep {j.points[-1][0]} best {best[2]:.3f}")
        self.status.config(text=" | ".join(parts))

    def write(self, text):
        self.log.insert("end", text)
        self.log.see("end")

    def draw(self):
        c = self.chart
        c.delete("all")
        w, h, pad = c.winfo_width(), c.winfo_height(), 30
        pts = [p for j in self.jobs for p in j.points]
        if len(pts) < 2:
            c.create_text(w / 2, h / 2, text="loss curves: val solid, train dashed, best: dot; one color per run",
                          fill="gray")
            return
        e0, e1 = min(p[0] for p in pts), max(p[0] for p in pts)
        vals = [v for p in pts for v in p[1:3]]
        lo, hi = min(vals), max(vals)
        x = lambda e: pad + (e - e0) / max(e1 - e0, 1) * (w - 2 * pad)
        y = lambda v: h - pad - (v - lo) / max(hi - lo, 1e-9) * (h - 2 * pad)
        npc = (self.npc_run() or "").split("/")[0]
        for j in self.jobs:
            thick = j.run is not None and j.run == npc  # the run the NPC uses
            if len(j.points) >= 2:
                c.create_line(*[q for p in j.points for q in (x(p[0]), y(p[1]))], fill=j.color, dash=(3, 3),
                              width=2 if thick else 1)
                c.create_line(*[q for p in j.points for q in (x(p[0]), y(p[2]))], fill=j.color, width=5 if thick else 2)
            for p in j.points:
                if p[3]:
                    c.create_oval(x(p[0]) - 3, y(p[2]) - 3, x(p[0]) + 3, y(p[2]) + 3, fill=j.color, outline="")
        for k, j in enumerate(self.jobs):  # legend
            if j.points:
                best = min(j.points, key=lambda p: p[2])
                c.create_text(w - pad, 10 + 14 * k, anchor="e", fill=j.color,
                              text=f"{j.label()}  best {best[2]:.3f} @ epoch {best[0]}" + ("  (NPC)" if j.run == npc else ""))
        c.create_text(4, pad, anchor="w", text=f"{hi:.2f}", fill="gray")
        c.create_text(4, h - pad, anchor="w", text=f"{lo:.2f}", fill="gray")
        c.create_text(pad, h - 10, anchor="w", text=f"epoch {e0}", fill="gray")
        c.create_text(w - pad, h - 10, anchor="e", text=f"epoch {e1}", fill="gray")


if __name__ == "__main__":
    root = tk.Tk()
    root.geometry("1000x680")
    App(root, sys.argv[1:])
    root.mainloop()
