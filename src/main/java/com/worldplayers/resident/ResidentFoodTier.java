package com.worldplayers.resident;

import com.worldplayers.WorldPlayers;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;

/**
 * 食物分级（主人 2026-11-20）：**农作物能不吃就不吃**，留给玩家做更好的食物。
 *
 * <p>不说「哪几个 ID 是农作物」，而是两层自动判定：</p>
 * <ol>
 *   <li><b>方块判定</b>：物品是方块物品，且方块是 {@link CropBlock} 或属于 {@code #minecraft:crops}
 *       方块标签 → 任何模组作物只要用原版作物基类 / 加入原版作物标签就自动降级；</li>
 *   <li><b>数据标签</b>：{@code #worldplayers:crops} 留给数据包 / 整合包手动补（西瓜片这种没有方块物品的产物、
 *       或者作者用自己基类写的作物）。</li>
 * </ol>
 *
 * <p>等级只用在「挑食物」上，不影响能不能吃：只有背包里**没有任何普通食物**时才会去吃农作物。</p>
 */
public final class ResidentFoodTier {
    /** 0 = 普通食物（优先），1 = 农作物（次选）。 */
    public static final int NORMAL = 0;
    public static final int CROP = 1;

    /** 数据包可加的农作物物品标签。 */
    public static final TagKey<Item> CROPS_TAG =
        TagKey.create(Registries.ITEM, WorldPlayers.id("crops"));

    /**
     * 社区作物物品标签 {@code #c:crops}（NeoForge / Fabric 惯例，所有作物产物都在里面）。
     *
     * <p>2026-11-23 主人报「身上有牛排却先去吃卷心菜」：FarmersDelight 这类模组的作物产物
     * 往往不是原版 {@link CropBlock} 的方块物品，靠方块判定漏掉 → 被当成普通食物。
     * 现在把这个标签也算农作物级。</p>
     */
    private static final TagKey<Item> COMMON_CROPS_TAG =
        TagKey.create(Registries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("c", "crops"));

    public static int tier(ItemStack stack) {
        if (stack.isEmpty()) {
            return NORMAL;
        }
        if (stack.is(CROPS_TAG) || stack.is(COMMON_CROPS_TAG)) {
            return CROP;
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            net.minecraft.world.level.block.Block block = blockItem.getBlock();
            if (block instanceof CropBlock || block.defaultBlockState().is(BlockTags.CROPS)) {
                return CROP;
            }
        }
        return NORMAL;
    }

    private ResidentFoodTier() {
    }
}
