package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentChat;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 详情页提交员工列表（主人 2026-11-20）：整份列表覆盖写入，空字符串 = 空槽位。
 *
 * <p>新绑定的居民会在聊天栏随口说一句（三种随机台词之一，走 {@link ResidentChat#say}，
 * 和其他居民对白同一个格式、同样带 chatheads 头像）。</p>
 */
public record ZoneEmployeesPayload(String zoneKey, List<String> employees) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ZoneEmployeesPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("zone_employees"));

    public static final StreamCodec<FriendlyByteBuf, ZoneEmployeesPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ZoneEmployeesPayload::zoneKey,
            ByteBufCodecs.collection(ArrayList::new, ByteBufCodecs.STRING_UTF8),
            ZoneEmployeesPayload::employees,
            ZoneEmployeesPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ZoneEmployeesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            WorkZoneData data = WorkZoneData.get(level);
            WorkZone zone = data.findByKey(payload.zoneKey());
            if (zone == null) {
                return;
            }
            List<String> cleaned = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            // 员工栏数量：渔场只有 1 格（v0.3.0，15 号规则第四节），其它最多 5 格。
            int slots = zone.employeeSlots();
            boolean rejected = false;
            for (String id : payload.employees()) {
                String value = id == null ? "" : id.trim();
                if (value.isEmpty()) {
                    if (cleaned.size() < slots) {
                        cleaned.add("");
                    }
                    continue;
                }
                // 同一个居民不能占两个员工栏（占一个空位，保持栏位布局）
                if (!seen.add(value)) {
                    if (cleaned.size() < slots) {
                        cleaned.add("");
                    }
                    continue;
                }
                // 栏位已经满了：后来的人**拒绝**（渔场第二个人会走到这里）
                if (cleaned.size() >= slots) {
                    rejected = true;
                    continue;
                }
                cleaned.add(value);
            }
            if (rejected && zone.isFishery()) {
                // 15 号规则 4.6：服务端兜底，回显提示
                player.displayClientMessage(
                    Component.translatable("chat.worldplayers.fishery.one_employee"), false);
            }
            Set<String> before = new HashSet<>(zone.employees());
            data.add(zone.withEmployees(cleaned));
            // 后选的区域**抢走并独占**员工（2026-11-22 主人定）：
            // 同一个人以前可以同时挂两个区域，现在从其它区域的员工栏里直接拿掉。
            for (String id : cleaned) {
                if (!id.isEmpty()) {
                    data.detachFromOtherZones(id, zone.key());
                }
            }

            for (String id : cleaned) {
                if (id.isEmpty() || before.contains(id)) {
                    continue;
                }
                ResidentEntity resident = findResident(level, id);
                if (resident == null) {
                    continue;
                }
                String typeName = Component.translatable(
                    "gui.worldplayers.zone.type." + zone.type()).getString();
                // 上岗搜工具（空壳，2026-11-21）：只置标志，现在不触发任何行为。
                // 以后这里接「去仓库/找合成台/自己做工具再上班」。
                resident.requestToolCheck();
                int pick = 1 + level.getRandom().nextInt(3);
                ResidentChat.say(level, resident, Component.translatable(
                    "chat.worldplayers.work.assigned_" + pick, zone.name(), typeName));
                // 流浪居民被指派时按体力再补一句（2026-11-23，14 号文档 3.24）
                if (resident.bedPos() == null) {
                    int homeless = com.worldplayers.resident.ResidentChatPool
                        .assignHomelessLevel(resident.stamina());
                    com.worldplayers.resident.ResidentChatPool.sayHomeless(level, resident, homeless);
                }
            }

            for (ServerPlayer online : level.getServer().getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(online,
                    new ZoneListPayload(WorkZoneData.get(level).zones()));
            }
            // 抢人后「无业」标记会变：成员列表也刷新一份
            com.worldplayers.resident.BedBinder.syncAll(level.getServer());
        });
    }

    @javax.annotation.Nullable
    private static ResidentEntity findResident(ServerLevel level, String id) {
        try {
            UUID uuid = UUID.fromString(id);
            if (level.getEntity(uuid) instanceof ResidentEntity resident) {
                return resident;
            }
            for (ServerLevel other : level.getServer().getAllLevels()) {
                if (other.getEntity(uuid) instanceof ResidentEntity resident) {
                    return resident;
                }
            }
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return null;
    }
}
