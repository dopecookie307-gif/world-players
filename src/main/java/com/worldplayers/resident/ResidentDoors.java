package com.worldplayers.resident;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

/**
 * 居民自己开**栅栏门**（主人 2026-11-20）。
 *
 * <p>普通木门交给原版 {@code OpenDoorGoal}（由 {@code ResidentEntity#registerGoals} 注册，
 * 路径节点是门时自动开关）；原版**没有**栅栏门的对应 goal，所以这里只补栅栏门。</p>
 *
 * <p><b>v0.2.12-alpha.18 修复「站在门边不停开关」</b>：以前的规则是「身边 ±2 格有栅栏门就开，
 * 离门超过 1.5 格就关」——居民站在 1.6~3 格处时会「开→立刻关→10 tick 后再开」，鬼畜开关 + 音效刷屏。现在的规则：</p>
 * <ol>
 *   <li>**站立模式一律不碰门**（主人要求）；</li>
 *   <li>只有**正在走路**（寻路进行中）且**下一个路径点就是这扇门**（或已经贴到门口 1.2 格内）才开；</li>
 *   <li>关门用**迟滞**：离门超过 2.5 格才关，而且至少开了 20 tick，避免在阈值附近来回抖。</li>
 * </ol>
 */
public final class ResidentDoors {
    /** 已经贴到门口（水平距离）——路径节点偶尔会跳过门那一格，用这个兜底。 */
    private static final double AT_DOOR_DISTANCE = 1.2D;
    /** 开门只考虑这个范围内的栅栏门（水平）。 */
    private static final double OPEN_RANGE = 2.5D;
    /** 走开这么远才关门（迟滞：要比开门判定范围大得多，防止在阈值附近来回抖）。 */
    private static final double CLOSE_DISTANCE = 3.5D;
    /** 最少开这么久再考虑关（1 秒），避免刚开就被自己的判定关掉。 */
    private static final int MIN_OPEN_TICKS = 20;
    /** 兜底：最多保持这么久（防止居民走掉后门一直开着）。 */
    private static final int MAX_OPEN_TICKS = 20 * 60;

    private static final class Opened {
        private final ResourceKey<Level> dimension;
        private final BlockPos pos;
        private final UUID resident;
        /** 穿门方向用的坐标轴：0 = 比较 X，1 = 比较 Z（= 门的墙方向的正交轴）。 */
        private final int passAxis;
        /** 开门那一刻居民在门的哪一侧（+1 / -1 / 0）。 */
        private final int side;
        private int age;

        private Opened(ResourceKey<Level> dimension, BlockPos pos, UUID resident,
                       int passAxis, int side) {
            this.dimension = dimension;
            this.pos = pos;
            this.resident = resident;
            this.passAxis = passAxis;
            this.side = side;
        }
    }

    private static final Map<GlobalPos, Opened> OPEN = new HashMap<>();

    /**
     * 每 10 tick 调一次：把居民**正要穿过**的栅栏门打开并登记，走了以后自动关。
     * 站立模式 / 没在走路 / 已经登记过的门都直接跳过。
     */
    public static void tickNearby(ServerLevel level, ResidentEntity resident) {
        if (ResidentJobs.STAND.equals(resident.jobKey())) {
            return;
        }
        if (resident.getNavigation().isDone()) {
            return;
        }
        Path path = resident.getNavigation().getPath();
        if (path == null) {
            return;
        }
        BlockPos next = path.getNextNodePos();
        BlockPos feet = resident.blockPosition();
        boolean following = ResidentJobs.FOLLOW.equals(resident.jobKey());
        WorkZoneData zones = WorkZoneData.get(level);
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-2, -1, -2), feet.offset(2, 1, 2))) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof FenceGateBlock) || state.getValue(FenceGateBlock.OPEN)) {
                continue;
            }
            GlobalPos key = GlobalPos.of(level.dimension(), pos.immutable());
            if (OPEN.containsKey(key)) {
                continue;
            }
            if (!wantsThrough(resident, next, pos)) {
                continue;
            }
            // 2026-11-23 主人新规则：
            // 1) 跟随中的居民可以打开任何门 / 栅栏门；
            // 2) 工作区域里的栅栏门只有该区域的员工能开关；
            // 3) 员工**只在真的要走出去时**才开（以前靠近门 2 格就自动开）。
            if (!following) {
                if (!zones.canOperateWorkGate(pos, resident.getUUID().toString())) {
                    continue;
                }
                WorkZone gateZone = zones.findWorkAreaAt(pos);
                if (gateZone != null && gateZone.containsBody(resident)) {
                    // 2026-11-23 三修：以前看**下一个路径点**在不在区域里 —— 员工在区域里绕路时，
                    // 下一节点偶尔会跨到门外，结果「路过」就开一次门，玩家看到的就是「工作期间开门想出去」。
                    // 现在看**路径终点**：终点还在区域里 = 只是绕路，不开；终点在区域外才开。
                    BlockPos end = path.getEndNode() == null
                        ? null : path.getEndNode().asBlockPos();
                    if (end == null || gateZone.containsBlock(end)
                            || gateZone.containsBlock(end.below())) {
                        continue;
                    }
                    if (gateZone.containsBlock(next)) {
                        // 下一步还在区域里：这扇门只是路过，不开
                        continue;
                    }
                }
            }
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, true), 10);
            level.playSound(null, pos, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS,
                1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
            // 记下「门沿哪个轴开合」和「人当时在门的哪一侧」：
            // 人一穿到另一侧就关门（主人 2026-11-23：牧场很小，居民进去后门一直开着关不上）。
            // FenceGateBlock 没有 AXIS 属性，用 FACING 的轴判断：门朝南北 = 人沿 Z 穿过。
            boolean facingZ = state.getValue(FenceGateBlock.FACING).getAxis()
                == net.minecraft.core.Direction.Axis.Z;
            int passAxis = facingZ ? 1 : 0;
            OPEN.put(key, new Opened(level.dimension(), pos.immutable(), resident.getUUID(),
                passAxis, sideOf(resident, pos, passAxis)));
        }
    }

    /** 这扇门是不是居民「真的要走过去」的：下一路径点就在门上，或者人已经贴到门口。 */
    private static boolean wantsThrough(ResidentEntity resident, BlockPos next, BlockPos gate) {
        double horizontal = horizontalDistance(resident, gate);
        if (horizontal > OPEN_RANGE) {
            return false;
        }
        if (next.distSqr(gate) <= 4.0D) {
            return true;
        }
        return horizontal <= AT_DOOR_DISTANCE;
    }

    private static double horizontalDistance(ResidentEntity resident, BlockPos pos) {
        double dx = resident.getX() - (pos.getX() + 0.5D);
        double dz = resident.getZ() - (pos.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 居民在门的哪一侧（沿穿门轴比较）：+1 / -1 / 0（正对着门中心）。 */
    private static int sideOf(ResidentEntity resident, BlockPos gate, int passAxis) {
        if (passAxis == 0) {
            double dx = resident.getX() - (gate.getX() + 0.5D);
            return dx > 0.0D ? 1 : (dx < 0.0D ? -1 : 0);
        }
        double dz = resident.getZ() - (gate.getZ() + 0.5D);
        return dz > 0.0D ? 1 : (dz < 0.0D ? -1 : 0);
    }

    /**
     * 居民是不是**已经穿过**这扇门（到了开门时的另一侧）。
     *
     * <p>2026-11-23 主人报「牧场范围很小，居民进去后不会关门」：以前只有「走开 3.5 格以上」
     * 或「最多 60 秒」才关，小牧场里员工离门永远不到 3.5 格，门就开着。现在一过门就关。</p>
     */
    private static boolean crossedGate(ResidentEntity resident, Opened entry) {
        if (horizontalDistance(resident, entry.pos) <= 0.6D) {
            return false;
        }
        int side = sideOf(resident, entry.pos, entry.passAxis);
        return side != 0 && side != entry.side;
    }

    /** 每 tick：居民走远（>2.5 格）或超时，且门已经开了至少 1 秒，就关上。 */
    public static void tick(MinecraftServer server) {
        if (OPEN.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<GlobalPos, Opened>> iterator = OPEN.entrySet().iterator();
        while (iterator.hasNext()) {
            Opened entry = iterator.next().getValue();
            ServerLevel level = server.getLevel(entry.dimension);
            if (level == null) {
                iterator.remove();
                continue;
            }
            ResidentEntity resident = level.getEntity(entry.resident) instanceof ResidentEntity found
                ? found : null;
            entry.age++;
            boolean gone = resident == null || resident.isRemoved()
                || horizontalDistance(resident, entry.pos) > CLOSE_DISTANCE;
            // 已经走到门的另一边 → 立即关门（小区域里「走开 3.5 格」永远不成立）
            boolean crossed = resident != null && !resident.isRemoved()
                && crossedGate(resident, entry);
            if ((!gone && !crossed || entry.age < MIN_OPEN_TICKS) && entry.age < MAX_OPEN_TICKS) {
                continue;
            }
            iterator.remove();
            close(level, entry.pos);
        }
    }

    private static void close(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN)) {
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, false), 10);
            level.playSound(null, pos, SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS,
                1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
        }
    }

    public static void clear() {
        OPEN.clear();
    }

    private ResidentDoors() {
    }
}
