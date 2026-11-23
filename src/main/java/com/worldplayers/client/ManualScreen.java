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
 *   <li><b>手册渲染</b>：五个勾选项（居民状态浮窗 / 工作区域 / 建造中的工程 / 城市道路 / 巡逻路径），
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
    /** 清单里「工作区域类型」名字的最大显示宽度：超出截断 + 悬浮滚动（主人 2026-11-23）。 */
    private static final int TYPE_LABEL_MAX_W = 64;
    private static final int CHECK_SIZE = 11;

    /** 「区域选择」页：顶端规划按钮 + 排序羽毛 + 已储存区域列表。 */
    private static final int PLAN_BUTTON_TOP = 10;
    private static final int PLAN_BUTTON_H = 22;
    private static final int PLAN_MARGIN = 12;
    /** 工作区顺序编辑羽毛：跟工作区图标同一个 x、同样 16×16。 */
    private static final int FEATHER_X = CONTENT_X;
    private static final int FEATHER_Y = 34;
    private static final int FEATHER_SIZE = 16;
    /** 成员列表页的行高 + 列表顶端（招募按钮下面）。 */
    private static final int MEMBER_ROW = 20;
    /** 成员页：招募按钮下面的羽毛 + 清单起点。 */
    private static final int MEMBER_LIST_TOP = 54;
    /** 一行里「成员框 / 工作框 / 床框」之间的间隔。 */
    private static final int MEMBER_GAP = 3;
    private static final int MEMBER_BOX_H = 18;
    /** 区域选择页的类型列表（规划按钮 + 羽毛下面）。 */
    private static final int TYPE_LIST_TOP = 54;
    private static final int TYPE_ROW = 24;
    /** 工作区子类清单（子类页只有羽毛 + 清单）。 */
    private static final int ZONE_LIST_TOP = 34;
    private static final int ZONE_ROW = 20;
    private static final int ZONE_X_SIZE = 12;
    /** 清单滚动条：细条贴在页面右边缘。 */
    private static final int ZONE_SCROLL_W = 3;
    private static final int ZONE_SCROLL_GAP = 4;
    /** 勾选项 / 类型列表共用的滚动条宽度（2026-11-23 主人：这两处以后会越加越多）。 */
    private static final int LIST_SCROLL_W = 3;
    private static final int LIST_SCROLL_GAP = 4;
    /** 列表底部预留（保证最后一行不贴在纸张边缘）。 */
    private static final int LIST_BOTTOM_PAD = 20;

    private static final int TAB_REGION = 0;
    private static final int TAB_RENDER = 1;
    /** 成员列表：招募新成员 + 已招募居民清单（主人 2026-11-20）。 */
    private static final int TAB_MEMBERS = 2;
    private static final int TAB_COUNT = 3;

    private static final String[] TAB_IDS = {"region", "render", "members"};
    /** 侧边栏栏位名：走语言文件（zh_cn / en_us）。 */
    private static final String[] TAB_KEYS = {
        "gui.worldplayers.manual.tab.region",
        "gui.worldplayers.manual.tab.render",
        "gui.worldplayers.manual.tab.members"
    };
    private static final ResourceLocation[] TAB_ICONS = {
        ResourceLocation.withDefaultNamespace("textures/item/map.png"),
        ResourceLocation.withDefaultNamespace("textures/item/writable_book.png"),
        // 成员列表：图标画的是玩家头颅**物品**（不用死贴图路径），材质包换头颅模型时跟着变
        ResourceLocation.withDefaultNamespace("textures/item/player_head.png")
    };
    private static final ResourceLocation ICON_PEN =
        ResourceLocation.withDefaultNamespace("textures/item/feather.png");

    /** 手册渲染勾选项的文字：走语言文件。 */
    private static final String[] RENDER_KEYS = {
        "gui.worldplayers.manual.render.status_card",
        "gui.worldplayers.manual.render.city_road",
        "gui.worldplayers.manual.render.work_area",
        "gui.worldplayers.manual.render.building_schematic",
        "gui.worldplayers.manual.render.bed_binding",
        "gui.worldplayers.manual.render.forbidden",
        // 14 号文档 1.4：娱乐区域独立勾选（默认开），不跟「工作区域」走
        "gui.worldplayers.manual.render.entertainment"
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

    /** 正在等确认删除的区域（null = 没弹窗）。用对象而不是名字：同名区域要各自独立。 */
    private com.worldplayers.resident.WorkZone pendingDelete;
    /** 正在拖动的栏位 id；-1 = 没在拖。 */
    private int dragTab = -1;
    private int dragSlot = -1;
    private int hoverSlot = -1;

    // ---- 工作区清单：滚动 + 顺序编辑（主人 2026-11-20） ----
    /** 清单滚动了多少行。 */
    private int zoneScroll;
    /** 是否在编辑工作区顺序。 */
    private boolean zoneEditMode;
    /** 编辑期间的本地顺序副本（不保存就丢弃）。 */
    private java.util.List<com.worldplayers.resident.WorkZone> zoneEditList;
    private int zoneDragIndex = -1;
    private int zoneHoverIndex = -1;
    /** 是否正在拖滚动条。 */
    private boolean zoneScrollDragging;

    // ---- 区域选择页：类型列表 / 子类（主人 2026-11-20） ----
    /** 当前进入的工作区类型（null = 类型列表页）。 */
    private String currentZoneType;
    /** 类型顺序编辑。 */
    private boolean typeEditMode;
    private java.util.List<String> typeEditList;
    private int typeDragIndex = -1;
    private int typeHoverIndex = -1;
    /** 类型列表滚动（2026-11-23 主人：以后区域类型会更多，得能滚）。 */
    private int typeScroll;
    private boolean typeScrollDragging;

    // ---- 手册渲染页：勾选项滚动（2026-11-23 主人：以后勾选项会更多） ----
    private int checkScroll;
    private boolean checkScrollDragging;

    // ---- 成员列表页：居民顺序编辑（主人 2026-11-20） ----
    /** 是否在编辑居民顺序（羽毛变绿 ✓）。 */
    private boolean memberEditMode;
    /** 编辑期间的本地顺序副本（不保存就丢弃）。 */
    private java.util.List<com.worldplayers.network.ResidentListPayload.Member> memberEditList;
    private int memberDragIndex = -1;
    private int memberHoverIndex = -1;
    /** 成员列表滚动了多少行（人多时可以翻）。 */
    private int memberScroll;
    /** 正在拖拽成员列表滚动条（主人 2026-11-23：滑动栏要能用鼠标拖）。 */
    private boolean memberScrollDragging;

    /** 打开时想直接定位到哪个栏位（-1 = 默认「区域选择」）。 */
    private int pendingTab = -1;
    /** 打开时想直接进入哪个工作区类型子类（null = 类型列表）。 */
    private String pendingZoneType;

    public ManualScreen() {
        this(-1, null);
    }

    public ManualScreen(int initialTab) {
        this(initialTab, null);
    }

    public ManualScreen(int initialTab, String initialZoneType) {
        super(Component.translatable("gui.worldplayers.manual.title"));
        this.loadOrder();
        this.pendingTab = initialTab;
        this.pendingZoneType = initialZoneType;
    }

    /** 客户端入口：右键手册打开这一页。 */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ManualScreen());
    }

    /** 从只读居民界面返回：直接开在「成员列表」页（主人 2026-11-20）。 */
    public static void openMembers() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ManualScreen(TAB_MEMBERS));
    }

    /** 从工作区详情页返回：开在「区域选择」页，并回到对应的类型子类。 */
    public static void openRegion(@javax.annotation.Nullable String zoneType) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ManualScreen(TAB_REGION, zoneType));
    }

    // ------------------------------------------------------------------
    // 生命周期 / 布局
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        this.theme = ManualThemes.current();
        if (this.pendingTab >= 0) {
            this.currentTab = this.pendingTab;
            this.pendingTab = -1;
        }
        if (this.pendingZoneType != null) {
            this.currentZoneType = this.pendingZoneType;
            this.pendingZoneType = null;
        }
        // 扫一遍农田区域的作物，给清单右下角的小图标用（内部有 500ms 节流）
        ZoneStore.refreshCrops();
        // 成员列表：每次打开手册都让服务端补发一份最新成员（招募/死亡/绑定都会变）
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new com.worldplayers.network.BedBindPayload(
                com.worldplayers.network.BedBindPayload.REQUEST_LIST, new java.util.UUID(0L, 0L)));

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
        this.zoneDragIndex = -1;
        this.zoneHoverIndex = -1;
        this.zoneScrollDragging = false;
        this.typeDragIndex = -1;
        this.typeHoverIndex = -1;
        this.typeScrollDragging = false;
        this.checkScrollDragging = false;
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
        // 视觉上两者互换位置 → 实际也做互换（主人 2026-11-20）
        int swap = this.order[from];
        this.order[from] = this.order[to];
        this.order[to] = swap;
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
        // 编辑顺序 / 工作区子类页：侧边栏（和它的编辑羽毛）都不画（主人 2026-11-20）
        if (!this.zoneEditMode && this.currentZoneType == null) {
            this.renderSidebar(guiGraphics, pageX, pageY);
        }
        this.renderContent(guiGraphics, pageX, pageY);
        this.renderDragGhost(guiGraphics, pageX, pageY);

        guiGraphics.pose().popPose();

        this.renderClose(guiGraphics, mouseX, mouseY);
        if (this.currentZoneType != null) {
            this.renderSubBack(guiGraphics, mouseX, mouseY);
        }
        this.renderButtonTooltip(guiGraphics, mouseX, mouseY);
        // 成员框悬浮提示必须在**屏幕坐标系**里画（放在 pose pop 之后），否则会被页面缩放叠加两次 → 远离鼠标
        this.renderMemberTooltip(guiGraphics, mouseX, mouseY);
    }

    /** 成员框的悬浮提示（居民当前坐标）：用屏幕坐标画在鼠标旁边。 */
    private void renderMemberTooltip(GuiGraphics gui, int mouseX, int mouseY) {
        if (this.currentTab != TAB_MEMBERS || this.memberEditMode || this.dragTab >= 0
                || this.memberDragIndex >= 0) {
            return;
        }
        float pageX = this.toPageX(mouseX);
        float pageY = this.toPageY(mouseY);
        int index = this.memberIndexAt(pageX, pageY);
        if (index < 0) {
            return;
        }
        java.util.List<com.worldplayers.network.ResidentListPayload.Member> members = this.orderedMembers();
        if (index >= members.size()) {
            return;
        }
        int slotY = MEMBER_LIST_TOP + (index - this.memberScroll) * MEMBER_ROW;
        int[] box = this.memberLayout(slotY);
        if (!inside(pageX, pageY, box[0], slotY, box[1], MEMBER_BOX_H)) {
            return;
        }
        com.worldplayers.network.ResidentListPayload.Member member = members.get(index);
        gui.renderTooltip(this.font,
            Component.translatable("gui.worldplayers.manual.member_pos",
                member.posX(), member.posY(), member.posZ()),
            mouseX, mouseY);
    }

    // ------------------------------------------------------------------
    // 工作区子类页：左上角 ← 返回（纸张外面，跟详情页同款）
    // ------------------------------------------------------------------

    private int subBackX() {
        return this.pageLeft - CLOSE_SIZE - 4;
    }

    private int subBackY() {
        return this.pageTop - CLOSE_SIZE - 4;
    }

    private boolean isOverSubBack(double mouseX, double mouseY) {
        int x = subBackX();
        int y = subBackY();
        return mouseX >= x && mouseX <= x + CLOSE_SIZE && mouseY >= y && mouseY <= y + CLOSE_SIZE;
    }

    private void renderSubBack(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = subBackX();
        int y = subBackY();
        boolean hovered = isOverSubBack(mouseX, mouseY);
        guiGraphics.fill(x, y, x + CLOSE_SIZE, y + CLOSE_SIZE,
            hovered ? this.theme.closeHoverColor() : this.theme.closeColor());
        int glyph = this.theme.closeGlyphColor();
        guiGraphics.fill(x + 3, y + 5, x + 9, y + 7, glyph);
        guiGraphics.fill(x + 3, y + 4, x + 5, y + 8, glyph);
        guiGraphics.fill(x + 4, y + 3, x + 6, y + 5, glyph);
        guiGraphics.fill(x + 4, y + 7, x + 6, y + 9, glyph);
    }

    /**
     * 侧边栏左上角按钮的悬浮提示：羽毛 = 编辑侧边栏顺序，勾 = 保存。
     * 用屏幕坐标画（不跟纸张缩放走），和原版 tooltip 一样。
     */
    private void renderButtonTooltip(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.dragTab >= 0 || this.zoneDragIndex >= 0 || this.typeDragIndex >= 0) {
            return;
        }
        float pageX = this.toPageX(mouseX);
        float pageY = this.toPageY(mouseY);
        // 成员列表页的羽毛：编辑 / 保存居民顺序
        if (this.currentTab == TAB_MEMBERS
                && inside(pageX, pageY, FEATHER_X, FEATHER_Y, FEATHER_SIZE, FEATHER_SIZE)) {
            guiGraphics.renderTooltip(this.font, Component.translatable(this.memberEditMode
                ? "gui.worldplayers.manual.member_save_hint"
                : "gui.worldplayers.manual.member_edit_hint"), mouseX, mouseY);
            return;
        }
        // 成员列表页的「招募新成员」按钮：提示要消耗多少食物（数量跟服务端配置浮动）
        if (this.currentTab == TAB_MEMBERS) {
            int recruitW = this.theme.pageWidth() - PLAN_MARGIN * 2;
            if (inside(pageX, pageY, PLAN_MARGIN, PLAN_BUTTON_TOP, recruitW, PLAN_BUTTON_H)) {
                guiGraphics.renderTooltip(this.font, Component.translatable(
                    "gui.worldplayers.manual.recruit_hint", ResidentStore.recruitCost()), mouseX, mouseY);
                return;
            }
        }
        // 区域选择页的羽毛（类型顺序 / 工作区顺序，按当前视图区分）
        if (this.currentTab == TAB_REGION
                && inside(pageX, pageY, FEATHER_X, this.featherY(), FEATHER_SIZE, FEATHER_SIZE)) {
            boolean typeView = this.currentZoneType == null;
            boolean editing = typeView ? this.typeEditMode : this.zoneEditMode;
            String key;
            if (typeView) {
                key = editing ? "gui.worldplayers.manual.zone_type_save_hint"
                    : "gui.worldplayers.manual.zone_type_edit_hint";
            } else {
                key = editing ? "gui.worldplayers.manual.zone_save_hint"
                    : "gui.worldplayers.manual.zone_edit_hint";
            }
            guiGraphics.renderTooltip(this.font, Component.translatable(key), mouseX, mouseY);
            return;
        }
        // 渲染勾选项：禁行区域 → 说明文字（主人 2026-11-23）
        if (this.currentTab == TAB_RENDER) {
            int checkbox = this.checkboxAt(pageX, pageY);
            if (checkbox == 5) {
                guiGraphics.renderTooltip(this.font,
                    Component.translatable("gui.worldplayers.zone.type.forbidden.tooltip"),
                    mouseX, mouseY);
                return;
            }
        }
        // 区域选择页的类型列表：每种类型自己的说明（农田 / 禁行 / 挖掘 / 填充 / 娱乐）
        if (this.currentTab == TAB_REGION && this.currentZoneType == null
                && !this.typeEditMode && !this.zoneEditMode) {
            int typeRowWidth = this.theme.pageWidth() - PLAN_MARGIN * 2;
            java.util.List<String> types = this.orderedTypes();
            int visibleRows = this.visibleRowsFor(TYPE_LIST_TOP, TYPE_ROW);
            int start = Mth.clamp(this.typeScroll, 0, Math.max(0, types.size() - visibleRows));
            int end = Math.min(types.size(), start + visibleRows);
            for (int index = start; index < end; index++) {
                String type = types.get(index);
                int rowY = TYPE_LIST_TOP + (index - start) * TYPE_ROW;
                if (!inside(pageX, pageY, PLAN_MARGIN, rowY, typeRowWidth, 20)) {
                    continue;
                }
                String key = switch (type) {
                    case com.worldplayers.resident.WorkZone.TYPE_FARM ->
                        "gui.worldplayers.zone.type.farm.tooltip";
                    case com.worldplayers.resident.WorkZone.TYPE_MINE ->
                        "gui.worldplayers.zone.type.mine.tooltip";
                    case com.worldplayers.resident.WorkZone.TYPE_FILL ->
                        "gui.worldplayers.zone.type.fill.tooltip";
                    case com.worldplayers.resident.WorkZone.TYPE_ENTERTAINMENT ->
                        "gui.worldplayers.zone.type.entertainment.tooltip";
                    case com.worldplayers.resident.WorkZone.TYPE_FORBIDDEN ->
                        "gui.worldplayers.zone.type.forbidden.tooltip";
                    default -> null;
                };
                if (key != null) {
                    guiGraphics.renderTooltip(this.font, Component.translatable(key), mouseX, mouseY);
                    return;
                }
            }
        }
        if (this.currentZoneType != null) {
            return;
        }
        if (!inside(pageX, pageY, PEN_X, PEN_Y, PEN_SIZE, PEN_SIZE)) {
            return;
        }
        Component hint = Component.translatable(this.editMode
            ? "gui.worldplayers.manual.save_hint"
            : "gui.worldplayers.manual.edit_hint");
        guiGraphics.renderTooltip(this.font, hint, mouseX, mouseY);
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
        if (this.currentTab == TAB_REGION) {
            this.renderRegionTab(guiGraphics, mouseX, mouseY);
            return;
        }
        if (this.currentTab == TAB_MEMBERS) {
            this.renderMembersTab(guiGraphics, mouseX, mouseY);
            return;
        }
        if (this.currentTab != TAB_RENDER) {
            return;
        }
        boolean[] values = {
            WPClientConfig.showResidentStatusCard(),
            WPClientConfig.showCityRoads(),
            WPClientConfig.showWorkArea(),
            WPClientConfig.showBuildingProject(),
            WPClientConfig.showBedBinding(),
            WPClientConfig.showForbidden(),
            WPClientConfig.showEntertainment()
        };
        // 2026-11-23 主人：以后勾选项会越来越多 → 超出可视区就出滚动条（滚轮 / 拖条都行）
        int visible = this.visibleRowsFor(CHECK_TOP, CHECK_ROW);
        this.checkScroll = Mth.clamp(this.checkScroll, 0, Math.max(0, values.length - visible));
        int end = Math.min(values.length, this.checkScroll + visible);
        for (int i = this.checkScroll; i < end; i++) {
            int y = CHECK_TOP + (i - this.checkScroll) * CHECK_ROW;
            drawCheckbox(guiGraphics, CONTENT_X, y, values[i]);
            guiGraphics.drawString(this.font, Component.translatable(RENDER_KEYS[i]), CONTENT_X + 18, y + 1,
                this.theme.inkColor(), false);
        }
        this.renderListScrollbar(guiGraphics, CHECK_TOP, CHECK_ROW, values.length, visible, this.checkScroll);
    }

    /** 区域选择页：类型列表（规划按钮 + 类型顺序羽毛 + 道路/农田…）。 */
    private void renderRegionTab(GuiGraphics guiGraphics, float mouseX, float mouseY) {
        if (this.currentZoneType == null) {
            this.renderZoneTypeList(guiGraphics, mouseX, mouseY);
        } else {
            this.renderZoneSubList(guiGraphics, mouseX, mouseY);
        }
    }

    /**
     * 成员列表页（主人 2026-11-20）：顶部「招募新成员」按钮 → 进入绑定床铺模式；
     * 下面是羽毛（编辑居民顺序）+ 一行一个居民：
     * 成员框（当前皮肤头像 + 名字）｜ 工作框（绑定工作区图标 + 名称，未绑定 = 无业）｜ 床框（床 / 流浪）。
     */
    private void renderMembersTab(GuiGraphics guiGraphics, float mouseX, float mouseY) {
        int buttonW = this.theme.pageWidth() - PLAN_MARGIN * 2;
        boolean hovered = inside(mouseX, mouseY, PLAN_MARGIN, PLAN_BUTTON_TOP, buttonW, PLAN_BUTTON_H);
        net.minecraft.resources.ResourceLocation sprite =
            net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                hovered ? "widget/button_highlighted" : "widget/button");
        guiGraphics.blitSprite(sprite, PLAN_MARGIN, PLAN_BUTTON_TOP, buttonW, PLAN_BUTTON_H);
        Component label = Component.translatable("gui.worldplayers.manual.recruit");
        guiGraphics.drawString(this.font, label,
            PLAN_MARGIN + (buttonW - this.font.width(label)) / 2,
            PLAN_BUTTON_TOP + (PLAN_BUTTON_H - 8) / 2, 0xFFFFFF, true);

        // 居民顺序羽毛（和区域页同一位置同一观感）
        this.renderFeather(guiGraphics, mouseX, mouseY, this.memberEditMode);

        java.util.List<com.worldplayers.network.ResidentListPayload.Member> members =
            this.memberEditMode && this.memberEditList != null ? this.memberEditList : this.orderedMembers();
        if (members.isEmpty()) {
            guiGraphics.drawString(this.font,
                Component.translatable("gui.worldplayers.manual.members_empty"),
                CONTENT_X, MEMBER_LIST_TOP, this.theme.inkMutedColor(), false);
            return;
        }
        int maxRows = Math.max(1, (this.theme.pageHeight() - MEMBER_LIST_TOP - 10) / MEMBER_ROW);
        this.memberScroll = Mth.clamp(this.memberScroll, 0, Math.max(0, members.size() - maxRows));
        int rows = Math.min(members.size() - this.memberScroll, maxRows);
        for (int slot = 0; slot < rows; slot++) {
            int i = this.memberScroll + slot;
            int y = MEMBER_LIST_TOP + slot * MEMBER_ROW;
            if (this.memberEditMode && this.memberDragIndex >= 0
                    && (i == this.memberDragIndex || i == this.memberHoverIndex)) {
                drawEmptySlot(guiGraphics, CONTENT_X, y, this.memberRowWidth(), MEMBER_BOX_H);
                // 源位置显示"被悬浮成员"的半透明影像（和侧边栏编辑一个观感）
                if (i == this.memberDragIndex && this.memberHoverIndex >= 0
                        && this.memberHoverIndex != this.memberDragIndex
                        && this.memberHoverIndex < members.size()) {
                    this.drawMemberRow(guiGraphics, members.get(this.memberHoverIndex),
                        CONTENT_X, y, -1, -1, 0.45F);
                }
                continue;
            }
            this.drawMemberRow(guiGraphics, members.get(i), CONTENT_X, y, mouseX, mouseY, 1.0F);
        }
        if (members.size() > maxRows) {
            this.renderMemberScrollbar(guiGraphics, members.size(), maxRows);
        }
    }

    /** 成员列表可见行数。 */
    private int visibleMemberRows() {
        return Math.max(1, (this.theme.pageHeight() - MEMBER_LIST_TOP - 10) / MEMBER_ROW);
    }

    private void renderMemberScrollbar(GuiGraphics gui, int total, int visible) {
        int x = this.theme.pageWidth() - ZONE_SCROLL_GAP - ZONE_SCROLL_W;
        int top = MEMBER_LIST_TOP;
        int height = visible * MEMBER_ROW;
        int maxScroll = Math.max(1, total - visible);
        int thumb = Math.max(12, height * visible / total);
        int thumbY = top + (height - thumb) * this.memberScroll / maxScroll;
        gui.fill(x, top, x + ZONE_SCROLL_W, top + height, 0x33000000);
        gui.fill(x, thumbY, x + ZONE_SCROLL_W, thumbY + thumb, 0x99000000);
    }

    /** 点到成员列表滚动条了吗（主人 2026-11-23 要求可拖拽）。 */
    private boolean memberScrollbarAt(float pageX, float pageY) {
        java.util.List<com.worldplayers.network.ResidentListPayload.Member> members =
            this.memberListForScroll();
        int visible = this.visibleMemberRows();
        if (members.size() <= visible) {
            return false;
        }
        int x = this.theme.pageWidth() - ZONE_SCROLL_GAP - ZONE_SCROLL_W;
        return inside(pageX, pageY, x - 2, MEMBER_LIST_TOP, ZONE_SCROLL_W + 4,
            visible * MEMBER_ROW);
    }

    private java.util.List<com.worldplayers.network.ResidentListPayload.Member> memberListForScroll() {
        return this.memberEditMode && this.memberEditList != null
            ? this.memberEditList : this.orderedMembers();
    }

    /** 拖拽 / 点击滚动条 → 按比例更新 memberScroll。 */
    private void updateMemberScrollFromMouse(float pageY) {
        java.util.List<com.worldplayers.network.ResidentListPayload.Member> members =
            this.memberListForScroll();
        int visible = this.visibleMemberRows();
        int max = Math.max(0, members.size() - visible);
        if (max <= 0) {
            this.memberScroll = 0;
            return;
        }
        int top = MEMBER_LIST_TOP;
        int height = visible * MEMBER_ROW;
        int thumb = Math.max(12, height * visible / members.size());
        float ratio = (pageY - top - thumb / 2.0F) / Math.max(1, height - thumb);
        this.memberScroll = Mth.clamp(Math.round(ratio * max), 0, max);
    }

    private int memberRowWidth() {
        return this.theme.pageWidth() - CONTENT_X * 2;
    }

    /** 一行里的三框布局：成员框 36%、工作框 28%、剩下给床框。 */
    private int[] memberLayout(int y) {
        int left = CONTENT_X;
        int total = this.memberRowWidth();
        int memberW = Math.round(total * 0.36F);
        int jobW = Math.round(total * 0.28F);
        int bedW = total - memberW - jobW - MEMBER_GAP * 2;
        int memberX = left;
        int jobX = memberX + memberW + MEMBER_GAP;
        int bedX = jobX + jobW + MEMBER_GAP;
        return new int[]{memberX, memberW, jobX, jobW, bedX, bedW, y};
    }

    private int[] memberWidths(int total) {
        int memberW = Math.round(total * 0.36F);
        int jobW = Math.round(total * 0.28F);
        int bedW = total - memberW - jobW - MEMBER_GAP * 2;
        return new int[]{memberW, jobW, bedW};
    }

    /** 画一个成员行（alpha &lt; 1 时是拖拽影像）。{@code rowX} 是这一行最左边的页面 x。 */
    private void drawMemberRow(GuiGraphics gui, com.worldplayers.network.ResidentListPayload.Member member,
                               int rowX, int y, float mouseX, float mouseY, float alpha) {
        int memberX = rowX;
        int total = this.memberRowWidth();
        int memberW = this.memberWidths(total)[0];
        int jobW = this.memberWidths(total)[1];
        int bedW = this.memberWidths(total)[2];
        int jobX = memberX + memberW + MEMBER_GAP;
        int bedX = jobX + jobW + MEMBER_GAP;
        boolean interactive = alpha >= 1.0F && !this.memberEditMode;

        // ---- 成员框：当前皮肤头像 + 名字 ----
        boolean memberHover = interactive && inside(mouseX, mouseY, memberX, y, memberW, MEMBER_BOX_H);
        if (memberHover) {
            gui.fill(memberX, y, memberX + memberW, y + MEMBER_BOX_H, 0x33FFFFFF);
        }
        this.drawMemberHead(gui, member, memberX + 1, y + 1, alpha);
        Component name = member.name() == null || member.name().isBlank()
            ? Component.translatable("entity.worldplayers.resident")
            : Component.literal(member.name());
        if (!member.hasBed()) {
            name = Component.translatable("gui.worldplayers.name_wanderer_suffix", name);
        }
        this.drawMarquee(gui, name.getString(), memberX + 19, y + 5, memberW - 21,
            memberHover, this.theme.inkColor(), 1.0F, true);

        // ---- 工作框：绑定的工作区域；没有 = 无业 ----
        com.worldplayers.resident.WorkZone job = this.jobZoneOf(member);
        boolean jobHover = interactive && job != null && inside(mouseX, mouseY, jobX, y, jobW, MEMBER_BOX_H);
        if (jobHover) {
            gui.fill(jobX, y, jobX + jobW, y + MEMBER_BOX_H, 0x33FFFFFF);
        }
        if (job == null) {
            Component none = Component.translatable("gui.worldplayers.manual.member_job_none");
            gui.drawString(this.font, none, jobX + (jobW - this.font.width(none)) / 2, y + 5,
                this.theme.inkMutedColor(), false);
        } else {
            gui.renderItem(zoneIcon(job.type()), jobX + 1, y + 1);
            this.drawMarquee(gui, job.name(), jobX + 19, y + 5, jobW - 21,
                jobHover, this.theme.inkColor(), 1.0F, true);
        }

        // ---- 床框：床图标 + 床坐标（悬浮可滚动查看）；没有床 = 流浪 ----
        if (member.hasBed()) {
            boolean bedHover = interactive && inside(mouseX, mouseY, bedX, y, bedW, MEMBER_BOX_H);
            if (bedHover) {
                gui.fill(bedX, y, bedX + bedW, y + MEMBER_BOX_H, 0x33FFFFFF);
            }
            gui.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.RED_BED),
                bedX + 1, y + 1);
            String coord = member.bed().getX() + ", " + member.bed().getY() + ", " + member.bed().getZ();
            this.drawMarquee(gui, coord, bedX + 19, y + 6, bedW - 21,
                bedHover, this.theme.inkColor(), 0.75F, true);
        } else {
            Component free = Component.translatable("gui.worldplayers.manual.member_free");
            gui.drawString(this.font, free, bedX + (bedW - this.font.width(free)) / 2, y + 5,
                this.theme.inkMutedColor(), false);
        }
    }

    /** 成员头像 = 他当前用的皮肤头部（8×8 头部 + 帽子层，16×16 显示）。 */
    private void drawMemberHead(GuiGraphics gui, com.worldplayers.network.ResidentListPayload.Member member,
                                int x, int y, float alpha) {
        net.minecraft.resources.ResourceLocation texture = ClientSkins.skin(member.skin()).texture();
        if (alpha < 1.0F) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        }
        gui.blit(texture, x, y, 16, 16, 8.0F, 8.0F, 8, 8, 64, 64);
        gui.blit(texture, x, y, 16, 16, 40.0F, 8.0F, 8, 8, 64, 64);
        if (alpha < 1.0F) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** 这个居民绑定的工作区域（`WorkZone.resident` 存的是居民 UUID）；没有返回 null。 */
    @javax.annotation.Nullable
    private com.worldplayers.resident.WorkZone jobZoneOf(
            com.worldplayers.network.ResidentListPayload.Member member) {
        String id = member.id().toString();
        for (com.worldplayers.resident.WorkZone zone : ZoneStore.zones()) {
            if (zone.hasEmployee(id)) {
                return zone;
            }
        }
        return null;
    }

    /** 成员列表的显示顺序：按 `WPClientConfig.memberOrder`，没列到的排在后面。 */
    private java.util.List<com.worldplayers.network.ResidentListPayload.Member> orderedMembers() {
        java.util.List<com.worldplayers.network.ResidentListPayload.Member> all =
            new java.util.ArrayList<>(ResidentStore.members());
        java.util.List<com.worldplayers.network.ResidentListPayload.Member> out = new java.util.ArrayList<>();
        for (String id : WPClientConfig.memberOrderList()) {
            for (var member : all) {
                if (member.id().toString().equals(id) && !out.contains(member)) {
                    out.add(member);
                }
            }
        }
        for (var member : all) {
            if (!out.contains(member)) {
                out.add(member);
            }
        }
        return out;
    }

    /** 鼠标所在的成员行（页面坐标）；不在任何行返回 -1。 */
    private int memberIndexAt(float pageX, float pageY) {
        int count = this.memberEditMode && this.memberEditList != null
            ? this.memberEditList.size() : this.orderedMembers().size();
        int rows = Math.min(count - this.memberScroll, this.visibleMemberRows());
        for (int slot = 0; slot < rows; slot++) {
            int y = MEMBER_LIST_TOP + slot * MEMBER_ROW;
            if (inside(pageX, pageY, CONTENT_X, y, this.memberRowWidth(), MEMBER_BOX_H)) {
                return this.memberScroll + slot;
            }
        }
        return -1;
    }

    /** 保存居民顺序（羽毛 ✓）。 */
    private void saveMemberOrder() {
        if (this.memberEditList == null) {
            return;
        }
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (var member : this.memberEditList) {
            ids.add(member.id().toString());
        }
        // 合并：这次没出现在列表里的成员保持在后面
        java.util.List<String> order = new java.util.ArrayList<>(WPClientConfig.memberOrderList());
        order.removeAll(ids);
        order.addAll(ids);
        WPClientConfig.setMemberOrder(order);
        WPClientConfig.save();
    }

    /** 类型列表：规划区域按钮 → 类型顺序羽毛 → 每个类型一行（图标 + 名称 + &gt;）。 */
    private void renderZoneTypeList(GuiGraphics guiGraphics, float mouseX, float mouseY) {
        int buttonW = this.theme.pageWidth() - PLAN_MARGIN * 2;
        boolean hovered = inside(mouseX, mouseY, PLAN_MARGIN, PLAN_BUTTON_TOP, buttonW, PLAN_BUTTON_H);
        net.minecraft.resources.ResourceLocation sprite =
            net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                hovered ? "widget/button_highlighted" : "widget/button");
        guiGraphics.blitSprite(sprite, PLAN_MARGIN, PLAN_BUTTON_TOP, buttonW, PLAN_BUTTON_H);
        Component label = Component.translatable("gui.worldplayers.manual.plan_region");
        guiGraphics.drawString(this.font, label,
            PLAN_MARGIN + (buttonW - this.font.width(label)) / 2,
            PLAN_BUTTON_TOP + (PLAN_BUTTON_H - 8) / 2, 0xFFFFFF, true);

        this.renderFeather(guiGraphics, mouseX, mouseY, this.typeEditMode);

        java.util.List<String> types = this.orderedTypes();
        // 2026-11-23 主人：以后区域类型会越来越多 → 超出可视区就滚动（滚轮 / 拖条）
        int visible = this.visibleRowsFor(TYPE_LIST_TOP, TYPE_ROW);
        this.typeScroll = Mth.clamp(this.typeScroll, 0, Math.max(0, types.size() - visible));
        int end = Math.min(types.size(), this.typeScroll + visible);
        for (int index = this.typeScroll; index < end; index++) {
            String type = types.get(index);
            int y = TYPE_LIST_TOP + (index - this.typeScroll) * TYPE_ROW;
            boolean rowHover = inside(mouseX, mouseY, PLAN_MARGIN, y, buttonW, 20);
            if (this.typeEditMode && this.typeDragIndex >= 0
                    && (index == this.typeDragIndex || index == this.typeHoverIndex)) {
                drawEmptySlot(guiGraphics, PLAN_MARGIN, y, buttonW, 20);
                // 源位置显示"被悬浮类型"的半透明影像（和侧边栏编辑一个观感）
                if (index == this.typeDragIndex && this.typeHoverIndex >= 0
                        && this.typeHoverIndex != this.typeDragIndex) {
                    this.drawTypeRow(guiGraphics, types.get(this.typeHoverIndex),
                        PLAN_MARGIN, y, buttonW, false, 0.45F);
                }
                continue;
            }
            this.drawTypeRow(guiGraphics, type, PLAN_MARGIN, y, buttonW,
                rowHover && !this.typeEditMode, 1.0F);
        }
        this.renderListScrollbar(guiGraphics, TYPE_LIST_TOP, TYPE_ROW, types.size(), visible, this.typeScroll);
    }

    /** 类型列表的一行：灰白按钮 + 类型图标 + 名称 + &gt;（alpha &lt; 1 时是拖拽影像）。 */
    private void drawTypeRow(GuiGraphics guiGraphics, String type, int x, int y, int w,
                             boolean hovered, float alpha) {
        int alphaByte = (int) (Mth.clamp(alpha, 0.0F, 1.0F) * 255.0F);
        if (alpha >= 1.0F) {
            guiGraphics.blitSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                hovered ? "widget/button_highlighted" : "widget/button"), x, y, w, 20);
        } else {
            guiGraphics.fill(x, y, x + w, y + 20,
                ManualTheme.withAlpha(0xFF3C3C3C, (int) (alphaByte * 0.6F)));
        }
        if (alpha < 1.0F) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
        }
        guiGraphics.renderItem(zoneIcon(type), x + 4, y + 2);
        if (alpha < 1.0F) {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        int color = alpha >= 1.0F ? 0xFFFFFF : ManualTheme.withAlpha(0xFFFFFF, alphaByte);
        // 主人 2026-11-23：类型名放不下就截断，悬浮时横向滚动显示全文（各种语言的类型名长度差很多）
        String name = Component.translatable(zoneTypeKey(type)).getString();
        this.drawMarquee(guiGraphics, name, x + 24, y + 6, Math.max(8, w - 24 - 16),
            hovered && alpha >= 1.0F, color, 1.0F, true);
        guiGraphics.drawString(this.font, ">", x + w - 12, y + 6, color, true);
    }

    /** 子类：羽毛（工作区顺序）+ 该类型的工作区清单 + 滚动条。 */
    private void renderZoneSubList(GuiGraphics guiGraphics, float mouseX, float mouseY) {
        this.renderFeather(guiGraphics, mouseX, mouseY, this.zoneEditMode);

        java.util.List<com.worldplayers.resident.WorkZone> list = this.zonesForDisplay();
        if (list.isEmpty()) {
            guiGraphics.drawString(this.font,
                Component.translatable("gui.worldplayers.manual.zone_empty"),
                CONTENT_X, ZONE_LIST_TOP, this.theme.inkMutedColor(), false);
            if (this.pendingDelete != null) {
                this.renderDeleteDialog(guiGraphics, mouseX, mouseY);
            }
            return;
        }
        int visible = this.visibleZoneRows();
        this.zoneScroll = Mth.clamp(this.zoneScroll, 0, Math.max(0, list.size() - visible));
        int end = Math.min(list.size(), this.zoneScroll + visible);
        for (int index = this.zoneScroll; index < end; index++) {
            com.worldplayers.resident.WorkZone zone = list.get(index);
            int y = ZONE_LIST_TOP + (index - this.zoneScroll) * ZONE_ROW;
            if (this.zoneEditMode) {
                this.renderZoneEditRow(guiGraphics, index, y, list);
            } else {
                this.drawZoneRow(guiGraphics, zone, y, mouseX, mouseY);
            }
        }
        if (list.size() > visible) {
            this.renderZoneScrollbar(guiGraphics, list.size(), visible);
        }

        // 删除确认小窗
        if (this.pendingDelete != null) {
            this.renderDeleteDialog(guiGraphics, mouseX, mouseY);
        }
    }

    /** 清单一行（图标 + 边框/徽标 + 名称 + 坐标 + 类型 + X）。 */
    private void drawZoneRow(GuiGraphics guiGraphics, com.worldplayers.resident.WorkZone zone,
                             int y, float mouseX, float mouseY) {
        // 图标 + 名称是**同一个可点击框**（点它进详情页）；坐标栏、类型、X 各自独立
        int[] coord = coordBounds(zone);
        int coordLeft = coord[0];
        int coordW = coord[1];
        int nameLeft = CONTENT_X + 20;
        int nameMax = Math.max(4, coordLeft - nameLeft - 4);
        boolean iconHover = inside(mouseX, mouseY, CONTENT_X - 1, y - 1, 18, 18);
        boolean nameHovered = inside(mouseX, mouseY, nameLeft, y, nameMax, 16);
        boolean boxHovered = iconHover || nameHovered;
        if (boxHovered) {
            // 整段（图标 + 名称）一起高亮 → 视觉上就是一个框
            guiGraphics.fill(CONTENT_X - 1, y - 1, nameLeft + nameMax, y + 17, 0x33FFFFFF);
        }
        guiGraphics.renderItem(zoneIcon(zone.type()), CONTENT_X, y);
        drawIconBorder(guiGraphics, CONTENT_X, y, 16);
        // 农田：右下角叠加「泥土 / 数量最多的作物产物」（并列最高会轮换）
        if (com.worldplayers.resident.WorkZone.TYPE_FARM.equals(zone.type())) {
            java.util.List<net.minecraft.world.item.ItemStack> badges = ZoneStore.cropBadges(zone.key());
            if (!badges.isEmpty()) {
                net.minecraft.world.item.ItemStack badge =
                    badges.get((int) ((System.currentTimeMillis() / 2000L) % badges.size()));
                renderCropBadge(guiGraphics, badge, CONTENT_X, y);
            }
        }

        // 类型（靠右、X 左边；固定上限宽度 + 悬浮滚动，长名字不再挤压坐标栏）
        Component typeName = Component.translatable(zoneTypeKey(zone.type()));
        int typeW = Math.min(TYPE_LABEL_MAX_W, this.font.width(typeName));
        int typeX = this.theme.pageWidth() - CONTENT_X - ZONE_X_SIZE - 6 - typeW;
        boolean typeHovered = inside(mouseX, mouseY, typeX, y - 1, typeW, 18);

        // 坐标栏（类型左边；宽度 = 类型文字宽 ×2），字体 0.75 倍
        boolean coordHovered = inside(mouseX, mouseY, coordLeft, y - 1, coordW, 18);
        if (coordHovered) {
            guiGraphics.fill(coordLeft, y - 1, coordLeft + coordW, y + 17, 0x33FFFFFF);
        }
        drawMarquee(guiGraphics, coordText(zone), coordLeft, y + 5, coordW,
            coordHovered, this.theme.inkMutedColor(), 0.75F, true);

        // 名称：优先占位（坐标栏不许盖它），太长截断 + 悬浮滚动
        drawMarquee(guiGraphics, zone.name(), nameLeft, y + 4, nameMax,
            nameHovered, boxHovered ? 0xFFD24A : this.theme.inkColor(), 1.0F, true);

        // 类型名：固定上限宽度，放不下时悬浮滚动（主人 2026-11-23）
        drawMarquee(guiGraphics, typeName.getString(), typeX, y + 4, typeW,
            typeHovered, this.theme.inkMutedColor(), 1.0F, true);        // X 按钮（12x12，垂直居中）
        int xX = this.theme.pageWidth() - CONTENT_X - ZONE_X_SIZE;
        boolean xHovered = inside(mouseX, mouseY, xX, y + 2, ZONE_X_SIZE, ZONE_X_SIZE);
        drawSmallClose(guiGraphics, xX, y + 2, ZONE_X_SIZE, xHovered);
    }

    /** 编辑顺序时的一行：拖拽中的源行/悬浮行画成空槽（和侧边栏编辑一个观感）。 */
    private void renderZoneEditRow(GuiGraphics guiGraphics, int index, int y,
                                   java.util.List<com.worldplayers.resident.WorkZone> list) {
        boolean dragging = this.zoneDragIndex >= 0;
        boolean emptied = dragging
            && (index == this.zoneDragIndex || index == this.zoneHoverIndex);
        if (emptied) {
            drawEmptySlot(guiGraphics, CONTENT_X - 2, y - 1,
                this.theme.pageWidth() - CONTENT_X * 2 + 4, 18);
            if (index == this.zoneDragIndex && this.zoneHoverIndex >= 0
                    && this.zoneHoverIndex != this.zoneDragIndex) {
                // 源位置显示"被悬浮行"的半透明影像（同侧边栏）
                this.drawZoneRow(guiGraphics, list.get(this.zoneHoverIndex), y, -1.0F, -1.0F);
            }
            return;
        }
        this.drawZoneRow(guiGraphics, list.get(index), y, -1.0F, -1.0F);
    }

    /** 羽毛的位置随视图变：类型列表在规划按钮下面，子类在页面内顶部。 */
    private int featherY() {
        return this.currentZoneType == null ? FEATHER_Y : 10;
    }

    /** 编辑羽毛：白色羽毛 ↔ 绿色 ✓（类型顺序 / 工作区顺序共用）。 */
    private void renderFeather(GuiGraphics guiGraphics, float mouseX, float mouseY, boolean editActive) {
        int x = FEATHER_X;
        int y = this.featherY();
        boolean hovered = inside(mouseX, mouseY, x, y, FEATHER_SIZE, FEATHER_SIZE);
        if (editActive) {
            guiGraphics.fill(x, y, x + FEATHER_SIZE, y + FEATHER_SIZE, this.theme.tabActiveColor());
            drawTick(guiGraphics, x + 2, y + 2, 12, this.theme.editIconColor());
            return;
        }
        if (hovered) {
            guiGraphics.fill(x, y, x + FEATHER_SIZE, y + FEATHER_SIZE, this.theme.tabHoverColor());
        }
        guiGraphics.blit(ICON_PEN, x, y, 0, 0, FEATHER_SIZE, FEATHER_SIZE, 16, 16);
    }

    /** 清单滚动条（数量多于可见行时出现）。 */
    private void renderZoneScrollbar(GuiGraphics guiGraphics, int total, int visible) {
        int x = this.theme.pageWidth() - ZONE_SCROLL_GAP - ZONE_SCROLL_W;
        int top = ZONE_LIST_TOP;
        int height = visible * ZONE_ROW - 2;
        guiGraphics.fill(x, top, x + ZONE_SCROLL_W, top + height, 0x33000000);
        int thumb = Math.max(12, height * visible / total);
        int maxScroll = Math.max(1, total - visible);
        int thumbY = top + (height - thumb) * this.zoneScroll / maxScroll;
        guiGraphics.fill(x, thumbY, x + ZONE_SCROLL_W, thumbY + thumb, 0x99000000);
    }

    private int visibleZoneRows() {
        return Math.max(1, (this.theme.pageHeight() - ZONE_LIST_TOP - 34) / ZONE_ROW);
    }

    /** 显示顺序：先按当前子类过滤，再按保存的顺序排（没记录过的保持服务端顺序）。 */
    private java.util.List<com.worldplayers.resident.WorkZone> zonesForDisplay() {
        if (this.zoneEditMode && this.zoneEditList != null) {
            return this.zoneEditList;
        }
        java.util.List<com.worldplayers.resident.WorkZone> all =
            new java.util.ArrayList<>(ZoneStore.zones());
        if (this.currentZoneType != null) {
            all.removeIf(zone -> !this.currentZoneType.equals(zone.type()));
        }
        java.util.List<String> order = WPClientConfig.zoneOrderList();
        all.sort(java.util.Comparator.comparingInt(zone -> {
            int index = order.indexOf(zone.key());
            return index < 0 ? Integer.MAX_VALUE : index;
        }));
        return all;
    }

    /** 类型顺序：编辑中用本地副本，平时按保存的顺序排。 */
    private java.util.List<String> orderedTypes() {
        if (this.typeEditMode && this.typeEditList != null) {
            return this.typeEditList;
        }
        java.util.List<String> canonical = com.worldplayers.resident.WorkZone.types();
        java.util.List<String> all = new java.util.ArrayList<>(canonical);
        java.util.List<String> order = WPClientConfig.zoneTypeOrderList();
        // 配置里没列到的新类型（挖掘 / 填充等）用默认顺序的位次，而不是一律排到最后
        java.util.Map<String, Integer> rank = new java.util.HashMap<>();
        for (int i = 0; i < canonical.size(); i++) {
            rank.put(canonical.get(i), i + order.size());
        }
        all.sort(java.util.Comparator.comparingInt(type -> {
            int index = order.indexOf(type);
            return index >= 0 ? index : rank.getOrDefault(type, Integer.MAX_VALUE);
        }));
        return all;
    }

    /** 删除确认窗的点击：确认发删除包，取消关窗（弹窗期间其它点击都吞掉）。 */
    private boolean handleDeleteDialog(float pageX, float pageY) {
        if (this.pendingDelete == null) {
            return true;
        }
        int w = 200;
        int h = 74;
        int x = (this.theme.pageWidth() - w) / 2;
        int y = (this.theme.pageHeight() - h) / 2;
        int bw = 84;
        int by = y + h - 28;
        if (inside(pageX, pageY, x + 8, by, bw, 20)) {
            ZoneUi.click();
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.worldplayers.network.ZoneDeletePayload(
                    this.pendingDelete.name(), this.pendingDelete.origin()));
            this.pendingDelete = null;
            return true;
        }
        if (inside(pageX, pageY, x + w - 8 - bw, by, bw, 20)) {
            ZoneUi.click();
            this.pendingDelete = null;
            return true;
        }
        return true;
    }

    /** 删除确认窗：是否要删除工作区域 名称（类型）？确认 / 取消。 */
    private void renderDeleteDialog(GuiGraphics guiGraphics, float mouseX, float mouseY) {
        com.worldplayers.resident.WorkZone zone = this.pendingDelete;
        if (zone == null) {
            return;
        }
        guiGraphics.fill(0, 0, this.theme.pageWidth(), this.theme.pageHeight(), 0x88000000);
        int w = 200;
        int h = 74;
        int x = (this.theme.pageWidth() - w) / 2;
        int y = (this.theme.pageHeight() - h) / 2;
        guiGraphics.fill(x, y, x + w, y + h, 0xF0201A0E);
        guiGraphics.fill(x, y, x + w, y + 1, 0xFF808080);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, 0xFF808080);
        Component line = Component.translatable("gui.worldplayers.manual.zone_delete_confirm",
            zone.name(), Component.translatable(zoneTypeKey(zone.type())));
        guiGraphics.drawString(this.font, line, x + (w - this.font.width(line)) / 2, y + 14,
            0xFFFFFF, true);
        // 确认 / 取消 两个原版按钮
        int bw = 84;
        int by = y + h - 28;
        boolean confirmHover = inside(mouseX, mouseY, x + 8, by, bw, 20);
        boolean cancelHover = inside(mouseX, mouseY, x + w - 8 - bw, by, bw, 20);
        this.drawVanillaButton(guiGraphics, x + 8, by, bw, 20,
            Component.translatable("gui.worldplayers.manual.zone_delete_yes"), confirmHover);
        this.drawVanillaButton(guiGraphics, x + w - 8 - bw, by, bw, 20,
            Component.translatable("gui.worldplayers.manual.zone_delete_no"), cancelHover);
    }

    private void drawVanillaButton(GuiGraphics guiGraphics, int x, int y, int w, int h,
                                   Component label, boolean hovered) {
        guiGraphics.blitSprite(net.minecraft.resources.ResourceLocation.withDefaultNamespace(
            hovered ? "widget/button_highlighted" : "widget/button"), x, y, w, h);
        guiGraphics.drawString(this.font, label, x + (w - this.font.width(label)) / 2,
            y + (h - 8) / 2, 0xFFFFFF, true);
    }

    /** 小 ✕ 按钮（清单行尾）。 */
    private void drawSmallClose(GuiGraphics guiGraphics, int x, int y, int size, boolean hovered) {
        guiGraphics.fill(x, y, x + size, y + size,
            hovered ? this.theme.closeHoverColor() : this.theme.closeColor());
        int glyph = this.theme.closeGlyphColor();
        for (int i = 2; i < size - 2; i++) {
            guiGraphics.fill(x + i, y + i, x + i + 1, y + i + 1, glyph);
            guiGraphics.fill(x + size - 1 - i, y + i, x + size - i, y + i + 1, glyph);
        }
    }

    static net.minecraft.world.item.ItemStack zoneIcon(String type) {
        if (com.worldplayers.resident.WorkZone.TYPE_FARM.equals(type)) {
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_HOE);
        }
        if (com.worldplayers.resident.WorkZone.TYPE_TREE_FARM.equals(type)) {
            // 树场：橡树树苗
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_SAPLING);
        }
        if (com.worldplayers.resident.WorkZone.TYPE_RANCH.equals(type)) {
            // 牧场：皮革
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER);
        }
        if (com.worldplayers.resident.WorkZone.TYPE_FISHERY.equals(type)) {
            // 渔场：钓竿
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FISHING_ROD);
        }
        if (com.worldplayers.resident.WorkZone.TYPE_ENTERTAINMENT.equals(type)) {
            // 娱乐区域（14 号文档 1.2）：唱片 cat
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.MUSIC_DISC_CAT);
        }
        if (com.worldplayers.resident.WorkZone.TYPE_MINE.equals(type)) {
            // 挖掘工程（主人 2026-11-22 指定：铁铲图标）
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SHOVEL);
        }
        if (com.worldplayers.resident.WorkZone.TYPE_FILL.equals(type)) {
            // 填充工程（主人 2026-11-22 指定：泥土图标）
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT);
        }
        if (com.worldplayers.resident.WorkZone.TYPE_FORBIDDEN.equals(type)) {
            // 禁行区域：屏障方块
            return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BARRIER);
        }
        return new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_BRICKS);
    }

    static String zoneTypeKey(String type) {
        if (com.worldplayers.resident.WorkZone.TYPE_FARM.equals(type)) {
            return "gui.worldplayers.zone.type.farm";
        }
        if (com.worldplayers.resident.WorkZone.TYPE_TREE_FARM.equals(type)) {
            return "gui.worldplayers.zone.type.tree_farm";
        }
        if (com.worldplayers.resident.WorkZone.TYPE_RANCH.equals(type)) {
            return "gui.worldplayers.zone.type.ranch";
        }
        if (com.worldplayers.resident.WorkZone.TYPE_FISHERY.equals(type)) {
            return "gui.worldplayers.zone.type.fishery";
        }
        if (com.worldplayers.resident.WorkZone.TYPE_ENTERTAINMENT.equals(type)) {
            return "gui.worldplayers.zone.type.entertainment";
        }
        if (com.worldplayers.resident.WorkZone.TYPE_MINE.equals(type)) {
            return "gui.worldplayers.zone.type.mine";
        }
        if (com.worldplayers.resident.WorkZone.TYPE_FILL.equals(type)) {
            return "gui.worldplayers.zone.type.fill";
        }
        if (com.worldplayers.resident.WorkZone.TYPE_FORBIDDEN.equals(type)) {
            return "gui.worldplayers.zone.type.forbidden";
        }
        return "gui.worldplayers.zone.type.road";
    }

    /** 清单里的坐标文本：工作区的第一选择点。 */
    private static String coordText(com.worldplayers.resident.WorkZone zone) {
        net.minecraft.core.BlockPos first = zone.origin();
        return first.getX() + ", " + first.getY() + ", " + first.getZ();
    }

    /** 坐标栏 [左边, 宽度]：固定上限，避免长类型名 / 长坐标挤压名称栏（主人 2026-11-23）。 */
    private int[] coordBounds(com.worldplayers.resident.WorkZone zone) {
        net.minecraft.network.chat.Component typeName =
            net.minecraft.network.chat.Component.translatable(zoneTypeKey(zone.type()));
        int typeW = Math.min(TYPE_LABEL_MAX_W, this.font.width(typeName));
        int typeX = this.theme.pageWidth() - CONTENT_X - ZONE_X_SIZE - 6 - typeW;
        int width = Mth.clamp(Math.round(this.font.width(coordText(zone)) * 0.75F) + 4, 44, 64);
        return new int[]{typeX - 6 - width, width};
    }

    /**
     * 在页面坐标 (x, y) 的 width 宽区域里画一行文字：
     * 放得下就正常画；放不下就截成「…」；鼠标悬浮时缓慢滚动显示全文（主人 2026-11-20）。
     * {@code scale} 是字的缩放（坐标栏用 0.75）；{@code leftAlign} 为 true 时左对齐、否则居中。
     */
    private void drawMarquee(GuiGraphics gui, String text, int x, int y, int width,
                             boolean hovered, int color, float scale, boolean leftAlign) {
        if (width <= 0 || text.isEmpty()) {
            return;
        }
        int fullW = Math.round(this.font.width(text) * scale);
        if (fullW > width && hovered) {
            // 悬浮 + 放不下：横向缓慢滚动，末尾停一下再回到开头。
            // 不走 scissor（在缩放 pose 下容易裁空）：按像素偏移截子串，文字永远在框内。
            int overflow = Math.max(1, Math.round(fullW - width + 14.0F));
            int shift = (int) ((System.currentTimeMillis() / 50L) % (overflow + 40L));
            if (shift > overflow) {
                shift = overflow;
            }
            String scrolled = substrFrom(text, shift);
            String visible = this.font.plainSubstrByWidth(scrolled,
                Math.max(1, (int) (width / scale)));
            gui.pose().pushPose();
            gui.pose().translate(x, y, 0.0F);
            gui.pose().scale(scale, scale, 1.0F);
            gui.drawString(this.font, visible, 0, 0, color, false);
            gui.pose().popPose();
            return;
        }
        String shown = text;
        if (fullW > width) {
            shown = this.font.plainSubstrByWidth(text,
                Math.max(0, (int) (width / scale) - this.font.width("…"))) + "…";
        }
        int shownW = Math.round(this.font.width(shown) * scale);
        int drawX = leftAlign ? x : x + (width - shownW) / 2;
        gui.pose().pushPose();
        gui.pose().translate(drawX, y, 0.0F);
        gui.pose().scale(scale, scale, 1.0F);
        gui.drawString(this.font, shown, 0, 0, color, false);
        gui.pose().popPose();
    }

    /** 从文本里跳过前 shift 像素，返回剩下的部分（滚动显示用，避免 scissor 裁剪）。 */
    private String substrFrom(String text, int shift) {
        int acc = 0;
        for (int i = 0; i < text.length(); i++) {
            int w = this.font.width(String.valueOf(text.charAt(i)));
            if (acc + w > shift) {
                return text.substring(i);
            }
            acc += w;
        }
        return "";
    }

    /** 清单图标的一圈细线边框（主人 2026-11-20）。 */
    private void drawIconBorder(GuiGraphics gui, int x, int y, int size) {
        drawIconBorderStatic(gui, this.theme, x, y, size);
    }

    /** 静态版：详情页也要画同样的边框。 */
    static void drawIconBorderStatic(GuiGraphics gui, ManualTheme theme, int x, int y, int size) {
        int color = ManualTheme.withAlpha(theme.inkMutedColor(), 0x66);
        gui.fill(x, y, x + size, y + 1, color);
        gui.fill(x, y + size - 1, x + size, y + size, color);
        gui.fill(x, y, x + 1, y + size, color);
        gui.fill(x + size - 1, y, x + size, y + size, color);
    }

    /** 详情页用：类型 → 语言键。 */
    static String typeKey(String type) {
        return zoneTypeKey(type);
    }

    /** 农田区域的右下角小图标：没有作物 → 泥土；有 → 数量最多那种作物的种子。 */
    private void renderCropBadge(GuiGraphics gui, net.minecraft.world.item.ItemStack stack, int iconX, int iconY) {
        if (stack.isEmpty()) {
            return;
        }
        gui.pose().pushPose();
        gui.pose().translate(iconX + 8.0F, iconY + 8.0F, 200.0F);
        gui.pose().scale(0.5F, 0.5F, 1.0F);
        gui.renderItem(stack, 0, 0);
        gui.pose().popPose();
    }

    /** 点了清单里的坐标：播按钮音、发聊天文本、白框高亮 20 秒、关掉手册回世界（主人 2026-11-20）。 */
    private void locateZone(com.worldplayers.resident.WorkZone zone) {
        this.playButtonClick();
        if (this.minecraft != null && this.minecraft.player != null) {
            net.minecraft.core.BlockPos first = zone.origin();
            Component line = com.worldplayers.resident.WorkZone.TYPE_ROAD.equals(zone.type())
                ? Component.translatable("chat.worldplayers.zone.locate_road",
                    zone.name(), first.getX(), first.getY(), first.getZ())
                : Component.translatable("chat.worldplayers.zone.locate_work",
                    Component.translatable(zoneTypeKey(zone.type())),
                    zone.name(), first.getX(), first.getY(), first.getZ());
            this.minecraft.player.displayClientMessage(line, false);
        }
        RegionPlanner.highlight(zone);
        if (this.minecraft != null) {
            this.minecraft.setScreen(null);
        }
    }

    /** 拖拽中的栏位跟着鼠标走（画在最上层）。 */
    private void renderDragGhost(GuiGraphics guiGraphics, float mouseX, float mouseY) {
        // 成员顺序编辑：拖着一整行成员跟鼠标走（主人 2026-11-20：拖拽时要看得见被拖的居民）
        if (this.memberDragIndex >= 0 && this.currentTab == TAB_MEMBERS
                && this.memberEditList != null && this.memberDragIndex < this.memberEditList.size()) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0F, 0.0F, 400.0F);
            int x = Math.round(mouseX - this.memberRowWidth() / 2.0F);
            int y = Math.round(mouseY - MEMBER_BOX_H / 2.0F);
            this.drawMemberRow(guiGraphics, this.memberEditList.get(this.memberDragIndex),
                x, y, -1, -1, 0.85F);
            guiGraphics.pose().popPose();
            return;
        }
        // 类型顺序编辑：拖着一张半透明的类型行跟鼠标走
        if (this.typeDragIndex >= 0) {
            java.util.List<String> types = this.orderedTypes();
            if (this.typeDragIndex < types.size()) {
                guiGraphics.pose().pushPose();
                guiGraphics.pose().translate(0.0F, 0.0F, 400.0F);
                int width = this.theme.pageWidth() - PLAN_MARGIN * 2;
                int x = Math.round(mouseX - width / 2.0F);
                int y = Math.round(mouseY - 10.0F);
                this.drawTypeRow(guiGraphics, types.get(this.typeDragIndex), x, y, width, false, 0.85F);
                guiGraphics.pose().popPose();
            }
            return;
        }
        if (this.dragTab < 0 || this.currentZoneType != null) {
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
        if (tab == TAB_MEMBERS) {
            // 玩家头颅走物品模型渲染 → 自动适配材质包（主人要求）
            guiGraphics.renderItem(new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.PLAYER_HEAD), x + 4, y + 3);
        } else {
            guiGraphics.blit(TAB_ICONS[tab], x + 5, y + 4, 0, 0, 16, 16, 16, 16);
        }
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
        drawEmptySlot(guiGraphics, x, y, TAB_W, TAB_H);
    }

    private void drawEmptySlot(GuiGraphics guiGraphics, int x, int y, int w, int h) {
        guiGraphics.fill(x, y, x + w, y + h, this.theme.slotEmptyBackColor());
        int border = this.theme.slotEmptyBorderColor();
        guiGraphics.fill(x, y, x + w, y + 1, border);
        guiGraphics.fill(x, y + h - 1, x + w, y + h, border);
        guiGraphics.fill(x, y, x + 1, y + h, border);
        guiGraphics.fill(x + w - 1, y, x + w, y + h, border);
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

        // 工作区子类页：左上角 ← 返回类型列表（按钮音效）
        if (this.currentZoneType != null && isOverSubBack(mouseX, mouseY)) {
            this.playButtonClick();
            this.backToTypeList();
            return true;
        }

        // ---- 正在编辑工作区顺序：只认羽毛（保存）和清单拖拽，其它点击一律无效 ----
        if (this.zoneEditMode) {
            if (inside(pageX, pageY, FEATHER_X, this.featherY(), FEATHER_SIZE, FEATHER_SIZE)) {
                this.playButtonClick();
                this.saveZoneOrderForType();
                this.zoneEditMode = false;
                this.zoneEditList = null;
                this.zoneDragIndex = -1;
                this.zoneHoverIndex = -1;
                return true;
            }
            int index = this.zoneIndexAt(pageX, pageY);
            if (index >= 0) {
                this.zoneDragIndex = index;
                this.zoneHoverIndex = index;
            }
            return true;
        }

        // ---- 正在编辑类型顺序（同样只认羽毛和类型行拖拽） ----
        if (this.typeEditMode) {
            if (inside(pageX, pageY, FEATHER_X, this.featherY(), FEATHER_SIZE, FEATHER_SIZE)) {
                this.playButtonClick();
                this.saveTypeOrder();
                this.typeEditMode = false;
                this.typeEditList = null;
                this.typeDragIndex = -1;
                this.typeHoverIndex = -1;
                return true;
            }
            int index = this.typeIndexAt(pageX, pageY);
            if (index >= 0) {
                this.typeDragIndex = index;
                this.typeHoverIndex = index;
            }
            return true;
        }

        // 侧边栏（编辑按钮 + 栏位）：只存在于类型列表视图
        if (this.currentZoneType == null) {
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
        }

        // 勾选项（仅「手册渲染」页）
        if (this.currentTab == TAB_RENDER) {
            int visible = this.visibleRowsFor(CHECK_TOP, CHECK_ROW);
            if (this.listScrollbarAt(pageX, pageY, CHECK_TOP, CHECK_ROW, RENDER_KEYS.length, visible)) {
                this.checkScrollDragging = true;
                this.checkScroll = this.listScrollFromMouse(pageY, CHECK_TOP, CHECK_ROW,
                    RENDER_KEYS.length, visible);
                return true;
            }
            int option = this.checkboxAt(pageX, pageY);
            if (option >= 0) {
                this.toggleOption(option);
                return true;
            }
        }

        // 「成员列表」页：羽毛（编辑居民顺序）/ 招募新成员 / 成员框 / 工作框
        if (this.currentTab == TAB_MEMBERS) {
            // 羽毛 → 编辑 / 保存居民顺序
            if (inside(pageX, pageY, FEATHER_X, FEATHER_Y, FEATHER_SIZE, FEATHER_SIZE)) {
                this.playButtonClick();
                if (this.memberEditMode) {
                    this.saveMemberOrder();
                    this.memberEditMode = false;
                    this.memberEditList = null;
                    this.memberDragIndex = -1;
                    this.memberHoverIndex = -1;
                } else {
                    this.memberEditList = new java.util.ArrayList<>(this.orderedMembers());
                    this.memberEditMode = true;
                    this.memberDragIndex = -1;
                    this.memberHoverIndex = -1;
                }
                return true;
            }
            if (this.memberEditMode) {
                int index = this.memberIndexAt(pageX, pageY);
                if (index >= 0) {
                    this.memberDragIndex = index;
                    this.memberHoverIndex = index;
                }
                return true;
            }
            int buttonW = this.theme.pageWidth() - PLAN_MARGIN * 2;
            if (inside(pageX, pageY, PLAN_MARGIN, PLAN_BUTTON_TOP, buttonW, PLAN_BUTTON_H)) {
                this.playButtonClick();
                BedBindMode.enterRecruit();
                this.onClose();
                return true;
            }
            // 滚动条：按住拖动（主人 2026-11-23）
            if (this.memberScrollbarAt(pageX, pageY)) {
                this.memberScrollDragging = true;
                this.updateMemberScrollFromMouse(pageY);
                return true;
            }
            int index = this.memberIndexAt(pageX, pageY);
            if (index >= 0) {
                java.util.List<com.worldplayers.network.ResidentListPayload.Member> members = this.orderedMembers();
                com.worldplayers.network.ResidentListPayload.Member member = members.get(index);
                // 点击命中必须用「屏幕上的行号」= 绝对索引 - 滚动量；直接用 index 会让滚动后
                // 第一屏以外的行全部算到屏幕外（2026-11-22 修：只有前 13 名能点开）。
                int y = MEMBER_LIST_TOP + (index - this.memberScroll) * MEMBER_ROW;
                int[] box = this.memberLayout(y);
                // 成员框 → 只读的居民界面（不显示玩家背包、不可编辑、不冻结 AI）
                if (inside(pageX, pageY, box[0], y, box[1], MEMBER_BOX_H)) {
                    this.playButtonClick();
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.worldplayers.network.OpenResidentViewPayload(member.id()));
                    this.onClose();
                    return true;
                }
                // 工作框 → 工作区域详情页
                if (inside(pageX, pageY, box[2], y, box[3], MEMBER_BOX_H)) {
                    com.worldplayers.resident.WorkZone zone = this.jobZoneOf(member);
                    if (zone != null) {
                        this.playButtonClick();
                        this.minecraft.setScreen(new WorkZoneDetailScreen(zone,
                            WorkZoneDetailScreen.FROM_MEMBERS, null));
                    }
                    return true;
                }
                // 床框：只显示，不可点
                if (inside(pageX, pageY, box[4], y, box[5], MEMBER_BOX_H)) {
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        // 「区域选择」页
        if (this.currentTab == TAB_REGION) {
            if (this.pendingDelete != null) {
                return this.handleDeleteDialog(pageX, pageY);
            }
            if (this.currentZoneType == null) {
                // 规划区域按钮
                int buttonW = this.theme.pageWidth() - PLAN_MARGIN * 2;
                if (inside(pageX, pageY, PLAN_MARGIN, PLAN_BUTTON_TOP, buttonW, PLAN_BUTTON_H)) {
                    this.playButtonClick();
                    RegionPlanner.begin();
                    this.onClose();
                    return true;
                }
                // 羽毛 → 编辑类型顺序
                if (inside(pageX, pageY, FEATHER_X, this.featherY(), FEATHER_SIZE, FEATHER_SIZE)) {
                    this.playButtonClick();
                    this.typeEditList = new java.util.ArrayList<>(this.orderedTypes());
                    this.typeEditMode = true;
                    this.typeDragIndex = -1;
                    this.typeHoverIndex = -1;
                    return true;
                }
                // 类型列表滚动条（2026-11-23：类型会越来越多）
                java.util.List<String> typeList = this.orderedTypes();
                int typeVisible = this.visibleRowsFor(TYPE_LIST_TOP, TYPE_ROW);
                if (this.listScrollbarAt(pageX, pageY, TYPE_LIST_TOP, TYPE_ROW,
                        typeList.size(), typeVisible)) {
                    this.typeScrollDragging = true;
                    this.typeScroll = this.listScrollFromMouse(pageY, TYPE_LIST_TOP, TYPE_ROW,
                        typeList.size(), typeVisible);
                    return true;
                }
                // 类型行 → 进入子类
                int typeIndex = this.typeIndexAt(pageX, pageY);
                if (typeIndex >= 0) {
                    this.enterZoneType(this.orderedTypes().get(typeIndex));
                    return true;
                }
                return super.mouseClicked(mouseX, mouseY, button);
            }
            // 子类：羽毛 → 编辑工作区顺序
            if (inside(pageX, pageY, FEATHER_X, this.featherY(), FEATHER_SIZE, FEATHER_SIZE)) {
                this.playButtonClick();
                this.zoneEditList = new java.util.ArrayList<>(this.zonesForDisplay());
                this.zoneEditMode = true;
                this.zoneDragIndex = -1;
                this.zoneHoverIndex = -1;
                this.zoneScroll = 0;
                return true;
            }
            // 滚动条
            if (this.zoneScrollbarAt(pageX, pageY)) {
                this.zoneScrollDragging = true;
                this.updateZoneScrollFromMouse(pageY);
                return true;
            }
            // 清单行
            java.util.List<com.worldplayers.resident.WorkZone> list = this.zonesForDisplay();
            int visible = this.visibleZoneRows();
            this.zoneScroll = Mth.clamp(this.zoneScroll, 0, Math.max(0, list.size() - visible));
            int end = Math.min(list.size(), this.zoneScroll + visible);
            for (int index = this.zoneScroll; index < end; index++) {
                com.worldplayers.resident.WorkZone zone = list.get(index);
                int y = ZONE_LIST_TOP + (index - this.zoneScroll) * ZONE_ROW;
                // 图标 → 工作区域详情页（按钮音效）
                if (inside(pageX, pageY, CONTENT_X - 1, y - 1, 18, 18)) {
                    this.playButtonClick();
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new WorkZoneDetailScreen(zone,
                            WorkZoneDetailScreen.FROM_SUBTYPE, this.currentZoneType));
                    }
                    return true;
                }
                // X 按钮
                if (inside(pageX, pageY, this.theme.pageWidth() - CONTENT_X - ZONE_X_SIZE,
                        y + 2, ZONE_X_SIZE, ZONE_X_SIZE)) {
                    ZoneUi.click();
                    this.pendingDelete = zone;
                    return true;
                }
                // 坐标栏 → 高亮 20 秒 + 聊天文本 + 关手册
                int[] coord = coordBounds(zone);
                if (inside(pageX, pageY, coord[0], y - 1, coord[1], 18)) {
                    this.locateZone(zone);
                    return true;
                }
                // 图标 + 名称 = 同一个可点击框 → 工作区域详情页（改名已搬到详情页）
                int nameBoxW = coord[0] - 4 - (CONTENT_X - 1);
                if (inside(pageX, pageY, CONTENT_X - 1, y - 1, nameBoxW, 18)) {
                    this.playButtonClick();
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(new WorkZoneDetailScreen(zone,
                            WorkZoneDetailScreen.FROM_SUBTYPE, this.currentZoneType));
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 进入工作区类型子类页。 */
    private void enterZoneType(String type) {
        this.playButtonClick();
        this.currentZoneType = type;
        this.zoneScroll = 0;
        this.zoneEditMode = false;
        this.zoneEditList = null;
        this.zoneDragIndex = -1;
        this.zoneHoverIndex = -1;
    }

    /** 从子类页回到类型列表（同时丢掉没保存的编辑状态）。 */
    private void backToTypeList() {
        this.currentZoneType = null;
        this.zoneScroll = 0;
        this.zoneEditMode = false;
        this.zoneEditList = null;
        this.zoneDragIndex = -1;
        this.zoneHoverIndex = -1;
        this.typeEditMode = false;
        this.typeEditList = null;
        this.typeDragIndex = -1;
        this.typeHoverIndex = -1;
    }

    /** 鼠标所在的类型行（页面坐标）；不在任何行返回 -1。 */
    private int typeIndexAt(float pageX, float pageY) {
        int buttonW = this.theme.pageWidth() - PLAN_MARGIN * 2;
        java.util.List<String> types = this.orderedTypes();
        int visible = this.visibleRowsFor(TYPE_LIST_TOP, TYPE_ROW);
        int start = Mth.clamp(this.typeScroll, 0, Math.max(0, types.size() - visible));
        int end = Math.min(types.size(), start + visible);
        for (int index = start; index < end; index++) {
            int y = TYPE_LIST_TOP + (index - start) * TYPE_ROW;
            if (inside(pageX, pageY, PLAN_MARGIN, y, buttonW, 20)) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // 手册渲染页：勾选项滚轮滚动（2026-11-23：勾选项会越来越多）
        if (this.currentTab == TAB_RENDER && scrollY != 0.0D) {
            float pageX = this.toPageX(mouseX);
            float pageY = this.toPageY(mouseY);
            int visible = this.visibleRowsFor(CHECK_TOP, CHECK_ROW);
            if (inside(pageX, pageY, CONTENT_X - 4, CHECK_TOP - 2,
                    this.theme.pageWidth() - CONTENT_X * 2 + 8, this.theme.pageHeight() - CHECK_TOP)) {
                int max = Math.max(0, RENDER_KEYS.length - visible);
                this.checkScroll = Mth.clamp(this.checkScroll - (int) Math.signum(scrollY), 0, max);
                return true;
            }
        }
        // 区域选择页：类型列表滚轮滚动（2026-11-23：区域类型会越来越多）
        if (this.currentTab == TAB_REGION && this.currentZoneType == null && scrollY != 0.0D) {
            float pageX = this.toPageX(mouseX);
            float pageY = this.toPageY(mouseY);
            int visible = this.visibleRowsFor(TYPE_LIST_TOP, TYPE_ROW);
            if (inside(pageX, pageY, PLAN_MARGIN - 2, TYPE_LIST_TOP - 2,
                    this.theme.pageWidth() - PLAN_MARGIN * 2 + 4, visible * TYPE_ROW + 4)) {
                int max = Math.max(0, this.orderedTypes().size() - visible);
                this.typeScroll = Mth.clamp(this.typeScroll - (int) Math.signum(scrollY), 0, max);
                return true;
            }
        }
        // 成员列表：滚轮滚动（人多时能翻页）
        if (this.currentTab == TAB_MEMBERS && scrollY != 0.0D) {
            float pageX = this.toPageX(mouseX);
            float pageY = this.toPageY(mouseY);
            java.util.List<com.worldplayers.network.ResidentListPayload.Member> members =
                this.memberEditMode && this.memberEditList != null ? this.memberEditList : this.orderedMembers();
            int visible = this.visibleMemberRows();
            if (inside(pageX, pageY, CONTENT_X - 2, MEMBER_LIST_TOP - 2,
                    this.memberRowWidth() + 4, visible * MEMBER_ROW + 4)) {
                this.memberScroll = Mth.clamp(this.memberScroll - (int) Math.signum(scrollY),
                    0, Math.max(0, members.size() - visible));
                return true;
            }
        }
        // 区域选择页：清单滚轮滚动（编辑顺序时也要能滚，主人 2026-11-20）
        if (this.currentTab == TAB_REGION && this.currentZoneType != null && scrollY != 0.0D) {
            float pageX = this.toPageX(mouseX);
            float pageY = this.toPageY(mouseY);
            if (inside(pageX, pageY, CONTENT_X - 2, ZONE_LIST_TOP - 2,
                    this.theme.pageWidth() - CONTENT_X * 2 + 4, this.theme.pageHeight() - ZONE_LIST_TOP)) {
                java.util.List<com.worldplayers.resident.WorkZone> list = this.zonesForDisplay();
                int visible = this.visibleZoneRows();
                int max = Math.max(0, list.size() - visible);
                this.zoneScroll = Mth.clamp(this.zoneScroll - (int) Math.signum(scrollY), 0, max);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.checkScrollDragging) {
            this.checkScroll = this.listScrollFromMouse(this.toPageY(mouseY), CHECK_TOP, CHECK_ROW,
                RENDER_KEYS.length, this.visibleRowsFor(CHECK_TOP, CHECK_ROW));
            return true;
        }
        if (this.typeScrollDragging) {
            this.typeScroll = this.listScrollFromMouse(this.toPageY(mouseY), TYPE_LIST_TOP, TYPE_ROW,
                this.orderedTypes().size(), this.visibleRowsFor(TYPE_LIST_TOP, TYPE_ROW));
            return true;
        }
        if (this.memberScrollDragging) {
            this.updateMemberScrollFromMouse(this.toPageY(mouseY));
            return true;
        }
        if (this.memberDragIndex >= 0) {
            int index = this.memberIndexAt(this.toPageX(mouseX), this.toPageY(mouseY));
            if (index >= 0) {
                this.memberHoverIndex = index;
            }
            return true;
        }
        if (this.typeDragIndex >= 0) {
            int index = this.typeIndexAt(this.toPageX(mouseX), this.toPageY(mouseY));
            if (index >= 0) {
                this.typeHoverIndex = index;
            }
            return true;
        }
        if (this.zoneScrollDragging) {
            this.updateZoneScrollFromMouse(this.toPageY(mouseY));
            return true;
        }
        if (this.zoneDragIndex >= 0) {
            int index = this.zoneIndexAt(this.toPageX(mouseX), this.toPageY(mouseY));
            if (index >= 0) {
                this.zoneHoverIndex = index;
            }
            return true;
        }
        if (this.dragTab >= 0) {
            this.hoverSlot = this.slotAt(this.toPageX(mouseX), this.toPageY(mouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.checkScrollDragging) {
            this.checkScrollDragging = false;
            return true;
        }
        if (this.typeScrollDragging) {
            this.typeScrollDragging = false;
            return true;
        }
        if (this.memberScrollDragging) {
            this.memberScrollDragging = false;
            return true;
        }
        if (this.memberDragIndex >= 0) {
            int index = this.memberIndexAt(this.toPageX(mouseX), this.toPageY(mouseY));
            if (index >= 0 && index != this.memberDragIndex && this.memberEditList != null) {
                // 视觉上两者互换位置 → 实际也做「互换」，不再把整排挤过去
                java.util.Collections.swap(this.memberEditList, this.memberDragIndex, index);
                this.playButtonClick();
            }
            this.memberDragIndex = -1;
            this.memberHoverIndex = -1;
            return true;
        }
        if (this.typeDragIndex >= 0) {
            int index = this.typeIndexAt(this.toPageX(mouseX), this.toPageY(mouseY));
            if (index >= 0 && index != this.typeDragIndex && this.typeEditList != null) {
                java.util.Collections.swap(this.typeEditList, this.typeDragIndex, index);
                this.playButtonClick();
            }
            this.typeDragIndex = -1;
            this.typeHoverIndex = -1;
            return true;
        }
        if (this.zoneScrollDragging) {
            this.zoneScrollDragging = false;
            return true;
        }
        if (this.zoneDragIndex >= 0) {
            int index = this.zoneIndexAt(this.toPageX(mouseX), this.toPageY(mouseY));
            if (index >= 0 && index != this.zoneDragIndex && this.zoneEditList != null) {
                java.util.Collections.swap(this.zoneEditList, this.zoneDragIndex, index);
                this.playButtonClick();
            }
            this.zoneDragIndex = -1;
            this.zoneHoverIndex = -1;
            return true;
        }
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

    // ------------------------------------------------------------------
    // 通用列表滚动（勾选项 / 类型列表）
    // ------------------------------------------------------------------

    /** 这个列表能显示几行。 */
    private int visibleRowsFor(int top, int rowH) {
        return Math.max(1, (this.theme.pageHeight() - top - LIST_BOTTOM_PAD) / rowH);
    }

    /** 画竖向滚动条（内容多于可见行才画）。 */
    private void renderListScrollbar(GuiGraphics gui, int top, int rowH, int total, int visible, int scroll) {
        if (total <= visible) {
            return;
        }
        int x = this.theme.pageWidth() - LIST_SCROLL_GAP - LIST_SCROLL_W;
        int height = visible * rowH - 2;
        gui.fill(x, top, x + LIST_SCROLL_W, top + height, 0x33000000);
        int thumb = Math.max(12, height * visible / total);
        int maxScroll = Math.max(1, total - visible);
        int thumbY = top + (height - thumb) * Math.max(0, scroll) / maxScroll;
        gui.fill(x, thumbY, x + LIST_SCROLL_W, thumbY + thumb, 0x99000000);
    }

    /** 点到滚动条了吗。 */
    private boolean listScrollbarAt(float pageX, float pageY, int top, int rowH, int total, int visible) {
        if (total <= visible) {
            return false;
        }
        int x = this.theme.pageWidth() - LIST_SCROLL_GAP - LIST_SCROLL_W;
        return inside(pageX, pageY, x - 2, top, LIST_SCROLL_W + 4, visible * rowH - 2);
    }

    /** 拖滚动条 → 对应的滚动行数。 */
    private int listScrollFromMouse(float pageY, int top, int rowH, int total, int visible) {
        int max = Math.max(0, total - visible);
        if (max <= 0) {
            return 0;
        }
        int height = visible * rowH - 2;
        int thumb = Math.max(12, height * visible / total);
        float ratio = (pageY - top - thumb / 2.0F) / Math.max(1, height - thumb);
        return Mth.clamp(Math.round(ratio * max), 0, max);
    }

    // ------------------------------------------------------------------
    // 工作区清单：滚动 + 顺序编辑的小工具
    // ------------------------------------------------------------------

    /** 鼠标所在的清单行（页面坐标）；不在任何行返回 -1。 */
    private int zoneIndexAt(float pageX, float pageY) {
        java.util.List<com.worldplayers.resident.WorkZone> list = this.zonesForDisplay();
        if (list.isEmpty()) {
            return -1;
        }
        int visible = this.visibleZoneRows();
        int end = Math.min(list.size(), this.zoneScroll + visible);
        for (int index = this.zoneScroll; index < end; index++) {
            int y = ZONE_LIST_TOP + (index - this.zoneScroll) * ZONE_ROW;
            if (inside(pageX, pageY, CONTENT_X - 2, y - 1,
                    this.theme.pageWidth() - CONTENT_X * 2 + 4, 18)) {
                return index;
            }
        }
        return -1;
    }

    /** 点到滚动条了吗。 */
    private boolean zoneScrollbarAt(float pageX, float pageY) {
        java.util.List<com.worldplayers.resident.WorkZone> list = this.zonesForDisplay();
        int visible = this.visibleZoneRows();
        if (list.size() <= visible) {
            return false;
        }
        int x = this.theme.pageWidth() - ZONE_SCROLL_GAP - ZONE_SCROLL_W;
        int top = ZONE_LIST_TOP;
        int height = visible * ZONE_ROW - 2;
        return inside(pageX, pageY, x - 2, top, ZONE_SCROLL_W + 4, height);
    }

    private void updateZoneScrollFromMouse(float pageY) {
        java.util.List<com.worldplayers.resident.WorkZone> list = this.zonesForDisplay();
        int visible = this.visibleZoneRows();
        int max = Math.max(0, list.size() - visible);
        if (max <= 0) {
            this.zoneScroll = 0;
            return;
        }
        int top = ZONE_LIST_TOP;
        int height = visible * ZONE_ROW - 2;
        int thumb = Math.max(12, height * visible / list.size());
        float ratio = (pageY - top - thumb / 2.0F) / Math.max(1, height - thumb);
        this.zoneScroll = Mth.clamp(Math.round(ratio * max), 0, max);
    }

    /**
     * 子类里编辑的顺序：合并回全局 zoneOrder（其他类型的位置不动，只重排当前类型那一段）。
     */
    private void saveZoneOrderForType() {
        if (this.zoneEditList == null) {
            return;
        }
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (com.worldplayers.resident.WorkZone zone : this.zoneEditList) {
            keys.add(zone.key());
        }
        java.util.List<String> order = new java.util.ArrayList<>(WPClientConfig.zoneOrderList());
        int insert = order.size();
        for (int i = 0; i < order.size(); i++) {
            if (keys.contains(order.get(i))) {
                insert = i;
                break;
            }
        }
        order.removeAll(keys);
        insert = Math.min(insert, order.size());
        order.addAll(insert, keys);
        WPClientConfig.setZoneOrder(order);
        WPClientConfig.save();
    }

    /** 保存类型顺序（区域选择页的羽毛 ✓）。 */
    private void saveTypeOrder() {
        if (this.typeEditList == null) {
            return;
        }
        WPClientConfig.setZoneTypeOrder(this.typeEditList);
        WPClientConfig.save();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // E / ESC：工作区子类页先返回类型列表；类型列表页关闭手册（都不发声）
        if (keyCode == GLFW.GLFW_KEY_E || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.currentZoneType != null) {
                this.backToTypeList();
                return true;
            }
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
        int visible = this.visibleRowsFor(CHECK_TOP, CHECK_ROW);
        int start = Mth.clamp(this.checkScroll, 0, Math.max(0, RENDER_KEYS.length - visible));
        int end = Math.min(RENDER_KEYS.length, start + visible);
        for (int i = start; i < end; i++) {
            int y = CHECK_TOP + (i - start) * CHECK_ROW;
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
            case 1 -> WPClientConfig.setShowCityRoads(!WPClientConfig.showCityRoads());
            case 2 -> WPClientConfig.setShowWorkArea(!WPClientConfig.showWorkArea());
            case 3 -> WPClientConfig.setShowBuildingProject(!WPClientConfig.showBuildingProject());
            case 4 -> WPClientConfig.setShowBedBinding(!WPClientConfig.showBedBinding());
            case 5 -> WPClientConfig.setShowForbidden(!WPClientConfig.showForbidden());
            case 6 -> WPClientConfig.setShowEntertainment(!WPClientConfig.showEntertainment());
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
