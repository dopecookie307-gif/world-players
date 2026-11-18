package com.worldplayers.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentSkins;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/** 客户端皮肤解析：皮肤名 → customs/defaults 里的 PNG → 动态纹理。 */
public final class ClientSkins {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceLocation DEFAULT =
        ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");
    private static final Skin DEFAULT_SKIN = new Skin(DEFAULT, null);
    private static final Map<String, Skin> CACHE = new HashMap<>();
    private static int reloads;

    /** texture = 渲染用纹理；pixels = 3DSL 生成网格用的像素（原版默认皮肤为 null）。 */
    public record Skin(ResourceLocation texture, NativeImage pixels) {
    }

    public static Skin skin(String name) {
        if (name == null || name.isEmpty()) {
            return DEFAULT_SKIN;
        }
        Skin cached = CACHE.get(name);
        if (cached != null) {
            return cached;
        }
        return load(name, WorldPlayers.id("skins/" + safeName(name)));
    }

    /** 正版皮肤刚下载/覆盖了文件：丢掉缓存、用新的纹理 id 重新读一次。 */
    public static void reload(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        CACHE.remove(name);
        load(name, WorldPlayers.id("skins/" + safeName(name) + "_r" + (++reloads)));
    }

    /** ResourceLocation 只允许 [a-z0-9_-]，中文/空格要过滤掉，再附 hash 保证唯一。 */
    private static String safeName(String name) {
        StringBuilder builder = new StringBuilder("skin_");
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            builder.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        return builder.append('_').append(Integer.toHexString(name.hashCode())).toString();
    }

    private static Skin load(String name, ResourceLocation id) {
        Optional<Path> file = ResidentSkins.locate(name);
        if (file.isEmpty()) {
            LOGGER.warn("World Players: 找不到皮肤 {}", name);
            return DEFAULT_SKIN;
        }
        try (InputStream in = Files.newInputStream(file.get())) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length < 8 || (bytes[0] & 0xFF) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
                LOGGER.error("World Players: {} 不是 PNG（请另存为 PNG）", file.get());
                return DEFAULT_SKIN;
            }
            NativeImage image = NativeImage.read(new java.io.ByteArrayInputStream(bytes));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            Skin skin = new Skin(id, image);
            CACHE.put(name, skin);
            return skin;
        } catch (IOException e) {
            LOGGER.error("World Players: 读取皮肤 {} 失败", file.get(), e);
            return DEFAULT_SKIN;
        }
    }

    private ClientSkins() {
    }
}
