package net.kroia.ainpc.replay;

import com.google.gson.stream.JsonWriter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * What every block and item is, beyond its id: its tags (logs, mineable/axe, ...) and physical properties.
 * The trainer looks block/item ids up in this table, so a birch log looks like a spruce log apart from the id,
 * and modded blocks in vanilla/common tags are understood without recordings of them.
 * Written next to the datasets by /ainpc generateTrainingset (it needs the server's loaded tags); changing what the
 * trainer uses from it needs no replay. Properties are of the default block state.
 *
 * {"format":1, "blocks":{id:{tags, hardness, resistance, needsTool, solid, blocksMotion, liquid, replaceable,
 *   light, color:[r,g,b], friction, speed, jump, props:{name:[value, ...]}}} (props: block state properties in
 *   their value order, so the trainer can scale crop age 0..7 and one-hot facing), "items":{id:{tags, maxStack, maxDamage, food, saturation,
 *   tool, block, attackDamage, attackSpeed}},
 *   "entities":{id:{tags, category, width, height, fireImmune, summonable, kind:[living, mob, animal, monster, ...],
 *   maxHealth, attackDamage, speed, armor, followRange}},
 *   "effects":{id:{category, color:[r,g,b], instant}}, "sounds":[id, ...],
 *   "damage":{msgId:{tags, exhaustion, scaling}} (msgId is what the body sensor reports; types sharing one merge)}
 */
public final class FeatureTable {
    public static final int FORMAT = 1;

    private FeatureTable() {
    }

    /** Kinds of entity, by class: one throwaway instance per type (never added to the world). */
    private static final java.util.Map<String, Class<?>> KINDS = new java.util.LinkedHashMap<>();

    static {
        KINDS.put("living", net.minecraft.world.entity.LivingEntity.class);
        KINDS.put("mob", net.minecraft.world.entity.Mob.class);
        KINDS.put("animal", net.minecraft.world.entity.animal.Animal.class);
        KINDS.put("monster", net.minecraft.world.entity.monster.Enemy.class);
        KINDS.put("neutral", net.minecraft.world.entity.NeutralMob.class);
        KINDS.put("tamable", net.minecraft.world.entity.TamableAnimal.class);
        KINDS.put("villager", net.minecraft.world.entity.npc.AbstractVillager.class);
        KINDS.put("golem", net.minecraft.world.entity.animal.AbstractGolem.class);
        KINDS.put("water", net.minecraft.world.entity.animal.WaterAnimal.class);
        KINDS.put("flying", net.minecraft.world.entity.animal.FlyingAnimal.class);
        KINDS.put("rideable", net.minecraft.world.entity.Saddleable.class);
        KINDS.put("projectile", net.minecraft.world.entity.projectile.Projectile.class);
        KINDS.put("item", net.minecraft.world.entity.item.ItemEntity.class);
        KINDS.put("vehicle", net.minecraft.world.entity.vehicle.VehicleEntity.class);
        KINDS.put("player", net.minecraft.world.entity.player.Player.class);
        KINDS.put("explosive", net.minecraft.world.entity.item.PrimedTnt.class);
        KINDS.put("experience", net.minecraft.world.entity.ExperienceOrb.class);
    }

    public static java.util.List<String> kinds() {
        return java.util.List.copyOf(KINDS.keySet());
    }

    public static void write(Path file, net.minecraft.server.level.ServerLevel level) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8); JsonWriter w = new JsonWriter(out)) {
            w.beginObject().name("format").value(FORMAT);
            w.name("blocks").beginObject();
            for (Block b : BuiltInRegistries.BLOCK) {
                BlockState s = b.defaultBlockState();
                var g = EmptyBlockGetter.INSTANCE;
                int col = s.getMapColor(g, BlockPos.ZERO).col;
                w.name(BuiltInRegistries.BLOCK.getKey(b).toString()).beginObject();
                tags(w, b.builtInRegistryHolder().tags());
                w.name("hardness").value(s.getDestroySpeed(g, BlockPos.ZERO));
                w.name("resistance").value(b.getExplosionResistance());
                w.name("needsTool").value(s.requiresCorrectToolForDrops());
                w.name("solid").value(s.isSolid());
                w.name("blocksMotion").value(s.blocksMotion());
                w.name("liquid").value(!s.getFluidState().isEmpty());
                w.name("replaceable").value(s.canBeReplaced());
                w.name("light").value(s.getLightEmission());
                w.name("color").beginArray().value(col >> 16 & 255).value(col >> 8 & 255).value(col & 255).endArray();
                w.name("friction").value(b.getFriction());
                w.name("speed").value(b.getSpeedFactor());
                w.name("jump").value(b.getJumpFactor());
                w.name("props").beginObject();
                for (var prop : b.getStateDefinition().getProperties()) {
                    w.name(prop.getName()).beginArray();
                    for (Object v : prop.getPossibleValues()) w.value(name(prop, v));
                    w.endArray();
                }
                w.endObject();
                w.endObject();
            }
            w.endObject();
            w.name("items").beginObject();
            for (Item i : BuiltInRegistries.ITEM) {
                ItemStack st = new ItemStack(i);
                var food = st.get(DataComponents.FOOD);
                ItemAttributeModifiers mods = st.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
                w.name(BuiltInRegistries.ITEM.getKey(i).toString()).beginObject();
                tags(w, i.builtInRegistryHolder().tags());
                w.name("maxStack").value(st.getMaxStackSize());
                w.name("maxDamage").value(st.getMaxDamage());
                w.name("food").value(food == null ? 0 : food.nutrition());
                w.name("saturation").value(food == null ? 0 : food.saturation());
                w.name("tool").value(st.has(DataComponents.TOOL));
                w.name("block").value(i instanceof BlockItem);
                w.name("attackDamage").value(sum(mods, Attributes.ATTACK_DAMAGE));
                w.name("attackSpeed").value(sum(mods, Attributes.ATTACK_SPEED));
                w.endObject();
            }
            w.endObject();
            entities(w, level);
            w.name("effects").beginObject();
            for (var e : BuiltInRegistries.MOB_EFFECT) {
                int c = e.getColor();
                w.name(BuiltInRegistries.MOB_EFFECT.getKey(e).toString()).beginObject()
                        .name("category").value(e.getCategory().name().toLowerCase())
                        .name("color").beginArray().value(c >> 16 & 255).value(c >> 8 & 255).value(c & 255).endArray()
                        .name("instant").value(e.isInstantenous()).endObject();
            }
            w.endObject();
            w.name("sounds").beginArray();
            for (var k : BuiltInRegistries.SOUND_EVENT.keySet().stream().map(Object::toString).sorted().toList()) w.value(k);
            w.endArray();
            // several damage types share a message id (mob attack with / without aggro): tags merge
            java.util.Map<String, java.util.TreeSet<String>> dTags = new java.util.TreeMap<>();
            java.util.Map<String, net.minecraft.world.damagesource.DamageType> dType = new java.util.HashMap<>();
            for (var h : level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).holders().toList()) {
                var t = dTags.computeIfAbsent(h.value().msgId(), k -> new java.util.TreeSet<>());
                h.tags().forEach(k -> t.add(k.location().toString()));
                dType.putIfAbsent(h.value().msgId(), h.value());
            }
            w.name("damage").beginObject();
            for (var en : dTags.entrySet()) {
                w.name(en.getKey()).beginObject().name("tags").beginArray();
                for (String t : en.getValue()) w.value(t);
                w.endArray().name("exhaustion").value(dType.get(en.getKey()).exhaustion())
                        .name("scaling").value(dType.get(en.getKey()).scaling().name().toLowerCase()).endObject();
            }
            w.endObject().endObject();
        }
    }

    @SuppressWarnings("unchecked")
    private static void entities(JsonWriter w, net.minecraft.server.level.ServerLevel level) throws IOException {
        w.name("entities").beginObject();
        for (net.minecraft.world.entity.EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
            net.minecraft.world.entity.Entity e = null;
            try {
                e = t.create(level);
            } catch (Throwable ignored) { // some types (player, fishing hook) cannot exist alone
            }
            w.name(BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()).beginObject();
            tags(w, t.builtInRegistryHolder().tags());
            w.name("category").value(t.getCategory().getName());
            w.name("width").value(t.getWidth()).name("height").value(t.getHeight());
            w.name("fireImmune").value(t.fireImmune()).name("summonable").value(t.canSummon());
            w.name("kind").beginArray();
            for (var k : KINDS.entrySet())
                if (k.getValue().isInstance(e) || k.getKey().equals("player") && t == net.minecraft.world.entity.EntityType.PLAYER
                        || k.getKey().equals("living") && t == net.minecraft.world.entity.EntityType.PLAYER) w.value(k.getKey());
            w.endArray();
            var attrs = net.minecraft.world.entity.ai.attributes.DefaultAttributes.hasSupplier(t)
                    ? net.minecraft.world.entity.ai.attributes.DefaultAttributes.getSupplier(
                    (net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.LivingEntity>) t) : null;
            attr(w, "maxHealth", attrs, Attributes.MAX_HEALTH);
            attr(w, "attackDamage", attrs, Attributes.ATTACK_DAMAGE);
            attr(w, "speed", attrs, Attributes.MOVEMENT_SPEED);
            attr(w, "armor", attrs, Attributes.ARMOR);
            attr(w, "followRange", attrs, Attributes.FOLLOW_RANGE);
            w.endObject();
            if (e != null) e.discard();
        }
        w.endObject();
    }

    private static void attr(JsonWriter w, String name, net.minecraft.world.entity.ai.attributes.AttributeSupplier s,
                             Holder<Attribute> a) throws IOException {
        w.name(name).value(s != null && s.hasAttribute(a) ? s.getBaseValue(a) : 0);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String name(net.minecraft.world.level.block.state.properties.Property prop, Object v) {
        return prop.getName((Comparable) v); // the spelling block state strings use (age=7, facing=north)
    }

    private static <T> void tags(JsonWriter w, Stream<TagKey<T>> tags) throws IOException {
        w.name("tags").beginArray();
        for (String t : tags.map(k -> k.location().toString()).sorted().toList()) w.value(t);
        w.endArray();
    }

    /** Bonus over the player's base value, as tooltips show it minus the base. */
    private static double sum(ItemAttributeModifiers mods, Holder<Attribute> attribute) {
        double s = 0;
        for (var e : mods.modifiers()) if (e.attribute().value() == attribute.value()) s += e.modifier().amount();
        return s;
    }
}
