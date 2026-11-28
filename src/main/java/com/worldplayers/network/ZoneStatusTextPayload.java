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
 * 自订区域的「工作状态」文字（2026-11-25，主人提出）。
 *
 * <p>详情页员工栏底下那个文字框：**输入后任何方式关闭页面都会保存**，所以这里和简介一样
 * 按「整份覆盖」写，没有增量协议。上限 {@link WorkZone#MAX_STATUS_TEXT}（40 字符），两头都夹一次。</p>
 */
public record ZoneStatusTextPayload(String zoneKey, String text) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ZoneStatusTextPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("zone_status_text"));

    public static final StreamCodec<FriendlyByteBuf, ZoneStatusTextPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ZoneStatusTextPayload::zoneKey,
            ByteBufCodecs.STRING_UTF8, ZoneStatusTextPayload::text,
            ZoneStatusTextPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ZoneStatusTextPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            WorkZoneData data = WorkZoneData.get(player.serverLevel());
            WorkZone zone = data.findByKey(payload.zoneKey());
            if (zone == null) {
                return;
            }
            String text = payload.text() == null ? "" : payload.text();
            if (text.length() > WorkZone.MAX_STATUS_TEXT) {
                text = text.substring(0, WorkZone.MAX_STATUS_TEXT);
            }
            data.add(zone.withStatusText(text));
            // 广播最新区域列表（单机就是自己）：客户端列表里也带着文字，重开页面不会丢
            for (ServerPlayer online : player.server.getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(online,
                    new ZoneListPayload(WorkZoneData.get(player.serverLevel()).zones()));
            }
        });
    }
}
