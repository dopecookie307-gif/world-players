package com.worldplayers.resident;

import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * 居民聊天的**唯一出口**（服务端）。
 *
 * <p>以后居民所有「说一句话」的地方都只调这里，格式与玩家聊天一致：{@code <名字> 文本}。
 * 隐藏标记 {@link #HEAD_MARK} 塞在 style 的 {@code insertion} 里——它不参与显示，
 * 客户端 {@link com.worldplayers.client.ResidentChatHeads} 靠它知道该画谁的脑袋；
 * 没装 Chat Heads 的客户端看不到 insertion，也就不会多出任何空位。</p>
 *
 * <p>v0.3.0（主人 2026-11-23）：**两条文本同时触发时按 2 秒间隔排队发送**，
 * 不再同一 tick 连刷两句（Chat Heads 也会因此两行都拿到头像）。</p>
 *
 * <p>新增一句居民对白只需要一行：</p>
 * <pre>{@code
 * ResidentChat.say(level, this, Component.translatable("chat.worldplayers.hello"));
 * }</pre>
 */
public final class ResidentChat {
    /** style.insertion 的隐藏前缀，后接皮肤名。客户端与服务端共用这个常量。 */
    public static final String HEAD_MARK = "wphead:";
    /** 两条聊天之间的最小间隔：2 秒（主人 2026-11-23）。 */
    public static final int SPEAK_INTERVAL_TICKS = 40;
    /** 排队上限：防止极端情况下无限堆积。 */
    private static final int MAX_QUEUE = 16;

    private record Utterance(ServerLevel level, ResidentEntity resident, Component text) {
    }

    private static final Deque<Utterance> QUEUE = new ArrayDeque<>();
    /** 上一条聊天发出的游戏时间（服务端 tick）；null 用哨兵表示还没说过。 */
    private static long lastSpokenTick = Long.MIN_VALUE;

    /**
     * 居民说一句话：自动加名字、自动带聊天头像标记，广播给全服。
     *
     * <p>入队后由 {@link #tick(MinecraftServer)} 按 2 秒节奏发出；队列外的系统公告
     * （{@link #announce}）仍然立刻发。</p>
     */
    public static void say(ServerLevel level, ResidentEntity resident, Component text) {
        if (level == null || resident == null || text == null) {
            return;
        }
        QUEUE.addLast(new Utterance(level, resident, text));
        while (QUEUE.size() > MAX_QUEUE) {
            QUEUE.removeFirst();
        }
    }

    /** 服务端每 tick 调一次：队列非空且距上一条 ≥2 秒 → 发一句。 */
    public static void tick(MinecraftServer server) {
        if (QUEUE.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        // 新世界 / 服务器重启后 tick 会倒退：直接当作「还没说过」，别卡住
        if (lastSpokenTick != Long.MIN_VALUE && now < lastSpokenTick) {
            lastSpokenTick = Long.MIN_VALUE;
        }
        if (lastSpokenTick != Long.MIN_VALUE && now - lastSpokenTick < SPEAK_INTERVAL_TICKS) {
            return;
        }
        Utterance next = QUEUE.pollFirst();
        if (next == null) {
            return;
        }
        // 排队期间居民没了（死亡 / 卸载）就跳过这一句，别对空气说话
        if (next.resident().isRemoved() || !next.resident().isAlive()) {
            return;
        }
        lastSpokenTick = now;
        deliver(next.level(), next.resident(), next.text());
    }

    private static void deliver(ServerLevel level, ResidentEntity resident, Component text) {
        // 名字走统一出口：没床的居民名字结尾带「（流浪）」
        Component name = ResidentDisplayNames.display(resident);
        MutableComponent line = Component.translatable("chat.worldplayers.say", name, text);
        // 主人 2026-11-21：**只有普通皮肤模型的居民**要聊天头像；
        // YSM 模型的居民一律不带标记（Chat Heads 的启发式也认不出居民的 `<名字>`，所以不会冒出头像）。
        if (resident.modelType() != ResidentEntity.MODEL_YSM) {
            line.withStyle(style -> style.withInsertion(HEAD_MARK + resident.skinName()));
        }
        level.getServer().getPlayerList().broadcastSystemMessage(line, false);
    }

    /** 原样广播一段文本（**不带**头像标记）。死亡信息这类由原版拼好的句子走这里。 */
    public static void announce(ServerLevel level, Component message) {
        level.getServer().getPlayerList().broadcastSystemMessage(message, false);
    }

    /** 换存档 / 重开：清空队列（上次世界的聊天不该带到新世界）。 */
    public static void clear() {
        QUEUE.clear();
        lastSpokenTick = Long.MIN_VALUE;
    }

    private ResidentChat() {
    }
}
