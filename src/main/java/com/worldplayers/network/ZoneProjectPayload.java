package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import com.worldplayers.resident.project.ProjectQueue;
import com.worldplayers.resident.project.ProjectRules;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 工程设定（规格 2.3）：设置填充方块 / 开始（勾）/ 暂停（叉）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>改方块 → 工程自动**暂停**，要重新按勾；</li>
 *   <li>没有方块 / 没有在线员工 → 不开始，聊天提示；</li>
 *   <li>改完立刻广播区域列表与进度，客户端不用猜。</li>
 * </ul>
 */
public record ZoneProjectPayload(String zoneKey, String blockId, boolean running)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ZoneProjectPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("zone_project"));

    public static final StreamCodec<FriendlyByteBuf, ZoneProjectPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ZoneProjectPayload::zoneKey,
            ByteBufCodecs.STRING_UTF8, ZoneProjectPayload::blockId,
            ByteBufCodecs.BOOL, ZoneProjectPayload::running,
            ZoneProjectPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ZoneProjectPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            WorkZoneData data = WorkZoneData.get(level);
            WorkZone zone = data.findByKey(payload.zoneKey());
            if (zone == null || !zone.isProject()) {
                return;
            }
            // 方块 id 归一化：能解析 → 完整 id；解析不了 / 挖掘 → 空
            String raw = payload.blockId() == null ? "" : payload.blockId().strip();
            Block resolved = zone.isFill() ? ProjectRules.blockById(raw) : null;
            String blockId = resolved == null ? "" : BuiltInRegistries.BLOCK.getKey(resolved).toString();

            boolean changed = !blockId.equals(zone.projectBlock());
            boolean wasRunning = zone.projectRunning();
            boolean running = payload.running() && !changed;

            if (running) {
                if (zone.isFill() && blockId.isEmpty()) {
                    player.displayClientMessage(
                        Component.translatable("chat.worldplayers.project.no_block"), false);
                    return;
                }
                if (!hasOnlineEmployee(level, zone)) {
                    player.displayClientMessage(
                        Component.translatable("chat.worldplayers.project.no_employee"), false);
                    // 让客户端把按钮状态回滚（万一本地以为开始了）
                    broadcast(level, data);
                    return;
                }
            }

            WorkZone updated = zone.withProject(blockId, running);
            // 开工 / 改方块：队列缓存作废，重扫进度（规格 5.3、2.4）
            ProjectQueue.invalidate(updated.key());
            if (running) {
                ProjectQueue queue = ProjectQueue.get(updated);
                queue.rebuild(level, updated);
                updated = updated.withProjectDone((int) Math.min(Integer.MAX_VALUE, queue.done()));
                data.add(updated);
                // 先广播列表（客户端会清掉旧进度缓存），再推进度包
                broadcast(level, data);
                PacketDistributor.sendToAllPlayers(
                    new ZoneProjectProgressPayload(updated.key(), queue.done(), queue.total()));
                player.displayClientMessage(Component.translatable(
                    zone.isMine() ? "chat.worldplayers.project.mine_started"
                        : "chat.worldplayers.project.fill_started",
                    zone.isMine() ? Component.empty() : ProjectRules.nameOf(resolved)), false);
            } else {
                data.add(updated);
                if (wasRunning) {
                    player.displayClientMessage(Component.translatable(
                        zone.isMine() ? "chat.worldplayers.project.mine_cancelled"
                            : "chat.worldplayers.project.fill_cancelled",
                        zone.isMine() ? Component.empty() : ProjectRules.nameOf(resolved)), false);
                }
                broadcast(level, data);
            }
        });
    }

    private static void broadcast(ServerLevel level, WorkZoneData data) {
        for (ServerPlayer online : level.getServer().getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(online, new ZoneListPayload(data.zones()));
        }
    }

    /** 「可用员工」= 员工列表里至少一个 UUID 能解析到在线实体（规格 5.3）。 */
    private static boolean hasOnlineEmployee(ServerLevel level, WorkZone zone) {
        for (String id : zone.employees()) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            try {
                if (level.getEntity(UUID.fromString(id)) instanceof ResidentEntity) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // 坏的 UUID 直接跳过
            }
        }
        return false;
    }
}
