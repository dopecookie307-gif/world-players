package com.worldplayers.item;

import com.worldplayers.client.ManualScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 居民手册。
 *
 * <p>手持时看向居民，头顶会浮出名牌/状态卡片（{@code ResidentNameplate}）。</p>
 * <p>右键打开手册页面（{@link ManualScreen}）。页面只在客户端打开，
 * 服务端这边返回成功让客户端正常挥手。</p>
 */
public class ResidentManualItem extends Item {
    public ResidentManualItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        // 副手的居民手册永远不可交互（2026-11-19 主人定的长期规矩，保留到开发结束）：
        // 副手拿手册只是为了渲染浮窗/以后的其它渲染，而且用调试棒右键时会误开手册。
        if (hand == InteractionHand.OFF_HAND) {
            return InteractionResultHolder.fail(player.getItemInHand(hand));
        }
        if (level.isClientSide) {
            // 类加载是惰性的：专用服务器永远走不到这一行，不会去加载客户端类
            ManualScreen.open();
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }
}
