package com.worldplayers.client;

import com.worldplayers.item.PathBrushItem;
import com.worldplayers.network.PathEditPayload;
import com.worldplayers.resident.path.PathAction;
import com.worldplayers.resident.path.PathNode;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 路径笔的客户端状态：当前动作、链式连段的「上一个点」。
 *
 * <p>动作模式只影响「下一个放下的点」，所以放点包自己带上动作序号，服务端不保存任何模式状态。</p>
 */
public final class PathBrushClient {
    /** 当前放置动作（默认走）。 */
    private static int actionOrdinal;
    /** 上一个放下的点（链式连段用；换维度/删掉后自然失效）。 */
    @Nullable
    private static BlockPos lastPlaced;
    /** 连线模式里「已经选中」的那个点（2026-11-24 主人：右键选点 → 再右键另一个点连线）。 */
    @Nullable
    private static BlockPos selectedPos;

    public static int actionOrdinal() {
        return actionOrdinal;
    }

    /** 连线模式选中的点（当前维度）；没有就在世界变化时自动失效。 */
    @Nullable
    public static PathNode selectedNode() {
        if (selectedPos == null) {
            return null;
        }
        PathNode node = PathStore.nodeAt(selectedPos);
        if (node == null) {
            selectedPos = null;
        }
        return node;
    }

    public static boolean selecting() {
        return selectedNode() != null;
    }

    /** 右键点一个点 = 进入连线模式并选中它。 */
    public static void select(PathNode node) {
        selectedPos = node.pos();
        actionBar("chat.worldplayers.path.selected",
            node.pos().getX(), node.pos().getY(), node.pos().getZ());
    }

    /** Shift+右键（或再点一次自己）：退出连线模式。返回有没有真的退出。 */
    public static boolean clearSelection() {
        if (selectedPos == null) {
            return false;
        }
        selectedPos = null;
        actionBar("chat.worldplayers.path.link_cancelled");
        return true;
    }

    /**
     * 连线模式：把选中的点接到另一个点上，**连完立刻退出连接模式**。
     *
     * @return 有没有发出连线请求
     */
    public static boolean linkTo(PathNode from, PathNode to) {
        if (from.id() == to.id()) {
            clearSelection();
            return false;
        }
        sendEdit(PathEditPayload.OP_LINK, to.pos(), from.id(), 0);
        selectedPos = null;
        return true;
    }

    /** 选中的点换动作（Alt+滚轮）：直接发服务端改这个点。 */
    public static void actionForSelected(PathNode node, PathAction action) {
        sendEdit(PathEditPayload.OP_SET_ACTION, node.pos(), -1, action.ordinal());
    }

    /** Alt+滚轮：切换下一个点的动作，返回新动作。 */
    public static PathAction cycleAction(boolean backwards) {
        PathAction[] values = PathAction.values();
        actionOrdinal = Math.floorMod(actionOrdinal + (backwards ? -1 : 1), values.length);
        return values[actionOrdinal];
    }

    /** 选中某个点时滚轮会切到哪个动作（不改全局「下一个点的动作」）。 */
    public static PathAction peekAction(PathNode node, boolean backwards) {
        PathAction[] values = PathAction.values();
        return values[Math.floorMod(node.action().ordinal() + (backwards ? -1 : 1), values.length)];
    }

    /** 放点包里的「上一个点」id（找不到就是 -1，不连段）。 */
    private static int linkFromId() {
        if (lastPlaced == null) {
            return -1;
        }
        PathNode node = PathStore.nodeAt(lastPlaced);
        return node == null ? -1 : node.id();
    }

    /** 发一次编辑请求（当前维度）。 */
    public static void sendEdit(int op, BlockPos pos, int linkFrom, int action) {
        Minecraft minecraft = Minecraft.getInstance();
        net.minecraft.resources.ResourceLocation dimension = PathStore.currentDimension();
        if (minecraft.player == null || dimension == null
                || !(minecraft.player.getMainHandItem().getItem() instanceof PathBrushItem)) {
            return;
        }
        PacketDistributor.sendToServer(new PathEditPayload(op, pos, dimension, linkFrom, action));
    }

    /** 左键三态：放/升级/删除，并把这里记成「上一个操作过的点」（服务端还会按空间最近兜底）。 */
    public static void cycleAt(BlockPos pos) {
        sendEdit(PathEditPayload.OP_CYCLE, pos, linkFromId(), actionOrdinal);
        lastPlaced = pos;
    }

    /** 聊天栏提示（actionbar）。 */
    public static void actionBar(String key, Object... args) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                Component.translatable(key, args).withStyle(ChatFormatting.AQUA), true);
        }
    }

    /** 动作提示：切到「跳 / 爬梯 / 传送」时说一声。 */
    public static void showAction(PathAction action) {
        actionBar("gui.worldplayers.path.action_switched",
            Component.translatable("gui.worldplayers.path.action." + action.id()));
    }

    /** 丢弃链（切换物品时用；保留给后面的撤销功能）。 */
    public static void resetChain() {
        lastPlaced = null;
    }

    private PathBrushClient() {
    }
}
