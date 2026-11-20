package net.kroia.ainpc.sensor;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.Comparator;
import java.util.List;

/**
 * Everything recorded about a non-player-self entity in one tick. Sensors only use this snapshot,
 * so a replay produces exactly what the live sensors saw.
 * Fields that do not apply are 0 / false / null (health of an arrow, item of a zombie).
 * item / count: the stack of a dropped item entity (count is 1 for everything else).
 * armor: feet, legs, chest, head item ids (null = empty), or an empty list when it wears nothing.
 * tags: visible details, e.g. aggressive, angry, tame, saddled, charged, sheared, color=white, profession=farmer.
 */
public record EntityState(int id, String type, Vec3 pos, float yRot, float xRot, float headYRot,
                          float width, float height, float eyeHeight, String pose,
                          boolean baby, boolean onFire, boolean invisible,
                          float health, int hurtTime, int deathTime,
                          String mainHand, String offHand, String item, int count, float swell,
                          List<String> armor, List<String> tags) {

    /** Same box vanilla builds from position and dimensions. */
    public AABB bbox() {
        double w = width / 2.0;
        return new AABB(pos.x - w, pos.y, pos.z - w, pos.x + w, pos.y + height, pos.z + w);
    }

    public Vec3 eyePos() {
        return pos.add(0, eyeHeight, 0);
    }

    public static EntityState of(Entity e) {
        LivingEntity living = e instanceof LivingEntity l ? l : null;
        return new EntityState(e.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), e.position(),
                e.getYRot(), e.getXRot(), e.getYHeadRot(), e.getBbWidth(), e.getBbHeight(), e.getEyeHeight(),
                e.getPose().name(),
                living != null && living.isBaby(), e.isOnFire(), e.isInvisible(),
                living == null ? 0 : living.getHealth(), living == null ? 0 : living.hurtTime,
                living == null ? 0 : living.deathTime,
                living == null ? null : itemId(living.getMainHandItem()),
                living == null ? null : itemId(living.getOffhandItem()),
                e instanceof ItemEntity ie ? itemId(ie.getItem()) : null,
                e instanceof ItemEntity ie ? ie.getItem().getCount() : 1,
                e instanceof Creeper c ? c.getSwelling(1f) : 0,
                armor(living), tags(e));
    }

    private static List<String> armor(LivingEntity l) {
        if (l == null) return List.of();
        List<String> a = Arrays.asList(itemId(l.getItemBySlot(EquipmentSlot.FEET)), itemId(l.getItemBySlot(EquipmentSlot.LEGS)),
                itemId(l.getItemBySlot(EquipmentSlot.CHEST)), itemId(l.getItemBySlot(EquipmentSlot.HEAD)));
        return a.stream().allMatch(Objects::isNull) ? List.of() : a;
    }

    private static List<String> tags(Entity e) {
        List<String> t = new ArrayList<>();
        if (e instanceof Mob m && m.isAggressive()) t.add("aggressive");
        if (e instanceof NeutralMob n && n.isAngry()) t.add("angry");
        if (e instanceof TamableAnimal a && a.isTame()) t.add("tame");
        if (e instanceof Saddleable s && s.isSaddled()) t.add("saddled");
        if (e instanceof Creeper c && c.isPowered()) t.add("charged");
        if (e instanceof Sheep s) {
            t.add("color=" + s.getColor().getName());
            if (s.isSheared()) t.add("sheared");
        }
        if (e instanceof VillagerDataHolder v)
            t.add("profession=" + BuiltInRegistries.VILLAGER_PROFESSION.getKey(v.getVillagerData().getProfession()).getPath());
        return t;
    }

    /** Entities around self (excluded), spectators skipped, sorted by id so every run sees the same order. */
    public static List<EntityState> collect(Level level, Entity self, double radius) {
        List<EntityState> out = new ArrayList<>();
        for (Entity e : level.getEntities(self, self.getBoundingBox().inflate(radius), e -> !e.isSpectator()))
            out.add(of(e));
        out.sort(Comparator.comparingInt(EntityState::id));
        return out;
    }

    public static String itemId(ItemStack st) {
        return st.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }
}
