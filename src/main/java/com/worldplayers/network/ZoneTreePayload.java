package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import com.worldplayers.resident.tree.TreeFarmIndex;
import com.worldplayers.resident.tree.TreeSpecies;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 树场设定（主人 2026-11-21 定稿）：树种 / 种植模式 / 种植位置。
 *
 * <p>规则 4：**树种一改** → 破坏区域内所有「不是新树种」的树苗（掉落直接进背包）→ 重建槽位表。
 * 模式 / 位置一改只重建槽位表，不动已经种下的苗。</p>
 */
public record ZoneTreePayload(String zoneKey, String saplingType, int plantMode, String placementMode)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ZoneTreePayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("zone_tree"));

    public static final StreamCodec<FriendlyByteBuf, ZoneTreePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ZoneTreePayload::zoneKey,
            ByteBufCodecs.STRING_UTF8, ZoneTreePayload::saplingType,
            ByteBufCodecs.VAR_INT, ZoneTreePayload::plantMode,
            ByteBufCodecs.STRING_UTF8, ZoneTreePayload::placementMode,
            ZoneTreePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ZoneTreePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            ServerLevel level = player.serverLevel();
            WorkZoneData data = WorkZoneData.get(level);
            WorkZone zone = data.findByKey(payload.zoneKey());
            if (zone == null || !zone.isTreeFarm()) {
                return;
            }
            String species = TreeSpecies.byId(payload.saplingType()).id();
            // 2026-11-22：种植模式改成按树种自动（深色橡木 / 丛林 = 2×2），不再听客户端的开关
            int mode = WorkZone.autoPlantMode(species);
            String placement = WorkZone.PLACE_MANUAL.equals(payload.placementMode())
                ? WorkZone.PLACE_MANUAL : WorkZone.PLACE_AUTO;
            boolean speciesChanged = !zone.saplingType().equals(species);
            WorkZone updated = zone.withTreeSettings(species, mode, placement);
            data.add(updated);
            // 槽位表缓存作废，下次扫描按新设定重建（规则 4、14①）
            TreeFarmIndex.invalidate(updated.key());
            if (speciesChanged) {
                clearWrongSaplings(level, updated);
            }
            for (ServerPlayer online : level.getServer().getPlayerList().getPlayers()) {
                PacketDistributor.sendToPlayer(online, new ZoneListPayload(WorkZoneData.get(level).zones()));
            }
        });
    }

    /**
     * 清掉区域内「不是新树种」的树苗（规则 4）：掉落直接进员工背包，没员工就掉在地上。
     *
     * <p>树种 = 「无」时，区域内所有树苗都算「不是新树种」，一律清掉。</p>
     */
    private static void clearWrongSaplings(ServerLevel level, WorkZone zone) {
        TreeSpecies species = zone.species();
        ResidentEntity receiver = firstEmployee(level, zone.employees());
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int x = zone.min().getX(); x <= zone.max().getX(); x++) {
            for (int y = zone.min().getY(); y <= zone.max().getY(); y++) {
                for (int z = zone.min().getZ(); z <= zone.max().getZ(); z++) {
                    probe.set(x, y, z);
                    BlockState state = level.getBlockState(probe);
                    if (!(state.getBlock() instanceof SaplingBlock) || species.isOwnSapling(state)) {
                        continue;
                    }
                    BlockPos pos = probe.immutable();
                    List<ItemStack> drops = Block.getDrops(state, level, pos, null, null, ItemStack.EMPTY);
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    level.levelEvent(2001, pos, Block.getId(state));
                    for (ItemStack drop : drops) {
                        if (drop.isEmpty()) {
                            continue;
                        }
                        ItemStack rest = receiver == null
                            ? drop : receiver.inventory().addItem(drop);
                        if (!rest.isEmpty()) {
                            Block.popResource(level, pos, rest);
                        }
                    }
                }
            }
        }
    }

    @Nullable
    private static ResidentEntity firstEmployee(ServerLevel level, List<String> employees) {
        for (String id : employees) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            try {
                ResidentEntity resident = level.getEntity(UUID.fromString(id)) instanceof ResidentEntity found
                    ? found : null;
                if (resident != null) {
                    return resident;
                }
            } catch (IllegalArgumentException ignored) {
                // 坏的 UUID 直接跳过
            }
        }
        return null;
    }
}
