package com.worldplayers.resident;

import com.worldplayers.registry.WPEntities;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

/**
 * 有床居民的死亡复活（主人 2026-11-20 定）。
 *
 * <p>死亡时先存一份完整的 NBT 快照，5 秒（100 tick）后在床边重新生成一个**同 UUID** 的实体：
 * 工作、皮肤、模型、名字、背包（死亡不掉落开着的时候）全部原样回来。
 * 床绑定按 UUID 认人，所以死亡不会解绑。</p>
 */
public final class ResidentRevival {
    /** 死后多久复活（tick）。 */
    public static final int REVIVE_DELAY_TICKS = 100;

    private static final class Pending {
        private final net.minecraft.resources.ResourceKey<Level> dimension;
        private final BlockPos bed;
        private final UUID resident;
        private final CompoundTag snapshot;
        private int ticksLeft;

        private Pending(net.minecraft.resources.ResourceKey<Level> dimension, BlockPos bed, UUID resident,
                        CompoundTag snapshot, int delay) {
            this.dimension = dimension;
            this.bed = bed;
            this.resident = resident;
            this.snapshot = snapshot;
            this.ticksLeft = delay;
        }
    }

    private static final Map<UUID, Pending> QUEUE = new HashMap<>();

    /** 排一次复活。快照由 {@link ResidentEntity#die} 在掉落处理完之后存好。 */
    public static void schedule(Level level, UUID residentId, CompoundTag snapshot, BlockPos bed, int delayTicks) {
        QUEUE.put(residentId, new Pending(level.dimension(), bed.immutable(), residentId, snapshot, delayTicks));
    }

    public static boolean isPending(UUID residentId) {
        return QUEUE.containsKey(residentId);
    }

    public static void clear() {
        QUEUE.clear();
    }

    public static void tick(MinecraftServer server) {
        if (QUEUE.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Pending>> iterator = QUEUE.entrySet().iterator();
        Map.Entry<UUID, Pending> entry = null;
        while (iterator.hasNext()) {
            entry = iterator.next();
            Pending pending = entry.getValue();
            if (--pending.ticksLeft > 0) {
                continue;
            }
            iterator.remove();
            revive(server, pending);
        }
    }

    private static void revive(MinecraftServer server, Pending pending) {
        ServerLevel level = server.getLevel(pending.dimension);
        if (level == null) {
            return;
        }
        ResidentEntity entity = WPEntities.RESIDENT.get().create(level);
        if (entity == null) {
            return;
        }
        CompoundTag tag = pending.snapshot.copy();
        tag.remove("id");
        tag.remove("UUID");
        entity.setUUID(pending.resident);
        entity.moveTo(pending.bed.getX() + 0.5D, pending.bed.getY() + 1.0D, pending.bed.getZ() + 0.5D,
            level.getRandom().nextFloat() * 360.0F, 0.0F);
        entity.load(tag);

        BlockPos spawn = safeSpawn(level, pending.bed);
        entity.moveTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
            level.getRandom().nextFloat() * 360.0F, 0.0F);
        entity.setHealth(entity.getMaxHealth());
        // 复活：饥饿回满；体力 / 心情保持死前的数值（NBT 已经恢复）
        entity.setHunger(100.0F);
        level.addFreshEntity(entity);

        level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
            SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 0.6F, 1.2F);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,
            entity.getX(), entity.getY() + 0.8D, entity.getZ(), 12, 0.3D, 0.5D, 0.3D, 0.02D);
        // 复活文本：从对白池里随机挑一句（主人 2026-11-21），以居民自己的名义说
        ResidentChat.say(level, entity, ResidentChatPool.pick(
            ResidentChatPool.Kind.REVIVE, level.getRandom()));
        BedBinder.syncAll(server);
    }

    private static BlockPos safeSpawn(ServerLevel level, BlockPos bed) {
        for (int up = 1; up <= 4; up++) {
            BlockPos candidate = bed.above(up);
            if (ResidentEntity.isSafeStand(level, candidate)) {
                return candidate;
            }
        }
        return bed.above();
    }

    private ResidentRevival() {
    }
}
