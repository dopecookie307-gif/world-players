package com.worldplayers.resident;

import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;

/**
 * 居民名字的**统一出口**（主人 2026-11-20）：没有床的居民，所有地方显示的名字结尾都加「（流浪）」。
 *
 * <p>改名栏位用的是裸名字（{@link ResidentEntity#getCustomName()}），不受这里影响。</p>
 */
public final class ResidentDisplayNames {
    /** 完整显示名：空名字 → 「居民」，流浪 → 结尾加「（流浪）」。 */
    public static Component display(ResidentEntity resident) {
        return withSuffix(resident, plain(resident));
    }

    /** 只有玩家起过名字时才返回完整显示名（浮窗/名字牌不想给无名居民硬造一个名字时用）。 */
    @Nullable
    public static Component displayOrNull(ResidentEntity resident) {
        Component custom = resident.getCustomName();
        if (custom == null || custom.getString().isBlank()) {
            // 无名 + 流浪：名字牌上就只显示「（流浪）」（主人 2026-11-20）
            return resident.hasBed() ? null : Component.translatable("gui.worldplayers.name_wanderer_only");
        }
        return withSuffix(resident, custom);
    }

    /** 裸名字：有自定义名就用，没有就用默认「居民」。 */
    public static Component plain(ResidentEntity resident) {
        Component custom = resident.getCustomName();
        if (custom != null && !custom.getString().isBlank()) {
            return custom;
        }
        return Component.translatable("entity.worldplayers.resident");
    }

    /** 给任意名字加上流浪后缀（只在没有床时加）。 */
    public static Component withSuffix(ResidentEntity resident, Component name) {
        if (resident.hasBed()) {
            return name;
        }
        return Component.translatable("gui.worldplayers.name_wanderer_suffix", name);
    }

    private ResidentDisplayNames() {
    }
}
