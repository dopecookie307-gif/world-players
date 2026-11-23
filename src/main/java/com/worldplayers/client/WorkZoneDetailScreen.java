package com.worldplayers.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.worldplayers.network.ResidentListPayload;
import com.worldplayers.network.ZoneEmployeesPayload;
import com.worldplayers.resident.WorkZone;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 「此工作区域详情」页面（v0.2.12，主人 2026-11-20 改版）。
 *
 * <p>标题 = **区域图标 + 区域类型 + 区域名称**；农田区域的特殊图标（作物产物）沿用。</p>
 *
 * <ul>
 *   <li>左上角 ←：返回手册（ESC 同效），播按钮音效；</li>
 *   <li>右上角 ✕：直接关掉详情页和手册、回到世界，播按钮音效；</li>
 *   <li>内容区可滚动，包含：坐标 / 体积（字缩小 10%）、**员工栏**（最多 5 个，可加可删）、农田详情；</li>
 *   <li>员工框点击 → 成员清单（最上面「无」= 清空；已被本区域其他栏占用的居民灰底黑框不可点）。</li>
 * </ul>
 */
public class WorkZoneDetailScreen extends Screen {
    private static final int CLOSE_SIZE = 12;
    private static final int CONTENT_TOP = 46;
    private static final int CONTENT_BOTTOM = 28;
    private static final int LINE_H = 12;
    private static final int SCROLL_W = 3;
    /** 坐标/体积两行的字体缩放（比正常小 10%）。 */
    private static final float SMALL_SCALE = 0.9F;
    /** 员工行高度 / 员工框尺寸 / 删除按钮尺寸。 */
    private static final int EMP_ROW = 18;
    private static final int EMP_BOX_H = 14;
    private static final int EMP_BOX_W = 110;
    private static final int EMP_DELETE = 10;
    /** 「增加员工栏」按钮：细长又矮扁。 */
    private static final int ADD_BTN_H = 10;
    /** 农田详情每行高度。 */
    private static final int FARM_LINE = 14;
    /** 树场设定每行高度。 */
    private static final int TREE_ROW = 16;
    /** 牧场设定每行高度（v0.3.0）。 */
    private static final int RANCH_ROW = 16;
    /** 牧场数量输入框宽度。 */
    private static final int RANCH_BOX_W = 40;
    /** 工程行（勾/叉 + 填充输入框）高度（规格 3.2）。 */
    private static final int PROJECT_ROW = 16;
    /** 进度行高度。 */
    private static final int PROGRESS_ROW = 14;
    /** 勾 / 叉按钮尺寸。 */
    private static final int PROJECT_BTN = 14;

    private static final int ACTION_PICK = 1;
    private static final int ACTION_DELETE = 2;
    private static final int ACTION_ADD = 3;

    /** 详情页是从哪里打开的：决定 ← 返回哪一页（主人 2026-11-20）。 */
    public static final int FROM_REGION = 0;
    public static final int FROM_SUBTYPE = 1;
    public static final int FROM_MEMBERS = 2;

    /** 员工选择清单一屏可见行数 / 右侧滚动条宽度（2026-11-22 加滚动）。 */
    private static final int PICKER_ROWS = 10;
    private static final int PICKER_SCROLL_W = 4;

    private final WorkZone zone;
    private final int from;
    @javax.annotation.Nullable
    private final String fromZoneType;
    private final ManualTheme theme = ManualThemes.current();
    private int pageLeft;
    private int pageTop;
    private int scroll;

    /** 标题里「区域名称」的屏幕区域（悬浮高亮 + 点击改名）。 */
    private int titleNameX;
    private int titleNameY;
    private int titleNameW;

    /** 本地员工列表（空字符串 = 空槽位）；改动后立刻发给服务端。 */
    private final java.util.List<String> employees = new ArrayList<>();
    /** 娱乐区简介编辑器（14 号文档 1.8）：任意关闭/失焦立即提交。 */
    private final ZoneDescriptionEditor descEditor = new ZoneDescriptionEditor();
    /** 简介框这一帧的屏幕矩形（点击 / 鼠标定位用）。 */
    private int descBoxX;
    private int descBoxY;
    private int descBoxW;
    private int descBoxH;
    /** 正在打开成员清单的员工槽位；-1 = 没打开。 */
    private int pickerSlot = -1;
    /** 员工清单滚动量（0 = 最上面）。 */
    private int pickerScroll;
    /** 正在确认删除的员工槽位；-1 = 没打开。 */
    private int confirmSlot = -1;

    // ---- 树场设定（主人 2026-11-21 定稿；2026-11-22 去掉「种植模式」开关，改为按树种自动） ----
    /** 本地树种 id / 种植位置（改动后立刻发给服务端）。 */
    private String treeSpecies;
    private String treePlacement;
    /** 三行设定在屏幕上的 y（渲染时记录，点击直接查）。 */
    private final int[] treeRowY = new int[3];
    /** 树种下拉打开没。 */
    private boolean speciesPickerOpen;

    // ---- 牧场设定（v0.3.0，主人 2026-11-23） ----
    /** 本地动物类型 id / 最大数量 / 剪毛（改动后由「储存」按钮统一发给服务端）。 */
    private String animalType;
    private int animalMax;
    private boolean animalShear;
    /** 动物最大数量的小输入框。 */
    @javax.annotation.Nullable
    private net.minecraft.client.gui.components.EditBox animalBox;
    /** 牧场四行设定在屏幕上的 y：0 动物类型 / 1 数量 / 2 剪毛（仅羊）/ 3 储存按钮。 */
    private final int[] ranchRowY = new int[4];
    /** 这一帧剪毛行是否存在（只有绵羊有）。 */
    private boolean ranchShearRow;
    /** 动物类型下拉打开没。 */
    private boolean animalPickerOpen;
    /** 数量输入框悬浮提示。 */
    private boolean animalMaxHover;

    // ---- 挖掘 / 填充工程（v0.2.15，规格 3.2） ----
    /** 填充方块输入框（只有填充工程才有）。 */
    @javax.annotation.Nullable
    private net.minecraft.client.gui.components.EditBox projectBox;
    /** 候选清单（防抖 / 滚动 / 点击都在它里面）。 */
    private final FillBlockPicker picker = new FillBlockPicker();
    /** 本地正在编辑的方块 id（空 = 没选）。 */
    private String projectBlock;
    /** 上一帧输入框的文字（变化时触发搜索）。 */
    private String lastBoxText = "";
    /** 工程行 / 进度行的屏幕 y（点击直接查）。 */
    private int projectRowY;
    private int progressRowY;
    /** 填充输入框的屏幕矩形（点击 / 清单定位用）。 */
    private int projectBoxX;
    private int projectBoxY;
    private int projectBoxW;
    /** 悬浮在勾 / 叉上没（提示窗在最后画）。 */
    private boolean checkHover;
    private boolean pauseHover;
    /** 勾 / 叉按钮的屏幕 x（点击用）。 */
    private int projectCheckX;
    private int projectCrossX;

    public WorkZoneDetailScreen(WorkZone zone) {
        this(zone, FROM_REGION, null);
    }

    public WorkZoneDetailScreen(WorkZone zone, int from, @javax.annotation.Nullable String fromZoneType) {
        super(Component.translatable("gui.worldplayers.zone.detail_title"));
        this.zone = zone;
        this.from = from;
        this.fromZoneType = fromZoneType;
        this.treeSpecies = zone.saplingType();
        this.treePlacement = zone.placementMode();
        this.projectBlock = zone.projectBlock();
        this.animalType = zone.animalType();
        this.animalMax = zone.animalMax();
        this.animalShear = zone.animalShear();
    }

    @Override
    protected void init() {
        this.pageLeft = (this.width - this.theme.pageWidth()) / 2;
        this.pageTop = (this.height - this.theme.pageHeight()) / 2;
        this.scroll = 0;
        if (this.zone.isEntertainment()) {
            this.descEditor.setText(this.zone.description());
        }
        if (this.employees.isEmpty()) {
            this.employees.addAll(this.zone.employees());
            if (this.employees.isEmpty()) {
                this.employees.add("");
            }
        }
        // 渔场只有 1 个员工栏（主人 2026-11-23）：旧存档里多出来的员工直接不显示
        while (this.employees.size() > this.staffSlots()) {
            this.employees.remove(this.employees.size() - 1);
        }
        // 填充工程：方块输入框（有输入框的页面不模糊，见 renderBackground）
        if (this.zone.isFill()) {
            this.projectBox = new net.minecraft.client.gui.components.EditBox(this.font, 0, 0, 100, 14,
                Component.translatable("gui.worldplayers.zone.project_placeholder"));
            this.projectBox.setHint(Component.translatable("gui.worldplayers.zone.project_placeholder"));
            this.projectBox.setMaxLength(64);
            this.projectBox.setValue(shortId(this.projectBlock));
            this.lastBoxText = this.projectBox.getValue();
            this.projectBox.setVisible(false);
            this.addRenderableWidget(this.projectBox);
        }
        // 牧场：动物最大数量的小输入框（2~64）
        if (this.zone.isRanch()) {
            this.animalBox = new net.minecraft.client.gui.components.EditBox(this.font, 0, 0,
                RANCH_BOX_W, 14, Component.translatable("gui.worldplayers.zone.animal_max"));
            this.animalBox.setMaxLength(2);
            this.animalBox.setFilter(text -> text.isEmpty() || text.matches("\\d{1,2}"));
            this.animalBox.setValue(String.valueOf(this.animalMax));
            this.animalBox.setVisible(false);
            this.addRenderableWidget(this.animalBox);
        }
    }

    /** 完整 id → 输入框里显示的形式（去掉 minecraft:）。 */
    private static String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** 同手册/储存页：不要原版菜单模糊。 */
    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // 故意留空：不模糊
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        // 员工列表一变动，区域列表会刷新并把作物统计清掉 → 这里持续补扫（内部 500ms 节流）
        if (WorkZone.TYPE_FARM.equals(this.zone.type())) {
            ZoneStore.refreshCrops();
        }
        gui.fill(0, 0, this.width, this.height, 0x99000000);
        gui.fill(this.pageLeft + 3, this.pageTop + 3,
            this.pageLeft + this.theme.pageWidth() + 3, this.pageTop + this.theme.pageHeight() + 3, 0x66000000);
        boolean tinted = this.theme.hasTint();
        if (tinted) {
            RenderSystem.setShaderColor(this.theme.tintRed(), this.theme.tintGreen(), this.theme.tintBlue(), 1.0F);
        }
        gui.blit(this.theme.pageTexture(), this.pageLeft, this.pageTop, 0, 0,
            this.theme.pageWidth(), this.theme.pageHeight(),
            this.theme.textureWidth(), this.theme.textureHeight());
        if (tinted) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }

        this.renderTitle(gui);
        this.renderContent(gui, mouseX, mouseY);
        this.renderBack(gui, mouseX, mouseY);
        this.renderClose(gui, mouseX, mouseY);
        // 2026-11-23 修：widget（数量输入框 / 方块输入框）要先画，否则会盖在员工选择清单等弹窗上面
        super.render(gui, mouseX, mouseY, partialTick);
        // 方块候选清单画在输入框上面、正文下面
        this.picker.tick();
        this.picker.render(gui, this.font, this.projectBoxX, this.projectBoxY, this.projectBoxW,
            PROJECT_ROW, this.pageLeft, this.pageTop, this.theme.pageWidth(),
            this.theme.pageHeight(), mouseX, mouseY);
        if (this.pickerSlot >= 0) {
            this.renderPicker(gui, mouseX, mouseY);
        }
        if (this.confirmSlot >= 0) {
            this.renderConfirm(gui, mouseX, mouseY);
        }
        if (this.speciesPickerOpen) {
            this.renderSpeciesPicker(gui, mouseX, mouseY);
        }
        if (this.animalPickerOpen) {
            this.renderAnimalPicker(gui, mouseX, mouseY);
        }
        if (this.checkHover) {
            gui.renderTooltip(this.font,
                Component.translatable("gui.worldplayers.zone.project_start_hint"),
                (int) mouseX, (int) mouseY);
        } else if (this.pauseHover) {
            gui.renderTooltip(this.font,
                Component.translatable("gui.worldplayers.zone.project_pause_hint"),
                (int) mouseX, (int) mouseY);
        } else if (this.animalMaxHover) {
            // 主人 2026-11-23 定：悬浮在数量输入框上显示这段提示
            gui.renderTooltip(this.font,
                Component.translatable("gui.worldplayers.zone.animal_max_hint"),
                (int) mouseX, (int) mouseY);
        }
    }

    /** 标题：区域图标（农田沿用作物产物徽标）+ 类型 + 名称。 */
    private void renderTitle(GuiGraphics gui) {
        int y = this.pageTop + 14;
        int x = this.pageLeft + 12;
        gui.renderItem(ManualScreen.zoneIcon(this.zone.type()), x, y - 4);
        ManualScreen.drawIconBorderStatic(gui, this.theme, x, y - 4, 16);
        if (WorkZone.TYPE_FARM.equals(this.zone.type())) {
            List<net.minecraft.world.item.ItemStack> badges = ZoneStore.cropBadges(this.zone.key());
            if (!badges.isEmpty()) {
                net.minecraft.world.item.ItemStack badge =
                    badges.get((int) ((System.currentTimeMillis() / 2000L) % badges.size()));
                gui.pose().pushPose();
                gui.pose().translate(x + 8.0F, y + 4.0F, 200.0F);
                gui.pose().scale(0.5F, 0.5F, 1.0F);
                gui.renderItem(badge, 0, 0);
                gui.pose().popPose();
            }
        }
        Component typeName = Component.translatable(ManualScreen.typeKey(this.zone.type()));
        int textX = x + 20;
        gui.drawString(this.font, typeName, textX, y, this.theme.inkMutedColor(), false);
        textX += this.font.width(typeName) + 6;
        // 区域名称：悬浮高亮，点击改名（改名功能从清单搬到这里，主人 2026-11-20）
        Component zoneName = Component.literal(this.zone.name());
        int nameW = this.font.width(zoneName);
        this.titleNameX = textX;
        this.titleNameY = y - 1;
        this.titleNameW = nameW;
        this.titleNameHover = inside(this.lastMouseX, this.lastMouseY, textX, y - 1, nameW, 10);
        if (this.titleNameHover) {
            gui.fill(textX - 1, y - 2, textX + nameW + 1, y + 9, 0x33FFFFFF);
        }
        gui.drawString(this.font, zoneName, textX, y,
            this.titleNameHover ? 0xFFFFD24A : this.theme.inkColor(), false);
    }

    private boolean titleNameHover;
    private int lastMouseX;
    private int lastMouseY;

    // ------------------------------------------------------------------
    // 内容
    // ------------------------------------------------------------------

    private List<ZoneStore.CropLine> crops() {
        return WorkZone.TYPE_FARM.equals(this.zone.type())
            ? ZoneStore.cropStats(this.zone.key()) : List.of();
    }

    /** 这个区域能有几个员工栏（渔场只有 1 格，其它最多 5）。 */
    private int staffSlots() {
        return this.zone.employeeSlots();
    }

    /** 内容总高度（滚动用）。 */
    private int contentHeight() {
        int h = 2 * LINE_H;
        // 道路区域没有员工（主人 2026-11-20）
        if (this.showEmployees()) {
            h += this.employees.size() * EMP_ROW;
            if (this.employees.size() < this.staffSlots()) {
                h += ADD_BTN_H + 4;
            }
        }
        List<ZoneStore.CropLine> crops = this.crops();
        if (!crops.isEmpty()) {
            h += LINE_H + crops.size() * FARM_LINE;
        }
        // 树场设定：树种 / 种植模式 / 种植位置（规则 5）
        if (this.zone.isTreeFarm()) {
            h += 3 * TREE_ROW;
        }
        // 牧场设定（v0.3.0）：动物类型 / 最大数量 /（剪毛，仅绵羊）/ 储存按钮
        if (this.zone.isRanch()) {
            int rows = com.worldplayers.resident.ranch.RanchType.byId(this.animalType).canShear() ? 3 : 2;
            h += RANCH_ROW * rows + 20;
        }
        // 挖掘 / 填充工程：工程行 + 进度行（规格 3.2）
        if (this.zone.isProject()) {
            h += PROJECT_ROW + PROGRESS_ROW;
        }
        // 娱乐区域简介（14 号文档 1.8）：标签行 + 自动扩高的框 + 字数行
        if (this.zone.isEntertainment()) {
            h += LINE_H + descriptionHeight() + LINE_H;
        }
        return h;
    }

    /** 简介框的宽度（占满详情页）。 */
    private int descriptionWidth() {
        return this.theme.pageWidth() - 24;
    }

    /** 简介框的高度：随内容自动扩高（至少一行）。 */
    private int descriptionHeight() {
        return Math.max(14, this.descEditor.height(this.font, descriptionWidth() - 4) + 4);
    }

    /** 道路 / 禁行 / 娱乐区域没有员工；其它类型（农田等）正常显示。 */
    private boolean showEmployees() {
        return !WorkZone.TYPE_ROAD.equals(this.zone.type())
            && !WorkZone.TYPE_FORBIDDEN.equals(this.zone.type())
            // 娱乐区域没有员工栏（14 号文档 1.7），原来员工栏的位置放简介
            && !this.zone.isEntertainment();
    }

    private void renderContent(GuiGraphics gui, int mouseX, int mouseY) {
        int contentTop = this.pageTop + CONTENT_TOP;
        int viewH = this.theme.pageHeight() - CONTENT_TOP - CONTENT_BOTTOM;
        int totalH = contentHeight();
        int maxScroll = Math.max(0, totalH - viewH);
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll);

        gui.enableScissor(this.pageLeft + 10, contentTop,
            this.pageLeft + this.theme.pageWidth() - 10, contentTop + viewH);

        int x = this.pageLeft + 12;
        int y = contentTop - this.scroll;

        // 坐标 / 体积：字比正常小 10%
        this.drawScaled(gui, Component.translatable("gui.worldplayers.zone.detail_origin",
            this.zone.origin().getX(), this.zone.origin().getY(), this.zone.origin().getZ()),
            x, y, SMALL_SCALE, this.theme.inkColor());
        y += LINE_H;
        this.drawScaled(gui, Component.translatable("gui.worldplayers.zone.detail_volume", this.zone.volume()),
            x, y, SMALL_SCALE, this.theme.inkColor());
        y += LINE_H;

        // 娱乐区域：原员工栏的位置放简介（14 号文档 1.8）
        if (this.zone.isEntertainment()) {
            y = this.renderDescription(gui, x, y, mouseX, mouseY);
        }

        // 员工栏（道路区域没有员工）
        if (this.showEmployees()) {
            for (int i = 0; i < this.employees.size(); i++) {
                this.drawEmployeeRow(gui, i, x, y, mouseX, mouseY);
                y += EMP_ROW;
            }

            // 增加员工栏（没满才显示；渔场只有 1 格，永远不显示）
            if (this.employees.size() < this.staffSlots()) {
                int addW = this.theme.pageWidth() - 24;
                boolean hover = inside(mouseX, mouseY, x, y, addW, ADD_BTN_H);
                gui.fill(x, y, x + addW, y + ADD_BTN_H, hover ? 0x44FFFFFF : 0x22FFFFFF);
                this.drawBorder(gui, x, y, addW, ADD_BTN_H, this.theme.inkMutedColor());
                Component label = Component.translatable("gui.worldplayers.zone.employee_add");
                this.drawScaled(gui, label, x + (addW - Math.round(this.font.width(label) * 0.8F)) / 2,
                    y + 2, 0.8F, this.theme.inkMutedColor());
                y += ADD_BTN_H + 4;
            }
        }

        // 树场设定（员工栏底下，规则 5）
        if (this.zone.isTreeFarm()) {
            y = this.renderTreeSettings(gui, x, y, mouseX, mouseY);
        }

        // 牧场设定（员工栏底下，v0.3.0）
        if (this.zone.isRanch()) {
            y = this.renderRanchSettings(gui, x, y, mouseX, mouseY);
        }

        // 挖掘 / 填充工程行 + 进度行（规格 3.2）
        if (this.zone.isProject()) {
            y = this.renderProjectRows(gui, x, y, mouseX, mouseY);
        }

        // 农田详情
        List<ZoneStore.CropLine> crops = this.crops();
        if (!crops.isEmpty()) {
            gui.drawString(this.font, Component.translatable("gui.worldplayers.zone.farm_title"),
                x, y, this.theme.inkColor(), false);
            y += LINE_H;
            for (ZoneStore.CropLine line : crops) {
                gui.renderItem(line.icon(), x, y + 1);
                Component text = Component.translatable("gui.worldplayers.zone.farm_line",
                    line.seed().getHoverName(), line.count());
                gui.drawString(this.font, text, x + 20, y + 4, this.theme.inkColor(), false);
                y += FARM_LINE;
            }
        }

        gui.disableScissor();

        if (maxScroll > 0) {
            int sx = this.pageLeft + this.theme.pageWidth() - 8;
            gui.fill(sx, contentTop, sx + SCROLL_W, contentTop + viewH, 0x33000000);
            int thumb = Math.max(12, viewH * viewH / totalH);
            int thumbY = contentTop + (viewH - thumb) * this.scroll / maxScroll;
            gui.fill(sx, thumbY, sx + SCROLL_W, thumbY + thumb, 0x99000000);
        }
    }

    /**
     * 树场三行设定（规则 5，主人 2026-11-21）：树种下拉（图标 + 名称）、种植模式、种植位置。
     * 摆在员工栏底下，仿皮肤选择栏的样子。
     */
    private int renderTreeSettings(GuiGraphics gui, int x, int y, double mouseX, double mouseY) {
        com.worldplayers.resident.tree.TreeSpecies species =
            com.worldplayers.resident.tree.TreeSpecies.byId(this.treeSpecies);
        this.treeRowY[0] = y;
        this.drawSettingRow(gui, x, y, mouseX, mouseY,
            Component.translatable("gui.worldplayers.zone.tree_species"),
            species.icon(),
            Component.translatable("gui.worldplayers.tree.species." + species.id()), true);

        this.treeRowY[1] = y + TREE_ROW;
        // 2026-11-22：种植模式不再是开关，按树种自动（深色橡木 / 丛林 = 2×2），只显示结果
        this.drawSettingRow(gui, x, y + TREE_ROW, mouseX, mouseY,
            Component.translatable("gui.worldplayers.zone.tree_mode"), null,
            Component.translatable("gui.worldplayers.tree.mode.auto."
                + (species.needsTwoByTwo() ? "two_by_two" : "single")), false);

        this.treeRowY[2] = y + TREE_ROW * 2;
        this.drawSettingRow(gui, x, y + TREE_ROW * 2, mouseX, mouseY,
            Component.translatable("gui.worldplayers.zone.tree_placement"), null,
            Component.translatable("gui.worldplayers.zone.tree_placement."
                + (WorkZone.PLACE_MANUAL.equals(this.treePlacement) ? "manual" : "auto")), false);
        return y + TREE_ROW * 3;
    }

    /** 一行设定：左边标签 + 右边框（可选物品图标 + 数值 + 下拉三角）。 */
    private void drawSettingRow(GuiGraphics gui, int x, int y, double mouseX, double mouseY,
                                Component label, @javax.annotation.Nullable net.minecraft.world.item.ItemStack icon,
                                Component value, boolean dropdown) {
        gui.drawString(this.font, label, x, y + 4, this.theme.inkColor(), false);
        int boxX = x + 62;
        int boxW = this.theme.pageWidth() - 24 - 62;
        boolean hover = inside(mouseX, mouseY, boxX, y, boxW, 14);
        gui.fill(boxX, y, boxX + boxW, y + 14, hover ? 0x44FFFFFF : 0x22FFFFFF);
        this.drawBorder(gui, boxX, y, boxW, 14, this.theme.inkMutedColor());
        int textX = boxX + 4;
        if (icon != null && !icon.isEmpty()) {
            this.drawSmallItem(gui, icon, boxX + 2, y + 1);
            textX = boxX + 18;
        }
        gui.drawString(this.font, value, textX, y + 3, this.theme.inkColor(), false);
        if (dropdown) {
            int ax = boxX + boxW - 9;
            int ay = y + 6;
            for (int i = 0; i < 4; i++) {
                gui.fill(ax + i, ay + i, ax + 8 - i, ay + i + 1, this.theme.inkMutedColor());
            }
        }
    }

    /** 小号物品图标（12×12 的框里画 16×16 的物品）。 */
    private void drawSmallItem(GuiGraphics gui, net.minecraft.world.item.ItemStack stack, int x, int y) {
        gui.pose().pushPose();
        gui.pose().translate(x, y, 200.0F);
        gui.pose().scale(0.75F, 0.75F, 1.0F);
        gui.renderItem(stack, 0, 0);
        gui.pose().popPose();
    }

    // ------------------------------------------------------------------
    // 牧场设定（v0.3.0，主人 2026-11-23）
    // ------------------------------------------------------------------

    /**
     * 牧场设定三行 + 储存按钮：动物类型（下拉、只有名字没有图标）、动物最大数量（小输入框）、
     * 剪毛勾选（**只有绵羊才显示这一行**），最底下是储存按钮。
     */
    private int renderRanchSettings(GuiGraphics gui, int x, int y, double mouseX, double mouseY) {
        com.worldplayers.resident.ranch.RanchType type =
            com.worldplayers.resident.ranch.RanchType.byId(this.animalType);
        this.ranchRowY[0] = y;
        this.drawSettingRow(gui, x, y, mouseX, mouseY,
            Component.translatable("gui.worldplayers.zone.animal_type"), null,
            type.displayName(), true);

        // 数量行：标签 + 小输入框（悬浮提示在 render 末尾画）
        this.ranchRowY[1] = y + RANCH_ROW;
        Component maxLabel = Component.translatable("gui.worldplayers.zone.animal_max");
        gui.drawString(this.font, maxLabel, x, y + RANCH_ROW + 4, this.theme.inkColor(), false);
        int boxX = x + this.font.width(maxLabel) + 4;
        int boxY = y + RANCH_ROW;
        this.animalMaxHover = inside(mouseX, mouseY, boxX, boxY, RANCH_BOX_W, 14);
        if (this.animalBox != null) {
            this.animalBox.setX(boxX);
            this.animalBox.setY(boxY);
            this.animalBox.setWidth(RANCH_BOX_W);
            boolean visible = this.isRanchRowVisible(boxY) && !this.isOverlayOpen();
            this.animalBox.setVisible(visible);
            if (!visible) {
                this.animalBox.setFocused(false);
            }
        }

        int next = y + RANCH_ROW * 2;
        this.ranchShearRow = type.canShear();
        if (this.ranchShearRow) {
            this.ranchRowY[2] = next;
            gui.drawString(this.font, Component.translatable("gui.worldplayers.zone.shear"),
                x, next + 4, this.theme.inkColor(), false);
            this.drawCheckBox(gui, x + 62, next, this.animalShear, mouseX, mouseY);
            next += RANCH_ROW;
        }

        // 储存按钮
        this.ranchRowY[3] = next;
        int btnW = 64;
        int btnH = 16;
        boolean hover = inside(mouseX, mouseY, x, next, btnW, btnH);
        this.drawButton(gui, x, next, btnW, btnH,
            Component.translatable("gui.worldplayers.zone.save"), hover);
        return next + btnH + 4;
    }

    /** 这一行是不是完整地落在内容视口里（EditBox 不吃 scissor，得自己藏）。 */
    private boolean isRanchRowVisible(int rowY) {
        int contentTop = this.pageTop + CONTENT_TOP;
        int viewH = this.theme.pageHeight() - CONTENT_TOP - CONTENT_BOTTOM;
        return rowY >= contentTop && rowY + 14 <= contentTop + viewH;
    }

    /** 有没有全屏弹窗打开（员工选择 / 删除确认 / 树种 / 动物类型）：这时输入框要藏起来。 */
    private boolean isOverlayOpen() {
        return this.pickerSlot >= 0 || this.confirmSlot >= 0
            || this.speciesPickerOpen || this.animalPickerOpen;
    }

    /** 自绘勾选框（14×14，选中画勾）。 */
    private void drawCheckBox(GuiGraphics gui, int x, int y, boolean checked,
                              double mouseX, double mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, PROJECT_BTN, PROJECT_BTN);
        gui.fill(x, y, x + PROJECT_BTN, y + PROJECT_BTN, hover ? 0x44FFFFFF : 0x22FFFFFF);
        this.drawBorder(gui, x, y, PROJECT_BTN, PROJECT_BTN, this.theme.inkMutedColor());
        if (checked) {
            int color = 0xFF2FA83C;
            for (int i = 0; i < 5; i++) {
                gui.fill(x + 3 + i, y + 8 + i, x + 4 + i, y + 9 + i, color);
            }
            for (int i = 0; i < 7; i++) {
                gui.fill(x + 6 + i, y + 10 - i, x + 7 + i, y + 11 - i, color);
            }
        }
    }

    /** 动物类型下拉：只有名字，没有图标（主人 2026-11-23 定）。 */
    private void renderAnimalPicker(GuiGraphics gui, int mouseX, int mouseY) {
        java.util.List<com.worldplayers.resident.ranch.RanchType> all =
            com.worldplayers.resident.ranch.RanchType.selectable();
        int w = 120;
        int h = 18 + all.size() * 12 + 4;
        int x = this.pageLeft + (this.theme.pageWidth() - w) / 2;
        int y = this.pageTop + (this.theme.pageHeight() - h) / 2;
        gui.fill(0, 0, this.width, this.height, 0x99000000);
        gui.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        gui.fill(x, y, x + w, y + h, 0xFF2B2B2B);
        int rowY = y + 16;
        for (com.worldplayers.resident.ranch.RanchType type : all) {
            boolean hover = inside(mouseX, mouseY, x, rowY, w, 12);
            boolean selected = type.id().equals(this.animalType);
            if (hover && !selected) {
                gui.fill(x, rowY, x + w, rowY + 12, 0x33FFFFFF);
            }
            gui.drawString(this.font, type.displayName(), x + 4, rowY + 2,
                selected ? 0xFFFFD24A : 0xFFFFFF, false);
            rowY += 12;
        }
    }

    /** 动物类型下拉的点击。 */
    private boolean handleAnimalClick(double mouseX, double mouseY) {
        java.util.List<com.worldplayers.resident.ranch.RanchType> all =
            com.worldplayers.resident.ranch.RanchType.selectable();
        int w = 120;
        int h = 18 + all.size() * 12 + 4;
        int x = this.pageLeft + (this.theme.pageWidth() - w) / 2;
        int y = this.pageTop + (this.theme.pageHeight() - h) / 2;
        int rowY = y + 16;
        for (com.worldplayers.resident.ranch.RanchType type : all) {
            if (inside(mouseX, mouseY, x, rowY, w, 12)) {
                ZoneUi.click();
                this.animalType = type.id();
                // 不是羊就没有剪毛这一行，勾选也归零
                if (!type.canShear()) {
                    this.animalShear = false;
                }
                this.animalPickerOpen = false;
                return true;
            }
            rowY += 12;
        }
        this.animalPickerOpen = false;
        return true;
    }

    /** 「储存」：把三种设定一起发给服务端（clamp 到 2~64）。 */
    private void pushRanch() {
        int max = this.animalMax;
        if (this.animalBox != null) {
            try {
                max = Integer.parseInt(this.animalBox.getValue().trim());
            } catch (NumberFormatException ignored) {
                // 空 / 非数字：用上一次的值
            }
        }
        max = Mth.clamp(max, WorkZone.ANIMAL_MIN, WorkZone.ANIMAL_MAX);
        this.animalMax = max;
        if (this.animalBox != null) {
            this.animalBox.setValue(String.valueOf(max));
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new com.worldplayers.network.ZoneRanchPayload(this.zone.key(),
                this.animalType, max, this.animalShear));
    }

    // ------------------------------------------------------------------
    // 挖掘 / 填充工程行（v0.2.15，规格 3.2）
    // ------------------------------------------------------------------

    /**
     * 工程行：填充（标签 + 输入框 + 勾 + 叉）/ 挖掘（标签 + 勾 + 叉），下面一行进度。
     *
     * <p>输入框是 widget（IME 输入中文也能用），位置每帧跟着滚动走；整行在视口外就藏起来。</p>
     */
    private int renderProjectRows(GuiGraphics gui, int x, int y, double mouseX, double mouseY) {
        this.projectRowY = y;
        boolean fill = this.zone.isFill();
        int inputW = this.theme.pageWidth() - 24 - 62 - 2 * 16 - 6;
        int checkX;
        int crossX;
        if (fill) {
            gui.drawString(this.font, Component.translatable("gui.worldplayers.zone.project_fill_block"),
                x, y + 4, this.theme.inkColor(), false);
            this.projectBoxX = x + 62;
            this.projectBoxY = y + 1;
            this.projectBoxW = inputW;
            if (this.projectBox != null) {
                this.projectBox.setX(this.projectBoxX);
                this.projectBox.setY(this.projectBoxY);
                this.projectBox.setWidth(inputW);
                boolean visible = this.isProjectRowVisible(y) && !this.isOverlayOpen();
                this.projectBox.setVisible(visible);
                if (!visible) {
                    // 滚出视口就别留着候选清单
                    this.picker.close();
                }
                String text = this.projectBox.getValue();
                if (!text.equals(this.lastBoxText)) {
                    this.lastBoxText = text;
                    this.picker.onQueryChanged(text);
                }
            }
            checkX = x + 62 + inputW + 2;
            crossX = checkX + PROJECT_BTN + 2;
        } else {
            Component label = Component.translatable("gui.worldplayers.zone.project_mine");
            gui.drawString(this.font, label, x, y + 4, this.theme.inkColor(), false);
            checkX = x + this.font.width(label) + 6;
            crossX = checkX + PROJECT_BTN + 2;
        }
        this.projectCheckX = checkX;
        this.projectCrossX = crossX;
        this.checkHover = this.drawProjectButton(gui, checkX, y + 1, true, mouseX, mouseY);
        this.pauseHover = this.drawProjectButton(gui, crossX, y + 1, false, mouseX, mouseY);

        // 进度行（已完成 / 总格数）
        this.progressRowY = y + PROJECT_ROW;
        long[] progress = ZoneStore.progress(this.zone.key());
        long done = progress[0];
        long total = progress[1] > 0L ? progress[1] : this.zone.projectTotal();
        this.drawScaled(gui,
            Component.translatable("gui.worldplayers.zone.project_progress",
                String.valueOf(done), String.valueOf(total)),
            x, this.progressRowY + 1, 0.9F, this.theme.inkColor());
        return y + PROJECT_ROW + PROGRESS_ROW;
    }

    /** 这一行是不是完整地落在内容视口里（输入框 widget 不吃 scissor，得自己藏）。 */
    private boolean isProjectRowVisible(int rowY) {
        int contentTop = this.pageTop + CONTENT_TOP;
        int viewH = this.theme.pageHeight() - CONTENT_TOP - CONTENT_BOTTOM;
        return rowY >= contentTop && rowY + PROJECT_ROW <= contentTop + viewH;
    }

    /** 勾 / 叉按钮（自绘，14×14）。返回是否悬浮。 */
    private boolean drawProjectButton(GuiGraphics gui, int x, int y, boolean check,
                                      double mouseX, double mouseY) {
        boolean hover = inside(mouseX, mouseY, x, y, PROJECT_BTN, PROJECT_BTN);
        gui.fill(x, y, x + PROJECT_BTN, y + PROJECT_BTN, hover ? 0x44FFFFFF : 0x22FFFFFF);
        this.drawBorder(gui, x, y, PROJECT_BTN, PROJECT_BTN, this.theme.inkMutedColor());
        int glyph = check ? (hover ? 0xFF2FA83C : this.theme.inkColor()) : 0xFF9A3A3A;
        if (check) {
            for (int i = 0; i < 5; i++) {
                gui.fill(x + 3 + i, y + 8 + i, x + 4 + i, y + 9 + i, glyph);
            }
            for (int i = 0; i < 7; i++) {
                gui.fill(x + 6 + i, y + 10 - i, x + 7 + i, y + 11 - i, glyph);
            }
        } else {
            for (int i = 0; i < 8; i++) {
                gui.fill(x + 3 + i, y + 3 + i, x + 4 + i, y + 4 + i, glyph);
                gui.fill(x + PROJECT_BTN - 4 - i, y + 3 + i, x + PROJECT_BTN - 3 - i, y + 4 + i, glyph);
            }
        }
        return hover;
    }

    /** 树种下拉（规则 5：图标 + 名称）。 */
    private void renderSpeciesPicker(GuiGraphics gui, int mouseX, int mouseY) {
        java.util.List<com.worldplayers.resident.tree.TreeSpecies> all =
            com.worldplayers.resident.tree.TreeSpecies.selectable();
        int w = 150;
        int h = 18 + all.size() * 12 + 4;
        int x = this.pageLeft + (this.theme.pageWidth() - w) / 2;
        int y = this.pageTop + (this.theme.pageHeight() - h) / 2;
        gui.fill(0, 0, this.width, this.height, 0x99000000);
        gui.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        gui.fill(x, y, x + w, y + h, 0xFF2B2B2B);
        int rowY = y + 16;
        for (com.worldplayers.resident.tree.TreeSpecies species : all) {
            boolean hover = inside(mouseX, mouseY, x, rowY, w, 12);
            boolean selected = species.id().equals(this.treeSpecies);
            if (hover && !selected) {
                gui.fill(x, rowY, x + w, rowY + 12, 0x33FFFFFF);
            }
            this.drawSmallItem(gui, species.icon(), x + 2, rowY - 1);
            gui.drawString(this.font,
                Component.translatable("gui.worldplayers.tree.species." + species.id()),
                x + 18, rowY + 2, selected ? 0xFFFFD24A : 0xFFFFFF, false);
            rowY += 12;
        }
    }

    /**
     * 娱乐区域简介（2026-11-22，14 号文档 1.8）：宽度占满详情页的输入框，随内容自动扩高；
     * 太长时由**详情页自己的滚动条**滚（输入框内部不滚）；内容上限 500 字符。
     */
    private int renderDescription(GuiGraphics gui, int x, int y, double mouseX, double mouseY) {
        gui.drawString(this.font, Component.translatable("gui.worldplayers.zone.description_label"),
            x, y + 1, this.theme.inkColor(), false);
        y += LINE_H;
        int w = descriptionWidth();
        int h = descriptionHeight();
        this.descBoxX = x;
        this.descBoxY = y;
        this.descBoxW = w;
        this.descBoxH = h;
        boolean focused = this.descEditor.focused();
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        gui.fill(x, y, x + w, y + h, hover || focused ? 0x33FFFFFF : 0x22FFFFFF);
        this.drawBorder(gui, x, y, w, h, focused ? this.theme.inkColor() : this.theme.inkMutedColor());

        int textX = x + 2;
        int textY = y + 2;
        int innerW = w - 4;
        if (this.descEditor.text().isEmpty()) {
            gui.drawString(this.font,
                Component.translatable("gui.worldplayers.zone.description_hint"),
                textX, textY, this.theme.inkMutedColor(), false);
        } else {
            for (ZoneDescriptionEditor.Line line : this.descEditor.wrap(this.font, innerW)) {
                gui.drawString(this.font, Component.literal(line.text()), textX, textY,
                    this.theme.inkColor(), false);
                textY += ZoneDescriptionEditor.LINE_H;
            }
        }
        if (this.descEditor.cursorVisible(System.currentTimeMillis())) {
            int[] caret = this.descEditor.cursorPixels(this.font, innerW);
            int cx = textX + caret[1];
            int cy = y + 2 + caret[0] * ZoneDescriptionEditor.LINE_H;
            if (cy < y + h - 2) {
                gui.fill(cx, cy, cx + 1, cy + ZoneDescriptionEditor.LINE_H - 1, 0xFFFFFFFF);
            }
        }
        y += h;
        this.drawScaled(gui,
            Component.translatable("gui.worldplayers.zone.description_count",
                this.descEditor.text().length(), WorkZone.MAX_DESCRIPTION),
            x, y + 1, 0.9F, this.theme.inkMutedColor());
        return y + LINE_H;
    }

    /** 简介有没有改动；有就整份发给服务端（关闭 / 失焦 / 切页都会走到这里）。 */
    private void pushDescription() {
        if (!this.zone.isEntertainment() || !this.descEditor.isDirty()) {
            return;
        }
        String text = this.descEditor.text();
        this.descEditor.markCommitted();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new com.worldplayers.network.ZoneDescriptionPayload(this.zone.key(), text));
    }

    /** 一行员工：左边「员工：」文字（无框）+ 右边员工框 + 最右边删除 ×。 */

    private void drawEmployeeRow(GuiGraphics gui, int index, int x, int y, float mouseX, float mouseY) {
        Component label = Component.translatable("gui.worldplayers.zone.employee_label");
        gui.drawString(this.font, label, x, y + 3, this.theme.inkColor(), false);
        int boxX = x + this.font.width(label) + 4;
        boolean hover = inside(mouseX, mouseY, boxX, y, EMP_BOX_W, EMP_BOX_H);
        gui.fill(boxX, y, boxX + EMP_BOX_W, y + EMP_BOX_H, hover ? 0x44FFFFFF : 0x22FFFFFF);
        this.drawBorder(gui, boxX, y, EMP_BOX_W, EMP_BOX_H, this.theme.inkMutedColor());

        String id = this.employees.get(index);
        if (id.isEmpty()) {
            Component plus = Component.literal("+");
            gui.drawString(this.font, plus, boxX + EMP_BOX_W / 2 - this.font.width(plus) / 2,
                y + 3, this.theme.inkMutedColor(), false);
        } else {
            ResidentListPayload.Member member = this.memberOf(id);
            if (member == null) {
                gui.drawString(this.font, Component.translatable("entity.worldplayers.resident"),
                    boxX + 4, y + 3, this.theme.inkColor(), false);
            } else {
                gui.blit(ClientSkins.skin(member.skin()).texture(), boxX + 2, y + 2, 10, 10,
                    8.0F, 8.0F, 8, 8, 64, 64);
                gui.blit(ClientSkins.skin(member.skin()).texture(), boxX + 2, y + 2, 10, 10,
                    40.0F, 8.0F, 8, 8, 64, 64);
                Component name = member.name() == null || member.name().isBlank()
                    ? Component.translatable("entity.worldplayers.resident")
                    : Component.literal(member.name());
                gui.drawString(this.font, name, boxX + 15, y + 3, this.theme.inkColor(), false);
            }
        }
        // 删除 ×：至少剩 1 个员工栏时不显示
        if (this.employees.size() > 1) {
            int dx = boxX + EMP_BOX_W + 4;
            boolean xHover = inside(mouseX, mouseY, dx, y + 2, EMP_DELETE, EMP_DELETE);
            if (xHover) {
                gui.fill(dx, y + 2, dx + EMP_DELETE, y + 2 + EMP_DELETE, 0x44FFFFFF);
            }
            int glyph = xHover ? 0xFFAA3333 : this.theme.inkMutedColor();
            for (int i = 2; i < EMP_DELETE - 1; i++) {
                gui.fill(dx + i, y + 2 + i - 1, dx + i + 1, y + 2 + i, glyph);
                gui.fill(dx + EMP_DELETE - 1 - i, y + 2 + i - 1, dx + EMP_DELETE - i, y + 2 + i, glyph);
            }
        }
    }

    /** 员工框里这个 UUID 对应的成员（找不到 null）。 */
    @javax.annotation.Nullable
    private ResidentListPayload.Member memberOf(String id) {
        for (ResidentListPayload.Member member : ResidentStore.members()) {
            if (member.id().toString().equals(id)) {
                return member;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 成员清单弹窗
    // ------------------------------------------------------------------

    private List<ResidentListPayload.Member> pickerMembers() {
        return ResidentStore.members();
    }

    private int pickerWidth() {
        return 170;
    }

    private int pickerHeight() {
        int rows = 1 + Math.min(this.pickerMembers().size(), PICKER_ROWS);
        return 20 + rows * 12 + 4;
    }

    private int pickerX() {
        return this.pageLeft + (this.theme.pageWidth() - pickerWidth()) / 2;
    }

    private int pickerY() {
        return this.pageTop + (this.theme.pageHeight() - pickerHeight()) / 2;
    }

    /** 正在选的槽位「其它」已占用的成员 UUID 集合（这些行不可点）。 */
    private java.util.Set<String> occupiedElsewhere() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (int i = 0; i < this.employees.size(); i++) {
            if (i != this.pickerSlot && !this.employees.get(i).isEmpty()) {
                used.add(this.employees.get(i));
            }
        }
        return used;
    }

    private void renderPicker(GuiGraphics gui, int mouseX, int mouseY) {
        int x = pickerX();
        int y = pickerY();
        int w = pickerWidth();
        int h = pickerHeight();
        gui.fill(0, 0, this.width, this.height, 0x99000000);
        gui.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        gui.fill(x, y, x + w, y + h, 0xFF2B2B2B);

        java.util.Set<String> used = occupiedElsewhere();
        int rowY = y + 20;
        // 第一行：「无」= 清空这个员工栏
        boolean noneHover = inside(mouseX, mouseY, x, rowY, w, 12);
        if (noneHover) {
            gui.fill(x, rowY, x + w, rowY + 12, 0x33FFFFFF);
        }
        gui.drawString(this.font, Component.translatable("gui.worldplayers.zone.employee_none"),
            x + 4, rowY + 2, 0xFFFFFF, false);
        rowY += 12;

        java.util.List<ResidentListPayload.Member> members = this.pickerMembers();
        int maxScroll = Math.max(0, members.size() - PICKER_ROWS);
        this.pickerScroll = Mth.clamp(this.pickerScroll, 0, maxScroll);
        boolean scrollable = members.size() > PICKER_ROWS;
        int scrollbarW = scrollable ? PICKER_SCROLL_W : 0;
        int rowsTop = rowY;
        int shown = Math.min(members.size() - this.pickerScroll, PICKER_ROWS);
        for (int i = 0; i < shown; i++) {
            ResidentListPayload.Member member = members.get(i + this.pickerScroll);
            boolean taken = used.contains(member.id().toString());
            boolean hover = !taken && inside(mouseX, mouseY, x, rowY, w - scrollbarW, 12);
            if (taken) {
                gui.fill(x, rowY, x + w - scrollbarW, rowY + 12, 0xFF3A3A3A);
                this.drawBorder(gui, x, rowY, w - scrollbarW, 12, 0xFF000000);
            } else if (hover) {
                gui.fill(x, rowY, x + w - scrollbarW, rowY + 12, 0x33FFFFFF);
            }
            float alphaColor = taken ? 0xFF777777 : 0xFFFFFFFF;
            gui.blit(ClientSkins.skin(member.skin()).texture(), x + 3, rowY + 1, 10, 10,
                8.0F, 8.0F, 8, 8, 64, 64);
            gui.blit(ClientSkins.skin(member.skin()).texture(), x + 3, rowY + 1, 10, 10,
                40.0F, 8.0F, 8, 8, 64, 64);
            Component name = member.name() == null || member.name().isBlank()
                ? Component.translatable("entity.worldplayers.resident")
                : Component.literal(member.name());
            gui.drawString(this.font, name, x + 16, rowY + 2, (int) alphaColor, false);
            // 无业标识（主人 2026-11-21）：方便把别处的员工抢过来 / 一眼看出谁还没工作
            if (member.unemployed()) {
                Component job = Component.translatable("gui.worldplayers.manual.member_job_none");
                int jobX = x + w - scrollbarW - 4 - this.font.width(job);
                gui.drawString(this.font, job, jobX, rowY + 2,
                    taken ? 0xFF777777 : 0xFFB8B8B8, false);
            }
            rowY += 12;
        }
        // 右侧滚动条（2026-11-22）：一屏 10 行，滚轮 / 点轨道都能滚
        if (scrollable) {
            int barX = x + w - PICKER_SCROLL_W;
            int trackH = PICKER_ROWS * 12;
            gui.fill(barX, rowsTop, barX + PICKER_SCROLL_W - 1, rowsTop + trackH, 0xFF202020);
            int thumbH = Math.max(8, trackH * PICKER_ROWS / members.size());
            int thumbY = rowsTop + (int) ((trackH - thumbH)
                * (this.pickerScroll / (float) Math.max(1, maxScroll)));
            gui.fill(barX + 1, thumbY, barX + PICKER_SCROLL_W - 1, thumbY + thumbH, 0xFFB0B0B0);
        }
    }

    // ------------------------------------------------------------------
    // 删除员工栏确认弹窗
    // ------------------------------------------------------------------

    private int confirmX() {
        return this.pageLeft + (this.theme.pageWidth() - 160) / 2;
    }

    private int confirmY() {
        return this.pageTop + (this.theme.pageHeight() - 60) / 2;
    }

    private void renderConfirm(GuiGraphics gui, int mouseX, int mouseY) {
        int x = confirmX();
        int y = confirmY();
        int w = 160;
        gui.fill(0, 0, this.width, this.height, 0x99000000);
        gui.fill(x - 1, y - 1, x + w + 1, y + 60 + 1, 0xFF000000);
        gui.fill(x, y, x + w, y + 60, 0xFF2B2B2B);
        Component title = Component.translatable("gui.worldplayers.zone.employee_remove_title");
        gui.drawString(this.font, title, x + (w - this.font.width(title)) / 2, y + 8, 0xFFFFFF, false);
        boolean yesHover = inside(mouseX, mouseY, x + 10, y + 36, 64, 16);
        boolean noHover = inside(mouseX, mouseY, x + 86, y + 36, 64, 16);
        this.drawButton(gui, x + 10, y + 36, 64, 16,
            Component.translatable("gui.worldplayers.zone.employee_remove_yes"), yesHover);
        this.drawButton(gui, x + 86, y + 36, 64, 16,
            Component.translatable("gui.worldplayers.zone.employee_remove_no"), noHover);
    }

    private void drawButton(GuiGraphics gui, int x, int y, int w, int h, Component label, boolean hover) {
        gui.blitSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace(
            hover ? "widget/button_highlighted" : "widget/button"), x, y, w, h);
        gui.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2,
            0xFFFFFF, true);
    }

    // ------------------------------------------------------------------
    // 点击
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // 点任何地方先收走数量输入框的焦点（点输入框本身时下面会重新聚焦）
        if (this.animalBox != null) {
            this.animalBox.setFocused(false);
        }
        // 弹窗优先
        if (this.pickerSlot >= 0) {
            return this.handlePickerClick(mouseX, mouseY);
        }
        if (this.confirmSlot >= 0) {
            return this.handleConfirmClick(mouseX, mouseY);
        }
        if (this.speciesPickerOpen) {
            return this.handleSpeciesClick(mouseX, mouseY);
        }
        if (this.animalPickerOpen) {
            return this.handleAnimalClick(mouseX, mouseY);
        }
        // 方块候选清单：点在清单上 = 选方块；点在外面 = 关清单
        if (this.picker.isOpen()) {
            int row = this.picker.click(mouseX, mouseY);
            if (row >= 0) {
                if (row < this.picker.results().size()) {
                    this.chooseBlock(this.picker.results().get(row));
                }
                return true;
            }
            if (row == -2) {
                this.picker.close();
            } else {
                return true;
            }
        }
        if (isOverBack(mouseX, mouseY)) {
            ZoneUi.click();
            this.backToManual();
            return true;
        }
        if (isOverClose(mouseX, mouseY)) {
            ZoneUi.click();
            this.pushDescription();
            if (this.minecraft != null) {
                this.minecraft.setScreen(null);
            }
            return true;
        }
        // 标题里的区域名称 → 改名（改名后回到本详情页）
        if (this.titleNameHover) {
            ZoneUi.click();
            if (this.minecraft != null) {
                this.minecraft.setScreen(new RenameZoneScreen(this.zone, this.from, this.fromZoneType));
            }
            return true;
        }

        // 娱乐区域简介（14 号文档 1.8）：点框里 = 开始输入并把光标放到点击处；点外面 = 立即保存并失焦
        if (this.zone.isEntertainment()) {
            if (inside(mouseX, mouseY, this.descBoxX, this.descBoxY, this.descBoxW, this.descBoxH)) {
                this.descEditor.setFocused(true);
                this.descEditor.setCursor(this.descEditor.indexAt(this.font, this.descBoxW - 4,
                    mouseX - this.descBoxX - 2, mouseY - this.descBoxY - 2));
                return true;
            }
            if (this.descEditor.focused()) {
                this.descEditor.setFocused(false);
                this.pushDescription();
            }
        }

        // 内容区：员工框 / 删除 / 增加（道路区域没有员工栏）
        int contentTop = this.pageTop + CONTENT_TOP;
        int x = this.pageLeft + 12;
        int y = contentTop - this.scroll + 2 * LINE_H;
        if (this.showEmployees()) {
            for (int i = 0; i < this.employees.size(); i++) {
                Component label = Component.translatable("gui.worldplayers.zone.employee_label");
                int boxX = x + this.font.width(label) + 4;
                if (inside(mouseX, mouseY, boxX, y, EMP_BOX_W, EMP_BOX_H)) {
                    ZoneUi.click();
                    this.pickerSlot = i;
                    this.pickerScroll = 0;
                    return true;
                }
                if (this.employees.size() > 1
                        && inside(mouseX, mouseY, boxX + EMP_BOX_W + 4, y + 2, EMP_DELETE, EMP_DELETE)) {
                    ZoneUi.click();
                    this.confirmSlot = i;
                    return true;
                }
                y += EMP_ROW;
            }
            if (this.employees.size() < this.staffSlots()) {
                int addW = this.theme.pageWidth() - 24;
                if (inside(mouseX, mouseY, x, y, addW, ADD_BTN_H)) {
                    ZoneUi.click();
                    this.employees.add("");
                    this.pushEmployees();
                    return true;
                }
            }
        }
        // 树场三行设定（规则 5）
        if (this.zone.isTreeFarm()) {
            int boxX = x + 62;
            int boxW = this.theme.pageWidth() - 24 - 62;
            if (inside(mouseX, mouseY, boxX, this.treeRowY[0], boxW, 14)) {
                ZoneUi.click();
                this.speciesPickerOpen = true;
                return true;
            }
            if (inside(mouseX, mouseY, boxX, this.treeRowY[1], boxW, 14)) {
                // 种植模式只显示、不响应点击（按树种自动）
                return false;
            }
            if (inside(mouseX, mouseY, boxX, this.treeRowY[2], boxW, 14)) {
                ZoneUi.click();
                this.treePlacement = WorkZone.PLACE_MANUAL.equals(this.treePlacement)
                    ? WorkZone.PLACE_AUTO : WorkZone.PLACE_MANUAL;
                this.pushTree();
                return true;
            }
        }
        // 牧场设定（v0.3.0）：类型下拉 / 数量输入框 / 剪毛勾选 / 储存按钮
        if (this.zone.isRanch()) {
            int boxX = x + 62;
            int boxW = this.theme.pageWidth() - 24 - 62;
            if (inside(mouseX, mouseY, boxX, this.ranchRowY[0], boxW, 14)) {
                ZoneUi.click();
                this.animalPickerOpen = true;
                return true;
            }
            Component maxLabel = Component.translatable("gui.worldplayers.zone.animal_max");
            int inputX = x + this.font.width(maxLabel) + 4;
            if (this.animalBox != null
                    && inside(mouseX, mouseY, inputX, this.ranchRowY[1], RANCH_BOX_W, 14)) {
                ZoneUi.click();
                this.animalBox.setFocused(true);
                return super.mouseClicked(mouseX, mouseY, button);
            }
            if (this.ranchShearRow
                    && inside(mouseX, mouseY, x + 62, this.ranchRowY[2], PROJECT_BTN, PROJECT_BTN)) {
                ZoneUi.click();
                this.animalShear = !this.animalShear;
                return true;
            }
            if (inside(mouseX, mouseY, x, this.ranchRowY[3], 64, 16)) {
                ZoneUi.click();
                this.pushRanch();
                return true;
            }
        }
        // 挖掘 / 填充工程：勾 / 叉 / 输入框（规格 3.2）
        if (this.zone.isProject()) {
            if (inside(mouseX, mouseY, this.projectCheckX, this.projectRowY + 1,
                    PROJECT_BTN, PROJECT_BTN)) {
                ZoneUi.click();
                this.startProject();
                return true;
            }
            if (inside(mouseX, mouseY, this.projectCrossX, this.projectRowY + 1,
                    PROJECT_BTN, PROJECT_BTN)) {
                ZoneUi.click();
                this.pushProject(false);
                return true;
            }
            if (this.zone.isFill() && this.projectBox != null
                    && inside(mouseX, mouseY, this.projectBoxX, this.projectBoxY,
                        this.projectBoxW, PROJECT_ROW)) {
                ZoneUi.click();
                this.projectBox.setFocused(true);
                this.picker.open(this.projectBox.getValue());
                return super.mouseClicked(mouseX, mouseY, button);
            }
            if (this.projectBox != null) {
                this.projectBox.setFocused(false);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 树种下拉点击。 */
    private boolean handleSpeciesClick(double mouseX, double mouseY) {
        java.util.List<com.worldplayers.resident.tree.TreeSpecies> all =
            com.worldplayers.resident.tree.TreeSpecies.selectable();
        int w = 150;
        int h = 18 + all.size() * 12 + 4;
        int x = this.pageLeft + (this.theme.pageWidth() - w) / 2;
        int y = this.pageTop + (this.theme.pageHeight() - h) / 2;
        int rowY = y + 16;
        for (com.worldplayers.resident.tree.TreeSpecies species : all) {
            if (inside(mouseX, mouseY, x, rowY, w, 12)) {
                ZoneUi.click();
                this.treeSpecies = species.id();
                this.speciesPickerOpen = false;
                this.pushTree();
                return true;
            }
            rowY += 12;
        }
        this.speciesPickerOpen = false;
        return true;
    }

    /** 把树场设定发给服务端（服务端改存档、按需清苗、广播区域列表）。 */
    private void pushTree() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new com.worldplayers.network.ZoneTreePayload(this.zone.key(),
                this.treeSpecies, WorkZone.autoPlantMode(this.treeSpecies), this.treePlacement));
    }

    // ------------------------------------------------------------------
    // 工程：勾 / 叉 / 选方块
    // ------------------------------------------------------------------

    /** 勾 = 开始工程：填充先要求输入框里是一个合法方块（规格 3.2）。 */
    private void startProject() {
        if (this.zone.isFill()) {
            net.minecraft.world.level.block.Block resolved = BlockSearch.resolve(
                this.projectBox == null ? "" : this.projectBox.getValue());
            if (resolved == null) {
                if (this.minecraft != null && this.minecraft.player != null) {
                    this.minecraft.player.displayClientMessage(
                        Component.translatable("chat.worldplayers.project.no_block"), false);
                }
                return;
            }
            this.projectBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(resolved).toString();
            BlockSearch.rememberUsed(this.projectBlock);
        } else {
            this.projectBlock = "";
        }
        this.pushProject(true);
    }

    /** 把工程设定发给服务端（方块 / 开始 / 暂停；服务端负责改存档、暂停规则与聊天提示）。 */
    private void pushProject(boolean running) {
        this.picker.close();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new com.worldplayers.network.ZoneProjectPayload(this.zone.key(),
                this.projectBlock == null ? "" : this.projectBlock, running));
    }

    /** 从候选清单选中一个方块：回填输入框 + 记最近使用 + 通知服务端（改方块会自动暂停）。 */
    private void chooseBlock(BlockSearch.Entry entry) {
        this.projectBlock = entry.id();
        this.lastBoxText = entry.shortId();
        if (this.projectBox != null) {
            this.projectBox.setValue(entry.shortId());
            this.projectBox.setCursorPosition(entry.shortId().length());
        }
        this.picker.close();
        BlockSearch.rememberUsed(entry.id());
        this.pushProject(false);
    }

    private boolean handlePickerClick(double mouseX, double mouseY) {
        int x = pickerX();
        int y = pickerY();
        int w = pickerWidth();
        int rowY = y + 20;
        if (inside(mouseX, mouseY, x, rowY, w, 12)) {
            ZoneUi.click();
            this.employees.set(this.pickerSlot, "");
            this.pickerSlot = -1;
            this.pushEmployees();
            return true;
        }
        rowY += 12;
        java.util.Set<String> used = occupiedElsewhere();
        List<ResidentListPayload.Member> members = this.pickerMembers();
        int maxScroll = Math.max(0, members.size() - PICKER_ROWS);
        this.pickerScroll = Mth.clamp(this.pickerScroll, 0, maxScroll);
        boolean scrollable = members.size() > PICKER_ROWS;
        int scrollbarW = scrollable ? PICKER_SCROLL_W : 0;
        int rowsTop = rowY;
        int shown = Math.min(members.size() - this.pickerScroll, PICKER_ROWS);
        // 滚动条轨道：点击按比例跳（连续滚用滚轮）
        if (scrollable && inside(mouseX, mouseY, x + w - PICKER_SCROLL_W, rowsTop,
                PICKER_SCROLL_W, PICKER_ROWS * 12)) {
            float ratio = (float) ((mouseY - rowsTop) / (PICKER_ROWS * 12.0D));
            this.pickerScroll = Mth.clamp(Math.round(ratio * maxScroll), 0, maxScroll);
            return true;
        }
        for (int i = 0; i < shown; i++) {
            ResidentListPayload.Member member = members.get(i + this.pickerScroll);
            if (inside(mouseX, mouseY, x, rowY, w - scrollbarW, 12)) {
                if (!used.contains(member.id().toString())) {
                    ZoneUi.click();
                    this.employees.set(this.pickerSlot, member.id().toString());
                    this.pickerSlot = -1;
                    this.pushEmployees();
                }
                return true;
            }
            rowY += 12;
        }
        // 点空白处：关掉清单
        this.pickerSlot = -1;
        return true;
    }

    private boolean handleConfirmClick(double mouseX, double mouseY) {
        int x = confirmX();
        int y = confirmY();
        if (inside(mouseX, mouseY, x + 10, y + 36, 64, 16)) {
            ZoneUi.click();
            if (this.employees.size() > 1 && this.confirmSlot < this.employees.size()) {
                this.employees.remove(this.confirmSlot);
                this.pushEmployees();
            }
            this.confirmSlot = -1;
            return true;
        }
        if (inside(mouseX, mouseY, x + 86, y + 36, 64, 16)) {
            ZoneUi.click();
            this.confirmSlot = -1;
            return true;
        }
        this.confirmSlot = -1;
        return true;
    }

    /** 把本地员工列表发给服务端（服务端更新存档并广播区域列表）。 */
    private void pushEmployees() {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new ZoneEmployeesPayload(this.zone.key(), new ArrayList<>(this.employees)));
    }

    // ------------------------------------------------------------------
    // 工具 / 边框 / 返回
    // ------------------------------------------------------------------

    private void drawScaled(GuiGraphics gui, Component text, int x, int y, float scale, int color) {
        gui.pose().pushPose();
        gui.pose().translate(x, y, 0.0F);
        gui.pose().scale(scale, scale, 1.0F);
        gui.drawString(this.font, text, 0, 0, color, false);
        gui.pose().popPose();
    }

    private void drawBorder(GuiGraphics gui, int x, int y, int w, int h, int color) {
        gui.fill(x, y, x + w, y + 1, color);
        gui.fill(x, y + h - 1, x + w, y + h, color);
        gui.fill(x, y, x + 1, y + h, color);
        gui.fill(x + w - 1, y, x + w, y + h, color);
    }

    private int backX() {
        return this.pageLeft - CLOSE_SIZE - 4;
    }

    private int backY() {
        return this.pageTop - CLOSE_SIZE - 4;
    }

    private boolean isOverBack(double mouseX, double mouseY) {
        return inside(mouseX, mouseY, backX(), backY(), CLOSE_SIZE, CLOSE_SIZE);
    }

    private boolean isOverClose(double mouseX, double mouseY) {
        int x = this.pageLeft + this.theme.pageWidth() + 4;
        int y = this.pageTop - CLOSE_SIZE - 4;
        return inside(mouseX, mouseY, x, y, CLOSE_SIZE, CLOSE_SIZE);
    }

    /** 左上角 ←：返回手册。 */
    private void renderBack(GuiGraphics gui, int mouseX, int mouseY) {
        int x = backX();
        int y = backY();
        boolean hovered = isOverBack(mouseX, mouseY);
        gui.fill(x, y, x + CLOSE_SIZE, y + CLOSE_SIZE,
            hovered ? this.theme.closeHoverColor() : this.theme.closeColor());
        int glyph = this.theme.closeGlyphColor();
        gui.fill(x + 3, y + 5, x + 9, y + 7, glyph);
        gui.fill(x + 3, y + 4, x + 5, y + 8, glyph);
        gui.fill(x + 4, y + 3, x + 6, y + 5, glyph);
        gui.fill(x + 4, y + 7, x + 6, y + 9, glyph);
    }

    /** 右上角 ✕：关掉详情页 + 手册，回世界。 */
    private void renderClose(GuiGraphics gui, int mouseX, int mouseY) {
        int x = this.pageLeft + this.theme.pageWidth() + 4;
        int y = this.pageTop - CLOSE_SIZE - 4;
        boolean hovered = isOverClose(mouseX, mouseY);
        gui.fill(x, y, x + CLOSE_SIZE, y + CLOSE_SIZE,
            hovered ? this.theme.closeHoverColor() : this.theme.closeColor());
        int glyph = this.theme.closeGlyphColor();
        for (int i = 2; i < CLOSE_SIZE - 2; i++) {
            gui.fill(x + i, y + i, x + i + 1, y + i + 1, glyph);
            gui.fill(x + CLOSE_SIZE - 1 - i, y + i, x + CLOSE_SIZE - i, y + i + 1, glyph);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.picker.isOpen()) {
            if (this.picker.scrollBy(scrollY)) {
                return true;
            }
            return true;
        }
        if (this.pickerSlot >= 0) {
            int maxScroll = Math.max(0, this.pickerMembers().size() - PICKER_ROWS);
            if (maxScroll > 0 && scrollY != 0.0D) {
                this.pickerScroll = Mth.clamp(this.pickerScroll - (int) Math.signum(scrollY),
                    0, maxScroll);
            }
            return true;
        }
        if (this.confirmSlot >= 0 || this.speciesPickerOpen || this.animalPickerOpen) {
            return true;
        }
        int viewH = this.theme.pageHeight() - CONTENT_TOP - CONTENT_BOTTOM;
        int totalH = contentHeight();
        int maxScroll = Math.max(0, totalH - viewH);
        if (maxScroll > 0 && scrollY != 0.0D) {
            this.scroll = Mth.clamp(this.scroll - (int) Math.signum(scrollY) * LINE_H, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 简介输入框优先吃键（14 号文档 1.8）：ESC 只失焦并提交，不离开详情页
        if (this.descEditor.focused()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UP
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
                this.descEditor.moveVertical(this.font, this.descBoxW - 4,
                    keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UP ? -1 : 1);
                return true;
            }
            if (this.descEditor.keyPressed(keyCode, modifiers)) {
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                this.descEditor.setFocused(false);
                this.pushDescription();
                return true;
            }
        }
        // 候选清单键盘操作：↑↓ 移动、Enter 选中、Esc 只关清单（规格 3.2）
        if (this.picker.isOpen()) {
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
                this.picker.move(-1);
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
                this.picker.move(1);
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                    || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
                BlockSearch.Entry entry = this.picker.selectedEntry();
                if (entry != null) {
                    this.chooseBlock(entry);
                }
                return true;
            }
            if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
                this.picker.close();
                return true;
            }
        }
        // 输入框里直接打完整 id 再按 Enter：也认
        if ((keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)
                && this.zone.isFill() && this.projectBox != null && this.projectBox.isFocused()) {
            net.minecraft.world.level.block.Block resolved =
                BlockSearch.resolve(this.projectBox.getValue());
            if (resolved != null) {
                this.projectBlock = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(resolved).toString();
                BlockSearch.rememberUsed(this.projectBlock);
                this.lastBoxText = this.projectBox.getValue();
                this.picker.close();
                this.pushProject(false);
            }
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            if (this.pickerSlot >= 0 || this.confirmSlot >= 0 || this.speciesPickerOpen
                    || this.animalPickerOpen) {
                this.pickerSlot = -1;
                this.confirmSlot = -1;
                this.speciesPickerOpen = false;
                this.animalPickerOpen = false;
                return true;
            }
            ZoneUi.click();
            this.backToManual();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** 简介输入框的普通字符（中文 / 英文 / 空格），走自绘编辑器。 */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.descEditor.focused() && this.descEditor.charTyped(codePoint)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void onClose() {
        this.pushDescription();
        this.backToManual();
    }
    private void backToManual() {
        // 任何方式离开详情页 → 简介立即保存（14 号文档 1.8.4）
        this.pushDescription();
        if (this.minecraft == null) {
            return;
        }
        // 回到打开这个详情页的那一页（主人 2026-11-20）
        switch (this.from) {
            case FROM_MEMBERS -> ManualScreen.openMembers();
            case FROM_SUBTYPE -> ManualScreen.openRegion(this.fromZoneType);
            default -> this.minecraft.setScreen(new ManualScreen());
        }
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x <= rx + rw && y >= ry && y <= ry + rh;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
