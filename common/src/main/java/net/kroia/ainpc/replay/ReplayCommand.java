package net.kroia.ainpc.replay;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.kroia.ainpc.AiNpc;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * /ainpc generateTrainingset [all] [filter]: turns the recordings under &lt;server dir&gt;/ainpc_recordings whose path
 * contains the filter into datasets under &lt;server dir&gt;/ainpc_datasets (same relative path). Without "all" only
 * recordings that have no dataset yet; "all" regenerates (overwrites) every one, e.g. after /ainpc vision.
 * /ainpc generateTrainingset stop: stops after the current recording.
 * Runs on a background thread; the server keeps ticking.
 */
public final class ReplayCommand {
    private ReplayCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ainpc").requires(s -> s.hasPermission(2))
                .then(Commands.literal("npc") // driven by a trained model: python training/serve.py
                        .then(Commands.literal("start")
                                .executes(ctx -> net.kroia.ainpc.npc.Brain.start(ctx.getSource(), net.kroia.ainpc.npc.Brain.DEFAULT_PORT))
                                .then(Commands.argument("port", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 65535))
                                        .executes(ctx -> net.kroia.ainpc.npc.Brain.start(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "port")))))
                        .then(Commands.literal("reload").executes(ctx -> net.kroia.ainpc.npc.Brain.reloadCommand(ctx.getSource(), "latest"))
                                .then(Commands.literal("latest").executes(ctx -> net.kroia.ainpc.npc.Brain.reloadCommand(ctx.getSource(), "latest")))
                                .then(Commands.literal("best").executes(ctx -> net.kroia.ainpc.npc.Brain.reloadCommand(ctx.getSource(), "best"))))
                        .then(Commands.literal("goal").executes(ctx -> net.kroia.ainpc.npc.Brain.goalCommand(ctx.getSource(), null))
                                .then(Commands.argument("goal", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(RecordingFormat.LABELS, b))
                                        .executes(ctx -> net.kroia.ainpc.npc.Brain.goalCommand(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "goal")))))
                        .then(Commands.literal("stop").executes(ctx -> net.kroia.ainpc.npc.Brain.stopCommand(ctx.getSource()))))
                .then(visionCommand())
                .then(Commands.literal("generateTrainingset")
                        .executes(ctx -> start(ctx.getSource(), "", false))
                        .then(Commands.literal("stop").executes(ctx -> stopGenerating(ctx.getSource())))
                        .then(Commands.literal("all").executes(ctx -> start(ctx.getSource(), "", true))
                                .then(Commands.argument("filter", StringArgumentType.greedyString())
                                        .executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "filter"), true))))
                        .then(Commands.argument("filter", StringArgumentType.greedyString())
                                .executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "filter"), false))))
                .then(Commands.literal("playback")
                        .then(Commands.literal("load")
                                .executes(ctx -> load(ctx.getSource(), "")) // no name: the newest recording
                                .then(Commands.argument("recording", StringArgumentType.greedyString())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(names(ctx.getSource()), b))
                                        .executes(ctx -> load(ctx.getSource(), StringArgumentType.getString(ctx, "recording")))))
                        .then(Commands.literal("start")
                                .executes(ctx -> Theater.startCommand(ctx.getSource(), null))
                                .then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.05, 20))
                                        .executes(ctx -> Theater.startCommand(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "speed")))))
                        .then(Commands.literal("pause").executes(ctx -> Theater.pauseCommand(ctx.getSource())))
                        .then(Commands.literal("stop").executes(ctx -> Theater.stopCommand(ctx.getSource())))
                        .then(Commands.literal("setting")
                                .then(Commands.literal("npc")
                                        .then(Commands.literal("on").executes(ctx -> Theater.npcCommand(ctx.getSource(), true)))
                                        .then(Commands.literal("off").executes(ctx -> Theater.npcCommand(ctx.getSource(), false))))
                                .then(Commands.literal("reference")
                                        .then(Commands.literal("on").executes(ctx -> Theater.referenceCommand(ctx.getSource(), true)))
                                        .then(Commands.literal("off").executes(ctx -> Theater.referenceCommand(ctx.getSource(), false))))
                                .then(Commands.literal("loop")
                                        .then(Commands.literal("on").executes(ctx -> Theater.loopCommand(ctx.getSource(), true)))
                                        .then(Commands.literal("off").executes(ctx -> Theater.loopCommand(ctx.getSource(), false))))
                                .then(Commands.literal("speed").then(Commands.argument("speed", DoubleArgumentType.doubleArg(0.05, 20))
                                        .executes(ctx -> Theater.speedCommand(ctx.getSource(), DoubleArgumentType.getDouble(ctx, "speed"))))))));
    }

    /** /ainpc vision [reset | &lt;setting&gt; &lt;value&gt;], see VisionSetting. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> visionCommand() {
        var vision = Commands.literal("vision").executes(ctx -> VisionSetting.show(ctx.getSource()))
                .then(Commands.literal("reset").executes(ctx -> VisionSetting.reset(ctx.getSource())));
        VisionSetting.SETTINGS.forEach((name, lim) -> vision.then(Commands.literal(name)
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(lim[0], lim[1]))
                        .executes(ctx -> VisionSetting.set(ctx.getSource(), name, DoubleArgumentType.getDouble(ctx, "value"))))));
        return vision;
    }

    private static Path recordingsDir(CommandSourceStack src) {
        return src.getServer().getServerDirectory().resolve("ainpc_recordings");
    }

    /** Recording names for suggestions: path below ainpc_recordings without ".jsonl.gz", e.g. gather_wood/20261117-130500. */
    private static List<String> names(CommandSourceStack src) {
        Path dir = recordingsDir(src);
        try (Stream<Path> s = Files.isDirectory(dir) ? Files.walk(dir) : Stream.empty()) {
            return s.filter(p -> p.toString().endsWith(".jsonl.gz")).map(p -> name(dir, p)).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String name(Path dir, Path file) {
        String rel = dir.relativize(file).toString().replace('\\', '/');
        return rel.substring(0, rel.length() - ".jsonl.gz".length());
    }

    /** Loads the recording with exactly this name, otherwise the newest one whose name contains it. */
    private static int load(CommandSourceStack src, String filter) {
        Path dir = recordingsDir(src);
        List<Path> files = find(src, dir, filter);
        if (files == null) return 0;
        Path f = files.stream().filter(p -> name(dir, p).equals(filter)).findFirst()
                .orElseGet(() -> files.stream().max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified())).orElseThrow());
        return Theater.load(src, f, name(dir, f));
    }

    /** Recordings whose relative path contains the filter, sorted (oldest first); null after reporting a failure. */
    private static List<Path> find(CommandSourceStack src, Path recordings, String filter) {
        List<Path> files;
        try (Stream<Path> s = Files.isDirectory(recordings) ? Files.walk(recordings) : Stream.empty()) {
            files = s.filter(p -> p.toString().endsWith(".jsonl.gz"))
                    .filter(p -> recordings.relativize(p).toString().replace('\\', '/').contains(filter))
                    .sorted().toList();
        } catch (IOException e) {
            src.sendFailure(Component.literal("Cannot list " + recordings + ": " + e.getMessage()));
            return null;
        }
        if (files.isEmpty()) {
            src.sendFailure(Component.literal("No recordings matching '" + filter + "' in " + recordings.toAbsolutePath()));
            return null;
        }
        return files;
    }

    /** The running generation, null when none; stop flag for /ainpc generateTrainingset stop. */
    private static volatile Thread generating;
    private static volatile boolean stopRequested;

    private static int stopGenerating(CommandSourceStack src) {
        if (generating == null) {
            src.sendFailure(Component.literal("No training set generation running"));
            return 0;
        }
        stopRequested = true;
        src.sendSuccess(() -> Component.literal("Stopping after the current recording..."), true);
        return 1;
    }

    private static int start(CommandSourceStack src, String filter, boolean all) {
        if (generating != null) {
            src.sendFailure(Component.literal("Training set generation already running (/ainpc generateTrainingset stop)"));
            return 0;
        }
        MinecraftServer server = src.getServer();
        Path recordings = server.getServerDirectory().resolve("ainpc_recordings");
        Path datasets = server.getServerDirectory().resolve("ainpc_datasets");
        List<Path> found = find(src, recordings, filter);
        if (found == null) return 0;
        List<Path> files = all ? found : found.stream().filter(f -> !Files.exists(datasets.resolve(recordings.relativize(f)))).toList();
        int skipped = found.size() - files.size();
        if (files.isEmpty()) {
            src.sendSuccess(() -> Component.literal("All " + skipped + " recording(s) already have a training set. "
                    + "Regenerate them (e.g. after /ainpc vision): /ainpc generateTrainingset all"), true);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("Generating " + files.size() + " training set(s)"
                + (skipped > 0 ? ", " + skipped + " already generated (all: /ainpc generateTrainingset all)" : "")
                + "... Stop: /ainpc generateTrainingset stop"), true);

        try { // the trainer's block/item lookup; needs this server's tags, so written on the server thread
            FeatureTable.write(datasets.resolve("features.json"), server.overworld());
        } catch (IOException e) {
            AiNpc.LOGGER.error("Writing features.json failed", e);
            src.sendFailure(Component.literal("Writing features.json failed: " + e));
        }
        stopRequested = false;
        Thread worker = new Thread(() -> {
            int done = 0, failed = 0;
            for (Path f : files) {
                if (stopRequested) break;
                Path rel = recordings.relativize(f);
                String msg;
                try {
                    long t0 = System.nanoTime();
                    // via a temp file: a half-written dataset (error, server stop) must not count as generated
                    Path out = datasets.resolve(rel), tmp = out.resolveSibling(out.getFileName() + ".part");
                    Replayer.Result r = Replayer.replay(f, tmp);
                    Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    done++;
                    double secs = (System.nanoTime() - t0) / 1e9;
                    msg = String.format("%s: %d ticks in %.1fs (%.0fx realtime), vision check %d/%d ok%s%s", rel,
                            r.ticks(), secs, r.ticks() / 20.0 / Math.max(secs, 1e-9),
                            r.checked() - r.mismatches(), r.checked(),
                            r.firstMismatchTick() >= 0 ? " (first mismatch at tick " + r.firstMismatchTick() + ")" : "",
                            r.complete() ? "" : ", recording was cut off");
                    AiNpc.LOGGER.info("Replay {}", msg);
                } catch (Exception e) {
                    AiNpc.LOGGER.error("Replay of {} failed", f, e);
                    msg = rel + ": FAILED " + e;
                    failed++;
                }
                String m = msg;
                server.execute(() -> src.sendSuccess(() -> Component.literal(m), true));
            }
            if (!RecordingFormat.UNKNOWN_STATES.isEmpty()) {
                String m = "Unknown block states replayed as air: " + RecordingFormat.UNKNOWN_STATES;
                AiNpc.LOGGER.warn(m);
                server.execute(() -> src.sendFailure(Component.literal(m)));
            }
            String end = (stopRequested ? "Generation stopped: " : "Generation done: ") + done + " training set(s) generated"
                    + (failed > 0 ? ", " + failed + " failed (see above)" : "")
                    + (stopRequested ? ", " + (files.size() - done - failed) + " left (run /ainpc generateTrainingset to continue)" : "");
            AiNpc.LOGGER.info(end);
            generating = null;
            server.execute(() -> src.sendSuccess(() -> Component.literal(end), true));
        }, "ainpc-replay");
        worker.setDaemon(true);
        generating = worker;
        worker.start();
        return files.size();
    }
}
