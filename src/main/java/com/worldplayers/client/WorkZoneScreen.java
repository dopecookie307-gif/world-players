package com.worldplayers.client;

import com.worldplayers.resident.WorkZone;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 工作区域命名 / 选类型页面（v0.2.12）。
 *
 * <p>和手册同一套纸张主题，但**没有侧边栏**；只能 ✕ 或 ESC 关。
 * 顶部输入框（占位「输入工作区域名称」）、中间选类型（道路=石砖 / 挖掘=铁铲 / 填充=泥土 …）、
 * 最底「储存」。按钮全部用原版按钮 sprite（`widget/button`），所以换材质包会跟着变。</p>
 *
 * <p>v0.2.15（2026-11-22）：类型行高 28 → 24、起点 64 → 56，给 8 种类型留出位置；
 * 同时加了**滚动条**，以后类型更多或小屏幕玩家都能滑动查看（主人要求）。</p>
 */
public class WorkZoneScreen extends Screen {
    private static final int PAGE_X = 20;
    /** 类型行间距：按钮 20 高 + 4 间隙（v0.2.15 从 28 缩小到 24）。 */
    private static final int ROW_H = 24;
    private static final int FIRST_ROW_Y = 56;
    private static final int BUTTON_H = 20;
    private static final int CLOSE_SIZE = 12;
    /** 类型列表底部留白（储存按钮上方）。 */
    private static final int LIST_BOTTOM_GAP = 52;
    private static final int SCROLL_W = 3;

    private final BlockPos min;
    private final BlockPos max;
    /** 第一选择点（聊天提示、清单坐标栏都用它）。 */
    private final BlockPos origin;
    private final ManualTheme theme = ManualThemes.current();
    private String selectedType = WorkZone.TYPE_ROAD;
    private EditBox nameBox;
    private int pageLeft;
    private int pageTop;
    /** 类型列表的像素滚动量（0 = 从第一个类型开始）。 */
    private int listScroll;

    public WorkZoneScreen(BlockPos min, BlockPos max, BlockPos origin) {
        super(Component.translatable("gui.worldplayers.zone.title"));
        this.min = min;
        this.max = max;
        this.origin = origin;
    }

    @Override
    protected void init() {
        this.pageLeft = (this.width - this.theme.pageWidth()) / 2;
        this.pageTop = (this.height - this.theme.pageHeight()) / 2;
        this.nameBox = new EditBox(this.font, this.pageLeft + PAGE_X, this.pageTop + 18,
            this.theme.pageWidth() - PAGE_X * 2, 20,
            Component.translatable("gui.worldplayers.zone.name_hint"));
        this.nameBox.setHint(Component.translatable("gui.worldplayers.zone.name_hint"));
        this.nameBox.setMaxLength(32);
        this.addRenderableWidget(this.nameBox);
    }

    /**
     * 主人 2026-11-20 明确要求：**有输入框的界面不要原版菜单模糊**。
     * 原版 `Screen#render` 会先调这个方法，而它的 `renderBlurredBackground` 会把整个屏幕糊掉；
     * 我们只用一层暗化（在 {@link #render} 里自己 fill），所以这里什么都不画。
     */
    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // 故意留空：不模糊
    }

    /** 类型列表的可见高度（滚动用）。 */
    private int listViewHeight() {
        return this.theme.pageHeight() - FIRST_ROW_Y - LIST_BOTTOM_GAP;
    }

    private int listContentHeight() {
        return WorkZone.types().size() * ROW_H;
    }

    private int maxListScroll() {
        return Math.max(0, listContentHeight() - listViewHeight());
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // 不要原版「菜单模糊」：只压暗，纸张和按钮保持清晰（主人 2026-11-19 要求）
        gui.fill(0, 0, this.width, this.height, 0x99000000);
        // 纸张投影 + 纸张
        gui.fill(this.pageLeft + 3, this.pageTop + 3,
            this.pageLeft + this.theme.pageWidth() + 3, this.pageTop + this.theme.pageHeight() + 3, 0x66000000);
        boolean tinted = this.theme.hasTint();
        if (tinted) {
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(
                this.theme.tintRed(), this.theme.tintGreen(), this.theme.tintBlue(), 1.0F);
        }
        gui.blit(this.theme.pageTexture(), this.pageLeft, this.pageTop, 0, 0,
            this.theme.pageWidth(), this.theme.pageHeight(),
            this.theme.textureWidth(), this.theme.textureHeight());
        if (tinted) {
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        // 类型列表（可滚动）
        List<String> types = WorkZone.types();
        this.listScroll = Mth.clamp(this.listScroll, 0, maxListScroll());
        int viewTop = this.pageTop + FIRST_ROW_Y;
        int viewH = listViewHeight();
        gui.enableScissor(this.pageLeft + 4, viewTop, this.pageLeft + this.theme.pageWidth() - 4,
            viewTop + viewH);
        String tooltipKey = null;
        // 渔场的提示很长（规则 5.1）：按像素宽度折行，其它类型的短提示照旧
        boolean wrapTooltip = false;
        for (int i = 0; i < types.size(); i++) {
            String type = types.get(i);
            int rowY = viewTop + i * ROW_H - this.listScroll;
            if (rowY + BUTTON_H < viewTop || rowY > viewTop + viewH) {
                continue;
            }
            boolean selected = type.equals(this.selectedType);
            // 选择按钮（靠页面右边）
            int buttonW = 70;
            int buttonX = this.pageLeft + this.theme.pageWidth() - PAGE_X - buttonW;
            // 图标（名称左边）
            gui.renderItem(iconFor(type), this.pageLeft + PAGE_X, rowY + 2);
            // 名称：太长就截断，别盖住右边的选择按钮（主人 2026-11-23）
            String typeLabel = Component.translatable(typeName(type)).getString();
            int nameX = this.pageLeft + PAGE_X + 22;
            int nameW = Math.max(8, buttonX - nameX - 4);
            String shownLabel = this.font.width(typeLabel) <= nameW ? typeLabel
                : this.font.plainSubstrByWidth(typeLabel,
                    Math.max(0, nameW - this.font.width("…"))) + "…";
            gui.drawString(this.font, shownLabel, nameX, rowY + 6, this.theme.inkColor(), false);
            boolean overButton = inside(mouseX, mouseY, buttonX, rowY, buttonW, BUTTON_H);
            // 悬浮提示：农田（不能种水稻）、挖掘 / 填充（规格 3.1）
            if (overButton) {
                if (WorkZone.TYPE_FARM.equals(type)) {
                    tooltipKey = "gui.worldplayers.zone.type.farm.tooltip";
                } else if (WorkZone.TYPE_MINE.equals(type)) {
                    tooltipKey = "gui.worldplayers.zone.type.mine.tooltip";
                } else if (WorkZone.TYPE_FISHERY.equals(type)) {
                    // 15 号规则 5.1：框只标站位，水不用框
                    tooltipKey = "gui.worldplayers.zone.type.fishery.tooltip";
                    wrapTooltip = true;
                } else if (WorkZone.TYPE_FILL.equals(type)) {
                    tooltipKey = "gui.worldplayers.zone.type.fill.tooltip";
                } else if (WorkZone.TYPE_ENTERTAINMENT.equals(type)) {
                    // 14 号文档 1.6：「用于恢复心情值 / For restoring Mood」
                    tooltipKey = "gui.worldplayers.zone.type.entertainment.tooltip";
                } else if (WorkZone.TYPE_FORBIDDEN.equals(type)) {
                    // 主人 2026-11-23：禁行区域说明
                    tooltipKey = "gui.worldplayers.zone.type.forbidden.tooltip";
                }
            }
            boolean hovered = overButton && !selected;
            this.drawVanillaButton(gui, buttonX, rowY, buttonW, BUTTON_H,
                Component.translatable(selected ? "gui.worldplayers.zone.chosen" : "gui.worldplayers.zone.choose"),
                hovered, selected, true);
        }
        gui.disableScissor();

        // 滚动条（内容放得下就不画）
        if (maxListScroll() > 0) {
            int sx = this.pageLeft + this.theme.pageWidth() - 8;
            gui.fill(sx, viewTop, sx + SCROLL_W, viewTop + viewH, 0x33000000);
            int thumb = Math.max(12, viewH * viewH / listContentHeight());
            int thumbY = viewTop + (viewH - thumb) * this.listScroll / maxListScroll();
            gui.fill(sx, thumbY, sx + SCROLL_W, thumbY + thumb, 0x99000000);
        }

        // 底部储存按钮
        int saveY = this.pageTop + this.theme.pageHeight() - 44;
        int saveW = this.theme.pageWidth() - PAGE_X * 2;
        boolean saveHovered = inside(mouseX, mouseY, this.pageLeft + PAGE_X, saveY, saveW, 22);
        this.drawVanillaButton(gui, this.pageLeft + PAGE_X, saveY, saveW, 22,
            Component.translatable("gui.worldplayers.zone.save"), saveHovered, false, true);

        // 关闭 ✕（纸张右上角外面）
        this.renderClose(gui, mouseX, mouseY);

        // 输入框最后画（在纸张上面）
        super.render(gui, mouseX, mouseY, partialTick);

        // 提示窗画在最上层
        if (tooltipKey != null) {
            Component text = Component.translatable(tooltipKey);
            if (wrapTooltip) {
                // 按**像素宽度**折行（15 号规则 5.1）：可用宽度 = 屏幕宽（已是 GUI 坐标）
                //   − 左右边距 − 图标宽度；每次渲染重算，窗口缩放 / 改 GUI scale 立刻跟着变。
                // 2026-11-23 主人要求「至少换一次行」：宽屏时再夹一个上限（240），
                // 否则英文那句会一口气占满整条屏幕宽度。
                int maxWidth = Math.max(120, Math.min(this.width - 36, 240));
                java.util.List<net.minecraft.util.FormattedCharSequence> lines =
                    this.font.split(text, maxWidth);
                gui.renderTooltip(this.font, lines, (int) mouseX, (int) mouseY);
            } else {
                gui.renderTooltip(this.font, text, (int) mouseX, (int) mouseY);
            }
        }
    }

    /** 原版按钮：普通 / 悬浮白边 / 按下（黑影 + 文字在黑影之上）。 */
    private void drawVanillaButton(GuiGraphics gui, int x, int y, int w, int h,
                                   Component label, boolean hovered, boolean pressed, boolean enabled) {
        ResourceLocation sprite = ResourceLocation.withDefaultNamespace(
            pressed ? "widget/button_disabled"
                : hovered ? "widget/button_highlighted" : "widget/button");
        gui.blitSprite(sprite, x, y, w, h);
        if (pressed) {
            gui.fill(x, y, x + w, y + h, 0x99000000);
        }
        int color = enabled ? 0xFFFFFF : 0xA0A0A0;
        int textY = y + (h - 8) / 2;
        gui.drawString(this.font, label, x + (w - this.font.width(label)) / 2, textY, color, true);
    }

    private ItemStack iconFor(String type) {
        // 和手册清单同一套图标（禁行 = 屏障，农田 = 锄头，道路 = 石砖）
        return ManualScreen.zoneIcon(type);
    }

    private static String typeName(String type) {
        return ManualScreen.zoneTypeKey(type);
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.nameBox != null && this.nameBox.isMouseOver(mouseX, mouseY)) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            if (this.isOverClose(mouseX, mouseY)) {
                ZoneUi.click();
                this.onClose();
                return true;
            }
            List<String> types = WorkZone.types();
            int viewTop = this.pageTop + FIRST_ROW_Y;
            for (int i = 0; i < types.size(); i++) {
                String type = types.get(i);
                int rowY = viewTop + i * ROW_H - this.listScroll;
                int buttonW = 70;
                int buttonX = this.pageLeft + this.theme.pageWidth() - PAGE_X - buttonW;
                if (inside(mouseX, mouseY, buttonX, rowY, buttonW, BUTTON_H)) {
                    if (!type.equals(this.selectedType)) {
                        this.selectedType = type;
                    }
                    ZoneUi.click();
                    return true;
                }
            }
            int saveY = this.pageTop + this.theme.pageHeight() - 44;
            int saveW = this.theme.pageWidth() - PAGE_X * 2;
            if (inside(mouseX, mouseY, this.pageLeft + PAGE_X, saveY, saveW, 22)) {
                ZoneUi.click();
                this.save();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxListScroll() > 0 && scrollY != 0.0D) {
            this.listScroll = Mth.clamp(this.listScroll - (int) Math.signum(scrollY) * ROW_H,
                0, maxListScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void save() {
        String name = this.nameBox == null ? "" : this.nameBox.getValue().strip();
        if (name.isEmpty()) {
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new com.worldplayers.network.ZoneSavePayload(
                name, this.selectedType, this.min, this.max, this.origin, ""));
        // 聊天提示（主人 2026-11-20）：道路一条文案，其它类型统一「××工作区」
        if (this.minecraft != null && this.minecraft.player != null) {
            BlockPos first = this.origin == null ? this.min : this.origin;
            Component line = WorkZone.TYPE_ROAD.equals(this.selectedType)
                ? Component.translatable("chat.worldplayers.zone.created_road",
                    name, first.getX(), first.getY(), first.getZ())
                : Component.translatable("chat.worldplayers.zone.created_work",
                    Component.translatable(typeName(this.selectedType)),
                    name, first.getX(), first.getY(), first.getZ());
            this.minecraft.player.displayClientMessage(line, false);
        }
        // 存完彻底清空草稿并退出规划模式
        RegionPlanner.clear();
        this.onClose();
    }

    private void renderClose(GuiGraphics gui, int mouseX, int mouseY) {
        int x = this.pageLeft + this.theme.pageWidth() + 4;
        int y = this.pageTop - CLOSE_SIZE - 4;
        boolean hovered = this.isOverClose(mouseX, mouseY);
        gui.fill(x, y, x + CLOSE_SIZE, y + CLOSE_SIZE,
            hovered ? this.theme.closeHoverColor() : this.theme.closeColor());
        // ✕ 简单两笔
        int glyph = this.theme.closeGlyphColor();
        for (int i = 2; i < CLOSE_SIZE - 2; i++) {
            gui.fill(x + i, y + i, x + i + 1, y + i + 1, glyph);
            gui.fill(x + CLOSE_SIZE - 1 - i, y + i, x + CLOSE_SIZE - i, y + i + 1, glyph);
        }
    }

    private boolean isOverClose(double mouseX, double mouseY) {
        int x = this.pageLeft + this.theme.pageWidth() + 4;
        int y = this.pageTop - CLOSE_SIZE - 4;
        return inside(mouseX, mouseY, x, y, CLOSE_SIZE, CLOSE_SIZE);
    }

    @Override
    public void onClose() {
        // 关掉这个页面只是回到规划模式，草稿还在
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
