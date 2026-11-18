package com.worldplayers.client;

import com.worldplayers.WorldPlayers;

/** 手册主题表。以后要加风格就在这加常量，并把 {@link #current()} 换成配置读取。 */
public final class ManualThemes {
    /**
     * 默认风格：泛黄的旧笔记本内页（240×330）。
     *
     * <p>纹理取自原版书与笔纸面（`textures/gui/book.png`）→ 拉伸 + 泛黄 + 极轻纤维噪点，
     * 四面各一条同样的 1px 细线，贴着页面最外缘。生成脚本：{@code 工具\make_manual_gui_texture.py}。</p>
     */
    public static final ManualTheme NOTEBOOK = new ManualTheme(
        WorldPlayers.id("textures/gui/manual_notebook.png"),
        240, 330,
        240, 330,
        0xFF3F3018,   // 墨色
        0xFF7A6746,   // 淡墨
        0xFFFFFF,     // 纸张原色
        0xFF5A4426,   // ✕ 普通
        0xFFC7952F,   // ✕ 悬停
        0xFFFFF4D8,   // ✕ 字形色
        0xE0201912,   // 栏位底（纸张外面，要不透明深色）
        0xF0342A1C,   // 栏位悬停
        0xFF4B3A24,   // 当前页栏位
        0xC08A6B4A,   // 栏位描边
        0xFFF0E6CE,   // 栏位文字（浅色）
        0x66201912,   // 拖动占位底
        0xCC8A6B4A,   // 拖动占位描边
        0xFF9A9A9A,   // 勾选框灰底
        0xFFFFFFFF,   // 勾选框白边
        0xFF1E1E1E,   // 勾选框黑外线
        0xFF141414,   // 勾
        0xFF2FA83C    // 编辑模式 ✓（绿）
    );

    /** 当前生效的主题。以后接配置/物品 NBT 时只改这一个方法。 */
    public static ManualTheme current() {
        return NOTEBOOK;
    }

    private ManualThemes() {
    }
}
