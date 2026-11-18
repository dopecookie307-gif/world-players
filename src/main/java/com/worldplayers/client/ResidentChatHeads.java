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

    /** 等待"消息真的进了聊天栏"的补丁请求。 */
    private static final class Pending {
        private final String prefix;
        private final HeadData headData;
        private int ticksLeft;

        private Pending(String prefix, HeadData headData, int ticksLeft) {
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
        Component message = event.getMessage();
        String insertion = message.getStyle() == null ? null : message.getStyle().getInsertion();
        if (insertion == null || !insertion.startsWith(MARK)) {
            return;
        }
        String skinName = insertion.substring(MARK.length());
        PlayerSkin skin = skinFor(skinName);
        if (skin == null) {
            return;
        }
        HeadData headData = new HeadData(headInfo(skinName, skin), 1);
        // fallback：如果 Chat Heads 这次没有覆盖，用它自己那条路也能出图
        ChatHeads.lastSenderData = headData;

        // 主路径：记下这一行的开头，等它真的进了 trimmedMessages 再写头像
        String text = message.getString();
        int end = text.indexOf('>');
        String prefix = end > 0 ? text.substring(0, end + 1) : text;
        PENDING.add(new Pending(prefix, headData, 4));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
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
        PENDING.removeIf(pending -> {
            for (GuiMessage.Line line : lines) {
                if (!plain(line.content()).startsWith(pending.prefix)) {
                    continue;
                }
                if (applyHead(line, pending.headData)) {
                    return true;
                }
            }
            pending.ticksLeft--;
            return pending.ticksLeft <= 0;
        });
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

    private static PlayerSkin skinFor(String skinName) {
        if (SKINS.containsKey(skinName)) {
            return SKINS.get(skinName);
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
        SKINS.put(skinName, skin);
        return skin;
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
