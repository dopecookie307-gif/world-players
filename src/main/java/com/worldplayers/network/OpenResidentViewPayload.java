package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.menu.ResidentMenu;
import com.worldplayers.resident.ResidentEntity;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 从手册「成员列表」点居民：打开**只读**的居民界面（主人 2026-11-20）。
 *
 * <p>和直接右键居民的区别：</p>
 * <ul>
 *   <li>不看玩家自己的背包；</li>
 *   <li>居民背包只能看、不能拿/放；</li>
 *   <li>**不冻结居民 AI**，居民受伤也不会把这个界面关掉。</li>
 * </ul>
 */
public record OpenResidentViewPayload(UUID resident) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenResidentViewPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("resident_view"));

    public static final StreamCodec<FriendlyByteBuf, OpenResidentViewPayload> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, OpenResidentViewPayload::resident,
            OpenResidentViewPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(OpenResidentViewPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.serverLevel().getEntity(payload.resident()) instanceof ResidentEntity resident)) {
                return;
            }
            Component title = resident.getDisplayName();
            player.openMenu(
                new SimpleMenuProvider(
                    (containerId, playerInventory, openingPlayer) ->
                        new ResidentMenu(containerId, playerInventory, resident, true),
                    title),
                buffer -> {
                    buffer.writeVarInt(resident.getId());
                    buffer.writeBoolean(true);
                });
        });
    }
}
