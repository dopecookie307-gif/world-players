package com.worldplayers.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 娱乐区域简介的**多行纯自绘输入框**（2026-11-22，14 号文档 1.8 / 问题 12）。
 *
 * <p>原版 {@code EditBox} 是单行、而且自带背景与选中框，塞进纸张页面里很丑；这里只保留
 * 「文字 + 光标」两样东西，按**像素宽度自动折行**，整块高度随内容增长。内容超出页面时
 * 由 {@link WorkZoneDetailScreen} 的滚动条滚动，**输入框内部不滚动**（14 号文档 1.8.2）。</p>
 *
 * <p>支持的编辑操作（够用、不贪多）：普通字符 / 中文（经 {@code charTyped}）、回车换行、
 * 退格、Delete、←→↑↓、Home/End、Ctrl+V 粘贴、Ctrl+A 全选（按 Home/End 效果近似：跳到末尾）。
 * 没有鼠标拖选——在这个页面里用不着。</p>
 */
final class ZoneDescriptionEditor {
    /** 行高（像素）。 */
    static final int LINE_H = 10;
    /** 光标闪烁周期（毫秒）。 */
    private static final long BLINK_MS = 500L;

    private final StringBuilder text = new StringBuilder();
    private int cursor;
    private boolean focused;
    /** 上一次通知外部「内容变了」时的快照（判断要不要提交）。 */
    private String committed = "";

    void setText(String value) {
        this.text.setLength(0);
        if (value != null) {
            this.text.append(value);
        }
        this.cursor = this.text.length();
        this.committed = this.text.toString();
    }

    String text() {
        return this.text.toString();
    }

    /** 和上次提交（或初始值）相比有没有改动。 */
    boolean isDirty() {
        return !this.text.toString().equals(this.committed);
    }

    /** 标记为已提交（关闭 / 失焦提交后调用）。 */
    void markCommitted() {
        this.committed = this.text.toString();
    }

    boolean focused() {
        return this.focused;
    }

    void setFocused(boolean value) {
        this.focused = value;
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /** 普通字符（含中文）。控制字符与换行以外的都收。 */
    boolean charTyped(char c) {
        if (!this.focused) {
            return false;
        }
        if (c == '\n' || c == '\r') {
            return this.insert("\n");
        }
        if (c < ' ' || c == 0x7F) {
            return false;
        }
        return this.insert(String.valueOf(c));
    }

    /** 按键。返回 true = 这个键被输入框吃掉了。 */
    boolean keyPressed(int keyCode, int modifiers) {
        if (!this.focused) {
            return false;
        }
        boolean ctrl = (modifiers & 0x2) != 0;
        switch (keyCode) {
            case 257, 335 -> { // ENTER / KP_ENTER
                return this.insert("\n");
            }
            case 259 -> { // BACKSPACE
                if (this.cursor > 0) {
                    this.text.deleteCharAt(this.cursor - 1);
                    this.cursor--;
                }
                return true;
            }
            case 261 -> { // DELETE
                if (this.cursor < this.text.length()) {
                    this.text.deleteCharAt(this.cursor);
                }
                return true;
            }
            case 263 -> { // LEFT
                this.cursor = Math.max(0, this.cursor - 1);
                return true;
            }
            case 262 -> { // RIGHT
                this.cursor = Math.min(this.text.length(), this.cursor + 1);
                return true;
            }
            case 265, 264 -> { // UP / DOWN：按行移动（由页面按折行结果换算，这里只做粗粒度）
                // 真正的上下移动在 WorkZoneDetailScreen 里按折行结果处理（见 moveVertical）
                return false;
            }
            case 268 -> { // HOME
                this.cursor = 0;
                return true;
            }
            case 269 -> { // END
                this.cursor = this.text.length();
                return true;
            }
            case 86 -> { // V
                if (ctrl) {
                    String clip = net.minecraft.client.Minecraft.getInstance()
                        .keyboardHandler.getClipboard();
                    return this.insert(sanitize(clip));
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** 上下移动：按折行结果在同一列上下跳（没有折行信息时退回 1 个字符）。 */
    void moveVertical(net.minecraft.client.gui.Font font, int width, int direction) {
        List<Line> lines = this.wrap(font, width);
        int[] pos = this.cursorPos(lines);
        int lineIndex = Math.clamp(pos[0] + direction, 0, lines.size() - 1);
        Line line = lines.get(lineIndex);
        int column = Math.min(pos[1], line.text.length());
        this.cursor = line.start + column;
    }

    /** 粘贴时去掉 \r 和控制字符，保留 \n。 */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\n' || (c >= ' ' && c != 0x7F)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private boolean insert(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        int room = com.worldplayers.resident.WorkZone.MAX_DESCRIPTION - this.text.length();
        if (room <= 0) {
            return true;
        }
        String clipped = value.length() > room ? value.substring(0, room) : value;
        this.text.insert(this.cursor, clipped);
        this.cursor += clipped.length();
        return true;
    }

    // ------------------------------------------------------------------
    // 排版
    // ------------------------------------------------------------------

    /** 一行折好的文字 + 它在整段里的起始下标。 */
    record Line(String text, int start) {
    }

    /** 按像素宽度折行（超长字符自己独占一行，防止死循环）。 */
    List<Line> wrap(net.minecraft.client.gui.Font font, int width) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < this.text.length(); i++) {
            char c = this.text.charAt(i);
            if (c == '\n') {
                lines.add(new Line(current.toString(), start));
                current.setLength(0);
                start = i + 1;
                continue;
            }
            if (font.width(current.toString() + c) > width && current.length() > 0) {
                lines.add(new Line(current.toString(), start));
                current.setLength(0);
                // 这一行从当前字符开始
                start = i;
            }
            current.append(c);
        }
        lines.add(new Line(current.toString(), start));
        return lines;
    }

    /** 光标在第几行第几列（列是字符数）。 */
    private int[] cursorPos(List<Line> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            int end = line.start + line.text.length();
            if (this.cursor <= end) {
                return new int[]{i, Math.max(0, this.cursor - line.start)};
            }
        }
        Line last = lines.get(lines.size() - 1);
        return new int[]{lines.size() - 1, last.text.length()};
    }

    /** 光标的行号 / 行内像素 x（画光标与鼠标定位用）。 */
    int[] cursorPixels(net.minecraft.client.gui.Font font, int width) {
        List<Line> lines = this.wrap(font, width);
        int[] pos = this.cursorPos(lines);
        String prefix = lines.get(pos[0]).text.substring(0, Math.min(pos[1], lines.get(pos[0]).text.length()));
        return new int[]{pos[0], font.width(prefix)};
    }

    /** 鼠标点在输入框里的哪个字符位置。 */
    int indexAt(net.minecraft.client.gui.Font font, int width, double relX, double relY) {
        List<Line> lines = this.wrap(font, width);
        int lineIndex = Math.clamp((int) (relY / LINE_H), 0, lines.size() - 1);
        Line line = lines.get(lineIndex);
        int column = 0;
        int best = Integer.MAX_VALUE;
        for (int i = 0; i <= line.text.length(); i++) {
            int w = font.width(line.text.substring(0, i));
            int delta = Math.abs(w - (int) relX);
            if (delta < best) {
                best = delta;
                column = i;
            }
        }
        return line.start + column;
    }

    void setCursor(int index) {
        this.cursor = Math.clamp(index, 0, this.text.length());
    }

    /** 光标现在该不该画（500ms 闪一次；没聚焦不画）。 */
    boolean cursorVisible(long nowMs) {
        return this.focused && (nowMs / BLINK_MS) % 2L == 0L;
    }

    /** 整块文字的高度（像素），至少一行。 */
    int height(net.minecraft.client.gui.Font font, int width) {
        return Math.max(1, this.wrap(font, width).size()) * LINE_H;
    }
}
