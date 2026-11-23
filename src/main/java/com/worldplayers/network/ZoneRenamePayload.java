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

/** 改工作区域名字（清单里点名字、回车就存）；用「旧名字 + 第一选择点」定位，同名互不影响。 */
public record ZoneRenamePayload(String from, BlockPos origin, String to) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ZoneRenamePayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("zone_rename"));

    public static final StreamCodec<FriendlyByteBuf, ZoneRenamePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ZoneRenamePayload::from,
            BlockPos.STREAM_CODEC, ZoneRenamePayload::origin,
            ByteBufCodecs.STRING_UTF8, ZoneRenamePayload::to,
            ZoneRenamePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ZoneRenamePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            String to = payload.to() == null ? "" : payload.to().strip();
            if (to.isEmpty()) {
                return;
            }
            WorkZoneData data = WorkZoneData.get(player.serverLevel());
            WorkZone zone = data.find(payload.from(), payload.origin());
            if (zone == null) {
                return;
            }
            data.remove(zone.name(), zone.origin());
            // 改名 = 换唯一键：区域级缓存一起作废（牧场循环 / 渔场钓点，v0.3.0）
            com.worldplayers.resident.ranch.RanchCycle.invalidate(zone.key());
            com.worldplayers.resident.fishery.FisheryCache.invalidate(zone.key());
            data.add(zone.withName(to));
            for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(online,
                    new ZoneListPayload(data.zones()));
            }
        });
    }
}
