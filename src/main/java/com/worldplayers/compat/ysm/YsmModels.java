package com.worldplayers.compat.ysm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.worldplayers.WorldPlayers;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.util.RandomSource;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Yes Steve Model（YSM）软兼容：模型发现 + 元数据读取。
 *
 * <p>YSM 的模型有三种放法（官方 wiki「模型格式说明」）：**文件夹**、**压缩包（.zip）**、
 * **加密包（.ysm）**。这里支持前两种：</p>
 * <ul>
 *   <li><b>文件夹</b>：目录里有 {@code ysm.json}（spec 2），或者老格式（根/ {@code models/} 下有 {@code main.json} + 一张 PNG）；</li>
 *   <li><b>.zip</b>：文件夹格式的直接打包，<b>不解压</b>，用 {@link ZipFile} 直接读里面的 ysm.json / 几何 / 贴图；</li>
 *   <li><b>.ysm</b>：加密容器（官方特意设计成不可读、防模型被改），**不解析**，只在日志里提示数量。</li>
 * </ul>
 *
 * <p>ysm.json 的关键字段：{@code metadata.name}（显示名，可能带 § 颜色符）、
 * {@code properties.height_scale / width_scale}、{@code files.player.model.main}、
 * {@code files.player.texture}（可能是字符串数组，也可能是 {@code [{"uv": "textures/x.png"}]} 这种对象数组）。</p>
 */
public final class YsmModels {
    private static final String MOD_ID = "yes_steve_model";
    private static final List<String> CANDIDATES = List.of("custom", "builtin", "players", "auth", "export");
    private static final int MAX_DEPTH = 6;
    private static final String YSM_JSON = "ysm.json";

    /**
     * 一个 YSM 模型。{@code dir} 与 {@code zip} 二选一；{@code base} 是模型在 zip 内的目录前缀（目录模型为空）。
     * 几何 / 贴图路径都相对 {@code base}。
     */
    public record Model(String name, Path dir, Path zip, String base,
                        String geometryPath, String texturePath,
                        float heightScale, float widthScale) {
        public boolean inZip() {
            return zip != null;
        }

        /** 给重名模型去重用：目录名 / zip 名（+ 包内子目录）。 */
        public String label() {
            String owner = zip != null ? zip.getFileName().toString()
                : (dir == null ? "?" : dir.getFileName().toString());
            return base.isEmpty() ? owner : owner + "/" + base.replaceAll("/+$", "");
        }

        /** 读模型里的一个文件（相对 {@code base}）。 */
        public byte[] read(String relative) throws IOException {
            String path = base + relative;
            if (zip != null) {
                try (ZipFile file = new ZipFile(zip.toFile())) {
                    ZipEntry entry = file.getEntry(path);
                    if (entry == null) {
                        String lower = path.toLowerCase(Locale.ROOT);
                        for (ZipEntry candidate : java.util.Collections.list(file.entries())) {
                            if (candidate.getName().toLowerCase(Locale.ROOT).equals(lower)) {
                                entry = candidate;
                                break;
                            }
                        }
                    }
                    if (entry == null) {
                        throw new IOException("压缩包里找不到 " + path);
                    }
                    try (InputStream in = file.getInputStream(entry)) {
                        return in.readAllBytes();
                    }
                }
            }
            return Files.readAllBytes(dir.resolve(path));
        }

        public String readText(String relative) throws IOException {
            return new String(read(relative), StandardCharsets.UTF_8);
        }
    }

    private static long cacheTime;
    private static List<Model> cache = List.of();
    private static final Map<String, Model> BY_NAME = new LinkedHashMap<>();
    private static String lastSkippedLog = "";

    public static boolean available() {
        return ModList.get().isLoaded(MOD_ID);
    }

    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve(MOD_ID);
    }

    /** 所有可用模型（按显示名排序），结果缓存 5 秒。 */
    public static List<Model> models() {
        long now = System.currentTimeMillis();
        if (now - cacheTime < 5000L) {
            return cache;
        }
        cacheTime = now;
        Map<String, Model> found = new LinkedHashMap<>();
        int encrypted = 0;
        Path root = root();
        for (String candidate : CANDIDATES) {
            encrypted += collect(root.resolve(candidate), found, 0);
        }
        if (found.isEmpty()) {
            encrypted += collect(root, found, 0);
        }
        List<Model> list = new ArrayList<>(found.values());
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        // 显示名唯一化：同名的补上来源（目录名 / zip 名），实体里只存名字也不会混
        Map<String, Integer> seen = new LinkedHashMap<>();
        List<Model> unique = new ArrayList<>(list.size());
        for (Model model : list) {
            int count = seen.merge(model.name(), 1, Integer::sum);
            String name = count == 1 ? model.name() : model.name() + " (" + model.label() + ")";
            unique.add(new Model(name, model.dir(), model.zip(), model.base(),
                model.geometryPath(), model.texturePath(), model.heightScale(), model.widthScale()));
        }
        BY_NAME.clear();
        for (Model model : unique) {
            BY_NAME.put(model.name(), model);
        }
        if (encrypted > 0) {
            String note = "encrypted:" + encrypted;
            if (!note.equals(lastSkippedLog)) {
                lastSkippedLog = note;
                WorldPlayers.LOGGER.info("World Players: 跳过 {} 个加密 .ysm 模型（不支持解密）", encrypted);
            }
        }
        cache = List.copyOf(unique);
        return cache;
    }

    /** 显示名列表（手册 UI 用）。 */
    public static List<String> names() {
        List<Model> all = models();
        List<String> list = new ArrayList<>(all.size());
        for (Model model : all) {
            list.add(model.name());
        }
        return list;
    }

    public static Optional<Model> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        models();
        return Optional.ofNullable(BY_NAME.get(name));
    }

    /** 模型名字 → 皮肤贴图的原始字节（渲染时注册成动态纹理）。 */
    public static Optional<byte[]> textureBytes(String name) {
        Optional<Model> model = find(name);
        if (model.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(model.get().read(model.get().texturePath()));
        } catch (Exception error) {
            WorldPlayers.LOGGER.warn("World Players: 读取 YSM 贴图失败 {}：{}", name, error.toString());
            return Optional.empty();
        }
    }

    /** 随机挑一个模型名（没有模型返回空）。 */
    public static Optional<String> random(RandomSource random) {
        List<Model> all = models();
        if (all.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(all.get(random.nextInt(all.size())).name());
    }

    public static void logAvailability() {
        if (!available()) {
            return;
        }
        WorldPlayers.LOGGER.info("World Players: Yes Steve Model 已安装，读到 {} 个模型", models().size());
    }

    // ---------------------------------------------------------------- 发现

    /** 返回跳过的加密模型数量。 */
    private static int collect(Path dir, Map<String, Model> out, int depth) {
        if (!Files.isDirectory(dir) || depth > MAX_DEPTH) {
            return 0;
        }
        List<Path> children;
        try (Stream<Path> stream = Files.list(dir)) {
            children = stream.toList();
        } catch (Exception ignored) {
            return 0;
        }
        int encrypted = 0;
        for (Path child : children) {
            String fileName = child.getFileName().toString().toLowerCase(Locale.ROOT);
            if (Files.isDirectory(child)) {
                if ("cache".equals(fileName)) {
                    // cache 里全是加密缓存，没有明文模型
                    continue;
                }
                Model model = readDir(child);
                if (model != null) {
                    if (!isExcluded(model)) {
                        out.put(model.label(), model);
                    }
                } else {
                    encrypted += collect(child, out, depth + 1);
                }
            } else if (fileName.endsWith(".zip")) {
                for (Model model : readZip(child)) {
                    if (!isExcluded(model)) {
                        out.put(model.label(), model);
                    }
                }
            } else if (fileName.endsWith(".ysm")) {
                encrypted++;
            }
        }
        return encrypted;
    }

    /** 读一个目录，是模型就返回（否则 null）。 */
    @javax.annotation.Nullable
    private static Model readDir(Path dir) {
        Path ysmJson = dir.resolve(YSM_JSON);
        if (Files.isRegularFile(ysmJson)) {
            try {
                return parseSpec(Files.readString(ysmJson, StandardCharsets.UTF_8), "", dir, null,
                    dir.getFileName().toString());
            } catch (Exception error) {
                WorldPlayers.LOGGER.warn("World Players: 读取 YSM 模型失败 {}：{}", ysmJson, error.toString());
            }
        }
        // 老格式：main.json（根或 models/）+ 任意 PNG
        String geometry = null;
        if (Files.isRegularFile(dir.resolve("main.json"))) {
            geometry = "main.json";
        } else if (Files.isRegularFile(dir.resolve("models").resolve("main.json"))) {
            geometry = "models/main.json";
        }
        if (geometry == null) {
            return null;
        }
        String texture = findTextureInDir(dir);
        if (texture == null) {
            return null;
        }
        return new Model(dir.getFileName().toString(), dir, null, "", geometry, texture, 0.0F, 0.0F);
    }

    /** 读一个 .zip：支持单模型（根 ysm.json）、模型包（多个子目录 ysm.json）、老格式（main.json）。 */
    private static List<Model> readZip(Path zipFile) {
        List<Model> out = new ArrayList<>();
        List<String> names;
        try (ZipFile file = new ZipFile(zipFile.toFile())) {
            names = file.stream().map(ZipEntry::getName).toList();
            for (String entryName : names) {
                String lower = entryName.toLowerCase(Locale.ROOT);
                if (!lower.equals(YSM_JSON) && !lower.endsWith("/" + YSM_JSON)) {
                    continue;
                }
                String base = entryName.substring(0, entryName.length() - YSM_JSON.length());
                try (InputStream in = file.getInputStream(file.getEntry(entryName))) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Model model = parseSpec(text, base, null, zipFile, zipBaseName(zipFile));
                    if (model != null) {
                        out.add(model);
                    }
                } catch (Exception error) {
                    WorldPlayers.LOGGER.warn("World Players: 读取压缩包模型失败 {}：{}", entryName, error.toString());
                }
            }
            if (out.isEmpty()) {
                for (String entryName : names) {
                    String lower = entryName.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith("main.json")) {
                        continue;
                    }
                    String base = entryName.substring(0, entryName.length() - "main.json".length());
                    String texture = findTextureInEntries(names, base);
                    if (texture != null) {
                        out.add(new Model(zipBaseName(zipFile), null, zipFile, base,
                            "main.json", texture, 1.0F, 1.0F));
                        break;
                    }
                }
            }
        } catch (Exception error) {
            WorldPlayers.LOGGER.warn("World Players: 打开压缩包失败 {}：{}", zipFile, error.toString());
        }
        return out;
    }

    private static String zipBaseName(Path zipFile) {
        String name = zipFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** 解析 ysm.json（spec 2）；任何必需字段缺失返回 null。 */
    @javax.annotation.Nullable
    private static Model parseSpec(String json, String base, Path dir, Path zip, String fallbackName) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("files") || !root.getAsJsonObject("files").has("player")) {
            return null;
        }
        JsonObject player = root.getAsJsonObject("files").getAsJsonObject("player");
        if (!player.has("model")) {
            return null;
        }
        JsonObject model = player.getAsJsonObject("model");
        String main = model.has("main") ? model.get("main").getAsString() : "models/main.json";
        String texture = textureFrom(player);
        if (texture == null) {
            return null;
        }
        // 0 = ysm.json 没写 → 由 YsmGeometry 按 Head 骨骼的高度自动归一到 BIPED 尺寸
        float height = 0.0F;
        float width = 0.0F;
        if (root.has("properties")) {
            JsonObject props = root.getAsJsonObject("properties");
            if (props.has("height_scale")) {
                height = props.get("height_scale").getAsFloat();
            }
            if (props.has("width_scale")) {
                width = props.get("width_scale").getAsFloat();
            }
        }
        String name = fallbackName;
        if (root.has("metadata") && root.getAsJsonObject("metadata").has("name")) {
            String cleaned = stripFormatting(root.getAsJsonObject("metadata").get("name").getAsString());
            if (!cleaned.isBlank()) {
                name = cleaned;
            }
        }
        return new Model(name, dir, zip, base, main, texture, height, width);
    }

    /**
     * texture 字段有两种写法：
     * {@code ["textures/a.png", "textures/b.png"]} 或 {@code [{"uv": "textures/a.png"}]}。
     */
    @javax.annotation.Nullable
    private static String textureFrom(JsonObject player) {
        if (!player.has("texture") || !player.get("texture").isJsonArray()) {
            return null;
        }
        JsonArray array = player.getAsJsonArray("texture");
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                return element.getAsString();
            }
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                if (object.has("uv")) {
                    return object.get("uv").getAsString();
                }
                for (String key : object.keySet()) {
                    JsonElement value = object.get(key);
                    if (value.isJsonPrimitive() && value.getAsString().toLowerCase(Locale.ROOT).endsWith(".png")) {
                        return value.getAsString();
                    }
                }
            }
        }
        return null;
    }

    /** 目录里的贴图：texture.png → skin.png → def.png → textures/ 或根目录下第一张 PNG。 */
    @javax.annotation.Nullable
    private static String findTextureInDir(Path dir) {
        for (String name : List.of("texture.png", "skin.png", "def.png")) {
            if (Files.isRegularFile(dir.resolve(name))) {
                return name;
            }
        }
        for (Path sub : List.of(dir.resolve("textures"), dir)) {
            if (!Files.isDirectory(sub)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(sub)) {
                Optional<Path> png = stream
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted()
                    .findFirst();
                if (png.isPresent()) {
                    Path relative = dir.relativize(png.get());
                    return relative.toString().replace('\\', '/');
                }
            } catch (Exception ignored) {
                // 忽略
            }
        }
        return null;
    }

    /** zip 条目里的贴图，逻辑同 {@link #findTextureInDir}。 */
    @javax.annotation.Nullable
    private static String findTextureInEntries(List<String> names, String base) {
        for (String candidate : List.of("texture.png", "skin.png", "def.png")) {
            if (names.contains(base + candidate)) {
                return candidate;
            }
        }
        String texturesPrefix = base + "textures/";
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(texturesPrefix.toLowerCase(Locale.ROOT)) && lower.endsWith(".png")) {
                return name.substring(base.length());
            }
        }
        for (String name : names) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(base.toLowerCase(Locale.ROOT)) && lower.endsWith(".png")
                && !name.substring(base.length()).contains("/")) {
                return name.substring(base.length());
            }
        }
        return null;
    }

    /**
     * 主人定的规矩：YSM 自带的默认模型 Alex / Steve 不进可选列表（它们就是原版玩家，没有意义）；
     * **「Default Controllers Model」这类控制器模型也别放进来**（主人 2026-11-20：它们是给动画控制用的
     * Q 版大头，选中后渲染出来就是一个巨大的脑袋）。
     */
    private static boolean isExcluded(Model model) {
        String name = model.name() == null ? "" : model.name().toLowerCase(Locale.ROOT).trim();
        String label = model.label().toLowerCase(Locale.ROOT);
        if (name.equals("alex") || name.equals("steve") || name.startsWith("alex ") || name.startsWith("steve ")) {
            return true;
        }
        if (name.equals("alex（艾利克斯）") || name.equals("steve（史蒂夫）")) {
            return true;
        }
        if (name.contains("controller") || label.contains("controller")) {
            return true;
        }
        return label.equals("1_alex") || label.equals("2_steve")
            || label.endsWith("/1_alex") || label.endsWith("/2_steve");
    }

    /** 去掉 §x 颜色符（模型作者常用它给名字上色）。 */
    private static String stripFormatting(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    private YsmModels() {
    }
}
