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
    /** 已经提示过「找不到皮肤」的名字（同一次会话里只提示一次，避免每帧刷日志）。 */
    private static final java.util.Set<String> WARNED = new java.util.HashSet<>();
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

    /** 直接从文件加载皮肤（YSM 模型目录里的 texture.png 用这个）。 */
    public static ResourceLocation skinFromPath(Path file, String cacheKey) {
        if (file == null) {
            return DEFAULT;
        }
        String key = "path:" + cacheKey;
        if (CACHE.containsKey(key)) {
            return CACHE.get(key).texture();
        }
        try {
            return skinFromBytes(Files.readAllBytes(file), cacheKey);
        } catch (IOException e) {
            LOGGER.error("World Players: cannot read skin file {}", file, e);
            CACHE.put(key, DEFAULT_SKIN);
            return DEFAULT;
        }
    }

    /** 直接从字节加载皮肤（YSM 的模型可能在 zip 里，拿不到 Path）。 */
    public static ResourceLocation skinFromBytes(byte[] bytes, String cacheKey) {
        if (bytes == null || bytes.length == 0) {
            return DEFAULT;
        }
        String key = "path:" + cacheKey;
        Skin cached = CACHE.get(key);
        if (cached != null) {
            return cached.texture();
        }
        try {
            NativeImage image = NativeImage.read(new java.io.ByteArrayInputStream(bytes));
            ResourceLocation id = WorldPlayers.id("skins/" + safeName(key));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            CACHE.put(key, new Skin(id, image));
            return id;
        } catch (IOException e) {
            LOGGER.error("World Players: cannot parse YSM texture {}", cacheKey, e);
            CACHE.put(key, DEFAULT_SKIN);
            return DEFAULT;
        }
    }

    /** 正版皮肤刚下载/覆盖了文件：丢掉缓存、用新的纹理 id 重新读一次。 */
    public static void reload(String name) {
        if (name == null || name.isEmpty()) {
            return;
        }
        CACHE.remove(name);
        WARNED.remove(name);
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
            // 2026-11-23 三修：以前找不到皮肤直接返回默认、**不写缓存**，而渲染/列表每帧都调 skin()，
            // 于是每帧读一次盘 + 一条 WARN —— 日志一分钟涨几万行（主人报的刷屏）。
            // 现在把「找不到」也缓存起来（同一次会话只提示一次）。
            Skin fallback = warnMissingOnce(name, "file not found");
            CACHE.put(name, fallback);
            return fallback;
        }
        try (InputStream in = Files.newInputStream(file.get())) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length < 8 || (bytes[0] & 0xFF) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
                LOGGER.error("World Players: skin file is not a PNG: {}", file.get());
                CACHE.put(name, DEFAULT_SKIN);
                return DEFAULT_SKIN;
            }
            NativeImage image = NativeImage.read(new java.io.ByteArrayInputStream(bytes));
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(image));
            Skin skin = new Skin(id, image);
            CACHE.put(name, skin);
            return skin;
        } catch (IOException e) {
            LOGGER.error("World Players: cannot read skin file {}", file.get(), e);
            CACHE.put(name, DEFAULT_SKIN);
            return DEFAULT_SKIN;
        }
    }

    /** 「找不到皮肤」只提示一次（每次会话 / 每个名字），并且用英文，控制台编码不对时也不会变成问号。 */
    private static Skin warnMissingOnce(String name, String reason) {
        if (WARNED.add(name)) {
            LOGGER.warn("World Players: skin '{}' not found ({}), using default skin", name, reason);
        }
        return DEFAULT_SKIN;
    }

    private ClientSkins() {
    }
}
