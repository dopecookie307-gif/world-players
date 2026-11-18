package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端在居民背包 GUI 里改了名字 / 模型 / 皮肤 → 发给服务端。
 *
 * <p>一个包带三项，简单省事；GUI 关闭或选择变化时发一次。</p>
 */
public record ResidentSettingsPayload(int entityId, String name, int model, String skin)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ResidentSettingsPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("resident_settings"));

    public static final StreamCodec<FriendlyByteBuf, ResidentSettingsPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ResidentSettingsPayload::entityId,
            ByteBufCodecs.STRING_UTF8, ResidentSettingsPayload::name,
            ByteBufCodecs.VAR_INT, ResidentSettingsPayload::model,
            ByteBufCodecs.STRING_UTF8, ResidentSettingsPayload::skin,
            ResidentSettingsPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ResidentSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Entity entity = player.level().getEntity(payload.entityId());
            if (!(entity instanceof ResidentEntity resident)) {
                return;
            }
            if (player.distanceToSqr(resident) > 64.0D) {
                return;
            }
            resident.setDisplayName(payload.name());
            resident.setModelType(payload.model());
            if (payload.skin() != null && !payload.skin().isBlank()) {
                resident.setSkinName(payload.skin());
            }
        });
    }
}
