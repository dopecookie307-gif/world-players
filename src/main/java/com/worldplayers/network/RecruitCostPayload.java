package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.client.ResidentStore;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端 → 客户端：招募要消耗多少个食物（手册「招募新成员」按钮的悬浮提示用）。 */
public record RecruitCostPayload(int cost) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RecruitCostPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("recruit_cost"));

    public static final StreamCodec<FriendlyByteBuf, RecruitCostPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, RecruitCostPayload::cost,
            RecruitCostPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RecruitCostPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ResidentStore.setRecruitCost(payload.cost()));
    }
}
