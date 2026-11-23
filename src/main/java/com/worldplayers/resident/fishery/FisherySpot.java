package com.worldplayers.resident.fishery;

import net.minecraft.core.BlockPos;

/**
 * 一个渔场「解析好的钓点」（15 号规则 2.2 第 6 条）：水坐标 + 选中的站位 + 一次咬钩的 tick。
 *
 * <p>规则原文记的是「水坐标 + 可站格集合 + biteTicks」，实际实现里候选站位集合只解析时用
 * （{@link Fishery#resolve} 内部），Spot 只保留**最终选中的那一格**——员工只需要它。</p>
 *
 * <p>{@code biteTicks} 是解析时按「没有附魔」算的基础值；真正抛竿时还会用
 * {@link Fishery#biteTicks} 按当前钓竿的饵钓等级重算一次。</p>
 */
public record FisherySpot(BlockPos water, BlockPos stand, int biteTicks) {
}
