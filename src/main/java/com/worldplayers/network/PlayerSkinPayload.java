package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.PlayerSkins;
import java.io.IOException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端把抓到的正版皮肤 PNG 发给客户端：每个客户端存一份到自己的 customs 皮肤池，
 * 这样退出重进世界、甚至下次连服务器都不用再联网。
 *
 * <p>服务端（专用服）不会执行客户端分支，所以客户端类不会在服务端被解析。</p>
 */
public record PlayerSkinPayload(String skinName, byte[] png) implements CustomPacketPayload {

    private static final int MAX_PNG = 512 * 1024;

    public static final CustomPacketPayload.Type<PlayerSkinPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("player_skin"));

    public static final StreamCodec<FriendlyByteBuf, PlayerSkinPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PlayerSkinPayload::skinName,
            ByteBufCodecs.byteArray(MAX_PNG), PlayerSkinPayload::png,
            PlayerSkinPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PlayerSkinPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!FMLEnvironment.dist.isClient()) {
                return;
            }
            try {
                PlayerSkins.save(payload.skinName(), payload.png());
            } catch (IOException e) {
                WorldPlayers.LOGGER.warn("World Players: 客户端保存正版皮肤失败", e);
            }
            com.worldplayers.client.ClientSkins.reload(payload.skinName());
        });
    }
}
