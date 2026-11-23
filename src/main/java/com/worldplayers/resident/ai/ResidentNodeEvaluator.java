package com.worldplayers.resident.ai;

import com.worldplayers.resident.WorkZoneData;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * 基础版"智能寻路"：在原版行走评估之上，把会让居民掉血 / 被困的格子判成不可走。
 *
 * <p>v0.1 覆盖：岩浆、火与灵魂火、点燃的营火、岩浆块、仙人掌、甜浆果丛、蜘蛛网、细雪。
 * v0.2.12 起（主人 2026-11-20）再加两件事：</p>
 * <ul>
 *   <li>**禁行区域**里的格子直接 BLOCKED（通勤 / 游荡都会绕开）；</li>
 *   <li>**道路区域**里的格子代价减半 → 通勤更愿意走道路。</li>
 * </ul>
 *
 * <p>区域数据在寻路开始时取一次缓存，寻路过程中不会变。</p>
 */
public class ResidentNodeEvaluator extends WalkNodeEvaluator {
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    @Nullable
    private final ServerLevel serverLevel;
    @Nullable
    private WorkZoneData zoneCache;
    /** 起点是否在禁行区（每次寻路按 mob 位置缓存一次）。 */
    private long startPos = Long.MIN_VALUE;
    private boolean startChecked;
    private boolean startForbidden;

    public ResidentNodeEvaluator(@Nullable Level level) {
        this.serverLevel = level instanceof ServerLevel server ? server : null;
    }

    @Nullable
    private WorkZoneData zones() {
        if (this.zoneCache == null && this.serverLevel != null) {
            this.zoneCache = WorkZoneData.get(this.serverLevel);
        }
        return this.zoneCache;
    }

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        PathType type = super.getPathType(context, x, y, z);
        if (type == PathType.BLOCKED) {
            return type;
        }
        // 站着的那格与脚下方的一格都不许是危险方块：站在岩浆块上会掉血，脚下是仙人掌也一样
        if (isDangerous(context.getBlockState(this.probe.set(x, y, z)))
                || isDangerous(context.getBlockState(this.probe.set(x, y - 1, z)))) {
            return PathType.BLOCKED;
        }
        // 栅栏门：原版把它们当"围栏"（FENCE = 不可通行），居民就永远绕路。
        // 关着的栅栏门按木门处理（可通行 + ResidentDoors 会去打开），开着的就是普通可走格。
        BlockState here = context.getBlockState(this.probe.set(x, y, z));
        if (here.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock) {
            return here.getValue(net.minecraft.world.level.block.FenceGateBlock.OPEN)
                ? PathType.DOOR_OPEN : PathType.DOOR_WOOD_CLOSED;
        }
        // 禁行区域：任何寻路都不许走进去（主人 2026-11-20）；
        // 但**已经站在里面**（被划进去 / 被推进来）时放行，好让居民自己走出来（主人 2026-11-23）
        WorkZoneData zones = this.zones();
        if (zones != null && zones.isForbidden(this.probe.set(x, y, z))
                && !this.startInForbidden(context)) {
            return PathType.BLOCKED;
        }
        // 挖掘 / 填充工程区：居民一律不许站进去（规格 5.7），员工站在区域外干活
        if (zones != null && zones.isProjectArea(this.probe.set(x, y, z))) {
            return PathType.BLOCKED;
        }
        // 树苗 = 障碍（规则 33）：不可走，但站在旁边就能种/砍
        if (here.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) {
            return PathType.BLOCKED;
        }
        return type;
    }

    @Override
    public int getNeighbors(Node[] output, Node node) {
        int count = super.getNeighbors(output, node);
        WorkZoneData zones = this.zones();
        int kept = 0;
        for (int i = 0; i < count; i++) {
            Node candidate = output[i];
            // 不允许主动跳下 4 格以上（会摔伤），也就不容易掉进爬不出来的坑。
            if (node.y - candidate.y > 3) {
                continue;
            }
            // 在道路上的格子便宜一半 → 通勤更愿意沿路走
            if (zones != null && zones.isOnRoad(this.probe.set(candidate.x, candidate.y, candidate.z))) {
                candidate.costMalus = Math.max(0.0F, candidate.costMalus * 0.5F);
            }
            output[kept++] = candidate;
        }
        return kept;
    }

    /**
     * 寻路起点是不是已经在禁行区域里（一次寻路内缓存：mob 位置变了才重算）。
     * 在禁行区里时不再把禁行格子判 BLOCKED，否则起点不可走、居民永远走不出来。
     */
    private boolean startInForbidden(PathfindingContext context) {
        long pos = context.mobPosition().asLong();
        if (!this.startChecked || pos != this.startPos) {
            this.startPos = pos;
            this.startChecked = true;
            WorkZoneData zones = this.zones();
            this.startForbidden = zones != null && zones.isForbidden(context.mobPosition());
        }
        return this.startForbidden;
    }

    /** 这个方块是不是"站上去会掉血 / 走进去会受伤"的那种。 */
    public static boolean isDangerous(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        Block block = state.getBlock();
        if (block == Blocks.LAVA || block == Blocks.FIRE || block == Blocks.SOUL_FIRE
                || block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.COBWEB
                || block == Blocks.POWDER_SNOW) {
            return true;
        }
        if (block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE) {
            return state.getValue(CampfireBlock.LIT);
        }
        return state.getFluidState().is(Fluids.LAVA);
    }
}
