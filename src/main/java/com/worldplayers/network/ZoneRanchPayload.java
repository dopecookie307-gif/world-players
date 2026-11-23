package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import com.worldplayers.resident.ranch.RanchType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 牧场设定（v0.3.0，主人 2026-11-23 定）：动物类型 / 动物最大数量 / 剪毛勾选。
 *
 * <p>服务端是唯一权威：类型按 {@link RanchType} 归一化、数量夹在 2~64、
 * 剪毛只对绵羊保留（其它动物一律 false）。改完广播区域清单，客户端详情页立刻刷新。</p>
 */
public record ZoneRanchPayload(String zoneKey, String animalType, int animalMax, boolean shear)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ZoneRanchPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("zone_ranch"));

    public static final StreamCodec<FriendlyByteBuf, ZoneRanchPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ZoneRanchPayload::zoneKey,
            ByteBufCodecs.STRING_UTF8, ZoneRanchPayload::animalType,
            ByteBufCodecs.VAR_INT, ZoneRanchPayload::animalMax,
            ByteBufCodecs.BOOL, ZoneRanchPayload::shear,
            ZoneRanchPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ZoneRanchPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            WorkZoneData data = WorkZoneData.get(level);
            WorkZone zone = data.findByKey(payload.zoneKey());
            if (zone == null || !zone.isRanch()) {
                return;
            }
            RanchType type = RanchType.byId(payload.animalType());
            int max = Mth.clamp(payload.animalMax(), WorkZone.ANIMAL_MIN, WorkZone.ANIMAL_MAX);
            // 剪毛只对绵羊有意义
            boolean shear = payload.shear() && type.canShear();
            data.add(zone.withRanch(type.id(), max, shear));
            for (ServerPlayer online : level.getServer().getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(online, new ZoneListPayload(WorkZoneData.get(level).zones()));
            }
        });
    }
}
