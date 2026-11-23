package com.worldplayers.item;

import com.worldplayers.resident.ResidentEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;

/**
 * 调试棒的多选表（v0.3.0 T3）：服务端内存里的「玩家 → 已选居民 UUID 集合」。
 *
 * <p>为什么存在服务端而不是物品 NBT：一个物品只能记一个 UUID，而且 64 格的左键选中
 * 走的是客户端发包，根本不经过物品交互。按玩家存之后，换手、丢棒、重进世界都不会丢选中。</p>
 *
 * <p>高亮：每 {@link #GLOW_INTERVAL} tick 给选中居民刷一次短时 {@link MobEffects#GLOWING}
 * （无粒子、无图标）。发光只在**手里拿着调试棒**时刷新，放下棒子最多 1.5 秒就自己熄灭。</p>
 */
public final class WandSelection {
    /** 刷新间隔（tick）。 */
    private static final int GLOW_INTERVAL = 20;
    /** 单次发光时长：比间隔略长，一直拿着就不会闪断。 */
    private static final int GLOW_TICKS = 30;

    private static final Map<UUID, LinkedHashSet<UUID>> SELECTED = new HashMap<>();

    /** 某个玩家手上所有还活着的选中居民（顺手把死掉/换维度的剪掉）。 */
    public static List<ResidentEntity> resolve(ServerPlayer player) {
        LinkedHashSet<UUID> set = SELECTED.get(player.getUUID());
        List<ResidentEntity> out = new ArrayList<>();
        if (set == null) {
            return out;
        }
        Iterator<UUID> it = set.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            if (player.serverLevel().getEntity(id) instanceof ResidentEntity resident
                    && resident.isAlive()) {
                out.add(resident);
            } else {
                it.remove();
            }
        }
        if (set.isEmpty()) {
            SELECTED.remove(player.getUUID());
        }
        return out;
    }

    /** 选中 ↔ 取消选中，返回操作后的状态。 */
    public static boolean toggle(ServerPlayer player, ResidentEntity resident) {
        LinkedHashSet<UUID> set = SELECTED.computeIfAbsent(player.getUUID(), key -> new LinkedHashSet<>());
        boolean nowSelected;
        if (set.remove(resident.getUUID())) {
            nowSelected = false;
        } else {
            set.add(resident.getUUID());
            nowSelected = true;
        }
        if (set.isEmpty()) {
            SELECTED.remove(player.getUUID());
        }
        player.displayClientMessage(Component.translatable(
            nowSelected ? "chat.worldplayers.wand.selected" : "chat.worldplayers.wand.deselected",
            resident.getName()), true);
        return nowSelected;
    }

    /** 断线时清掉（否则 UUID 集合会一直挂在内存里）。 */
    public static void clear(Player player) {
        if (player != null) {
            SELECTED.remove(player.getUUID());
        }
    }

    /** 每 tick 调一次；内部按 20 tick 节流，给「手持调试棒」的玩家刷发光。 */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % GLOW_INTERVAL != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player.getMainHandItem().getItem() instanceof ResidentWandItem)) {
                continue;
            }
            Set<UUID> selected = SELECTED.get(player.getUUID());
            if (selected == null || selected.isEmpty()) {
                continue;
            }
            for (ResidentEntity resident : resolve(player)) {
                // YSM 居民**不刷发光**（主人 2026-11-21 定）：原版发光会让实体再走一遍「描边」通道，
                // 而 YsmModelRenderer 是自己拼顶点、不配合那条通道 → 客户端崩 `IllegalStateException: Not building!`。
                // 调试棒只用于测试，YSM 居民不发光完全可以接受；**绝不为了高亮去动 YSM 渲染链**。
                if (resident.modelType() == ResidentEntity.MODEL_YSM) {
                    continue;
                }
                resident.addEffect(new MobEffectInstance(
                    MobEffects.GLOWING, GLOW_TICKS, 0, false, false, false));
            }
        }
    }

    private WandSelection() {
    }
}
