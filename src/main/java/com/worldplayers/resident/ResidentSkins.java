package com.worldplayers.resident;

import com.worldplayers.WorldPlayers;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.util.RandomSource;
import net.neoforged.fml.loading.FMLPaths;

/**
 * 居民皮肤池。
 *
 * <pre>
 *   config/worldplayers/skins/defaults/   内置默认皮肤（9 张：8 wide + alex 的 slim），随机生成只从这里抽
 *   config/worldplayers/skins/customs/    玩家自己丢进来的皮肤，列表里排最前，但不会被随机抽中
 * </pre>
 *
 * <p>启动时只补缺失的内置皮肤、不覆盖已有文件；同时会清掉上一版内置里被移除的
 * slim 皮肤（2026-11-17 起 slim 只保留 alex 一张）。</p>
 */
public final class ResidentSkins {
    /** jar 内置的默认皮肤：8 张 wide + 只有 alex 保留 slim。 */
    public static final List<String> BUILT_INS = List.of(
        "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri",
        "alex_slim");

    /** 旧版内置、现已移除（启动时从 defaults/ 里删掉，免得老存档残留）。 */
    private static final List<String> REMOVED_BUILT_INS = List.of(
        "alex", "ari_slim", "efe_slim", "kai_slim", "makena_slim",
        "noor_slim", "steve_slim", "sunny_slim", "zuri_slim");

    private static final String RESOURCE_ROOT = "/assets/worldplayers/skins/";
    private static final String PNG = ".png";

    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve(WorldPlayers.MOD_ID).resolve("skins");
    }

    public static Path defaults() {
        return root().resolve("defaults");
    }

    public static Path customs() {
        return root().resolve("customs");
    }

    /** 启动时准备目录、迁移旧版散落在 skins/ 下的皮肤、补齐内置默认皮肤（已存在的不覆盖）。 */
    public static void installDefaults() {
        Path defaults = defaults();
        Path customs = customs();
        try {
            Files.createDirectories(defaults);
            Files.createDirectories(customs);
            migrateLegacy(defaults);
        } catch (IOException e) {
            WorldPlayers.LOGGER.error("World Players: 无法创建皮肤池目录", e);
            return;
        }
        for (String name : REMOVED_BUILT_INS) {
            if (!BUILT_INS.contains(name)) {
                try {
                    Files.deleteIfExists(defaults.resolve(name + PNG));
                } catch (IOException e) {
                    WorldPlayers.LOGGER.warn("World Players: 删除旧内置皮肤 {} 失败", name, e);
                }
            }
        }
        for (String name : BUILT_INS) {
            Path target = defaults.resolve(name + PNG);
            if (Files.isRegularFile(target)) {
                continue;
            }
            try (InputStream in = ResidentSkins.class.getResourceAsStream(RESOURCE_ROOT + name + PNG)) {
                if (in == null) {
                    WorldPlayers.LOGGER.warn("World Players: jar 内缺少默认皮肤 {}", name);
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                WorldPlayers.LOGGER.error("World Players: 写入默认皮肤 {} 失败", name, e);
            }
        }
    }

    /** 旧版把皮肤铺在 skins/ 根目录，这里搬进 defaults/。 */
    private static void migrateLegacy(Path defaults) throws IOException {
        Path root = root();
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> legacy;
        try (Stream<Path> files = Files.list(root)) {
            legacy = files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(PNG))
                .toList();
        }
        for (Path file : legacy) {
            Path target = defaults.resolve(file.getFileName().toString());
            if (!Files.exists(target)) {
                Files.move(file, target);
            }
        }
    }

    /** 默认池（随机生成用），按文件名排序。 */
    public static List<String> defaultsPool() {
        return listPng(defaults());
    }

    /** 自定义池，按文件名排序。 */
    public static List<String> customsPool() {
        return listPng(customs());
    }

    /** GUI 列表顺序：自定义在前、默认在后。 */
    public static List<String> all() {
        List<String> names = new ArrayList<>(customsPool());
        names.addAll(defaultsPool());
        return names;
    }

    /** 找皮肤文件：customs 优先，其次 defaults。 */
    public static Optional<Path> locate(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Path custom = customs().resolve(name + PNG);
        if (Files.isRegularFile(custom)) {
            return Optional.of(custom);
        }
        Path builtin = defaults().resolve(name + PNG);
        if (Files.isRegularFile(builtin)) {
            return Optional.of(builtin);
        }
        return Optional.empty();
    }

    /** 生成居民时的真随机：只从默认池里抽。池子空则返回空（渲染会落到 steve）。 */
    public static Optional<String> pickRandom(RandomSource random) {
        List<String> names = defaultsPool();
        if (names.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(names.get(random.nextInt(names.size())));
    }

    private static List<String> listPng(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(file -> file.toLowerCase(Locale.ROOT).endsWith(PNG))
                .map(file -> file.substring(0, file.length() - PNG.length()))
                .sorted(Comparator.naturalOrder())
                .toList();
        } catch (IOException e) {
            WorldPlayers.LOGGER.error("World Players: 读取皮肤池 {} 失败", dir, e);
            return List.of();
        }
    }

    private ResidentSkins() {
    }
}
