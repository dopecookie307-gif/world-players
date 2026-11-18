package com.worldplayers.compat.jade;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.EntityAccessor;
import snownee.jade.api.IEntityComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade 顶部信息栏：工作 / 饥饿 / 心情 / 体力（生命值 Jade 自己会显示）。
 * 只在装了 Jade 的客户端会被加载（没装时这个类不会被引用）。
 * 文本全部走语言文件（zh_cn / en_us 各一份）。
 */
public class ResidentInfoProvider implements IEntityComponentProvider {
    public static final ResidentInfoProvider INSTANCE = new ResidentInfoProvider();
    private static final ResourceLocation UID = WorldPlayers.id("resident_info");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public void appendTooltip(ITooltip tooltip, EntityAccessor accessor, IPluginConfig config) {
        if (!(accessor.getEntity() instanceof ResidentEntity resident)) {
            return;
        }
        tooltip.add(Component.translatable("gui.worldplayers.job", jobText(resident.jobKey()))
            .withStyle(ChatFormatting.GRAY));
        // 生命值由 Jade 自己显示，这里不再重复
        tooltip.add(Component.translatable("gui.worldplayers.stat.hunger", (int) Math.ceil(resident.hunger()))
            .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("gui.worldplayers.stat.mood", (int) Math.ceil(resident.mood()))
            .withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.translatable("gui.worldplayers.stat.stamina", (int) Math.ceil(resident.stamina()))
            .withStyle(ChatFormatting.GREEN));
    }

    /** 工作状态文字：和浮窗共用同一张登记表。 */
    private static Component jobText(String jobKey) {
        return com.worldplayers.resident.ResidentJobs.text(jobKey);
    }
}
