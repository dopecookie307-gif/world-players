package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.client.BedBindMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端通知客户端「绑定床铺模式结束了」（招募成功 / 被服务端踢出模式时）。
 *
 * <p>客户端本来就能自己退出；这个包用来兜住「服务端这边已经结束了」的情况。</p>
 */
public record BedBindExitPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BedBindExitPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("bed_bind_exit"));

    public static final StreamCodec<FriendlyByteBuf, BedBindExitPayload> STREAM_CODEC =
        StreamCodec.unit(new BedBindExitPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BedBindExitPayload payload, IPayloadContext context) {
        context.enqueueWork(BedBindMode::exitFromServer);
    }
}
