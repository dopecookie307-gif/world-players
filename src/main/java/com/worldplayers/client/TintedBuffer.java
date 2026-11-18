package com.worldplayers.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * 顶点色染色（jeb_ 彩虹 / 受击闪红共用）。
 *
 * <p>为什么不用 {@code RenderSystem.setShaderColor}：那是全局 uniform，世界里实体的顶点
 * 是攒在 {@code MultiBufferSource} 里、到渲染末尾才统一提交的，中间随便谁调一次
 * {@code setShaderColor(1,1,1,1)} 就会把颜色冲掉 —— 所以世界看不见、GUI 里却能立刻提交
 * 于是正常显示（之前受伤闪红失败也是同一个原因）。</p>
 *
 * <p>这里把颜色直接写进每个顶点的颜色里，纹理怎么改、批次怎么排都不影响；
 * 3D 皮肤层走同一个 buffer，所以也会一起变色。</p>
 */
final class TintedBuffer implements MultiBufferSource {
    private final MultiBufferSource delegate;
    private final int red;
    private final int green;
    private final int blue;

    TintedBuffer(MultiBufferSource delegate, float r, float g, float b) {
        this.delegate = delegate;
        this.red = Math.round(Math.clamp(r, 0.0F, 1.0F) * 255.0F);
        this.green = Math.round(Math.clamp(g, 0.0F, 1.0F) * 255.0F);
        this.blue = Math.round(Math.clamp(b, 0.0F, 1.0F) * 255.0F);
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        return new Tinted(this.delegate.getBuffer(type), this.red, this.green, this.blue);
    }

    /** 把每个顶点的颜色替换成 tint（alpha 保留）。 */
    private record Tinted(VertexConsumer delegate, int red, int green, int blue) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int alpha) {
            this.delegate.setColor(this.red, this.green, this.blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            this.delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            this.delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            this.delegate.setNormal(x, y, z);
            return this;
        }
    }
}
