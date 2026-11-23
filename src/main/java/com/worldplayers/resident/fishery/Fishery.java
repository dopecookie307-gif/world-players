package com.worldplayers.resident.fishery;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.WorkZone;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 渔场解析（纯静态，状态在 {@link FisherySpot} / {@link FisheryCache} 里，动作在
 * {@code ResidentWorkGoal.FisheryWork} 里）——规则见 `纸上谈兵\15-渔场规则.md` 第二节。
 *
 * <p>流程：区域内的**站位候选**（每格 + 上面一格，站得住）→ 4.5 格内所有水方块 →
 * 按「站位脚底 → 水中心」距离平方升序 → 逐个验视线 → **第一个通过就采用**。</p>
 *
 * <p>钓竿相关的数值（饵钓缩时间、海之眷顾提幸运）也放这里，咬钩与结算都走这一份。</p>
 */
public final class Fishery {
    /** 玩家方块交互距离：4.5 格（规则 2.2 第 2 条）。 */
    public static final double REACH = 4.5D;
    private static final double REACH_SQR = REACH * REACH;
    /** 眼睛高度（规则 2.2 第 4 条：脚底 + 1.62）。 */
    private static final double EYE_HEIGHT = 1.62D;
    /** 咬钩基础时间：5~30 秒（规则 3.3，照抄 fw）。 */
    public static final int BITE_MIN = 100;
    public static final int BITE_MAX = 600;
    /** 饵钓每级缩短的 tick。 */
    private static final int LURE_STEP = 100;

    /** 这一格是不是水（任意水，流水也算——规则 2.2 第 1 条）。 */
    public static boolean isWater(BlockState state) {
        return state.getFluidState().is(FluidTags.WATER);
    }

    /**
     * 解析这个渔场区域：找到「能站 + 够得着 + 看得见」的水，返回钓点。
     * 找不到（干涸）返回 {@code null}。
     */
    @Nullable
    public static FisherySpot resolve(ServerLevel level, WorkZone zone, ResidentEntity resident) {
        List<BlockPos> stands = stands(level, zone);
        if (stands.isEmpty()) {
            return null;
        }
        record Candidate(BlockPos stand, BlockPos water, double distance) {
        }
        List<Candidate> candidates = new ArrayList<>();
        int radius = (int) Math.ceil(REACH);
        for (BlockPos stand : stands) {
            Vec3 foot = new Vec3(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
            for (BlockPos water : BlockPos.betweenClosed(
                    stand.offset(-radius, -radius, -radius),
                    stand.offset(radius, radius, radius))) {
                Vec3 center = Vec3.atCenterOf(water);
                double sq = foot.distanceToSqr(center);
                if (sq > REACH_SQR || !isWater(level.getBlockState(water))) {
                    continue;
                }
                candidates.add(new Candidate(stand, water.immutable(), sq));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::distance));
        for (Candidate candidate : candidates) {
            if (hasLineOfSight(level, candidate.stand(), candidate.water(), resident)) {
                return new FisherySpot(candidate.water(), candidate.stand(),
                    biteTicks(level, ItemStack.EMPTY));
            }
        }
        return null;
    }

    /**
     * 站位候选（规则 2.1）：区域内每一格 + 它上面一格，脚下站得住、上方两格没有碰撞。
     *
     * <p>{@link ResidentEntity#isSafeStand} 已经保证「本格与头顶一格能站」，
     * 这里再补一格头顶（人高 1.8 格，两格才真的不会顶头）。</p>
     */
    private static List<BlockPos> stands(ServerLevel level, WorkZone zone) {
        java.util.Set<BlockPos> set = new java.util.LinkedHashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(zone.min(), zone.max())) {
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos stand = pos.above(dy).immutable();
                if (!ResidentEntity.isSafeStand(level, stand)) {
                    continue;
                }
                BlockPos head = stand.above(2);
                if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) {
                    continue;
                }
                set.add(stand);
            }
        }
        return new ArrayList<>(set);
    }

    /**
     * 视线（规则 2.2 第 4 条）：从站位眼睛打到水方块中心，打不穿方块才算数
     * （MISS，或者正好命中这格水）。
     */
    public static boolean hasLineOfSight(ServerLevel level, BlockPos stand, BlockPos water,
                                         ResidentEntity resident) {
        Vec3 from = new Vec3(stand.getX() + 0.5D, stand.getY() + EYE_HEIGHT, stand.getZ() + 0.5D);
        BlockHitResult hit = level.clip(new ClipContext(from, Vec3.atCenterOf(water),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, resident));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(water);
    }

    /** 一次咬钩要等多少 tick（规则 3.3）：100~600，饵钓每级 −100，最低 1。 */
    public static int biteTicks(ServerLevel level, ItemStack rod) {
        RandomSource random = level.getRandom();
        int ticks = BITE_MIN + random.nextInt(BITE_MAX - BITE_MIN + 1);
        return Math.max(1, ticks - enchant(level, rod, Enchantments.LURE) * LURE_STEP);
    }

    /** 海之眷顾等级 → 战利品幸运值（规则 3.3：走原版 loot 的 withLuck）。 */
    public static int luck(ServerLevel level, ItemStack rod) {
        return enchant(level, rod, Enchantments.LUCK_OF_THE_SEA);
    }

    /**
     * 原版钓鱼战利品（规则 3.4）：`BuiltInLootTables.FISHING`，鱼 / 靴子 / 书 / 腐肉都照原版出。
     */
    public static List<ItemStack> rollLoot(ServerLevel level, ItemStack rod, ResidentEntity angler,
                                           BlockPos water, int luck) {
        LootTable table = level.getServer().reloadableRegistries()
            .getLootTable(BuiltInLootTables.FISHING);
        LootParams params = new LootParams.Builder(level)
            .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(water))
            .withParameter(LootContextParams.TOOL, rod)
            .withParameter(LootContextParams.THIS_ENTITY, angler)
            .withLuck((float) luck)
            .create(LootContextParamSets.FISHING);
        return table.getRandomItems(params);
    }

    /** 读一件钓竿上的附魔等级（空栈 = 0）。 */
    private static int enchant(ServerLevel level, ItemStack stack,
                               ResourceKey<Enchantment> key) {
        if (stack.isEmpty()) {
            return 0;
        }
        Holder<Enchantment> holder = level.registryAccess()
            .lookupOrThrow(Registries.ENCHANTMENT)
            .getOrThrow(key);
        return EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
    }

    private Fishery() {
    }
}
