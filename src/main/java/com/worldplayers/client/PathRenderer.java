package com.worldplayers.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.worldplayers.WorldPlayers;
import com.worldplayers.item.PathBrushItem;
import com.worldplayers.resident.path.PathNode;
import com.worldplayers.resident.path.PathSegment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * 路网渲染：**只有手持路径笔的人**能看见，半径 32 格内的锚点 / 路径点 / 路段。
 *
 * <p>颜色（草案 12.2）：锚点紫、路径点黄绿；点被实体方块占据或被堵 → 红。
 * 点画成「比方块小一圈的正方体」（0.3 格），贴在方块面外侧。</p>
 */
@EventBusSubscriber(modid = WorldPlayers.MOD_ID, value = Dist.CLIENT)
public final class PathRenderer {
    /** 渲染半径（主人 2026-11-24 定：32 格，不按「最近一条路径」）。 */
    public static final int RADIUS = 32;
    public static final int ANCHOR_COLOR = 0xB45CF5;
    public static final int NODE_COLOR = 0xC8E62A;
    public static final int BAD_COLOR = 0xFF3B3B;
    /** 连线模式里被选中的点：白色（2026-11-24）。 */
    public static final int SELECTED_COLOR = 0xFFFFFF;
    /** 点方块边长（比 1 格小一圈）。 */
    private static final float SIZE = 0.30F;

    /** 半透明填充：**关闭深度测试**（主人 2026-11-24：点被方块埋住时也要看得见）。 */
    private static final RenderType NODE_FILL = RenderType.create(
        "wp_path_nodes",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        1536,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setCullState(RenderStateShard.NO_CULL)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .createCompositeState(false));

    /** 段连线：同样**关闭深度测试**，墙后的路网也能看到走向。 */
    private static final RenderType PATH_LINES = RenderType.create(
        "wp_path_segments",
        DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.LINES,
        1536,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
            .setLineState(new RenderStateShard.LineStateShard(java.util.OptionalDouble.empty()))
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setCullState(RenderStateShard.NO_CULL)
            .createCompositeState(false));

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (!PathBrushItem.holdsBrush(minecraft.player)) {
            return;
        }
        BlockPos center = minecraft.player.blockPosition();
        List<PathNode> nodes = PathStore.near(center, RADIUS);
        List<PathSegment> segments = segmentsInside(nodes);
        if (nodes.isEmpty()) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Matrix4f matrix = pose.last().pose();

        VertexConsumer fill = buffers.getBuffer(NODE_FILL);
        PathNode selected = PathBrushClient.selectedNode();
        for (PathNode node : nodes) {
            int color = selected != null && selected.id() == node.id()
                ? SELECTED_COLOR : colorOf(minecraft, node);
            float x = node.pos().getX() + 0.5F;
            float y = node.pos().getY() + 0.5F;
            float z = node.pos().getZ() + 0.5F;
            cube(fill, matrix, x, y, z, color);
        }
        buffers.endBatch(NODE_FILL);

        VertexConsumer lines = buffers.getBuffer(PATH_LINES);
        for (PathSegment segment : segments) {
            PathNode from = PathStore.byId(segment.from());
            PathNode to = PathStore.byId(segment.to());
            if (from == null || to == null) {
                continue;
            }
            int color = colorOf(minecraft, from);
            line(lines, matrix,
                from.pos().getX() + 0.5F, from.pos().getY() + 0.5F, from.pos().getZ() + 0.5F,
                to.pos().getX() + 0.5F, to.pos().getY() + 0.5F, to.pos().getZ() + 0.5F, color);
        }
        buffers.endBatch(PATH_LINES);
        pose.popPose();
    }

    /** 两端都在 32 格显示范围里的段（否则会画出很长的半截线）。 */
    private static List<PathSegment> segmentsInside(List<PathNode> nodes) {
        Set<Integer> visible = new HashSet<>();
        for (PathNode node : nodes) {
            visible.add(node.id());
        }
        List<PathSegment> found = new ArrayList<>();
        for (PathSegment segment : PathStore.segments()) {
            if (visible.contains(segment.from()) && visible.contains(segment.to())) {
                found.add(segment);
            }
        }
        return found;
    }

    /**
     * 点的颜色：锚点紫、路径点黄绿、被实体方块占据（或脚下没有可站的支撑）红。
     *
     * <p>红色和以后「验证」的失败色一致（草案 12.2）。</p>
     */
    private static int colorOf(Minecraft minecraft, PathNode node) {
        BlockState at = minecraft.level.getBlockState(node.pos());
        BlockState below = minecraft.level.getBlockState(node.pos().below());
        if (at.blocksMotion() || !below.blocksMotion()) {
            return BAD_COLOR;
        }
        return node.anchor() ? ANCHOR_COLOR : NODE_COLOR;
    }

    private static void cube(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z,
                             int color) {
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float a = 0.85F;
        float half = SIZE / 2.0F;
        float x0 = x - half;
        float y0 = y - half;
        float z0 = z - half;
        float x1 = x + half;
        float y1 = y + half;
        float z1 = z + half;
        quad(consumer, matrix, r, g, b, a, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1); // 上
        quad(consumer, matrix, r, g, b, a, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0); // 下
        quad(consumer, matrix, r, g, b, a, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0); // 北
        quad(consumer, matrix, r, g, b, a, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1); // 南
        quad(consumer, matrix, r, g, b, a, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1); // 西
        quad(consumer, matrix, r, g, b, a, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0); // 东
    }

    private static void quad(VertexConsumer consumer, Matrix4f matrix,
                             float r, float g, float b, float a,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3) {
        consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        consumer.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        consumer.addVertex(matrix, x3, y3, z3).setColor(r, g, b, a);
    }

    private static void line(VertexConsumer consumer, Matrix4f matrix,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             int color) {
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        consumer.addVertex(matrix, x0, y0, z0).setColor(r, g, b, 0.9F)
            .setNormal(0.0F, 1.0F, 0.0F);
        consumer.addVertex(matrix, x1, y1, z1).setColor(r, g, b, 0.9F)
            .setNormal(0.0F, 1.0F, 0.0F);
    }

    private PathRenderer() {
    }
}
