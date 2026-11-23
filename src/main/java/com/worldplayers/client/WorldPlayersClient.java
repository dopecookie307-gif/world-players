package com.worldplayers.client;

import com.worldplayers.WorldPlayers;
import com.worldplayers.registry.WPEntities;
import com.worldplayers.registry.WPMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = WorldPlayers.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WorldPlayersClient {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(WPEntities.RESIDENT.get(), ResidentRenderer::new);
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(WPMenus.RESIDENT.get(), ResidentScreen::new);
    }

    /** Chat Heads 是可选软依赖：装了就接上"居民聊天气泡头"，没装就完全跳过。 */
    @SubscribeEvent
    public static void clientSetup(FMLClientSetupEvent event) {
        if (!ModList.get().isLoaded("chat_heads")) {
            return;
        }
        try {
            ResidentChatHeads.register();
        } catch (Throwable missing) {
            WorldPlayers.LOGGER.warn("World Players: Chat Heads 存在但联动初始化失败，聊天不带头像", missing);
        }
    }

    /** 资源重载（换语言 / 加载模组资源）：填充方块搜索的名字缓存要重建（规格 4.1）。 */
    @SubscribeEvent
    public static void registerReloadListeners(
            net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
            new net.minecraft.server.packs.resources.ResourceManagerReloadListener() {
                @Override
                public void onResourceManagerReload(
                        net.minecraft.server.packs.resources.ResourceManager resourceManager) {
                    BlockSearch.invalidate();
                }
            });
    }

    private WorldPlayersClient() {
    }
}
