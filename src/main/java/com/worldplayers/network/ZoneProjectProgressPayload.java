package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 工程进度同步（规格 2.3 / 6）：服务端 → 客户端，节流 5 秒或有变化时发。
 *
 * <p>不塞进 {@code ZoneListPayload}，避免每处理一格就全量广播区域列表。</p>
 */
public record ZoneProjectProgressPayload(String zoneKey, long done, long total)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ZoneProjectProgressPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("zone_project_progress"));

    public static final StreamCodec<FriendlyByteBuf, ZoneProjectProgressPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ZoneProjectProgressPayload::zoneKey,
            ByteBufCodecs.VAR_LONG, ZoneProjectProgressPayload::done,
            ByteBufCodecs.VAR_LONG, ZoneProjectProgressPayload::total,
            ZoneProjectProgressPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ZoneProjectProgressPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.worldplayers.client.ZoneStore.setProgress(payload.zoneKey(), payload.done(), payload.total()));
    }
}
