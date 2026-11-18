package com.worldplayers.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.worldplayers.config.WPClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.lwjgl.glfw.GLFW;

/**
 * 居民手册页面。
 *
 * <p>布局：纸的左侧是一列侧边栏（栏位 = 图标 + 名称），右侧是当前栏位的内容。</p>
 *
 * <ul>
 *   <li><b>区域选择</b>：内容暂空</li>
 *   <li><b>手册渲染</b>：四个勾选项（居民状态浮窗 / 工作区域 / 城市道路 / 巡逻路径），
 *       直接读写 {@link WPClientConfig}，勾选立即影响渲染并存盘</li>
 * </ul>
 *
 * <p>侧边栏左上角是编辑按钮（白色羽毛笔）。点它进入编辑模式、图标变绿色 ✓：
 * 编辑模式下可以拖动栏位排序，点 ✓ 记下顺序（写进配置）；不点 ✓ 直接关掉则不变。</p>
 *
 * <p>声音：打开随机播一个原版翻页音效；点 ✕ / 切栏位 / 勾选 / 编辑按钮播 UI 按钮音效；
 * 按 E 或 ESC 关闭时**不发声**。</p>
 */
public class ManualScreen extends Screen {
    private static final int CLOSE_SIZE = 12;
    private static final int CLOSE_GAP = 3;

    // ---- 纸张内布局（相对纸张左上角；渲染时会整体按 pageScale 缩放） ----
    // 侧边栏整个在纸张**外面**（纸张左边），所以 x 是负的
    private static final int SIDEBAR_W = 76;
    private static final int SIDEBAR_GAP = 6;
    private static final int PEN_X = -SIDEBAR_W - SIDEBAR_GAP;
    private static final int PEN_Y = 0;
    private static final int PEN_SIZE = 18;

    private static final int TAB_X = PEN_X;
    private static final int TAB_TOP = 26;
    private static final int TAB_W = SIDEBAR_W;
    private static final int TAB_H = 24;
    private static final int TAB_GAP = 5;

    private static final int CONTENT_X = 18;
    private static final int CHECK_TOP = 44;
    private static final int CHECK_ROW = 22;
    private static final int CHECK_SIZE = 11;

    private static final int TAB_REGION = 0;
    private static final int TAB_RENDER = 1;
    private static final int TAB_COUNT = 2;

    private static final String[] TAB_IDS = {"region", "render"};
    /** 侧边栏栏位名：走语言文件（zh_cn / en_us）。 */
    private static final String[] TAB_KEYS = {
        "gui.worldplayers.manual.tab.region",
        "gui.worldplayers.manual.tab.render"
    };
    private static final ResourceLocation[] TAB_ICONS = {
        ResourceLocation.withDefaultNamespace("textures/item/map.png"),
        ResourceLocation.withDefaultNamespace("textures/item/writable_book.png")
    };
    private static final ResourceLocation ICON_PEN =
        ResourceLocation.withDefaultNamespace("textures/item/feather.png");

    /** 手册渲染四项勾选的文字：走语言文件。 */
    private static final String[] RENDER_KEYS = {
        "gui.worldplayers.manual.render.status_card",
        "gui.worldplayers.manual.render.work_area",
        "gui.worldplayers.manual.render.city_road",
        "gui.worldplayers.manual.render.patrol_path"
    };

    private ManualTheme theme = ManualThemes.current();
    private final RandomSource random = RandomSource.create();

    private int pageLeft;
    private int pageTop;
    private int pageW;
    private int pageH;
    private float pageScale = 1.0F;

    private boolean pageTurnPlayed;

    /** 侧边栏显示顺序（存的是栏位 id：0=区域选择，1=手册渲染）。 */
    private final int[] order = new int[TAB_COUNT];
    private int currentTab = TAB_REGION;

    private boolean editMode;
    /** 正在拖动的栏位 id；-1 = 没在拖。 */
    private int dragTab = -1;
    private int dragSlot = -1;
    private int hoverSlot = -1;

    public ManualScreen() {
        super(Component.translatable("gui.worldplayers.manual.title"));
        this.loadOrder();
    }

    /** 客户端入口：右键手册打开这一页。 */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ManualScreen());
    }

    // ------------------------------------------------------------------
    // 生命周期 / 布局
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        this.theme = ManualThemes.current();

        // 打开页面 → 随机一个原版翻页音效（窗口尺寸变化会重新 init，别重复响）
        if (!this.pageTurnPlayed && this.minecraft != null) {
            this.pageTurnPlayed = true;
            this.playPageTurn();
        }

        // 纸张比居民 GUI（176×300）大一圈；侧边栏在纸张左边，一起算进宽度
        float sideW = SIDEBAR_W + SIDEBAR_GAP;
        float fitH = (this.height - 2 * (CLOSE_SIZE + CLOSE_GAP + 4)) / (float) this.theme.pageHeight();
        float fitW = (this.width - 24) / (sideW + this.theme.pageWidth());
        this.pageScale = Math.min(1.0F, Math.min(fitH, fitW));

        this.pageW = Math.max(1, Math.round(this.theme.pageWidth() * this.pageScale));
        this.pageH = Math.max(1, Math.round(this.theme.pageHeight() * this.pageScale));

        int totalW = Math.round(sideW * this.pageScale) + this.pageW;
        this.pageLeft = (this.width - totalW) / 2 + Math.round(sideW * this.pageScale);
        this.pageTop = Math.max(CLOSE_SIZE + CLOSE_GAP + 2, (this.height - this.pageH) / 2);

        // 窗口尺寸变了，拖动状态作废
        this.dragTab = -1;
        this.dragSlot = -1;
        this.hoverSlot = -1;
    }

    private void loadOrder() {
        boolean[] used = new boolean[TAB_COUNT];
        int n = 0;
        String raw = WPClientConfig.sidebarOrder();
        for (String part : raw.split(",")) {
            int tab = tabFromId(part.trim());
            if (tab >= 0 && !used[tab]) {
                this.order[n++] = tab;
                used[tab] = true;
            }
        }
        for (int tab = 0; tab < TAB_COUNT && n < TAB_COUNT; tab++) {
            if (!used[tab]) {
                this.order[n++] = tab;
                used[tab] = true;
            }
        }
    }

    private static int tabFromId(String id) {
        for (int i = 0; i < TAB_COUNT; i++) {
            if (TAB_IDS[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** 把当前内存里的顺序写进配置（只有点 ✓ 才调用）。 */
    private void saveOrder() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < TAB_COUNT; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(TAB_IDS[this.order[i]]);
        }
        WPClientConfig.setSidebarOrder(builder.toString());
        WPClientConfig.save();
    }

    private void moveSlot(int from, int to) {
        int tab = this.order[from];
        if (from < to) {
            System.arraycopy(this.order, from + 1, this.order, from, to - from);
        } else if (from > to) {
            System.arraycopy(this.order, to, this.order, to + 1, from - to);
        }
        this.order[to] = tab;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);

        // 纸张投影
        guiGraphics.fill(this.pageLeft + 3, this.pageTop + 3,
            this.pageLeft + this.pageW + 3, this.pageTop + this.pageH + 3, 0x66000000);

        float pageX = this.toPageX(mouseX);
        float pageY = this.toPageY(mouseY);

        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(this.pageLeft, this.pageTop, 0.0F);
        guiGraphics.pose().scale(this.pageScale, this.pageScale, 1.0F);

        this.renderPage(guiGraphics);
        this.renderSidebar(guiGraphics, pageX, pageY);
        this.renderContent(guiGraphics, pageX, pageY);
        this.renderDragGhost(guiGraphics, pageX, pageY);

        guiGraphics.pose().popPose();

        this.renderClose(guiGraphics, mouseX, mouseY);
    }

    /** 画纸张本身（8 参 blit：显式给纹理尺寸，否则会被按 256×256 采样拉伸）。 */
    private void renderPage(GuiGraphics guiGraphics) {
        boolean tinted = this.theme.hasTint();
        if (tinted) {
            RenderSystem.setShaderColor(this.theme.tintRed(), this.theme.tintGreen(),
                this.theme.tintBlue(), 1.0F);
        }
        guiGraphics.blit(this.theme.pageTexture(), 0, 0, 0, 0,
            this.theme.pageWidth(), this.theme.pageHeight(),
            this.theme.textureWidth(), this.theme.textureHeight());
        if (tinted) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private void renderSidebar(GuiGraphics guiGraphics, float mouseX, float mouseY) {
        // ---- 左上角：编辑按钮（白色羽毛笔 ↔ 绿色 ✓） ----
        boolean penHovered = inside(mouseX, mouseY, PEN_X, PEN_Y, PEN_SIZE, PEN_SIZE);
        if (penHovered && !this.editMode) {
            guiGraphics.fill(PEN_X, PEN_Y, PEN_X + PEN_SIZE, PEN_Y + PEN_SIZE,
                this.theme.tabHoverColor());
        }
        if (this.editMode) {
            guiGraphics.fill(PEN_X, PEN_Y, PEN_X + PEN_SIZE, PEN_Y + PEN_SIZE,
                this.theme.tabActiveColor());
            drawTick(guiGraphics, PEN_X + 2, PEN_Y + 2, 14, this.theme.editIconColor());
        } else {
            guiGraphics.blit(ICON_PEN, PEN_X + 1, PEN_Y + 1, 0, 0, 16, 16, 16, 16);
        }

        // ---- 栏位 ----
        for (int slot = 0; slot < TAB_COUNT; slot++) {
            int x = TAB_X;
            int y = TAB_TOP + slot * (TAB_H + TAB_GAP);
            int tab = this.order[slot];

            boolean dragging = this.dragTab >= 0;
            boolean emptied = dragging && (slot == this.dragSlot || slot == this.hoverSlot);

            if (emptied) {
                drawEmptySlot(guiGraphics, x, y);
                // 拖拽悬停在别的栏位上时：原位置出现"被悬浮栏位的半透明影像"
                if (slot == this.dragSlot && this.hoverSlot >= 0 && this.hoverSlot != this.dragSlot) {
                    drawTab(guiGraphics, x, y, this.order[this.hoverSlot], false, false, 0.45F);
                }
            } else {
                boolean hovered = inside(mouseX, mouseY, x, y, TAB_W, TAB_H) && !dragging;
                drawTab(guiGraphics, x, y, tab, tab == this.currentTab, hovered, 1.0F);
            }
        }
    }

    private void renderContent(GuiGraphics guiGraphics, float mouseX, float mouseY) {
        if (this.currentTab != TAB_RENDER) {
            return;
        }
        boolean[] values = {
            WPClientConfig.showResidentStatusCard(),
            WPClientConfig.showWorkArea(),
            WPClientConfig.showCityRoads(),
            WPClientConfig.showPatrolPath()
        };
        for (int i = 0; i < values.length; i++) {
            int y = CHECK_TOP + i * CHECK_ROW;
            drawCheckbox(guiGraphics, CONTENT_X, y, values[i]);
            guiGraphics.drawString(this.font, Component.translatable(RENDER_KEYS[i]), CONTENT_X + 18, y + 1,
                this.theme.inkColor(), false);
        }
    }

    /** 拖拽中的栏位跟着鼠标走（画在最上层）。 */
    private void renderDragGhost(GuiGraphics guiGraphics, float mouseX, float mouseY) {
        if (this.dragTab < 0) {
            return;
        }
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 400.0F);
        int x = Math.round(mouseX - TAB_W / 2.0F);
        int y = Math.round(mouseY - TAB_H / 2.0F);
        drawTab(guiGraphics, x, y, this.dragTab, false, false, 0.85F);
        guiGraphics.pose().popPose();
    }

    private void drawTab(GuiGraphics guiGraphics, int x, int y, int tab, boolean active,
                         boolean hovered, float alpha) {
        int alphaByte = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        int back = active ? this.theme.tabActiveColor()
            : (hovered ? this.theme.tabHoverColor() : this.theme.tabBackColor());
        int border = this.theme.tabBorderColor();
        if (alpha < 1.0F) {
            back = ManualTheme.withAlpha(back, (int) (alphaByte * 0.45F));
            border = ManualTheme.withAlpha(border, (int) (alphaByte * 0.55F));
        }

        guiGraphics.fill(x, y, x + TAB_W, y + TAB_H, back);
        // 描边
        guiGraphics.fill(x, y, x + TAB_W, y + 1, border);
        guiGraphics.fill(x, y + TAB_H - 1, x + TAB_W, y + TAB_H, border);
        guiGraphics.fill(x, y, x + 1, y + TAB_H, border);
        guiGraphics.fill(x + TAB_W - 1, y, x + TAB_W, y + TAB_H, border);

        // 图标
        if (alpha < 1.0F) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        }
        guiGraphics.blit(TAB_ICONS[tab], x + 5, y + 4, 0, 0, 16, 16, 16, 16);
        if (alpha < 1.0F) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        int textColor = this.theme.tabTextColor();
        if (alpha < 1.0F) {
            textColor = ManualTheme.withAlpha(textColor, alphaByte);
        }
        guiGraphics.drawString(this.font, Component.translatable(TAB_KEYS[tab]), x + 25, y + 8, textColor, false);
    }

    private void drawEmptySlot(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(x, y, x + TAB_W, y + TAB_H, this.theme.slotEmptyBackColor());
        int border = this.theme.slotEmptyBorderColor();
        guiGraphics.fill(x, y, x + TAB_W, y + 1, border);
        guiGraphics.fill(x, y + TAB_H - 1, x + TAB_W, y + TAB_H, border);
        guiGraphics.fill(x, y, x + 1, y + TAB_H, border);
        guiGraphics.fill(x + TAB_W - 1, y, x + TAB_W, y + TAB_H, border);
    }

    private void drawCheckbox(GuiGraphics guiGraphics, int x, int y, boolean checked) {
        // 黑色外线 → 白色边框 → 灰色底
        guiGraphics.fill(x - 1, y - 1, x + CHECK_SIZE + 1, y + CHECK_SIZE + 1,
            this.theme.checkLineColor());
        guiGraphics.fill(x, y, x + CHECK_SIZE, y + CHECK_SIZE, this.theme.checkBorderColor());
        guiGraphics.fill(x + 1, y + 1, x + CHECK_SIZE - 1, y + CHECK_SIZE - 1,
            this.theme.checkBackColor());
        if (checked) {
            drawTick(guiGraphics, x + 2, y + 2, CHECK_SIZE - 4, this.theme.checkMarkColor());
        }
    }

    /** 在 size×size 的方框里画一个像素勾（左上原点）。 */
    private static void drawTick(GuiGraphics guiGraphics, int x, int y, int size, int color) {
        int thickness = Math.max(1, size / 8);
        float x0 = x + size * 0.15F;
        float y0 = y + size * 0.52F;
        float x1 = x + size * 0.40F;
        float y1 = y + size * 0.80F;
        float x2 = x + size * 0.88F;
        float y2 = y + size * 0.12F;
        drawLine(guiGraphics, x0, y0, x1, y1, thickness, color);
        drawLine(guiGraphics, x1, y1, x2, y2, thickness, color);
    }

    private static void drawLine(GuiGraphics guiGraphics, float x0, float y0, float x1, float y1,
                                 int thickness, int color) {
        int steps = Math.max(1, Math.round(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)) * 2.0F));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int px = Math.round(Mth.lerp(t, x0, x1));
            int py = Math.round(Mth.lerp(t, y0, y1));
            guiGraphics.fill(px, py, px + thickness, py + thickness, color);
        }
    }

    // ------------------------------------------------------------------
    // ✕（在纸张外面，右上角）
    // ------------------------------------------------------------------

    private int closeX() {
        return this.pageLeft + this.pageW + CLOSE_GAP;
    }

    private int closeY() {
        return this.pageTop - CLOSE_SIZE - CLOSE_GAP;
    }

    private boolean isOverClose(double mouseX, double mouseY) {
        int x = closeX();
        int y = closeY();
        return mouseX >= x && mouseX <= x + CLOSE_SIZE && mouseY >= y && mouseY <= y + CLOSE_SIZE;
    }

    private void renderClose(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = closeX();
        int y = closeY();
        boolean hovered = isOverClose(mouseX, mouseY);

        guiGraphics.fill(x, y, x + CLOSE_SIZE, y + CLOSE_SIZE,
            hovered ? this.theme.closeHoverColor() : this.theme.closeColor());
        guiGraphics.fill(x, y, x + CLOSE_SIZE, y + 1, 0x55FFFFFF);
        guiGraphics.fill(x, y + CLOSE_SIZE - 1, x + CLOSE_SIZE, y + CLOSE_SIZE, 0x44000000);
        guiGraphics.drawCenteredString(this.font, "\u2715", x + CLOSE_SIZE / 2,
            y + (CLOSE_SIZE - 8) / 2, this.theme.closeGlyphColor());
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // ✕：按钮音效 + 关
        if (isOverClose(mouseX, mouseY)) {
            this.playButtonClick();
            this.onClose();
            return true;
        }

        float pageX = this.toPageX(mouseX);
        float pageY = this.toPageY(mouseY);

        // 编辑按钮
        if (inside(pageX, pageY, PEN_X, PEN_Y, PEN_SIZE, PEN_SIZE)) {
            this.playButtonClick();
            if (this.editMode) {
                this.saveOrder();
                this.editMode = false;
            } else {
                this.editMode = true;
            }
            this.dragTab = -1;
            this.dragSlot = -1;
            this.hoverSlot = -1;
            return true;
        }

        // 栏位
        int slot = this.slotAt(pageX, pageY);
        if (slot >= 0) {
            if (this.editMode) {
                this.dragTab = this.order[slot];
                this.dragSlot = slot;
                this.hoverSlot = -1;
            } else {
                this.currentTab = this.order[slot];
                this.playButtonClick();
            }
            return true;
        }

        // 勾选项（仅「手册渲染」页）
        if (this.currentTab == TAB_RENDER) {
            int option = this.checkboxAt(pageX, pageY);
            if (option >= 0) {
                this.toggleOption(option);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.dragTab >= 0) {
            this.hoverSlot = this.slotAt(this.toPageX(mouseX), this.toPageY(mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.dragTab >= 0) {
            int slot = this.slotAt(this.toPageX(mouseX), this.toPageY(mouseY));
            if (slot >= 0 && slot != this.dragSlot) {
                this.moveSlot(this.dragSlot, slot);
                this.playButtonClick();
            }
            this.dragTab = -1;
            this.dragSlot = -1;
            this.hoverSlot = -1;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // E / ESC 关闭：按要求不发出任何音效
        if (keyCode == GLFW.GLFW_KEY_E || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // 命中检测（都在纸张坐标里）
    // ------------------------------------------------------------------

    private int slotAt(float pageX, float pageY) {
        for (int slot = 0; slot < TAB_COUNT; slot++) {
            int y = TAB_TOP + slot * (TAB_H + TAB_GAP);
            if (inside(pageX, pageY, TAB_X, y, TAB_W, TAB_H)) {
                return slot;
            }
        }
        return -1;
    }

    private int checkboxAt(float pageX, float pageY) {
        for (int i = 0; i < RENDER_KEYS.length; i++) {
            int y = CHECK_TOP + i * CHECK_ROW;
            // 勾选框 + 右侧文字一起算点击区
            if (inside(pageX, pageY, CONTENT_X - 2, y - 3, 118, CHECK_SIZE + 6)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean inside(float x, float y, int rx, int ry, int rw, int rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    private float toPageX(double mouseX) {
        return (float) ((mouseX - this.pageLeft) / this.pageScale);
    }

    private float toPageY(double mouseY) {
        return (float) ((mouseY - this.pageTop) / this.pageScale);
    }

    // ------------------------------------------------------------------
    // 勾选项 → 配置
    // ------------------------------------------------------------------

    private void toggleOption(int index) {
        switch (index) {
            case 0 -> WPClientConfig.setShowResidentStatusCard(!WPClientConfig.showResidentStatusCard());
            case 1 -> WPClientConfig.setShowWorkArea(!WPClientConfig.showWorkArea());
            case 2 -> WPClientConfig.setShowCityRoads(!WPClientConfig.showCityRoads());
            case 3 -> WPClientConfig.setShowPatrolPath(!WPClientConfig.showPatrolPath());
            default -> {
                return;
            }
        }
        WPClientConfig.save();
        this.playButtonClick();
    }

    // ------------------------------------------------------------------
    // 音效
    // ------------------------------------------------------------------

    /** 原版 UI 按钮音效（✕ / 切栏位 / 勾选 / 编辑按钮）。 */
    private void playButtonClick() {
        if (this.minecraft == null) {
            return;
        }
        this.minecraft.getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
    }

    /** 打开页面：播原版书页翻动音效（原版内部会随机挑一个书页变体）。 */
    private void playPageTurn() {
        if (this.minecraft == null) {
            return;
        }
        float pitch = 0.95F + this.random.nextFloat() * 0.1F;
        this.minecraft.getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, pitch));
    }
}
