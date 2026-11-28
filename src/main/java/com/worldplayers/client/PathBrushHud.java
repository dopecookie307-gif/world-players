package com.worldplayers.client;

import com.worldplayers.WorldPlayers;
import com.worldplayers.config.WPClientConfig;
import com.worldplayers.item.PathBrushItem;
import com.worldplayers.resident.path.PathAction;
import com.worldplayers.resident.path.PathNode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 路径笔的左侧教学面板（草案 12.2：像规划模式那样写在屏幕左侧，可折叠）。
 *
 * <p>只在手持路径笔、没开任何界面、没隐藏 HUD 时显示；折叠状态存客户端配置。</p>
 */
@EventBusSubscriber(modid = WorldPlayers.MOD_ID, value = Dist.CLIENT)
public final class PathBrushHud {
    private static final int X = 6;
    private static final int Y = 40;
    private static final int WIDTH = 226;
    private static final int ROW = 10;
    private static final int PAD = 5;
    /** 关闭按钮（折叠开关）在 GUI 里的位置，每帧重算。 */
    private static int closeX;
    private static int closeY;
    private static int closeW;
    private static int closeH;

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null
                || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        if (!PathBrushItem.holdsBrush(minecraft.player)) {
            return;
        }
        boolean open = WPClientConfig.pathTutorialOpen();
        PathAction action = PathAction.byOrdinal(PathBrushClient.actionOrdinal());
        List<Component> rows = open ? rows(action) : List.of();
        int height = PAD * 2 + ROW + rows.size() * ROW;
        GuiGraphics gui = event.getGuiGraphics();
        gui.fill(X, Y, X + WIDTH, Y + height, 0xC0101018);
        gui.renderOutline(X, Y, WIDTH, height, 0x60FFFFFF);
        gui.drawString(minecraft.font,
            Component.translatable("gui.worldplayers.path.tutorial.title"), X + PAD, Y + PAD, 0xFFFFFF, false);
        closeW = 12;
        closeH = 10;
        closeX = X + WIDTH - PAD - closeW;
        closeY = Y + PAD - 1;
        gui.drawString(minecraft.font, open ? "-" : "+", closeX + 4, closeY, 0xFFFFFF, false);
        int y = Y + PAD + ROW;
        for (Component row : rows) {
            gui.drawString(minecraft.font, row, X + PAD, y, 0xE0E0E0, false);
            y += ROW;
        }
    }

    private static List<Component> rows(PathAction action) {
        List<Component> rows = new ArrayList<>();
        rows.add(Component.translatable("gui.worldplayers.path.tutorial.cycle"));
        rows.add(Component.translatable("gui.worldplayers.path.tutorial.link"));
        rows.add(Component.translatable("gui.worldplayers.path.tutorial.cancel_link"));
        rows.add(Component.translatable("gui.worldplayers.path.tutorial.scroll"));
        rows.add(Component.translatable("gui.worldplayers.path.tutorial.current",
            Component.translatable("gui.worldplayers.path.action." + action.id())));
        // 连线模式里选中了某个点：多显示一行「已选中」和它的动作（2026-11-24）
        PathNode selected = PathBrushClient.selectedNode();
        if (selected != null) {
            rows.add(Component.translatable("gui.worldplayers.path.tutorial.selected",
                selected.pos().getX(), selected.pos().getY(), selected.pos().getZ(),
                Component.translatable("gui.worldplayers.path.action." + selected.action().id())));
        }
        rows.add(Component.translatable("gui.worldplayers.path.tutorial.colors"));
        rows.add(Component.translatable("gui.worldplayers.path.tutorial.radius", PathRenderer.RADIUS));
        return rows;
    }

    /**
     * 无界面时的左键：点「-/+」折叠/展开面板。
     *
     * @return 有没有吃掉这次点击
     */
    static boolean handleClick(Minecraft minecraft) {
        if (minecraft.player == null || !PathBrushItem.holdsBrush(minecraft.player)) {
            return false;
        }
        double guiX = minecraft.mouseHandler.xpos()
            * minecraft.getWindow().getGuiScaledWidth()
            / Math.max(1.0D, minecraft.getWindow().getScreenWidth());
        double guiY = minecraft.mouseHandler.ypos()
            * minecraft.getWindow().getGuiScaledHeight()
            / Math.max(1.0D, minecraft.getWindow().getScreenHeight());
        if (guiX >= closeX && guiX <= closeX + closeW
                && guiY >= closeY && guiY <= closeY + closeH) {
            WPClientConfig.setPathTutorialOpen(!WPClientConfig.pathTutorialOpen());
            WPClientConfig.save();
            return true;
        }
        return false;
    }

    private PathBrushHud() {
    }
}
