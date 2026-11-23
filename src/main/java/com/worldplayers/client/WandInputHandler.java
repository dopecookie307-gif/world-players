package com.worldplayers.client;

import com.worldplayers.WorldPlayers;
import com.worldplayers.item.ResidentWandItem;
import com.worldplayers.network.WandClickPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端：手持调试棒时，左键点中的居民按「64 格射线」判定（v0.3.0 T3）。
 *
 * <p>原版左键只对 3 格内的实体发包，所以远处选中必须由客户端先拦下攻击、
 * 自己打一条 64 格射线，命中居民才发 {@link WandClickPayload}。
 * 没对准居民就完全不管，不影响正常打怪/挖方块。</p>
 */
@EventBusSubscriber(modid = WorldPlayers.MOD_ID, value = Dist.CLIENT)
public final class WandInputHandler {
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        // 绑定床铺模式自己吃左键，别抢
        if (BedBindMode.active()) {
            return;
        }
        if (!(minecraft.player.getMainHandItem().getItem() instanceof ResidentWandItem)) {
            return;
        }
        if (ResidentWandItem.residentAt(minecraft.player) == null) {
            return;
        }
        event.setCanceled(true);
        PacketDistributor.sendToServer(new WandClickPayload());
    }

    private WandInputHandler() {
    }
}
