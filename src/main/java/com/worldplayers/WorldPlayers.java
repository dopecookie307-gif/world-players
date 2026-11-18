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
        // 调试命令（/worldplayers mood <0-100>）：临时的心情测试入口
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
            com.worldplayers.command.WPCommands::register);
        // 敌对生物的仇恨清单：任何实现了 Enemy 的生物都会被补一个「打居民」的目标
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WorldPlayers::onEntityJoin);
        // 防"地底互相锁死"：怪物从没见过这个居民时，不许一直恨它
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(WorldPlayers::onEntityTick);
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
                || !(mob instanceof net.minecraft.world.entity.monster.Enemy)
                || mob.level().isClientSide()) {
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
                com.worldplayers.network.PlayerSkinPayload::handle);
    }
}
