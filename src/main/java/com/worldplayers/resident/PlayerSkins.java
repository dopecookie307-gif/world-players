package com.worldplayers.resident;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.worldplayers.WorldPlayers;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 正版账号皮肤：按玩家 ID 去 Mojang 查**一次**，把皮肤 PNG 下载下来存进
 * {@code config/worldplayers/skins/customs/}，之后居民就当成普通皮肤文件用。
 *
 * <p>这样做的好处：渲染 / 3DSL 3D 层 / 聊天头像全都能复用现有皮肤管线，
 * 而且图片留在本地，账号主人以后换皮肤也不会影响已经取用的居民。</p>
 *
 * <p>文件名规则 {@code player_<小写字母数字>_<hash>}：同一个 ID 再探测会覆盖同一个文件，
 * 不同 ID 是不同的文件，所以多个居民互不覆盖。</p>
 */
public final class PlayerSkins {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String NAME_API = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String PROFILE_API = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** ID → 皮肤文件名（不带 .png）：直接用正版 ID 本身，方便在文件夹里一眼认出来。 */
    public static String fileName(String username) {
        String trimmed = username == null ? "" : username.trim();
        StringBuilder builder = new StringBuilder();
        for (char c : trimmed.toCharArray()) {
            boolean legal = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
            builder.append(legal ? c : '_');
        }
        return builder.length() == 0 ? "player" : builder.toString();
    }

    /** 查一次正版皮肤：用户名 → UUID → 皮肤 URL → PNG 字节。失败抛异常。 */
    public static CompletableFuture<byte[]> fetch(String username) {
        String encoded = URLEncoder.encode(username.trim(), StandardCharsets.UTF_8);
        return CLIENT.sendAsync(get(NAME_API + encoded), HttpResponse.BodyHandlers.ofString())
            .thenCompose(nameResponse -> {
                if (nameResponse.statusCode() != 200) {
                    throw new CompletionException(new IOException("找不到正版账号 " + username));
                }
                JsonObject name = JsonParser.parseString(nameResponse.body()).getAsJsonObject();
                String uuid = name.get("id").getAsString();
                return CLIENT.sendAsync(get(PROFILE_API + uuid), HttpResponse.BodyHandlers.ofString());
            })
            .thenCompose(profileResponse -> {
                Optional<String> url = profileResponse.statusCode() == 200
                    ? skinUrl(profileResponse.body())
                    : Optional.empty();
                if (url.isEmpty()) {
                    throw new CompletionException(new IOException("账号没有可用皮肤"));
                }
                return CLIENT.sendAsync(get(url.get()), HttpResponse.BodyHandlers.ofByteArray());
            })
            .thenApply(skinResponse -> {
                byte[] png = skinResponse.body();
                if (skinResponse.statusCode() != 200 || png == null || png.length < 8
                        || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
                    throw new CompletionException(new IOException("皮肤文件无效"));
                }
                return png;
            });
    }

    /** 写进 customs 皮肤池（服务端和客户端都写自己那份 config）。 */
    public static void save(String fileName, byte[] png) throws IOException {
        Path dir = ResidentSkins.customs();
        Files.createDirectories(dir);
        Path target = dir.resolve(fileName + ".png");
        Path tmp = dir.resolve(fileName + ".png.tmp");
        Files.write(tmp, png);
        Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static Optional<String> skinUrl(String profileJson) {
        JsonObject root = JsonParser.parseString(profileJson).getAsJsonObject();
        JsonArray properties = root.getAsJsonArray("properties");
        if (properties == null) {
            return Optional.empty();
        }
        for (JsonElement element : properties) {
            JsonObject property = element.getAsJsonObject();
            if (!"textures".equals(property.get("name").getAsString())) {
                continue;
            }
            String decoded = new String(
                Base64.getDecoder().decode(property.get("value").getAsString()), StandardCharsets.UTF_8);
            JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject().getAsJsonObject("textures");
            if (textures == null || !textures.has("SKIN")) {
                return Optional.empty();
            }
            return Optional.of(textures.getAsJsonObject("SKIN").get("url").getAsString());
        }
        return Optional.empty();
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build();
    }

    private PlayerSkins() {
    }
}
