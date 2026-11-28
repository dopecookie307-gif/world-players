package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.path.PathNode;
import com.worldplayers.resident.path.PathSegment;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端 → 客户端：整张路径网（登录时一次，每次改动后再发一次）。 */
public record PathSyncPayload(List<PathNode> nodes, List<PathSegment> segments)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PathSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("path_sync"));

    public static final StreamCodec<FriendlyByteBuf, PathSyncPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, PathNode.STREAM_CODEC), PathSyncPayload::nodes,
            ByteBufCodecs.collection(ArrayList::new, PathSegment.STREAM_CODEC),
            PathSyncPayload::segments,
            PathSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PathSyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.worldplayers.client.PathStore.set(payload.nodes(), payload.segments()));
    }
}
