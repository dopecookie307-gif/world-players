package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.client.ResidentStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：当前所有居民（成员列表页 + 绑定床铺模式判断「这张床归谁」都用它）。
 *
 * <p>床坐标是可选的：流浪居民没有床。</p>
 */
public record ResidentListPayload(List<Member> members) implements CustomPacketPayload {
    /**
     * 一个成员：UUID + 名字 + 当前皮肤名 + 床位（没有 = 流浪）+ 当前坐标（成员框悬浮提示用）
     * + **是否无业**（2026-11-21：员工选择列表里显示「无业」）。
     */
    public record Member(UUID id, String name, String skin, boolean hasBed, BlockPos bed,
                         int posX, int posY, int posZ, boolean unemployed) {
    }

    public static final CustomPacketPayload.Type<ResidentListPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("resident_list"));

    public static final StreamCodec<FriendlyByteBuf, ResidentListPayload> STREAM_CODEC =
        StreamCodec.of(ResidentListPayload::encode, ResidentListPayload::decode);

    private static void encode(FriendlyByteBuf buffer, ResidentListPayload payload) {
        List<Member> list = payload.members() == null ? List.of() : payload.members();
        buffer.writeVarInt(list.size());
        for (Member member : list) {
            buffer.writeUUID(member.id());
            buffer.writeUtf(member.name() == null ? "" : member.name(), 128);
            buffer.writeUtf(member.skin() == null ? "" : member.skin(), 128);
            buffer.writeBoolean(member.hasBed());
            if (member.hasBed()) {
                buffer.writeBlockPos(member.bed());
            }
            buffer.writeInt(member.posX());
            buffer.writeInt(member.posY());
            buffer.writeInt(member.posZ());
            buffer.writeBoolean(member.unemployed());
        }
    }

    private static ResidentListPayload decode(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<Member> list = new ArrayList<>(Math.min(size, 512));
        for (int i = 0; i < size; i++) {
            UUID id = buffer.readUUID();
            String name = buffer.readUtf(128);
            String skin = buffer.readUtf(128);
            boolean hasBed = buffer.readBoolean();
            BlockPos bed = hasBed ? buffer.readBlockPos() : null;
            int posX = buffer.readInt();
            int posY = buffer.readInt();
            int posZ = buffer.readInt();
            boolean unemployed = buffer.readBoolean();
            list.add(new Member(id, name, skin, hasBed, bed, posX, posY, posZ, unemployed));
        }
        return new ResidentListPayload(list);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ResidentListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ResidentStore.set(payload.members()));
    }
}
