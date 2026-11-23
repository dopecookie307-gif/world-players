package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.BedBinder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 玩家进入 / 退出「绑定床铺模式」。
 *
 * <ul>
 *   <li>{@link #ENTER_RECRUIT}：手册里的「招募新成员」——右键空床会消耗食物召唤**新**居民</li>
 *   <li>{@link #ENTER_ASSIGN}：流浪居民背包里的「分配床铺」——右键空床把床给**这个**居民</li>
 *   <li>{@link #EXIT}：退出</li>
 * </ul>
 */
public record BedBindPayload(int action, UUID target) implements CustomPacketPayload {
    public static final int ENTER_RECRUIT = 0;
    public static final int ENTER_ASSIGN = 1;
    public static final int EXIT = 2;
    /** 请求重新同步一次成员列表（打开手册时用）。 */
    public static final int REQUEST_LIST = 3;

    public static final CustomPacketPayload.Type<BedBindPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("bed_bind"));

    public static final StreamCodec<FriendlyByteBuf, BedBindPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BedBindPayload::action,
            UUIDUtil.STREAM_CODEC, BedBindPayload::target,
            BedBindPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BedBindPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                BedBinder.handleAction(player, payload.action(),
                    payload.target() == null ? null : payload.target());
            }
        });
    }
}
