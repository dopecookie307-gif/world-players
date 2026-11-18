package com.worldplayers.resident;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;

/**
 * 居民聊天的**唯一出口**（服务端）。
 *
 * <p>以后居民所有「说一句话」的地方都只调这里，格式与玩家聊天一致：{@code <名字> 文本}。
 * 隐藏标记 {@link #HEAD_MARK} 塞在 style 的 {@code insertion} 里——它不参与显示，
 * 客户端 {@link com.worldplayers.client.ResidentChatHeads} 靠它知道该画谁的脑袋；
 * 没装 Chat Heads 的客户端看不到 insertion，也就不会多出任何空位。</p>
 *
 * <p>新增一句居民对白只需要一行：</p>
 * <pre>{@code
 * ResidentChat.say(level, this, Component.translatable("chat.worldplayers.hello"));
 * }</pre>
 */
public final class ResidentChat {
    /** style.insertion 的隐藏前缀，后接皮肤名。客户端与服务端共用这个常量。 */
    public static final String HEAD_MARK = "wphead:";

    /** 居民说一句话：自动加名字、自动带聊天头像标记，广播给全服。 */
    public static void say(ServerLevel level, ResidentEntity resident, Component text) {
        Component name = resident.getCustomName() != null && !resident.getCustomName().getString().isBlank()
            ? Component.literal(resident.getCustomName().getString())
            : Component.translatable("entity.worldplayers.resident");
        MutableComponent line = Component.translatable("chat.worldplayers.say", name, text);
        line.withStyle(style -> style.withInsertion(HEAD_MARK + resident.skinName()));
        level.getServer().getPlayerList().broadcastSystemMessage(line, false);
    }

    /** 原样广播一段文本（**不带**头像标记）。死亡信息这类由原版拼好的句子走这里。 */
    public static void announce(ServerLevel level, Component message) {
        level.getServer().getPlayerList().broadcastSystemMessage(message, false);
    }

    private ResidentChat() {
    }
}
