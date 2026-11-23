package com.worldplayers.client;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentChat;
import dzwdz.chat_heads.ChatHeads;
import dzwdz.chat_heads.HeadData;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Chat Heads（mod id: {@code chat_heads}）联动：让居民在聊天室里也顶着自己的**2D 脑袋**。
 *
 * <p>只有装了 Chat Heads 才会加载这个类（{@code WorldPlayersClient} 里先查 ModList）。</p>
 *
 * <h3>为什么不直接用 lastSenderData</h3>
 * Chat Heads 自己也会处理系统消息（{@code handleSystemMessages} 开着时），它的处理**在我们之后**跑，
 * 会把 {@code lastSenderData} 重新检测成 EMPTY —— 所以那条路会被覆盖。
 * 现在改成：消息进聊天栏之后，直接把它那一行（{@code GuiMessage.Line}）上的头像字段写成我们的，
 * Chat Heads 渲染时照旧用行自己的数据算偏移、画头，只是数据来源换成居民皮肤。
 *
 * <p>聊天行格式由服务端给：{@code <名字> 受击测试}，并在 style 里塞一个不显示的
 * {@code insertion = "wphead:<皮肤名>"} 当隐藏标记。</p>
 */
public final class ResidentChatHeads {
    private static final String MARK = ResidentChat.HEAD_MARK;
    private static final Map<String, PlayerSkin> SKINS = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static Field trimmedMessagesField;
    /** 运行时出错（对方版本变了 / 字段没了）就整体停用，绝不在聊天里刷屏、更不让游戏崩。 */
    private static boolean disabled;

    /** 等待"消息真的进了聊天栏"的补丁请求。 */
    private static final class Pending {
        /** 完整显示文本（用来和行内容精确配对，避免同名的两条消息互相抢行）。 */
        private final String text;
        /** `<名字>` 前缀（多行消息被裁切时兜底）。 */
        private final String prefix;
        private final HeadData headData;
        private int ticksLeft;

        private Pending(String text, String prefix, HeadData headData, int ticksLeft) {
            this.text = text;
            this.prefix = prefix;
            this.headData = headData;
            this.ticksLeft = ticksLeft;
        }
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ResidentChatHeads::onSystemChat);
        NeoForge.EVENT_BUS.addListener(ResidentChatHeads::onClientTick);
        WorldPlayers.LOGGER.info("World Players: Chat Heads 联动已启用（居民聊天气泡头）");
    }

    /** 系统聊天到达时（原版 addMessage 之前）：顺手给个 fallback，再排队一次"行级补丁"。 */
    private static void onSystemChat(ClientChatReceivedEvent.System event) {
        if (disabled) {
            return;
        }
        try {
            handleSystemChat(event);
        } catch (Throwable incompatible) {
            disable(incompatible);
        }
    }

    private static void handleSystemChat(ClientChatReceivedEvent.System event) {
        Component message = event.getMessage();
        String insertion = message.getStyle() == null ? null : message.getStyle().getInsertion();
        if (insertion == null || !insertion.startsWith(MARK)) {
            return;
        }
        String payload = insertion.substring(MARK.length());
        int bar = payload.indexOf('|');
        String skinName = bar < 0 ? payload : payload.substring(0, bar);
        String model = bar < 0 ? "" : payload.substring(bar + 1);
        PlayerSkin skin = skinFor(skinName, model);
        if (skin == null) {
            return;
        }
        HeadData headData = new HeadData(headInfo(skinName.isEmpty() ? model : skinName, skin), 1);
        // fallback：如果 Chat Heads 这次没有覆盖，用它自己那条路也能出图
        ChatHeads.lastSenderData = headData;
        // 双保险：聊天行是「之后」才创建的，先在 lineData 里放好，行一出生就带头像
        try {
            ChatHeads.setLineData(headData);
        } catch (Throwable ignored) {
            // 旧版本没有这个入口时无声降级，主路径（行级写入）仍然有效
        }

        // 主路径：记下这一行的开头，等它真的进了 trimmedMessages 再写头像
        String text = message.getString();
        int end = text.indexOf('>');
        String prefix = end > 0 ? text.substring(0, end + 1) : text;
        PENDING.add(new Pending(text, prefix, headData, 4));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (disabled || PENDING.isEmpty()) {
            return;
        }
        try {
            handleClientTick();
        } catch (Throwable incompatible) {
            disable(incompatible);
        }
    }

    private static void handleClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.gui == null) {
            return;
        }
        ChatComponent chat = minecraft.gui.getChat();
        List<GuiMessage.Line> lines = lines(chat);
        if (lines == null) {
            PENDING.clear();
            return;
        }
        // 2026-11-23 修「被指派工作时的回应没有头像」：
        // 员工被指派时通常会连说两句（「我这就前往…」+ 流浪台词），两行的 `<名字>` 前缀一样，
        // 以前按前缀匹配，后到的那行先把两条 PENDING 都认领了，第一句就永远没有头像。
        // 现在先按**完整文本**配对，并记录本 tick 已被认领的行，一条 PENDING 只吃一行。
        List<String> plainLines = new ArrayList<>(lines.size());
        for (GuiMessage.Line line : lines) {
            plainLines.add(plain(line.content()));
        }
        java.util.Set<Integer> claimedIndex = new java.util.HashSet<>();
        PENDING.removeIf(pending -> {
            int match = -1;
            // 1) 完整文本相等（同名多句时唯一正确的配对）
            for (int i = 0; i < plainLines.size() && match < 0; i++) {
                if (!claimedIndex.contains(i) && plainLines.get(i).equals(pending.text)) {
                    match = i;
                }
            }
            // 2) 行是完整文本的前缀（长消息被聊天栏裁成多行时的第一行）
            for (int i = 0; i < plainLines.size() && match < 0; i++) {
                if (!claimedIndex.contains(i) && !plainLines.get(i).isEmpty()
                        && pending.text.startsWith(plainLines.get(i))) {
                    match = i;
                }
            }
            // 3) 最后退回 `<名字>` 前缀
            for (int i = 0; i < plainLines.size() && match < 0; i++) {
                if (!claimedIndex.contains(i) && plainLines.get(i).startsWith(pending.prefix)) {
                    match = i;
                }
            }
            if (match < 0) {
                pending.ticksLeft--;
                return pending.ticksLeft <= 0;
            }
            claimedIndex.add(match);
            applyHead(lines.get(match), pending.headData);
            return true;
        });
    }

    /** 一次性关掉联动（版本不兼容 / 反射失败时）：只警告一次，之后完全静默。 */
    private static void disable(Throwable cause) {
        if (disabled) {
            return;
        }
        disabled = true;
        PENDING.clear();
        WorldPlayers.LOGGER.warn("World Players: Chat Heads 接口与当前版本不兼容，居民聊天头像已停用", cause);
    }

    @SuppressWarnings("unchecked")
    private static List<GuiMessage.Line> lines(ChatComponent chat) {
        try {
            if (trimmedMessagesField == null) {
                trimmedMessagesField = ChatComponent.class.getDeclaredField("trimmedMessages");
                trimmedMessagesField.setAccessible(true);
            }
            return (List<GuiMessage.Line>) trimmedMessagesField.get(chat);
        } catch (ReflectiveOperationException e) {
            WorldPlayers.LOGGER.warn("World Players: 读不到聊天行，居民头像关闭", e);
            return null;
        }
    }

    /** 把头像数据写到具体那一行上（Chat Heads 的 mixin 字段）。 */
    private static boolean applyHead(GuiMessage.Line line, HeadData headData) {
        try {
            Field field = line.getClass().getDeclaredField("chatheads$headData");
            field.setAccessible(true);
            if (field.get(line) == headData) {
                return true;
            }
            field.set(line, headData);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static String plain(net.minecraft.util.FormattedCharSequence sequence) {
        StringBuilder builder = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            builder.appendCodePoint(codePoint);
            return true;
        });
        return builder.toString();
    }

    private static PlayerSkin skinFor(String skinName, String model) {
        String key = skinName + "|" + model;
        if (SKINS.containsKey(key)) {
            return SKINS.get(key);
        }
        if (!model.isEmpty()) {
            PlayerSkin ysm = ysmSkin(model);
            if (ysm != null) {
                SKINS.put(key, ysm);
                return ysm;
            }
        }
        ClientSkins.Skin base = ClientSkins.skin(skinName);
        PlayerSkin skin;
        NativeImage pixels = base.pixels();
        if (pixels == null || pixels.getWidth() < 64 || pixels.getHeight() < 64) {
            // 原版默认皮肤没有本地像素，直接用它的贴图
            skin = new PlayerSkin(base.texture(), null, null, null, PlayerSkin.Model.WIDE, true);
        } else {
            // Chat Heads 按**标准皮肤布局**取头：fallback 路径从 (8,8) 采样 8×8。
            // 所以不能把合成头塞到左上角 (0,0)——那样它采到的全是透明像素，只剩一块空白。
            // 正确做法：复制整张皮肤，再把「第二层优先」的合成头写回标准头区 (8,8)。
            NativeImage merged = new NativeImage(pixels.getWidth(), pixels.getHeight(), true);
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    merged.setPixelRGBA(x, y, pixels.getPixelRGBA(x, y));
                }
            }
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int hat = pixels.getPixelRGBA(40 + x, 8 + y);
                    boolean hatVisible = (0xFF & (hat >> 24)) > 0;
                    merged.setPixelRGBA(8 + x, 8 + y,
                        hatVisible ? hat : pixels.getPixelRGBA(8 + x, 8 + y));
                }
            }
            ResourceLocation id = WorldPlayers.id("chat_head/" + safe(skinName));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(merged));
            skin = new PlayerSkin(id, null, null, null, PlayerSkin.Model.WIDE, true);
        }
        SKINS.put(key, skin);
        return skin;
    }

    /**
     * 用 YSM 模型的贴图做头像。
     *
     * <p>YSM 是 Bedrock 布局，但**头部 32×16 区域和原版皮肤同一位置**（脸在 (8,8)），
     * 所以只要把这块拷进一张 64×64 的皮肤图、再把脸写进帽子层 (40,8)，Chat Heads 就能照常取头。
     * 这样即使居民的皮肤名为空（换成 YSM 模型后常见），也一定有头像。</p>
     */
    private static PlayerSkin ysmSkin(String model) {
        try {
            java.util.Optional<byte[]> bytes = com.worldplayers.compat.ysm.YsmModels.textureBytes(model);
            if (bytes.isEmpty()) {
                return null;
            }
            NativeImage source = NativeImage.read(new java.io.ByteArrayInputStream(bytes.get()));
            if (source.getWidth() < 32 || source.getHeight() < 16) {
                source.close();
                return null;
            }
            NativeImage head = new NativeImage(64, 64, true);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 32; x++) {
                    head.setPixelRGBA(x, y, source.getPixelRGBA(x, y));
                }
            }
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    head.setPixelRGBA(40 + x, 8 + y, source.getPixelRGBA(8 + x, 8 + y));
                }
            }
            source.close();
            ResourceLocation id = WorldPlayers.id("chat_head/ysm_" + safe(model));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(head));
            return new PlayerSkin(id, null, null, null, PlayerSkin.Model.WIDE, true);
        } catch (Throwable broken) {
            return null;
        }
    }

    private static PlayerInfo headInfo(String skinName, PlayerSkin skin) {
        UUID id = UUID.nameUUIDFromBytes(
            ("worldplayers:" + skinName).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(id, skinName.isEmpty() ? "resident" : skinName);
        return new HeadInfo(profile, skin);
    }

    private static String safe(String name) {
        StringBuilder builder = new StringBuilder("head_");
        for (char c : (name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)).toCharArray()) {
            builder.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        return builder.append('_').append(Integer.toHexString(String.valueOf(name).hashCode())).toString();
    }

    /** 只为了给 Chat Heads 提供"这个人的皮肤"，不联网、不查玩家列表。 */
    private static final class HeadInfo extends PlayerInfo {
        private final PlayerSkin skin;

        private HeadInfo(GameProfile profile, PlayerSkin skin) {
            super(profile, false);
            this.skin = skin;
        }

        @Override
        public PlayerSkin getSkin() {
            return this.skin;
        }
    }

    private ResidentChatHeads() {
    }
}
