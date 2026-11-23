package com.worldplayers;

import com.mojang.logging.LogUtils;
import com.worldplayers.config.WPClientConfig;
import com.worldplayers.network.ResidentSettingsPayload;
import com.worldplayers.registry.WPEntities;
import com.worldplayers.registry.WPItems;
import com.worldplayers.registry.WPMenus;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentSkins;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

/**
 * World Players —— 由 Folkways 的居民（Resident）部分裁剪而来的、与玩家共享渲染与行为的 NPC 框架。
 */
@Mod(WorldPlayers.MOD_ID)
public class WorldPlayers {
    public static final String MOD_ID = "worldplayers";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WorldPlayers(IEventBus modEventBus, ModContainer modContainer) {
        WPEntities.ENTITY_TYPES.register(modEventBus);
        WPItems.ITEMS.register(modEventBus);
        WPMenus.MENUS.register(modEventBus);
        modEventBus.addListener(WorldPlayers::onCommonSetup);
        modEventBus.addListener(WorldPlayers::onEntityAttributes);
        modEventBus.addListener(WorldPlayers::onCreativeTabContents);
        modEventBus.addListener(WorldPlayers::onRegisterPayloads);
        // 客户端配置：手册页面里的勾选项直接改这里（手持手册时的渲染开关）
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT,
            WPClientConfig.SPEC);
        // 服务端配置：招募消耗的食物数量等规则（跟着存档走）
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER,
            com.worldplayers.config.WPServerConfig.SPEC);
        // 调试命令（/worldplayers mood <0-100>）：临时的心情测试入口
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            com.worldplayers.command.WPCommands::register);
        // 敌对生物的仇恨清单：任何实现了 Enemy 的生物都会被补一个「打居民」的目标
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WorldPlayers::onEntityJoin);
        // 防"地底互相锁死"：怪物从没见过这个居民时，不许一直恨它
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WorldPlayers::onEntityTick);
        // Modular Golems（软兼容，没装就空转）：傀儡和居民永远不把对方设成目标
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            com.worldplayers.compat.modulargolems.ModularGolemsCompat::onLivingChangeTarget);
        // 调试棒：左键点居民 = 选中，不是攻击
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WorldPlayers::onAttackEntity);
        // 调试棒多选（T3）：断线时清掉选中表
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WorldPlayers::onPlayerLoggedOut);
        // 工作区域：玩家进世界时把已储存列表同步给他
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WorldPlayers::onPlayerLoggedIn);
        // 招募 / 床铺绑定（主人 2026-11-20）：绑定模式的右键、已绑定床不可互动、左键退出
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            com.worldplayers.resident.BedBinder::onRightClickBlock);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            com.worldplayers.resident.BedBinder::onLeftClickBlock);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            com.worldplayers.resident.BedBinder::onAttackEntity);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            com.worldplayers.resident.BedBinder::onLoggedOut);
        // 床被破坏 → 解除绑定
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            com.worldplayers.resident.BedBinder::onBlockBreak);
        // 有床居民的死亡复活队列
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WorldPlayers::onServerTick);
    }

    /** 每 tick 推进「5 秒后床边复活」队列。 */
    private static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        com.worldplayers.resident.ResidentRevival.tick(event.getServer());
        // 居民生成后合并刷新成员列表（同一 tick 只发一次；2026-11-22）
        com.worldplayers.resident.BedBinder.flushSync(event.getServer());
        // 居民自己开过的门：走远了 / 超时就关上
        com.worldplayers.resident.ResidentDoors.tick(event.getServer());
        // 居民聊天 2 秒排队的出口（主人 2026-11-23：同时两条要排队、隔 2 秒发）
        com.worldplayers.resident.ResidentChat.tick(event.getServer());
        // 调试棒：手持时给已选居民刷发光（内部 20 tick 节流）
        com.worldplayers.item.WandSelection.tick(event.getServer());
    }

    /** 工作区域列表同步（v0.2.12）：进世界先发一份，之后每次保存也会广播。 */
    private static void onPlayerLoggedIn(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            com.worldplayers.resident.WorkZoneData data =
                com.worldplayers.resident.WorkZoneData.get(player.serverLevel());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new com.worldplayers.network.ZoneListPayload(data.zones()));
            // 成员列表（成员列表页 / 绑定床铺模式判断床归属）
            com.worldplayers.resident.BedBinder.syncAll(player.server);
            // 招募消耗数量（手册按钮的悬浮提示要按服务端配置浮动）
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new com.worldplayers.network.RecruitCostPayload(
                    com.worldplayers.config.WPServerConfig.recruitFoodCost()));
        }
    }

    /** 断线：清掉调试棒的选中表（T3）。 */
    private static void onPlayerLoggedOut(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        com.worldplayers.item.WandSelection.clear(event.getEntity());
    }

    /** 调试棒左键点中居民：选中/取消并取消这次攻击（远处由客户端 {@code WandInputHandler} 发包处理）。 */
    private static void onAttackEntity(
            net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if (event.getEntity().level().isClientSide()) {
            return;
        }
        net.minecraft.world.item.ItemStack stack = event.getEntity().getMainHandItem();
        if (!(stack.getItem() instanceof com.worldplayers.item.ResidentWandItem)) {
            return;
        }
        if (!(event.getTarget() instanceof ResidentEntity resident)) {
            return;
        }
        event.setCanceled(true);
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            com.worldplayers.item.WandSelection.toggle(player, resident);
        }
    }

    /**
     * 怪物一 tick 一次的守卫：仇恨目标若是居民、而它**从来没看见过**这个居民，就清掉仇恨。
     *
     * <p>治的是这种死亡螺旋：玩家在地底打了只怪 → 居民跑来支援（隔着地板）→ 怪被箭打中后
     * `HurtByTargetGoal` 立刻仇恨居民 → 双方谁也够不着谁，互相锁死。
     * 只要它真正看见过这个居民一次（记在 persistentData 里），之后隔着墙也允许继续仇恨。</p>
     */
    private static void onEntityTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof net.minecraft.world.entity.Mob mob)
                || mob.level().isClientSide()) {
            return;
        }
        // Modular Golems（软兼容）：任何方向上残留的"傀儡 ↔ 居民"目标都清掉（含玩家强制指派）
        com.worldplayers.compat.modulargolems.ModularGolemsCompat.onEntityTick(mob);
        if (!(mob instanceof net.minecraft.world.entity.monster.Enemy)) {
            return;
        }
        if (!(mob.getTarget() instanceof com.worldplayers.resident.ResidentEntity resident)) {
            return;
        }
        String key = "wp_seen_" + resident.getId();
        if (mob.getSensing().hasLineOfSight(resident)) {
            mob.getPersistentData().putBoolean(key, true);
            return;
        }
        if (!mob.getPersistentData().getBoolean(key)) {
            mob.setTarget(null);
        }
    }

    /** 敌对生物加入世界时，补上「居民也在仇恨名单里」这个目标（和打玩家同优先级）。 */
    private static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof net.minecraft.world.entity.Mob mob)
                || !(mob instanceof net.minecraft.world.entity.monster.Enemy)) {
            return;
        }
        // 傀儡永远不进"会主动仇恨居民"的名单（哪怕对方以后某版傀儡实现了 Enemy）
        if (com.worldplayers.compat.modulargolems.ModularGolemsCompat.isGolem(mob)) {
            return;
        }
        com.worldplayers.resident.ResidentHate.attach(mob);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        // 皮肤池：config/worldplayers/skins/，首次运行时把 jar 内自带的 MC 默认玩家皮肤铺进去。
        event.enqueueWork(ResidentSkins::installDefaults);
    }

    private static void onEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(WPEntities.RESIDENT.get(), ResidentEntity.createAttributes().build());
    }

    private static void onCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(WPItems.RESIDENT_SPAWN_EGG.get());
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(WPItems.RESIDENT_MANUAL.get());
            event.accept(WPItems.RESIDENT_WAND.get());
        }
    }

    private static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
            .playToServer(
                ResidentSettingsPayload.TYPE,
                ResidentSettingsPayload.STREAM_CODEC,
                ResidentSettingsPayload::handle)
            .playToServer(
                com.worldplayers.network.PlayerSkinRequestPayload.TYPE,
                com.worldplayers.network.PlayerSkinRequestPayload.STREAM_CODEC,
                com.worldplayers.network.PlayerSkinRequestPayload::handle)
            .playToClient(
                com.worldplayers.network.PlayerSkinPayload.TYPE,
                com.worldplayers.network.PlayerSkinPayload.STREAM_CODEC,
                com.worldplayers.network.PlayerSkinPayload::handle)
            .playToServer(
                com.worldplayers.network.ZoneSavePayload.TYPE,
                com.worldplayers.network.ZoneSavePayload.STREAM_CODEC,
                com.worldplayers.network.ZoneSavePayload::handle)
            .playToClient(
                com.worldplayers.network.ZoneListPayload.TYPE,
                com.worldplayers.network.ZoneListPayload.STREAM_CODEC,
                com.worldplayers.network.ZoneListPayload::handle)
            .playToServer(
                com.worldplayers.network.ZoneDeletePayload.TYPE,
                com.worldplayers.network.ZoneDeletePayload.STREAM_CODEC,
                com.worldplayers.network.ZoneDeletePayload::handle)
            .playToServer(
                com.worldplayers.network.ZoneRenamePayload.TYPE,
                com.worldplayers.network.ZoneRenamePayload.STREAM_CODEC,
                com.worldplayers.network.ZoneRenamePayload::handle)
            // 招募 / 床铺绑定（主人 2026-11-20）
            .playToServer(
                com.worldplayers.network.BedBindPayload.TYPE,
                com.worldplayers.network.BedBindPayload.STREAM_CODEC,
                com.worldplayers.network.BedBindPayload::handle)
            .playToClient(
                com.worldplayers.network.BedBindExitPayload.TYPE,
                com.worldplayers.network.BedBindExitPayload.STREAM_CODEC,
                com.worldplayers.network.BedBindExitPayload::handle)
            .playToClient(
                com.worldplayers.network.ResidentListPayload.TYPE,
                com.worldplayers.network.ResidentListPayload.STREAM_CODEC,
                com.worldplayers.network.ResidentListPayload::handle)
            // 手册成员列表点开只读居民界面（主人 2026-11-20）
            .playToServer(
                com.worldplayers.network.OpenResidentViewPayload.TYPE,
                com.worldplayers.network.OpenResidentViewPayload.STREAM_CODEC,
                com.worldplayers.network.OpenResidentViewPayload::handle)
            // 工作区员工列表（详情页提交）
            .playToServer(
                com.worldplayers.network.ZoneEmployeesPayload.TYPE,
                com.worldplayers.network.ZoneEmployeesPayload.STREAM_CODEC,
                com.worldplayers.network.ZoneEmployeesPayload::handle)
            .playToServer(com.worldplayers.network.ZoneTreePayload.TYPE,
                com.worldplayers.network.ZoneTreePayload.STREAM_CODEC,
                com.worldplayers.network.ZoneTreePayload::handle)
            .playToServer(com.worldplayers.network.ZoneRanchPayload.TYPE,
                com.worldplayers.network.ZoneRanchPayload.STREAM_CODEC,
                com.worldplayers.network.ZoneRanchPayload::handle)
            // 娱乐区域简介（v0.2.17，14 号文档 1.8）：关闭/失焦即整份覆盖
            .playToServer(com.worldplayers.network.ZoneDescriptionPayload.TYPE,
                com.worldplayers.network.ZoneDescriptionPayload.STREAM_CODEC,
                com.worldplayers.network.ZoneDescriptionPayload::handle)
            // 挖掘 / 填充工程（v0.2.15）：勾叉 / 改方块 + 进度同步
            .playToServer(com.worldplayers.network.ZoneProjectPayload.TYPE,
                com.worldplayers.network.ZoneProjectPayload.STREAM_CODEC,
                com.worldplayers.network.ZoneProjectPayload::handle)
            .playToClient(com.worldplayers.network.ZoneProjectProgressPayload.TYPE,
                com.worldplayers.network.ZoneProjectProgressPayload.STREAM_CODEC,
                com.worldplayers.network.ZoneProjectProgressPayload::handle)
            // 招募消耗数量同步（服务端配置 → 手册悬浮提示）
            .playToClient(
                com.worldplayers.network.RecruitCostPayload.TYPE,
                com.worldplayers.network.RecruitCostPayload.STREAM_CODEC,
                com.worldplayers.network.RecruitCostPayload::handle)
            // 调试棒左键（T3）：客户端 64 格射线命中居民后发包，服务端再判一次
            .playToServer(
                com.worldplayers.network.WandClickPayload.TYPE,
                com.worldplayers.network.WandClickPayload.STREAM_CODEC,
                com.worldplayers.network.WandClickPayload::handle);
    }
}
