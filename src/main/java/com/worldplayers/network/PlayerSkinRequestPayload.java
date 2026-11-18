package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.PlayerSkins;
import com.worldplayers.resident.ResidentEntity;
import java.io.IOException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端在「正版账号皮肤」界面里确认 ID 后发给服务端：请帮我把这个账号的皮肤抓下来。
 *
 * <p>服务端只在**这一次**去 Mojang 查询并下载 PNG，然后写进 customs 皮肤池、
 * 把居民皮肤名换成该文件，再把 PNG 广播给所有看到这个居民的客户端（它们各自存一份本地文件）。</p>
 */
public record PlayerSkinRequestPayload(int entityId, String username) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlayerSkinRequestPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("player_skin_request"));

    public static final StreamCodec<FriendlyByteBuf, PlayerSkinRequestPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PlayerSkinRequestPayload::entityId,
            ByteBufCodecs.STRING_UTF8, PlayerSkinRequestPayload::username,
            PlayerSkinRequestPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PlayerSkinRequestPayload payload, IPayloadContext context) {
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
            String username = payload.username() == null ? "" : payload.username().trim();
            if (!username.matches("[A-Za-z0-9_]{1,16}")) {
                return;
            }
            MinecraftServer server = player.getServer();
            PlayerSkins.fetch(username).whenComplete((png, error) -> {
                if (png == null || server == null) {
                    WorldPlayers.LOGGER.warn("World Players: 获取正版皮肤 {} 失败（{}）", username,
                        error == null ? "未知原因" : error.getMessage());
                    return;
                }
                server.execute(() -> {
                    if (!resident.isAlive()) {
                        return;
                    }
                    String name = PlayerSkins.fileName(username);
                    try {
                        PlayerSkins.save(name, png);
                    } catch (IOException e) {
                        WorldPlayers.LOGGER.warn("World Players: 保存正版皮肤 {} 失败", username, e);
                        return;
                    }
                    resident.setSkinName(name);
                    PacketDistributor.sendToPlayersTrackingEntityAndSelf(resident,
                        new PlayerSkinPayload(name, png));
                });
            });
        });
    }
}
