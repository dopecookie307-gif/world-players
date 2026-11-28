package com.worldplayers.resident.path;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * 路网里的一个点：要么是锚点（车站，居民的大目标），要么是路径点（轨道拐点）。
 *
 * <p>坐标是**点本身所在的方块格**（贴在方块面外侧一格，像插火把），不是被点击的那个方块。</p>
 *
 * @param id            路网内唯一 id（段用 id 引用）
 * @param dimension     维度（一个世界可以有多张路网，按维度分开渲染/执行）
 * @param anchor        true = 锚点，false = 路径点
 * @param action        到点后的动作（锚点也存一份，以后做「下车」用）
 * @param width         通行宽度（1~8，默认 1）
 * @param bidirectional 双向（默认 true；楼梯以后可设单向）
 */
public record PathNode(int id, BlockPos pos, ResourceLocation dimension, boolean anchor,
                       PathAction action, int width, boolean bidirectional) {

    public static final int WIDTH_MIN = 1;
    public static final int WIDTH_MAX = 8;

    public static final StreamCodec<FriendlyByteBuf, PathNode> STREAM_CODEC = StreamCodec.of(
        (buf, node) -> {
            buf.writeVarInt(node.id());
            buf.writeBlockPos(node.pos());
            buf.writeResourceLocation(node.dimension());
            buf.writeBoolean(node.anchor());
            buf.writeUtf(node.action().name());
            buf.writeVarInt(node.width());
            buf.writeBoolean(node.bidirectional());
        },
        buf -> new PathNode(
            buf.readVarInt(),
            buf.readBlockPos(),
            buf.readResourceLocation(),
            buf.readBoolean(),
            PathAction.byName(buf.readUtf()),
            buf.readVarInt(),
            buf.readBoolean()));

    /** 换成正身（路径点 → 锚点），其它字段不变。 */
    public PathNode asAnchor() {
        return new PathNode(this.id, this.pos, this.dimension, true,
            this.action, this.width, this.bidirectional);
    }

    /** 改成另一种动作。 */
    public PathNode withAction(PathAction newAction) {
        return new PathNode(this.id, this.pos, this.dimension, this.anchor,
            newAction, this.width, this.bidirectional);
    }

    /** 改宽度（夹到合法范围）。 */
    public PathNode withWidth(int newWidth) {
        int width = Math.max(WIDTH_MIN, Math.min(WIDTH_MAX, newWidth));
        return new PathNode(this.id, this.pos, this.dimension, this.anchor,
            this.action, width, this.bidirectional);
    }

    /** 这个点在不在（某个维度的）范围内 —— 渲染按半径只取附近的点。 */
    public boolean inDimension(@Nullable ResourceLocation dim) {
        return dim != null && dim.equals(this.dimension);
    }
}
