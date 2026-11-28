package com.worldplayers.resident.path;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** 两个点之间的一段轨（有向：from → to；双向由两端的 {@link PathNode#bidirectional()} 决定）。 */
public record PathSegment(int from, int to) {

    public static final StreamCodec<FriendlyByteBuf, PathSegment> STREAM_CODEC = StreamCodec.of(
        (buf, segment) -> {
            buf.writeVarInt(segment.from());
            buf.writeVarInt(segment.to());
        },
        buf -> new PathSegment(buf.readVarInt(), buf.readVarInt()));

    /** 这段是不是连着这个点（渲染/删除时用）。 */
    public boolean touches(int nodeId) {
        return this.from == nodeId || this.to == nodeId;
    }
}
