package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.item.ResidentWandItem;
import com.worldplayers.item.WandSelection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 调试棒左键（v0.3.0 T3）：客户端在「准心 64 格内真的是居民」时才发这个包。
 *
 * <p>为什么不在服务端监听 {@code AttackEntityEvent}：原版左键只会对
 * `entity_interaction_range`（3 格）内的实体发包，远处根本到不了服务端。
 * 所以由客户端自己做 64 格射线，命中居民就拦下这次攻击并发包；服务器收到后
 * 用自己的射线再判一次（不信任客户端坐标）。</p>
 */
public record WandClickPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<WandClickPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("wand_click"));

    public static final StreamCodec<FriendlyByteBuf, WandClickPayload> STREAM_CODEC =
        StreamCodec.unit(new WandClickPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WandClickPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.getMainHandItem().getItem() instanceof ResidentWandItem)) {
                return;
            }
            if (ResidentWandItem.residentAt(player) instanceof com.worldplayers.resident.ResidentEntity resident) {
                WandSelection.toggle(player, resident);
            }
        });
    }
}
