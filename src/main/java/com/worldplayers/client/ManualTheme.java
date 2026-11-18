package com.worldplayers.client;

import net.minecraft.resources.ResourceLocation;

/**
 * 居民手册页面主题：纸张素材 + 尺寸 + 配色。
 *
 * <p>手册页面（{@link ManualScreen}）所有外观参数都从这里取，
 * 想换风格（泛黄笔记本 / 羊皮纸 / 工程图纸 / 终端界面…）只要在 {@link ManualThemes}
 * 里再加一个实例、把 {@link ManualThemes#current()} 指过去，一行渲染代码都不用改。</p>
 *
 * @param pageTexture         纸张纹理
 * @param textureWidth        纹理实际宽（blit 的 uv 上限，必须大于 0）
 * @param textureHeight       纹理实际高
 * @param pageWidth           页面上屏后的宽（GUI 像素）
 * @param pageHeight          页面上屏后的高
 * @param inkColor            主文字色（ARGB）
 * @param inkMutedColor       次要文字 / 装饰色（ARGB）
 * @param pageTint            纸张整体着色（0xRRGGBB，0xFFFFFF 为原色）——留给"换配色"用
 * @param closeColor          关闭按钮底色（ARGB）
 * @param closeHoverColor     关闭按钮悬停底色（ARGB）
 * @param closeGlyphColor     ✕ 的颜色（ARGB）
 * @param tabBackColor        侧边栏栏位底（ARGB）
 * @param tabHoverColor       侧边栏栏位悬停底（ARGB）
 * @param tabActiveColor      当前页栏位底（ARGB）
 * @param tabBorderColor      栏位描边（ARGB）
 * @param tabTextColor        栏位名称文字（ARGB）—— 侧边栏在纸张外面，文字要浅色
 * @param slotEmptyBackColor  拖动时占位空框的底（ARGB）
 * @param slotEmptyBorderColor 拖动时占位空框的描边（ARGB）
 * @param checkBackColor      勾选框灰色底（ARGB）
 * @param checkBorderColor    勾选框白色边框（ARGB）
 * @param checkLineColor      勾选框黑色外线（ARGB）
 * @param checkMarkColor      勾的颜色（ARGB）
 * @param editIconColor       编辑模式 ✓ 的颜色（ARGB）
 */
public record ManualTheme(
    ResourceLocation pageTexture,
    int textureWidth,
    int textureHeight,
    int pageWidth,
    int pageHeight,
    int inkColor,
    int inkMutedColor,
    int pageTint,
    int closeColor,
    int closeHoverColor,
    int closeGlyphColor,
    int tabBackColor,
    int tabHoverColor,
    int tabActiveColor,
    int tabBorderColor,
    int tabTextColor,
    int slotEmptyBackColor,
    int slotEmptyBorderColor,
    int checkBackColor,
    int checkBorderColor,
    int checkLineColor,
    int checkMarkColor,
    int editIconColor
) {
    /** 纸张着色拆成 0~1 的 RGB，方便 {@code RenderSystem.setShaderColor}。 */
    public float tintRed() {
        return ((this.pageTint >> 16) & 0xFF) / 255.0F;
    }

    public float tintGreen() {
        return ((this.pageTint >> 8) & 0xFF) / 255.0F;
    }

    public float tintBlue() {
        return (this.pageTint & 0xFF) / 255.0F;
    }

    public boolean hasTint() {
        return this.pageTint != 0xFFFFFF;
    }

    /** 换透明度（拖动时的半透明影像用）。 */
    public static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
