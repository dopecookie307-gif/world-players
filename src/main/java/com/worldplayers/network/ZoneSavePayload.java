package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 客户端画好区域、填好名字和类型 → 存进服务端的工作区域数据。 */
public record ZoneSavePayload(String name, String kind, BlockPos min, BlockPos max,
                              BlockPos origin, String resident)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ZoneSavePayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("zone_save"));

    public static final StreamCodec<FriendlyByteBuf, ZoneSavePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ZoneSavePayload::name,
            ByteBufCodecs.STRING_UTF8, ZoneSavePayload::kind,
            BlockPos.STREAM_CODEC, ZoneSavePayload::min,
            BlockPos.STREAM_CODEC, ZoneSavePayload::max,
            BlockPos.STREAM_CODEC, ZoneSavePayload::origin,
            ByteBufCodecs.STRING_UTF8, ZoneSavePayload::resident,
            ZoneSavePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ZoneSavePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (payload.name() == null || payload.name().isBlank()) {
                return;
            }
            WorkZone zone = new WorkZone(payload.name().strip(), payload.kind(),
                payload.min(), payload.max(), payload.origin(),
                payload.resident() == null || payload.resident().isBlank()
                    ? java.util.List.of() : java.util.List.of(payload.resident()));
            WorkZoneData.get(player.serverLevel()).add(zone);
            // 存完把最新列表发给所有玩家（单机就是自己）
            for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(online,
                    new ZoneListPayload(WorkZoneData.get(player.serverLevel()).zones()));
            }
        });
    }
}
