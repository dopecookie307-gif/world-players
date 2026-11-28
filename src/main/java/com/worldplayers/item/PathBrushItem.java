package com.worldplayers.item;

import com.worldplayers.client.PathBrushClient;
import com.worldplayers.client.PathStore;
import com.worldplayers.resident.path.PathNode;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 路径笔（第一版临时用木棍的模型）。
 *
 * <p>第一轮负责「画 + 看」；2026-11-24（`0.3.1-alpha.4`）右键改成**连线模式**：</p>
 * <ul>
 *   <li><b>左键</b>同一个位置三态：放路径点 → 升成锚点 → 删除；</li>
 *   <li><b>右键</b>点一个已有的点：选中它（进入连线模式）→ 再右键另一个点 = 把两点连起来，连完退出；</li>
 *   <li><b>Shift + 右键</b>任何地方：退出连线模式；</li>
 *   <li><b>Alt + 滚轮</b>：有选中的点时改那个点的动作，否则切「下一个点的动作」（客户端处理）；</li>
 *   <li>手持时渲染半径 32 格内的锚点 / 路径点 / 路段（客户端 {@code PathRenderer}）。</li>
 * </ul>
 *
 * <p>点放「被点击方块面的外侧一格」，像往方块面上插火把，不占用被点的方块本身。</p>
 */
public class PathBrushItem extends Item {
    public PathBrushItem(Properties properties) {
        super(properties);
    }

    /** 右键方块：选点 / 连线（2026-11-24 主人要求）；Shift+右键退出连接模式。 */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!context.getLevel().isClientSide || context.getPlayer() == null) {
            return InteractionResult.SUCCESS;
        }
        Player player = context.getPlayer();
        BlockPos clicked = context.getClickedPos().relative(context.getClickedFace());
        // Shift+右键（点到方块、点到点、点到空气都算）：退出连接模式
        if (player.isShiftKeyDown()) {
            if (!PathBrushClient.clearSelection()) {
                PathBrushClient.actionBar("chat.worldplayers.path.no_selection");
            }
            return InteractionResult.SUCCESS;
        }
        PathNode node = PathStore.nodeAt(clicked);
        if (node == null) {
            PathBrushClient.actionBar("chat.worldplayers.path.no_node");
            return InteractionResult.SUCCESS;
        }
        PathNode selected = PathBrushClient.selectedNode();
        if (selected == null) {
            // 第一次右键：选中这个点，进入连接模式
            PathBrushClient.select(node);
            return InteractionResult.SUCCESS;
        }
        if (selected.id() == node.id()) {
            // 再点自己一次 = 取消
            PathBrushClient.clearSelection();
            return InteractionResult.SUCCESS;
        }
        // 第二次右键另一个点：连起来，然后**退出连接模式**
        PathBrushClient.linkTo(selected, node);
        return InteractionResult.SUCCESS;
    }

    /** 右键空气：提示当前动作模式 / 连接模式（渲染设置界面留到后面）。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide && hand == InteractionHand.MAIN_HAND) {
            if (player.isShiftKeyDown()) {
                if (!PathBrushClient.clearSelection()) {
                    PathBrushClient.actionBar("chat.worldplayers.path.no_selection");
                }
                return InteractionResultHolder.success(player.getItemInHand(hand));
            }
            if (PathBrushClient.selecting()) {
                PathBrushClient.actionBar("chat.worldplayers.path.selecting");
                return InteractionResultHolder.success(player.getItemInHand(hand));
            }
            player.displayClientMessage(Component.translatable(
                "chat.worldplayers.path.current_action",
                Component.translatable("gui.worldplayers.path.action."
                    + com.worldplayers.resident.path.PathAction.byOrdinal(
                        PathBrushClient.actionOrdinal()).id()))
                .withStyle(ChatFormatting.AQUA), true);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    /** 手持这个物品时是否显示教学面板（PathBrushHud 用）。 */
    public static boolean holdsBrush(Player player) {
        return player != null
            && (player.getMainHandItem().getItem() instanceof PathBrushItem
                || player.getOffhandItem().getItem() instanceof PathBrushItem);
    }

    private static void pathHint(Player player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
    }
}
