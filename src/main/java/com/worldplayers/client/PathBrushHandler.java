package com.worldplayers.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.worldplayers.WorldPlayers;
import com.worldplayers.item.PathBrushItem;
import com.worldplayers.network.PathEditPayload;
import com.worldplayers.resident.path.PathAction;
import com.worldplayers.resident.path.PathNode;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * 客户端：手持路径笔时的输入分流。
 *
 * <p>原版左键只对 4.5 格内的方块生效，路网要画 64 格，所以这里自己打射线再发包；
 * Alt+滚轮切换「下一个点的动作」，顺便吞掉滚轮，不许切到快捷栏。</p>
 */
@EventBusSubscriber(modid = WorldPlayers.MOD_ID, value = Dist.CLIENT)
public final class PathBrushHandler {
    /**
     * 这一次「按下左键」是否已经处理过。
     *
     * <p>主人 2026-11-24 报：按住左键（甚至手快地点一下）会连续触发多次，
     * 点刚放下就立刻变成锚点、再点就删掉 —— 所以一次按下只认**第一次**事件，
     * 松开左键（下一 tick 检测）才允许下一次编辑。</p>
     */
    private static boolean pressHandled;

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null || !minecraft.options.keyAttack.isDown()) {
            pressHandled = false;
        }
    }

    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        if (BedBindMode.active()) {
            return;
        }
        if (!(minecraft.player.getMainHandItem().getItem() instanceof PathBrushItem)) {
            return;
        }
        // 只在「按下」时编辑：按住期间每 tick 重复触发的攻击事件、以及松开时的那次事件，一律吞掉不处理
        if (minecraft.options.keyAttack.isDown()) {
            if (pressHandled) {
                event.setCanceled(true);
                return;
            }
            pressHandled = true;
        } else {
            event.setCanceled(true);
            return;
        }
        // 拿着路径笔时左键不该挖方块
        event.setCanceled(true);
        HitResult hit = minecraft.player.pick(PathEditPayload.RANGE, 1.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)
                || blockHit.getType() != HitResult.Type.BLOCK) {
            PathBrushClient.actionBar("chat.worldplayers.path.no_target");
            return;
        }
        Direction face = blockHit.getDirection();
        BlockPos pos = blockHit.getBlockPos().relative(face);
        PathBrushClient.cycleAt(pos);
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        if (!(minecraft.player.getMainHandItem().getItem() instanceof PathBrushItem)) {
            return;
        }
        if (!altDown(minecraft)) {
            return;
        }
        // 按住 Alt 时滚轮只切动作，**绝不能**滚到快捷栏
        event.setCanceled(true);
        boolean backwards = event.getScrollDeltaY() < 0.0D;
        // 连接模式里选了点：滚轮直接改「这个被选中的点」的动作
        PathNode selected = PathBrushClient.selectedNode();
        if (selected != null) {
            PathAction action = PathBrushClient.peekAction(selected, backwards);
            PathBrushClient.actionForSelected(selected, action);
            PathBrushClient.showAction(action);
            return;
        }
        PathAction action = PathBrushClient.cycleAction(backwards);
        PathBrushClient.showAction(action);
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        if (PathBrushHud.handleClick(minecraft)) {
            event.setCanceled(true);
        }
    }

    private static boolean altDown(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        return InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT)
            || InputConstants.isKeyDown(window, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT);
    }

    private PathBrushHandler() {
    }
}
