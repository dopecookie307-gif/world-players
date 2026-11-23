package com.worldplayers.resident;

import com.worldplayers.WorldPlayers;
import com.worldplayers.config.WPServerConfig;
import com.worldplayers.network.BedBindExitPayload;
import com.worldplayers.network.ResidentListPayload;
import com.worldplayers.registry.WPEntities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 招募 / 床铺绑定（服务端）。
 *
 * <p>玩法（主人 2026-11-20 定）：</p>
 * <ul>
 *   <li>手册「成员列表」页 →「招募新成员」→ 进入绑定床铺模式（物品栏上方常驻提示）；</li>
 *   <li>绑定模式下右键**未被绑定**的床：消耗配置数量的可食用物品（默认 16 个，每个至少 1 点饱食度，
 *       可不同种类相加）→ 床上召唤一名新居民并绑定；</li>
 *   <li>流浪居民背包里的「分配床铺」→ 同一种模式，但把床给**这个已有居民**，不消耗食物；</li>
 *   <li>已绑定的床不可互动（不睡觉、不设重生点），提示「这张床是属于（居民名称）的。」；</li>
 *   <li>给床绑定时，如果这张床原本是某个玩家的重生点，就把那个重生点移除并提示。</li>
 * </ul>
 */
public final class BedBinder {
    /** 招募模式的哨兵（值为空 = 招新人；有值 = 分配给这个居民）。 */
    private static final Map<UUID, Optional<UUID>> MODES = new HashMap<>();

    // ------------------------------------------------------------------
    // 进入 / 退出模式
    // ------------------------------------------------------------------

    public static boolean isBinding(ServerPlayer player) {
        return MODES.containsKey(player.getUUID());
    }

    public static void handleAction(ServerPlayer player, int action, @Nullable UUID target) {
        switch (action) {
            case com.worldplayers.network.BedBindPayload.ENTER_RECRUIT -> MODES.put(player.getUUID(), Optional.empty());
            case com.worldplayers.network.BedBindPayload.ENTER_ASSIGN -> {
                if (target != null) {
                    MODES.put(player.getUUID(), Optional.of(target));
                }
            }
            case com.worldplayers.network.BedBindPayload.EXIT -> MODES.remove(player.getUUID());
            case com.worldplayers.network.BedBindPayload.REQUEST_LIST ->
                syncAll(player.server);
            default -> {
                return;
            }
        }
    }

    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        MODES.remove(event.getEntity().getUUID());
    }

    /** 左键方块 / 左键实体也退出模式（左键空气由客户端负责）。 */
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player && isBinding(player)) {
            exitMode(player, true);
            player.displayClientMessage(
                Component.translatable("chat.worldplayers.bed.mode_off"), true);
        }
    }

    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && isBinding(player)) {
            exitMode(player, true);
            player.displayClientMessage(
                Component.translatable("chat.worldplayers.bed.mode_off"), true);
        }
    }

    public static void onServerStopped(MinecraftServer server) {
        MODES.clear();
    }

    private static void exitMode(ServerPlayer player, boolean notifyClient) {
        boolean had = MODES.remove(player.getUUID()) != null;
        if (notifyClient && had) {
            PacketDistributor.sendToPlayer(player, new BedBindExitPayload());
        }
    }

    // ------------------------------------------------------------------
    // 右键
    // ------------------------------------------------------------------

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);

        // 先处理「这张床已经有主」：任何情况下都不许互动（不睡觉、不设重生点）
        BedBindingData.Entry owned = findBed(level, pos);
        if (owned != null) {
            event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
            player.displayClientMessage(Component.translatable(
                "chat.worldplayers.bed.owned", nameOf(owned.name())), true);
            if (isBinding(player)) {
                exitMode(player, true);
            }
            return;
        }

        if (!isBinding(player)) {
            return;
        }

        // 模式里只认「未绑定的床」；其它方块 → 退出模式（但方块本身照常使用）
        if (!(state.getBlock() instanceof BedBlock)) {
            exitMode(player, true);
            player.displayClientMessage(
                Component.translatable("chat.worldplayers.bed.mode_off"), true);
            return;
        }

        event.setUseBlock(net.neoforged.neoforge.common.util.TriState.FALSE);
        Optional<UUID> target = MODES.get(player.getUUID());
        if (target == null) {
            return;
        }
        if (target.isPresent()) {
            assignBed(player, level, pos, target.get());
        } else {
            recruitAt(player, level, pos);
        }
    }

    /** 找到这张床的绑定（头/脚两格都算同一张床）。 */
    @Nullable
    public static BedBindingData.Entry findBed(ServerLevel level, BlockPos pos) {
        BedBindingData data = BedBindingData.get(level);
        BedBindingData.Entry direct = data.find(level.dimension(), pos);
        if (direct != null) {
            return direct;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BedBlock) {
            BlockPos other = pos.relative(BedBlock.getConnectedDirection(state));
            return data.find(level.dimension(), other);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 招募 / 分配
    // ------------------------------------------------------------------

    private static void recruitAt(ServerPlayer player, ServerLevel level, BlockPos bedPos) {
        int cost = WPServerConfig.recruitFoodCost();
        int have = countFood(player);
        if (cost > 0 && have < cost) {
            player.displayClientMessage(Component.translatable(
                "chat.worldplayers.bed.need_food", cost - have), true);
            return;
        }
        consumeFood(player, cost);

        ResidentEntity resident = WPEntities.RESIDENT.get().create(level);
        if (resident == null) {
            return;
        }
        BlockPos spawn = safeSpawnNear(level, bedPos);
        // 招募出来的居民**不给名字、不排序号**（主人 2026-11-20）——名字等玩家自己起
        resident.moveTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
            level.getRandom().nextFloat() * 360.0F, 0.0F);
        resident.finalizeSpawn(level, level.getCurrentDifficultyAt(spawn), MobSpawnType.COMMAND, null);
        level.addFreshEntity(resident);

        // 招募音效：和复活同款（升级音）
        level.playSound(null, resident.getX(), resident.getY(), resident.getZ(),
            net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, net.minecraft.sounds.SoundSource.NEUTRAL,
            0.6F, 1.2F);

        bindBed(player, level, bedPos, resident, "");
    }

    private static void assignBed(ServerPlayer player, ServerLevel level, BlockPos bedPos, UUID residentId) {
        ResidentEntity resident = level.getEntity(residentId) instanceof ResidentEntity found
            ? found
            : null;
        if (resident == null) {
            player.displayClientMessage(Component.translatable("chat.worldplayers.bed.no_resident"), true);
            exitMode(player, true);
            return;
        }
        // 换床：先解掉旧床（旧床立刻变回普通床）
        BedBindingData data = BedBindingData.get(level);
        BedBindingData.Entry old = data.findFor(resident.getUUID());
        if (old != null) {
            data.unbind(old.dimension(), old.pos());
        }
        String name = resident.getCustomName() != null && !resident.getCustomName().getString().isBlank()
            ? resident.getCustomName().getString()
            : Component.translatable("entity.worldplayers.resident").getString();
        bindBed(player, level, bedPos, resident, name);
    }

    /** 真正写入绑定 + 移除重生点 + 聊天提示 + 退出模式。 */
    private static void bindBed(ServerPlayer player, ServerLevel level, BlockPos bedPos,
                                ResidentEntity resident, String residentName) {
        BedBindingData data = BedBindingData.get(level);
        data.bind(level.dimension(), bedPos, resident.getUUID(), residentName);
        resident.setBedPos(bedPos);

        boolean removedRespawn = removeRespawnPoints(level, bedPos);

        player.displayClientMessage(Component.translatable("chat.worldplayers.bed.bound",
            bedPos.getX(), bedPos.getY(), bedPos.getZ(), nameOf(residentName)), false);
        if (removedRespawn) {
            player.displayClientMessage(
                Component.translatable("chat.worldplayers.bed.respawn_removed"), false);
        }
        exitMode(player, true);
        syncAll(level.getServer());
    }

    /** 绑定数据里没名字（招募出来的居民）→ 显示默认「居民」。 */
    private static Component nameOf(String storedName) {
        if (storedName == null || storedName.isBlank()) {
            return Component.translatable("entity.worldplayers.resident");
        }
        return Component.literal(storedName);
    }

    /** 床被破坏 → 解除绑定（含两半的任一格），聊天栏一条测试文本。 */
    public static void onBlockBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getState().getBlock() instanceof BedBlock)) {
            return;
        }
        BedBindingData.Entry entry = findBed(level, event.getPos());
        if (entry == null) {
            return;
        }
        BedBindingData data = BedBindingData.get(level);
        data.unbind(entry.dimension(), entry.pos());
        Component text = ResidentChatPool.pick(ResidentChatPool.Kind.BED_BROKEN, level.getRandom());
        // 用居民自己的名字发这条（和受击测试文本同一个格式：名字 + chatheads 头像）
        if (level.getEntity(entry.resident()) instanceof ResidentEntity resident) {
            resident.setBedPos(null);
            ResidentChat.say(level, resident, text);
        } else {
            ResidentChat.announce(level, text);
        }
        syncAll(level.getServer());
    }

    /** 这张床如果被玩家设成了重生点，就清掉（主人要求：绑定后移除玩家的出生点）。 */
    private static boolean removeRespawnPoints(ServerLevel level, BlockPos bedPos) {
        BlockState state = level.getBlockState(bedPos);
        BlockPos head = state.getBlock() instanceof BedBlock
            && state.getValue(BedBlock.PART) == BedPart.HEAD
            ? bedPos : null;
        boolean any = false;
        for (ServerPlayer online : level.getServer().getPlayerList().getPlayers()) {
            BlockPos respawn = online.getRespawnPosition();
            if (respawn == null || !level.dimension().equals(online.getRespawnDimension())) {
                continue;
            }
            boolean sameBed = respawn.equals(bedPos)
                || (head != null && respawn.equals(head))
                || isSameBedNeighbour(level, respawn, bedPos);
            if (!sameBed) {
                continue;
            }
            online.setRespawnPosition(level.dimension(), null, 0.0F, false, false);
            online.displayClientMessage(
                Component.translatable("chat.worldplayers.bed.respawn_removed"), false);
            any = true;
        }
        return any;
    }

    /** 判断两个位置是不是同一张床的两半。 */
    private static boolean isSameBedNeighbour(ServerLevel level, BlockPos a, BlockPos b) {
        BlockState state = level.getBlockState(a);
        if (!(state.getBlock() instanceof BedBlock)) {
            return false;
        }
        return a.relative(BedBlock.getConnectedDirection(state)).equals(b)
            || b.relative(BedBlock.getConnectedDirection(level.getBlockState(b))).equals(a);
    }

    // ------------------------------------------------------------------
    // 食物
    // ------------------------------------------------------------------

    private static boolean isEdible(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        net.minecraft.world.food.FoodProperties food =
            stack.get(net.minecraft.core.component.DataComponents.FOOD);
        return food != null && food.nutrition() >= 1;
    }

    private static int countFood(ServerPlayer player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isEdible(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 从后往前扣（先扣背包最里面那些，玩家马上能看到快捷栏的变化）。 */
    private static void consumeFood(ServerPlayer player, int amount) {
        int left = amount;
        for (int slot = player.getInventory().getContainerSize() - 1; slot >= 0 && left > 0; slot--) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!isEdible(stack)) {
                continue;
            }
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            left -= take;
        }
        player.getInventory().setChanged();
        player.containerMenu.broadcastChanges();
    }

    // ------------------------------------------------------------------
    // 找位置 / 计数
    // ------------------------------------------------------------------

    /** 床的上方：能站人就用上方，不行就往上找几格，再不行退回床头。 */
    private static BlockPos safeSpawnNear(ServerLevel level, BlockPos bedPos) {
        for (int up = 1; up <= 4; up++) {
            BlockPos candidate = bedPos.above(up);
            if (ResidentEntity.isSafeStand(level, candidate)) {
                return candidate;
            }
        }
        return bedPos.above();
    }

    private static int residentCount(MinecraftServer server) {
        int total = 0;
        for (ServerLevel level : server.getAllLevels()) {
            total += level.getEntities(EntityTypeTest.forClass(ResidentEntity.class), resident -> true).size();
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    /** 把所有居民（含床位）发给所有玩家。招募/绑定/复活/死亡后都调一次。 */
    public static void syncAll(MinecraftServer server) {
        List<ResidentListPayload.Member> list = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            BedBindingData data = BedBindingData.get(level);
            for (ResidentEntity resident : level.getEntities(EntityTypeTest.forClass(ResidentEntity.class),
                resident -> true)) {
                String name = resident.getCustomName() != null
                    ? resident.getCustomName().getString() : "";
                String skin = resident.skinName() == null ? "" : resident.skinName();
                BedBindingData.Entry entry = data.findFor(resident.getUUID());
                int px = net.minecraft.util.Mth.floor(resident.getX());
                int py = net.minecraft.util.Mth.floor(resident.getY());
                int pz = net.minecraft.util.Mth.floor(resident.getZ());
                // 无业 = 没有任何「真正的工作区」（道路 / 禁行不算工作场所），员工列表里会标出来
                boolean unemployed = ColonyAnchors.unemployed(level, resident);
                if (entry == null) {
                    list.add(new ResidentListPayload.Member(resident.getUUID(), name, skin, false, null,
                        px, py, pz, unemployed));
                } else {
                    list.add(new ResidentListPayload.Member(resident.getUUID(), name, skin, true, entry.pos(),
                        px, py, pz, unemployed));
                }
            }
        }
        ResidentListPayload payload = new ResidentListPayload(list);
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(online, payload);
        }
    }

    /**
     * 居民死了：床绑定数据留着（等复活）。
     *
     * @param removeEmployees 是否把他从工作区员工栏里拿掉——**只有不会复活的流浪居民**才拿掉；
     *                        有床的居民 5 秒后原地复活，还要继续上班。
     */
    public static void onResidentDied(ServerLevel level, UUID residentId, boolean removeEmployees) {
        MinecraftServer server = level.getServer();
        if (removeEmployees) {
            WorkZoneData data = WorkZoneData.get(level);
            // 不留幽灵员工：工作区详情页 / 浮窗头像都靠员工栏里的 UUID 找人
            if (data.detachFromOtherZones(residentId.toString(), null)) {
                broadcastZones(server);
            }
        }
        syncAll(server);
    }

    /** 广播所有工作区域（员工行 / 浮窗头像靠它刷新）。 */
    public static void broadcastZones(MinecraftServer server) {
        java.util.List<WorkZone> zones = WorkZoneData.get(server.overworld()).zones();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(online,
                new com.worldplayers.network.ZoneListPayload(zones));
        }
    }

    /** 成员列表刷新请求：同一 tick 内的多次请求合并成一次（2026-11-22 刷怪蛋生成的居民要能马上看到）。 */
    private static boolean syncPending;

    public static void requestSync() {
        syncPending = true;
    }

    /** server tick 调用：有待同步就发一次。 */
    public static void flushSync(MinecraftServer server) {
        if (syncPending) {
            syncPending = false;
            syncAll(server);
        }
    }

    private BedBinder() {
    }
}
