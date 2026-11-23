package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 娱乐区域简介（2026-11-22，14 号文档 1.8 / 第 1 包）。
 *
 * <p>详情页里那段多行文字**任何方式关闭 / 中断输入都会立刻提交**，所以这里按「整份覆盖」写，
 * 没有增量协议。上限 {@link WorkZone#MAX_DESCRIPTION}（500 字符），两头都夹一次。</p>
 */
public record ZoneDescriptionPayload(String zoneKey, String description) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ZoneDescriptionPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("zone_description"));

    public static final StreamCodec<FriendlyByteBuf, ZoneDescriptionPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ZoneDescriptionPayload::zoneKey,
            ByteBufCodecs.STRING_UTF8, ZoneDescriptionPayload::description,
            ZoneDescriptionPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ZoneDescriptionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            WorkZoneData data = WorkZoneData.get(player.serverLevel());
            WorkZone zone = data.findByKey(payload.zoneKey());
            if (zone == null) {
                return;
            }
            String text = payload.description() == null ? "" : payload.description();
            if (text.length() > WorkZone.MAX_DESCRIPTION) {
                text = text.substring(0, WorkZone.MAX_DESCRIPTION);
            }
            data.add(zone.withDescription(text));
            // 广播最新区域列表（单机就是自己）：客户端列表里也带着简介，重开页面不会丢
            for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(online,
                    new ZoneListPayload(WorkZoneData.get(player.serverLevel()).zones()));
            }
        });
    }
}
