package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.WorkZone;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端 → 客户端：把当前所有工作区域同步过来（登录时一次、每次保存后再发一次）。 */
public record ZoneListPayload(List<WorkZone> zones) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ZoneListPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("zone_list"));

    public static final StreamCodec<FriendlyByteBuf, ZoneListPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.collection(ArrayList::new, WorkZone.STREAM_CODEC), ZoneListPayload::zones,
            ZoneListPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ZoneListPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
            com.worldplayers.client.ZoneStore.set(payload.zones()));
    }
}
