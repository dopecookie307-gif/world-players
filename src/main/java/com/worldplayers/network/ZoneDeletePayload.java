package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.WorkZoneData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 删除一个工作区域（清单里的 X 按钮，确认后发）；用「名字 + 第一选择点」定位，同名互不影响。 */
public record ZoneDeletePayload(String name, BlockPos origin) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ZoneDeletePayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("zone_delete"));

    public static final StreamCodec<FriendlyByteBuf, ZoneDeletePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ZoneDeletePayload::name,
            BlockPos.STREAM_CODEC, ZoneDeletePayload::origin,
            ZoneDeletePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ZoneDeletePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            WorkZoneData data = WorkZoneData.get(player.serverLevel());
            com.worldplayers.resident.WorkZone zone = data.find(payload.name(), payload.origin());
            if (data.remove(payload.name(), payload.origin())) {
                // 工程队列缓存跟着作废（规格 2.4：区域删除要失效）
                if (zone != null) {
                    com.worldplayers.resident.project.ProjectQueue.invalidate(zone.key());
                    // 牧场循环（v0.3.0）也跟着作废
                    com.worldplayers.resident.ranch.RanchCycle.invalidate(zone.key());
                    // 渔场钓点缓存（v0.3.0）同样作废
                    com.worldplayers.resident.fishery.FisheryCache.invalidate(zone.key());
                }
                for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(online,
                        new ZoneListPayload(data.zones()));
                }
            }
        });
    }
}
