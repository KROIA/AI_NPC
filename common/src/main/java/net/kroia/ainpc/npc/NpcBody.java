package net.kroia.ainpc.npc;

import com.google.gson.JsonArray;
import com.mojang.authlib.GameProfile;
import net.kroia.ainpc.replay.RecordingFormat;
import net.kroia.ainpc.sensor.PlayerState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The NPC's body: a server-side player without a client, moved only by {@link Actions}, one call per tick.
 * It does what the vanilla client does with the same keys and clicks (input to movement, sprint rules,
 * crosshair pick, mining progress, item use), so a recording's actions should reproduce the recorded path.
 */
public final class NpcBody {
    private static final UUID ID = UUID.nameUUIDFromBytes("ainpc:npc".getBytes());
    /** Entities with this tag are invisible to the NPC's crosshair (the recording's ghost). */
    public static final String IGNORE_TAG = "ainpc_npc_ignore";

    public final ServerPlayer player;
    private ServerLevel level; // follows the player, e.g. after /tp into another dimension
    private Actions last;
    private int missTime, sequence;
    // Client-side sprint state (LocalPlayer): double-tap timer and last tick's inputs
    private int sprintTriggerTime;
    private boolean lastSneak, lastImpulse;
    // Client-side mining state (MultiPlayerGameMode)
    private boolean destroying;

    public boolean destroying() {
        return destroying;
    }

    private BlockPos destroyPos = BlockPos.ZERO;
    private ItemStack destroyItem = ItemStack.EMPTY;
    private float destroyProgress;

    /** Block breaking progress 0..1 like the recorder's "breaking" (0 when not mining). */
    public float breaking() {
        return destroying ? destroyProgress : 0;
    }
    private int destroyDelay;

    private NpcBody(ServerPlayer player) {
        this.player = player;
        this.level = player.serverLevel();
    }

    /** Spawns the NPC in the recorded body state and inventory (main 0-35, armor 36-39, offhand 40). */
    public static NpcBody spawn(ServerLevel level, PlayerState s, JsonArray inventory) {
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(ID, "NPC");
        ServerPlayer p = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        new Silent(server, p, profile); // sets p.connection
        p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        p.moveTo(s.pos().x, s.pos().y, s.pos().z, s.yRot(), s.xRot());
        p.setYHeadRot(s.yRot());
        p.setDeltaMovement(s.velocity());
        p.setOnGround(s.onGround());
        p.fallDistance = s.fallDistance();
        for (PlayerState.Effect e : s.effects())
            BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse(e.id()))
                    .ifPresent(h -> p.addEffect(new MobEffectInstance(h, e.duration(), e.amplifier())));
        p.setHealth(s.health());
        p.setAbsorptionAmount(s.absorption());
        p.getFoodData().setFoodLevel(s.food());
        p.getFoodData().setSaturation(s.saturation());
        p.setAirSupply(s.air());
        p.experienceLevel = s.xpLevel();
        if (inventory != null)
            for (int i = 0; i < Math.min(inventory.size(), p.getInventory().getContainerSize()); i++)
                p.getInventory().setItem(i, RecordingFormat.item(inventory.get(i), level.registryAccess()));
        p.getInventory().selected = s.slot();
        // Clients only render a player they got a player-info entry for.
        server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(p)));
        level.addNewPlayer(p);
        // In the player list, so it works like a player: /tp NPC, tab completion, @a, info for players joining later.
        // Only the list, not placeNewPlayer (that would load saved player data over the recorded state).
        server.getPlayerList().getPlayers().add(p);
        return new NpcBody(p);
    }

    public void remove() {
        level = player.serverLevel();
        level.getServer().getPlayerList().getPlayers().remove(player);
        level.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
        level.getServer().getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(ID)));
    }

    /** One tick, in the client's order: screen clicks, slot, crosshair, clicks, held buttons, then movement. */
    public void act(Actions a) {
        if (player.isDeadOrDying()) return;
        level = player.serverLevel();
        if (player.isChangingDimension()) player.hasChangedDimension(); // a client would confirm the teleport
        player.setYRot(a.yRot());
        player.setXRot(a.xRot());
        player.setYHeadRot(a.yRot());

        for (Actions.Click c : a.clicks())
            if (player.containerMenu.isValidSlotIndex(c.slot()))
                player.containerMenu.clicked(c.slot(), c.button(), c.type(), player);
        if (!a.clicks().isEmpty()) player.containerMenu.broadcastChanges();
        if (a.screen() == null && last != null && last.screen() != null) player.doCloseContainer();

        // Inside a screen the hotbar is not switched (number keys move items there instead).
        if (a.screen() == null && a.clicks().isEmpty()) hold(a.hold());
        if (missTime > 0) missTime--;
        HitResult hit = pick();

        // ponytail: drop / swap on the key press only; holding Q (key repeat) and ctrl+Q (whole stack) are not recorded.
        if (a.drop() && (last == null || !last.drop()) && player.drop(false)) player.swing(InteractionHand.MAIN_HAND);
        if (a.swapHands() && (last == null || !last.swapHands())) {
            ItemStack off = player.getOffhandItem();
            player.setItemInHand(InteractionHand.OFF_HAND, player.getMainHandItem());
            player.setItemInHand(InteractionHand.MAIN_HAND, off);
            player.stopUsingItem();
        }
        if (player.isUsingItem()) {
            if (!a.use()) player.releaseUsingItem();
        } else {
            for (int i = 0; i < a.attacks(); i++) startAttack(hit);
            for (int i = 0; i < a.uses(); i++) startUseItem(hit);
        }
        continueAttack(a.screen() == null && a.attack(), hit);

        move(a);
        last = a;
    }

    /**
     * Takes the item into the main hand: selects its hotbar slot, or swaps it in from the main inventory like
     * vanilla pick-block does. Not owned: an empty slot is taken, so the hand is empty (a full inventory keeps the hand).
     */
    private void hold(String id) {
        var inv = player.getInventory();
        if (java.util.Objects.equals(net.kroia.ainpc.sensor.EntityState.itemId(inv.getSelected()), id)) return;
        for (int i = 0; i < inv.items.size(); i++) {
            if (!java.util.Objects.equals(net.kroia.ainpc.sensor.EntityState.itemId(inv.items.get(i)), id)) continue;
            if (player.getUsedItemHand() == InteractionHand.MAIN_HAND) player.stopUsingItem();
            if (net.minecraft.world.entity.player.Inventory.isHotbarSlot(i)) inv.selected = i;
            else inv.pickSlot(i);
            return;
        }
        if (id != null) hold(null); // empty stacks have id null
    }

    // --- movement (LocalPlayer.aiStep) ------------------------------------------------------------

    private void move(Actions a) {
        if (sprintTriggerTime > 0) sprintTriggerTime--;
        boolean prevSneak = lastSneak, prevImpulse = lastImpulse;
        float fwd = (a.forward() ? 1 : 0) - (a.back() ? 1 : 0), side = (a.left() ? 1 : 0) - (a.right() ? 1 : 0);
        if (player.isCrouching() || player.isVisuallyCrawling()) {
            float f = (float) player.getAttributeValue(Attributes.SNEAKING_SPEED);
            fwd *= f;
            side *= f;
        }
        if (player.isUsingItem() && !player.isPassenger()) {
            fwd *= 0.2f;
            side *= 0.2f;
            sprintTriggerTime = 0;
        }
        player.setShiftKeyDown(a.sneak());

        boolean impulse = player.isUnderWater() ? fwd > 1e-5f : fwd >= 0.8f;
        boolean food = player.getFoodData().getFoodLevel() > 6 || player.getAbilities().mayfly;
        boolean canStart = !player.isSprinting() && impulse && food && !player.isUsingItem()
                && !player.hasEffect(MobEffects.BLINDNESS) && !player.isFallFlying();
        // Double-tap forward: a fresh forward press within 7 ticks of the last one starts sprinting.
        boolean ground = player.isPassenger() ? player.getVehicle().onGround() : player.onGround();
        if ((ground || player.isUnderWater()) && !prevSneak && !prevImpulse && canStart) {
            if (sprintTriggerTime <= 0 && !a.sprint()) sprintTriggerTime = 7;
            else player.setSprinting(true);
        }
        lastSneak = a.sneak();
        lastImpulse = impulse;
        if ((!player.isInWater() || player.isUnderWater()) && canStart && a.sprint()) player.setSprinting(true);
        if (player.isSprinting()) {
            boolean stop = !impulse || !food;
            if (player.isSwimming()) {
                if (!player.onGround() && !a.sneak() && stop || !player.isInWater()) player.setSprinting(false);
            } else if (stop || player.horizontalCollision && !player.minorHorizontalCollision
                    || player.isInWater() && !player.isUnderWater()) {
                player.setSprinting(false);
            }
        }

        player.xxa = side;
        player.zza = fwd;
        player.setJumping(a.jump());
        Vec3 before = player.position();
        player.doTick();
        // Server players skip fall damage in move(): real clients get it checked when their position arrives.
        Vec3 d = player.position().subtract(before);
        player.doCheckFallDamage(d.x, d.y, d.z, player.onGround());
        level.getChunkSource().move(player);
    }

    // --- crosshair (GameRenderer.pick) -------------------------------------------------------------

    private HitResult pick() {
        double blockRange = player.blockInteractionRange(), entityRange = player.entityInteractionRange();
        double max = Math.max(blockRange, entityRange), maxSq = max * max;
        Vec3 eye = player.getEyePosition();
        HitResult block = player.pick(max, 1f, false);
        double blockSq = block.getLocation().distanceToSqr(eye);
        if (block.getType() != HitResult.Type.MISS) {
            maxSq = blockSq;
            max = Math.sqrt(maxSq);
        }
        Vec3 view = player.getViewVector(1f);
        AABB box = player.getBoundingBox().expandTowards(view.scale(max)).inflate(1);
        Predicate<Entity> pickable = e -> !e.isSpectator() && e.isPickable() && !e.getTags().contains(IGNORE_TAG);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, eye, eye.add(view.scale(max)), box, pickable, maxSq);
        if (entity != null && entity.getLocation().distanceToSqr(eye) < blockSq) return inRange(entity, eye, entityRange);
        return inRange(block, eye, blockRange);
    }

    private static HitResult inRange(HitResult hit, Vec3 eye, double range) {
        Vec3 l = hit.getLocation();
        if (l.closerThan(eye, range)) return hit;
        return BlockHitResult.miss(l, Direction.getNearest(l.x - eye.x, l.y - eye.y, l.z - eye.z), BlockPos.containing(l));
    }

    // --- attack and mining (Minecraft.startAttack / continueAttack, MultiPlayerGameMode) ----------------

    private void startAttack(HitResult hit) {
        if (missTime > 0) return;
        if (hit instanceof EntityHitResult e) {
            player.attack(e.getEntity());
        } else if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK
                && !level.getBlockState(b.getBlockPos()).isAir()) {
            startDestroy(b.getBlockPos(), b.getDirection());
        } else {
            missTime = 10;
            player.resetAttackStrengthTicker();
        }
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void continueAttack(boolean held, HitResult hit) {
        if (!held) missTime = 0;
        if (missTime > 0 || player.isUsingItem()) return;
        if (held && hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
            if (!level.getBlockState(b.getBlockPos()).isAir() && continueDestroy(b.getBlockPos(), b.getDirection()))
                player.swing(InteractionHand.MAIN_HAND);
        } else {
            stopDestroy();
        }
    }

    private boolean startDestroy(BlockPos pos, Direction face) {
        // mayInteract: spawn protection and the world border, which the server checks too. A real client doesn't
        // know spawn protection and shows a crack the server never accepts; the NPC shouldn't sense that fake progress.
        if (player.blockActionRestricted(level, pos, player.gameMode.getGameModeForPlayer())
                || !level.mayInteract(player, pos)) return false;
        if (!destroying || !sameTarget(pos)) {
            if (destroying) send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, destroyPos, face);
            BlockState s = level.getBlockState(pos);
            boolean instant = !s.isAir() && s.getDestroyProgress(player, level, pos) >= 1;
            send(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face); // the server breaks instant blocks itself
            if (!instant) {
                destroying = true;
                destroyPos = pos;
                destroyItem = player.getMainHandItem();
                destroyProgress = 0;
            }
        }
        return true;
    }

    private boolean continueDestroy(BlockPos pos, Direction face) {
        if (destroyDelay > 0) {
            destroyDelay--;
            return true;
        }
        if (!destroying || !sameTarget(pos)) return startDestroy(pos, face);
        BlockState s = level.getBlockState(pos);
        if (s.isAir()) {
            destroying = false;
            return false;
        }
        destroyProgress += s.getDestroyProgress(player, level, pos);
        if (destroyProgress >= 1) {
            destroying = false;
            send(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, face);
            destroyProgress = 0;
            destroyDelay = 5;
        }
        return true;
    }

    private void stopDestroy() {
        if (!destroying) return;
        send(ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, destroyPos, Direction.DOWN);
        destroying = false;
        destroyProgress = 0;
        player.resetAttackStrengthTicker();
    }

    private boolean sameTarget(BlockPos pos) {
        return pos.equals(destroyPos) && ItemStack.isSameItemSameComponents(player.getMainHandItem(), destroyItem);
    }

    /** What the client's block action packet makes the server do. */
    private void send(ServerboundPlayerActionPacket.Action action, BlockPos pos, Direction face) {
        player.gameMode.handleBlockBreakAction(pos, action, face, level.getMaxBuildHeight(), ++sequence);
    }

    // --- use (Minecraft.startUseItem) ----------------------------------------------------------------

    private void startUseItem(HitResult hit) {
        if (destroying) return;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.isItemEnabled(level.enabledFeatures())) return;
            if (hit instanceof EntityHitResult e) {
                Entity target = e.getEntity();
                InteractionResult r = target.interactAt(player, e.getLocation().subtract(target.position()), hand);
                if (!r.consumesAction()) r = player.interactOn(target, hand);
                if (r.consumesAction()) {
                    if (r.shouldSwing()) player.swing(hand);
                    return;
                }
            } else if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
                InteractionResult r = player.gameMode.useItemOn(player, level, stack, hand, b);
                if (r.consumesAction()) {
                    if (r.shouldSwing()) player.swing(hand);
                    return;
                }
                if (r == InteractionResult.FAIL) return;
            }
            if (!stack.isEmpty()) {
                InteractionResult r = player.gameMode.useItem(player, level, stack, hand);
                if (r.consumesAction()) {
                    if (r.shouldSwing()) player.swing(hand);
                    return;
                }
            }
        }
    }

    /** No client behind it: every packet to it is dropped. */
    /** Packets the server sends this NPC, as a client would get them (sounds, for its hearing). */
    public void onPacket(java.util.function.Consumer<Packet<?>> listener) {
        ((Silent) player.connection).listener = listener;
    }

    private static final class Silent extends ServerGamePacketListenerImpl {
        java.util.function.Consumer<Packet<?>> listener = p -> {
        };

        Silent(MinecraftServer server, ServerPlayer player, GameProfile profile) {
            super(server, new Connection(PacketFlow.SERVERBOUND), player, CommonListenerCookie.createInitial(profile, false));
        }

        @Override
        public void send(Packet<?> packet) {
            listener.accept(packet);
        }

        @Override
        public void send(Packet<?> packet, @Nullable PacketSendListener sent) {
            listener.accept(packet);
        }
    }
}
