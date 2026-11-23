package com.worldplayers.resident.tree;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 树场能种的树种（主人 2026-11-21 定稿）。
 *
 * <p>规则（10 号文件「树场规则（定稿）」第 1、6、8 条）：</p>
 * <ul>
 *   <li>**树种**：无 / 橡木 / 云杉 / 白桦 / 丛林 / 金合欢 / 深色橡木 / 樱花；**排除红树**；默认「无」；</li>
 *   <li>**目标方块只认该树种的 `xxx_log`**——`xxx_wood`、去皮原木/木头、其他方块统统不算；</li>
 *   <li>**树叶只处理「这棵树自己」的叶子**（同树种的 `xxx_leaves`）；别的树/装饰叶不碰。</li>
 * </ul>
 */
public enum TreeSpecies {
    NONE("none", null, null, null),
    OAK("oak", Blocks.OAK_LOG, Blocks.OAK_LEAVES, Items.OAK_SAPLING),
    SPRUCE("spruce", Blocks.SPRUCE_LOG, Blocks.SPRUCE_LEAVES, Items.SPRUCE_SAPLING),
    BIRCH("birch", Blocks.BIRCH_LOG, Blocks.BIRCH_LEAVES, Items.BIRCH_SAPLING),
    JUNGLE("jungle", Blocks.JUNGLE_LOG, Blocks.JUNGLE_LEAVES, Items.JUNGLE_SAPLING),
    ACACIA("acacia", Blocks.ACACIA_LOG, Blocks.ACACIA_LEAVES, Items.ACACIA_SAPLING),
    DARK_OAK("dark_oak", Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_LEAVES, Items.DARK_OAK_SAPLING),
    CHERRY("cherry", Blocks.CHERRY_LOG, Blocks.CHERRY_LEAVES, Items.CHERRY_SAPLING);

    private final String id;
    @Nullable
    private final Block log;
    @Nullable
    private final Block leaves;
    @Nullable
    private final Item sapling;

    TreeSpecies(String id, @Nullable Block log, @Nullable Block leaves, @Nullable Item sapling) {
        this.id = id;
        this.log = log;
        this.leaves = leaves;
        this.sapling = sapling;
    }

    /** 存档 / 网络用的 id（`none` / `oak` / `dark_oak` …）。 */
    public String id() {
        return this.id;
    }

    /** 目标原木方块（「无」返回 null）。 */
    @Nullable
    public Block logBlock() {
        return this.log;
    }

    /** 这棵树自己的树叶方块（「无」返回 null）。 */
    @Nullable
    public Block leavesBlock() {
        return this.leaves;
    }

    /** 树苗物品（「无」返回 null）。 */
    @Nullable
    public Item saplingItem() {
        return this.sapling;
    }

    /** 树苗方块（种下去的那一格；「无」返回 null）。 */
    @Nullable
    public Block saplingBlock() {
        if (this.sapling == null) {
            return null;
        }
        return this.sapling instanceof net.minecraft.world.item.BlockItem blockItem
            ? blockItem.getBlock() : null;
    }

    /** 树苗物品的图标（下拉栏 / 清单显示用；「无」用屏障）。 */
    public ItemStack icon() {
        return this.sapling == null
            ? new ItemStack(Items.BARRIER) : new ItemStack(this.sapling);
    }

    /** 这个方块是不是本树种的目标原木（`xxx_log` 精确匹配，木头/去皮都不算）。 */
    public boolean isTargetLog(BlockState state) {
        return this.log != null && state.getBlock() == this.log;
    }

    /** 这个方块是不是本树种自己的树叶。 */
    public boolean isOwnLeaves(BlockState state) {
        return this.leaves != null && state.getBlock() == this.leaves;
    }

    /** 这格是不是**本树种**的树苗。 */
    public boolean isOwnSapling(BlockState state) {
        Block sapling = this.saplingBlock();
        return sapling != null && state.getBlock() == sapling;
    }

    /** 是不是任何树苗（违规清扫用）。 */
    public static boolean isAnySapling(BlockState state) {
        return state.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock;
    }

    /**
     * 这种树要不要 2×2 种植（2026-11-22 主人要求去掉「种植模式」选项，改成按树种自动）。
     *
     * <ul>
     *   <li>**深色橡木**：原版只有 2×2 才长成树，单株永远长不大；</li>
     *   <li>**丛林**：2×2 才是「大丛林树」，单株只是小树，产量差得多；</li>
     *   <li>其余（橡木 / 云杉 / 白桦 / 金合欢 / 樱花）：单株最好，种 2×2 反而难看。</li>
     * </ul>
     */
    public boolean needsTwoByTwo() {
        return this == DARK_OAK || this == JUNGLE;
    }

    /** id → 树种（找不到 / 未知 / 旧存档 → 默认「无」）。 */
    public static TreeSpecies byId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return NONE;
        }
        for (TreeSpecies species : values()) {
            if (species.id.equals(id)) {
                return species;
            }
        }
        return NONE;
    }

    /** 下拉栏的顺序（「无」永远在最前面）。 */
    public static List<TreeSpecies> selectable() {
        return List.of(values());
    }
}
