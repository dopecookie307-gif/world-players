package com.worldplayers.compat.jade;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentHunger;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

/**
 * Jade 居民信息（v0.2.11）：**饥饿 / 工作 / 心情 / 体力**，四项各自一个开关。
 *
 * <p>位置靠 {@link #getDefaultPriority()}：Jade 按 priority 升序排，它自己的
 * 「实体生命值 / 盔甲值」是 -4500，我们取 -4400 —— 整块信息落在**血量和护甲下面、其他信息上面**，
 * 饥饿是第一行。</p>
 *
 * <p>Jade 设置里（「显示居民信息」条目下面）有五个子开关：</p>
 * <ul>
 *   <li>{@link #CONFIG_HUNGER}「饥饿值」—— 要不要显示饥饿行；</li>
 *   <li>{@link #CONFIG_HUNGER_NUMBERS}「饥饿值显示数字」——
 *       是 = 一个满格鸡腿 + {@code 18/20}；否 = 10 格鸡腿条（含空缺）且不显示数字；</li>
 *   <li>{@link #CONFIG_WORK}「工作状态」、{@link #CONFIG_MOOD}「心情值」、{@link #CONFIG_STAMINA}「体力值」。</li>
 * </ul>
 *
 * <p>鸡腿图标直接用原版 GUI sprite，材质包替换 GUI 贴图就自动跟着变；Jade 本体不需要做任何改动。</p>
 */
public class ResidentInfoProvider implements IEntityComponentProvider {
    public static final ResidentInfoProvider INSTANCE = new ResidentInfoProvider();
    private static final ResourceLocation UID = WorldPlayers.id("resident_info");

    /** Jade 设置项：四行信息各自的开关。 */
    public static final ResourceLocation CONFIG_WORK = WorldPlayers.id("resident_info.work");
    public static final ResourceLocation CONFIG_HUNGER = WorldPlayers.id("resident_info.hunger");
    public static final ResourceLocation CONFIG_MOOD = WorldPlayers.id("resident_info.mood");
    public static final ResourceLocation CONFIG_STAMINA = WorldPlayers.id("resident_info.stamina");
    /** Jade 设置项：饥饿值要不要显示数字（是 = 一格鸡腿 + 数值；否 = 10 格图标）。 */
    public static final ResourceLocation CONFIG_HUNGER_NUMBERS =
        WorldPlayers.id("resident_info.hunger_numbers");

    /** 饥饿满值换算成点数。 */
    private static final int MAX_POINTS = 20;

    /** 配置项注册成功之前（老版本 Jade / 接口不兼容）不读 config，直接用默认值。 */
    private static volatile boolean configRegistered;

    public static void markConfigRegistered() {
        configRegistered = true;
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    /** 血量/护甲是 -4500：比它大一点 = 紧跟在血/护甲下方、其他信息上方。 */
    @Override
    public int getDefaultPriority() {
        return -4400;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
        if (!(accessor.getEntity() instanceof ResidentEntity resident)) {
            return;
        }
        if (flag(config, CONFIG_HUNGER, true)) {
            this.appendHunger(tooltip, resident, flag(config, CONFIG_HUNGER_NUMBERS, true));
        }
        if (flag(config, CONFIG_WORK, true)) {
            tooltip.add(Component.translatable("gui.worldplayers.job", jobText(resident.displayJobKey()))
                .withStyle(ChatFormatting.GRAY));
            // 工作地点名字（农作 / 道路维护时显示）
            if (!resident.destinationKey().isEmpty()) {
                tooltip.add(resident.destinationText().copy().withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        if (flag(config, CONFIG_MOOD, true)) {
            tooltip.add(Component.translatable("gui.worldplayers.stat.mood", (int) Math.ceil(resident.mood()))
                .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (flag(config, CONFIG_STAMINA, true)) {
            tooltip.add(Component.translatable("gui.worldplayers.stat.stamina", (int) Math.ceil(resident.stamina()))
                .withStyle(ChatFormatting.GREEN));
        }
    }

    /** 饥饿行：数字模式 = 一个满格鸡腿 + 18/20；图标模式 = 10 格鸡腿条（无数字）。 */
    private void appendHunger(ITooltip tooltip, ResidentEntity resident, boolean showNumbers) {
        int points = Math.round(Math.max(0.0F, resident.hunger()) / ResidentHunger.PERCENT_PER_POINT);
        IElementHelper helper = IElementHelper.get();
        List<IElement> line = new ArrayList<>(3);
        try {
            line.add(new HungerIconElement(points, showNumbers));
        } catch (Throwable unavailable) {
            // 极端情况（Jade 元素接口变样）：没有图标，只留数字
            line.clear();
        }
        if (showNumbers) {
            if (!line.isEmpty()) {
                line.add(helper.spacer(3, 0));
            }
            line.add(helper.text(Component.literal(points + "/" + MAX_POINTS)
                .withStyle(ChatFormatting.WHITE)));
        }
        if (line.isEmpty()) {
            return;
        }
        tooltip.add(line.get(0));
        for (int i = 1; i < line.size(); i++) {
            tooltip.append(line.get(i));
        }
    }

    /** 读 Jade 配置项；没注册成功或接口不对时用默认值。 */
    private static boolean flag(IPluginConfig config, ResourceLocation key, boolean fallback) {
        if (!configRegistered) {
            return fallback;
        }
        try {
            return config.get(key);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /** 工作状态文字：和浮窗共用同一张登记表。 */
    private static Component jobText(String jobKey) {
        return com.worldplayers.resident.ResidentJobs.text(jobKey);
    }
}
