package com.worldplayers.resident.project;

import com.worldplayers.resident.WorkZone;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 挖掘 / 填充的纯规则判定（规格 5.4、5.5）：不持有状态，员工 goal 与队列共用。
 */
public final class ProjectRules {
    /**  digs 这一格需要什么工具（规格 5.4 的判定表）。 */
    public enum ToolKind {
        /** 石类：必须镐。 */
        PICKAXE,
        /** 木类：必须斧。 */
        AXE,
        /** 泥沙类：有铲 2 倍速，没铲照常挖。 */
        SHOVEL,
        /** 其他：徒手。 */
        NONE
    }

    /** 这一格还该不该挖（规格 5.4 的 {@code isDiggable}）。 */
    public static boolean isDiggable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !state.isAir()
            && !(state.getBlock() instanceof LiquidBlock)
            && state.getDestroySpeed(level, pos) >= 0.0F;
    }

    /**
     * 这一格还该不该填（规格 5.5）：空气 / 液体 / **无碰撞箱的装饰方块**（火把花草藤蔓甘蔗作物红石线…）。
     * 玻璃 / 树叶 / 雪层 / 地毯有碰撞箱 → 不算。
     */
    public static boolean isFillable(ServerLevel level, BlockPos pos, Block target) {
        BlockState state = level.getBlockState(pos);
        if (target != null && state.is(target)) {
            return false;
        }
        if (state.isAir()) {
            return true;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            return true;
        }
        return state.getCollisionShape(level, pos).isEmpty();
    }

    /** 需要什么工具：先石、再木、再泥沙，最后徒手。 */
    public static ToolKind toolFor(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return ToolKind.PICKAXE;
        }
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return ToolKind.AXE;
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return ToolKind.SHOVEL;
        }
        return ToolKind.NONE;
    }

    /** 这种工具是不是「必须有」（镐 / 斧）；铲是可选加速。 */
    public static boolean required(ToolKind kind) {
        return kind == ToolKind.PICKAXE || kind == ToolKind.AXE;
    }

    /** 工具种类 → 背包匹配条件。 */
    public static Predicate<ItemStack> matcher(ToolKind kind) {
        return switch (kind) {
            case PICKAXE -> stack -> stack.getItem() instanceof PickaxeItem;
            case AXE -> stack -> stack.getItem() instanceof AxeItem;
            case SHOVEL -> stack -> stack.getItem() instanceof ShovelItem;
            case NONE -> stack -> false;
        };
    }

    /** 阻塞工具的喊话语言键（镐 / 斧）；铲不需要喊话。 */
    public static String missingToolKey(ToolKind kind) {
        return kind == ToolKind.AXE
            ? "chat.worldplayers.project.need_axe" : "chat.worldplayers.project.need_pickaxe";
    }

    /** 方块 id → 方块；空气 / 非法 / 排除清单里的方块一律 null。 */
    @Nullable
    public static Block blockById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.BLOCK.containsKey(location)) {
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(location);
        return isValidFillTarget(block) ? block : null;
    }

    /**
     * 能不能当填充目标（规格 4.6 排除清单）：
     * 空气、液体、屏障、命令方块、结构方块、拼图方块、光源方块、没有对应物品的方块一律不行。
     */
    public static boolean isValidFillTarget(@Nullable Block block) {
        if (block == null || block == Blocks.AIR) {
            return false;
        }
        if (block instanceof LiquidBlock) {
            return false;
        }
        if (block == Blocks.BARRIER || block == Blocks.COMMAND_BLOCK
                || block == Blocks.CHAIN_COMMAND_BLOCK || block == Blocks.REPEATING_COMMAND_BLOCK
                || block == Blocks.STRUCTURE_BLOCK || block == Blocks.JIGSAW
                || block == Blocks.LIGHT || block == Blocks.STRUCTURE_VOID) {
            return false;
        }
        return block.asItem() != net.minecraft.world.item.Items.AIR;
    }

    /** 方块的可读名字（聊天提示用，跟随当前语言）。 */
    public static Component nameOf(@Nullable Block block) {
        return block == null ? Component.empty() : block.getName();
    }

    /** 方块 → 背包里对应的物品（拿不到 → 空栈）。 */
    public static ItemStack itemOf(Block block) {
        return block == null ? ItemStack.EMPTY : new ItemStack(block.asItem());
    }

    /** 方块对应的 BlockItem（摆放要用；不是方块物品 → null）。 */
    @Nullable
    public static BlockItem blockItemOf(Block block) {
        return block != null && block.asItem() instanceof BlockItem item ? item : null;
    }

    /** 区域 id（给 tag 用，暂时只留接口）。 */
    public static net.minecraft.tags.TagKey<Block> fillTargetsTag() {
        return net.minecraft.tags.TagKey.create(Registries.BLOCK,
            com.worldplayers.WorldPlayers.id("fill_targets"));
    }

    private ProjectRules() {
    }
}
