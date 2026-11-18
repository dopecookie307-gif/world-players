package com.worldplayers.client;

import com.worldplayers.config.WPClientConfig;
import com.worldplayers.item.ResidentManualItem;
import com.worldplayers.resident.ResidentEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/** 客户端显示条件（从实体里隔出来，避免服务端加载客户端类）。 */
public final class ClientDisplay {
    public static final double NAMEPLATE_RANGE = 64.0D;

    /**
     * 手持手册时的状态浮窗。
     *
     * <p>受手册页面「手册渲染 → 居民状态浮窗」勾选控制（{@link WPClientConfig}）：
     * 取消勾选后，手持手册不再画浮窗（名字牌本身不受影响）。</p>
     */
    public static boolean showNameplate(ResidentEntity resident) {
        if (!WPClientConfig.showResidentStatusCard()) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            return false;
        }
        boolean holdsManual = player.getMainHandItem().getItem() instanceof ResidentManualItem
            || player.getOffhandItem().getItem() instanceof ResidentManualItem;
        if (!holdsManual) {
            return false;
        }
        return player.distanceToSqr(resident) <= NAMEPLATE_RANGE * NAMEPLATE_RANGE;
    }

    private ClientDisplay() {
    }
}
