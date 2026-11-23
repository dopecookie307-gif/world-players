package com.worldplayers.resident;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 「殖民地边境」的唯一定义（主人 2026-11-20）。
 *
 * <p>边境 = <b>任意工作区域 / 道路区域 / 已绑定床的 16 格光环</b>：</p>
 * <ul>
 *   <li>区域算的是**整个包围盒**（不是只在中心点量 16 格 —— 一条很长的道路，
 *       站在路中间也算在殖民地内，以前的「中心点 16 格」会把居民从路中间拖回中心）；</li>
 *   <li>禁行区域不算锚点（它本身就是不许去的地方）；</li>
 *   <li>床按床的方块位置算 16 格（只算本维度的已绑定床）。</li>
 * </ul>
 *
 * <p>闲逛（{@link com.worldplayers.resident.ai.ResidentStrollGoal}）和战后归位
 * （{@link com.worldplayers.resident.ai.ResidentReturnHomeGoal}）都问这里要答案，
 * 以后加新的殖民地要素（仓库、路灯……）只要在 {@link #distanceSq} 里加一条。</p>
 */
public final class ColonyAnchors {
    /** 居民可以离开殖民地的最大距离（主人定的 16 格）。 */
    public static final double RANGE = 16.0D;
    public static final double RANGE_SQ = RANGE * RANGE;

    /** 世界里没有任何殖民地要素时，流浪居民自己的闲逛锚点半径（和旧的游荡范围一致）。 */
    public static final double PERSONAL_RANGE = 16.0D;

    /** 这个点到最近的殖民地要素有多远（0 = 站在区域里 / 床上；世界没有殖民地要素 = MAX_VALUE）。 */
    public static double distanceSq(ServerLevel level, Vec3 point) {
        double best = Double.MAX_VALUE;
        if (zonesApply(level)) {
            for (WorkZone zone : WorkZoneData.get(level).zones()) {
                if (WorkZone.TYPE_FORBIDDEN.equals(zone.type())) {
                    continue;
                }
                best = Math.min(best, zone.box().distanceToSqr(point));
            }
        }
        for (BedBindingData.Entry entry : BedBindingData.get(level).all()) {
            if (!entry.dimension().equals(level.dimension())) {
                continue;
            }
            best = Math.min(best, Vec3.atCenterOf(entry.pos()).distanceToSqr(point));
        }
        return best;
    }

    /** 世界里有没有任何殖民地要素（非禁行区域或本维度的已绑定床）。 */
    public static boolean hasColony(ServerLevel level) {
        if (zonesApply(level)) {
            for (WorkZone zone : WorkZoneData.get(level).zones()) {
                if (!WorkZone.TYPE_FORBIDDEN.equals(zone.type())) {
                    return true;
                }
            }
        }
        for (BedBindingData.Entry entry : BedBindingData.get(level).all()) {
            if (entry.dimension().equals(level.dimension())) {
                return true;
            }
        }
        return false;
    }

    /** 工作区域没有存维度（都在主世界的存档数据里），所以只在主世界算数。 */
    private static boolean zonesApply(ServerLevel level) {
        return level == level.getServer().overworld();
    }

    /** 这个点是不是还在殖民地的 16 格边境之内（世界里没有殖民地 → false，交给个人锚点兜底）。 */
    public static boolean inside(ServerLevel level, Vec3 point) {
        return distanceSq(level, point) <= RANGE_SQ;
    }

    /** 离这个点最近的「入境点」：最近区域包围盒上的最近点 / 床的位置；没有殖民地 → null。 */
    @Nullable
    public static BlockPos entryPoint(ServerLevel level, BlockPos from) {
        Vec3 point = Vec3.atCenterOf(from);
        double best = Double.MAX_VALUE;
        BlockPos bestPos = null;
        if (zonesApply(level)) {
            for (WorkZone zone : WorkZoneData.get(level).zones()) {
                if (WorkZone.TYPE_FORBIDDEN.equals(zone.type())) {
                    continue;
                }
                Vec3 closest = clampTo(zone.box(), point);
                double distance = closest.distanceToSqr(point);
                if (distance < best) {
                    best = distance;
                    bestPos = BlockPos.containing(closest);
                }
            }
        }
        for (BedBindingData.Entry entry : BedBindingData.get(level).all()) {
            if (!entry.dimension().equals(level.dimension())) {
                continue;
            }
            Vec3 closest = Vec3.atCenterOf(entry.pos());
            double distance = closest.distanceToSqr(point);
            if (distance < best) {
                best = distance;
                bestPos = entry.pos();
            }
        }
        return bestPos;
    }

    /**
     * 居民自己的**持久闲逛锚点**（写进 NBT）。
     *
     * <p>有殖民地时边境本身就够用了（也就是「任意工作区域 / 道路区域 / 床的 16 格内」），
     * 个人锚点只是给「世界里一个殖民地要素都没有」的野外居民兜底：第一次需要时记在脚下，
     * 这样流浪居民也不会变成「没有锚点 = 想去哪去哪」。</p>
     */
    public static BlockPos personalAnchor(ResidentEntity resident) {
        BlockPos stored = resident.strollAnchor();
        if (stored != null) {
            return stored;
        }
        stored = resident.blockPosition();
        resident.setStrollAnchor(stored);
        return stored;
    }

    /** 居民是不是「无业」（没有可以上班的工作区；道路/禁行本来就不算工作场所）。 */
    public static boolean unemployed(ServerLevel level, ResidentEntity resident) {
        WorkZone zone = WorkZoneData.get(level).findByEmployee(resident.getUUID().toString());
        return zone == null
            || WorkZone.TYPE_ROAD.equals(zone.type())
            || WorkZone.TYPE_FORBIDDEN.equals(zone.type());
    }

    /** 居民是不是「流浪」（没有绑定床）。 */
    public static boolean homeless(ResidentEntity resident) {
        return resident.bedPos() == null;
    }

    /** 无业或流浪：这两类居民打完援助战后要自己走回边境（主人 2026-11-20）。 */
    public static boolean mustReturnAfterCombat(ServerLevel level, ResidentEntity resident) {
        return unemployed(level, resident) || homeless(resident);
    }

    private static Vec3 clampTo(AABB box, Vec3 point) {
        return new Vec3(
            Math.max(box.minX, Math.min(box.maxX, point.x)),
            Math.max(box.minY, Math.min(box.maxY, point.y)),
            Math.max(box.minZ, Math.min(box.maxZ, point.z)));
    }

    private ColonyAnchors() {
    }
}
