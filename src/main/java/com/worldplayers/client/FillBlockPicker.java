package com.worldplayers.client;

import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/**
 * 填充方块候选清单（规格 3.2 / 4.5）：输入框下的浮层列表。
 *
 * <ul>
 *   <li>最多 8 行 × 12 高，超过可滚（滚轮）；</li>
 *   <li>输入防抖 50ms，避免每个按键全量重排；</li>
 *   <li>空间不足时**向上弹**，并始终限制在页面矩形内；</li>
 *   <li>每行：左 10×10 方块图标，右边两行（8px 当前语言名 + 6px 灰色 tag 名）。</li>
 * </ul>
 */
public final class FillBlockPicker {
    private static final int MAX_ROWS = 8;
    /** 行高（12 px）。 */
    public static final int ROW_H = 12;
    /** 防抖（规格 4.5：50ms）。 */
    private static final long DEBOUNCE_MS = 50L;
    private static final int LIST_MIN_W = 160;

    private List<BlockSearch.Entry> results = List.of();
    private int selected = -1;
    private int scroll;
    private boolean open;
    private String pendingQuery;
    private long pendingAt;

    // 渲染时记录的布局（点击直接用）
    private int boxX;
    private int boxY;
    private int boxW;
    private int boxH;
    private int hoverRow = -1;

    public boolean isOpen() {
        return this.open;
    }

    public void open(String text) {
        this.open = true;
        this.filter(text);
    }

    public void close() {
        this.open = false;
        this.selected = -1;
        this.scroll = 0;
    }

    /** 输入框的文字变了：防抖 50ms 后重新过滤（第一次 / 列表空时立刻过滤）。 */
    public void onQueryChanged(String text) {
        if (!this.open) {
            this.open = true;
        }
        this.pendingQuery = text;
        this.pendingAt = System.currentTimeMillis();
        if (this.results.isEmpty()) {
            this.filter(text);
            this.pendingQuery = null;
        }
    }

    /** 每帧调一次：到时间了就把挂起的查询执行掉。 */
    public void tick() {
        if (this.pendingQuery != null && System.currentTimeMillis() - this.pendingAt >= DEBOUNCE_MS) {
            this.filter(this.pendingQuery);
            this.pendingQuery = null;
        }
    }

    /** 立即过滤（选中方块 / 回填时用）。 */
    public void filterNow(String text) {
        this.filter(text);
        this.pendingQuery = null;
    }

    private void filter(String text) {
        this.results = BlockSearch.search(text);
        this.selected = this.results.isEmpty() ? -1 : 0;
        this.scroll = 0;
    }

    public List<BlockSearch.Entry> results() {
        return this.results;
    }

    /** 键盘当前选中的那条（没有 → null）。 */
    public BlockSearch.Entry selectedEntry() {
        return this.selected >= 0 && this.selected < this.results.size()
            ? this.results.get(this.selected) : null;
    }

    /** ↑↓ 移动选中行。 */
    public void move(int delta) {
        if (this.results.isEmpty()) {
            return;
        }
        this.selected = Mth.clamp(this.selected + delta, 0, this.results.size() - 1);
        // 让选中行留在可见范围里
        if (this.selected < this.scroll) {
            this.scroll = this.selected;
        } else if (this.selected >= this.scroll + MAX_ROWS) {
            this.scroll = this.selected - MAX_ROWS + 1;
        }
    }

    /** 滚轮滚动（只滚列表）。 */
    public boolean scrollBy(double delta) {
        if (!this.open || delta == 0.0D) {
            return false;
        }
        int maxScroll = Math.max(0, this.results.size() - MAX_ROWS);
        if (maxScroll == 0) {
            return false;
        }
        this.scroll = Mth.clamp(this.scroll - (int) Math.signum(delta), 0, maxScroll);
        return true;
    }

    /** 鼠标是否在清单矩形里。 */
    public boolean isOver(double mouseX, double mouseY) {
        return this.open && mouseX >= this.boxX && mouseX <= this.boxX + this.boxW
            && mouseY >= this.boxY && mouseY <= this.boxY + this.boxH;
    }

    /**
     * 画清单。位置：输入框正下方；放不下就翻到输入框上方；始终夹在页面里。
     *
     * @param inputY 输入框顶部的屏幕 y
     */
    public void render(GuiGraphics gui, Font font, int inputX, int inputY, int inputW, int inputH,
                       int pageLeft, int pageTop, int pageWidth, int pageHeight,
                       double mouseX, double mouseY) {
        if (!this.open) {
            return;
        }
        int rows = Math.min(this.results.size(), MAX_ROWS);
        if (rows <= 0) {
            return;
        }
        int w = Math.max(LIST_MIN_W, inputW);
        int h = rows * ROW_H + 2;
        // 默认下方；到底了就翻到输入框上方
        int y = inputY + inputH + 2;
        if (y + h > pageTop + pageHeight - 6) {
            y = inputY - h - 2;
        }
        y = Mth.clamp(y, pageTop + 6, pageTop + pageHeight - 6 - h);
        int x = Mth.clamp(inputX, pageLeft + 6, pageLeft + pageWidth - 6 - w);

        this.boxX = x;
        this.boxY = y;
        this.boxW = w;
        this.boxH = h;

        gui.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF000000);
        gui.fill(x, y, x + w, y + h, 0xFF2B2B2B);
        this.hoverRow = -1;
        for (int row = 0; row < rows; row++) {
            int index = this.scroll + row;
            if (index >= this.results.size()) {
                break;
            }
            BlockSearch.Entry entry = this.results.get(index);
            int rowY = y + 1 + row * ROW_H;
            boolean over = mouseX >= x && mouseX <= x + w && mouseY >= rowY && mouseY <= rowY + ROW_H;
            if (over) {
                this.hoverRow = index;
            }
            if (index == this.selected) {
                gui.fill(x, rowY, x + w, rowY + ROW_H, 0x3300FF88);
            } else if (over) {
                gui.fill(x, rowY, x + w, rowY + ROW_H, 0x33FFFFFF);
            }
            // 方块图标（10×10）
            gui.pose().pushPose();
            gui.pose().translate(x + 2.0F, rowY + 1.0F, 200.0F);
            gui.pose().scale(0.625F, 0.625F, 1.0F);
            gui.renderItem(new net.minecraft.world.item.ItemStack(entry.block()), 0, 0);
            gui.pose().popPose();
            // 第一行：当前语言名称（8px）
            gui.pose().pushPose();
            gui.pose().translate(x + 15.0F, rowY + 1.0F, 0.0F);
            gui.pose().scale(0.72F, 0.72F, 1.0F);
            gui.drawString(font, entry.name(), 0, 0, 0xFFFFFFFF, false);
            gui.pose().popPose();
            // 第二行：tag（6px 灰色）
            gui.pose().pushPose();
            gui.pose().translate(x + 15.0F, rowY + 6.5F, 0.0F);
            gui.pose().scale(0.55F, 0.55F, 1.0F);
            gui.drawString(font,
                net.minecraft.network.chat.Component.translatable(entry.tag().isEmpty()
                    ? "gui.worldplayers.zone.project_no_tag"
                    : "gui.worldplayers.zone.project_tag_line", entry.tag()),
                0, 0, 0xFFB8B8B8, false);
            gui.pose().popPose();
        }
        // 滚动条
        if (this.results.size() > MAX_ROWS) {
            int total = this.results.size();
            int thumb = Math.max(6, (h - 2) * MAX_ROWS / total);
            int maxScroll = total - MAX_ROWS;
            int thumbY = y + 1 + (h - 2 - thumb) * this.scroll / maxScroll;
            gui.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, 0x33FFFFFF);
            gui.fill(x + w - 2, thumbY, x + w - 1, thumbY + thumb, 0x99FFFFFF);
        }
    }

    /**
     * 点击清单。
     *
     * @return 命中的行下标；-1 = 没命中（但可能点到别处）；-2 = 点在清单外（调用方关清单）
     */
    public int click(double mouseX, double mouseY) {
        if (!this.open) {
            return -1;
        }
        if (!this.isOver(mouseX, mouseY)) {
            return -2;
        }
        int row = (int) ((mouseY - (this.boxY + 1)) / ROW_H);
        if (row < 0 || row >= MAX_ROWS) {
            return -1;
        }
        int index = this.scroll + row;
        if (index < 0 || index >= this.results.size()) {
            return -1;
        }
        this.selected = index;
        return index;
    }

    /** 鼠标悬停行（渲染时更新）。 */
    public int hoverRow() {
        return this.hoverRow;
    }
}
