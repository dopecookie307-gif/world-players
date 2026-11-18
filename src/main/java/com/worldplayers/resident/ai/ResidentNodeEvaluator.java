package com.worldplayers.resident.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * 基础版"智能寻路"：在原版行走评估之上，把会让居民掉血 / 被困的格子判成不可走。
 *
 * <p>v0.1 覆盖：岩浆、火与灵魂火、点燃的营火、岩浆块、仙人掌、甜浆果丛、蜘蛛网、细雪。
 * 后续（见纸上谈兵）再加：摔落高度代价、无法爬出的坑、TNT 爆炸范围、怪物近战范围。</p>
 */
public class ResidentNodeEvaluator extends WalkNodeEvaluator {
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

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
        return type;
    }

    /**
     * 不允许主动跳下 4 格以上（会摔伤），也就不容易掉进爬不出来的坑。
     * 原版 {@code WalkNodeEvaluator} 自己允许的下落高度比玩家能无伤落地的高度大，这里收紧。
     */
    @Override
    public int getNeighbors(net.minecraft.world.level.pathfinder.Node[] output,
                            net.minecraft.world.level.pathfinder.Node node) {
        int count = super.getNeighbors(output, node);
        int kept = 0;
        for (int i = 0; i < count; i++) {
            net.minecraft.world.level.pathfinder.Node candidate = output[i];
            if (node.y - candidate.y > 3) {
                continue;
            }
            output[kept++] = candidate;
        }
        return kept;
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
