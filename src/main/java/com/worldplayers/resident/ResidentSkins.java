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
 *   config/worldplayers/skins/defaults/   内置默认皮肤（9 张：8 wide + alex 的 slim）
 *   config/worldplayers/skins/randoms/    内置随机皮肤（55 张，打进 jar，首次运行自动铺出来）
 *   config/worldplayers/skins/customs/    玩家自己丢进来的皮肤，列表里排最前
 * </pre>
 *
 * <p>随机生成 / 招募居民时从 **customs + randoms + defaults 三池合并** 后均匀抽
 * （2026-11-22 主人要求 customs 也参与随机）。启动时只补缺失的内置皮肤、不覆盖已有文件；
 * 同时会清掉上一版默认池里被移除的 slim 皮肤（2026-11-17 起默认 slim 只保留 alex 一张）。</p>
 */
public final class ResidentSkins {
    /** jar 内置的默认皮肤：8 张 wide + 只有 alex 保留 slim。 */
    public static final List<String> DEFAULT_BUILT_INS = List.of(
        "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri",
        "alex_slim");

    /**
     * jar 内置的随机皮肤（2026-11-22 从实例 customs 原样收编，保持原文件名）。
     * <p>文件名末尾是 {@code _slim} 的按成人 slim 渲染，其余按 wide——包括
     * {@code alexchan}、{@code CuteGirlQiQi} 等本来不带后缀的自定义皮肤。</p>
     */
    public static final List<String> RANDOM_BUILT_INS = List.of(
        "5_ge_slim", "Ai_Yu_Anug_slim", "alexchan", "alexchan_summer_slim",
        "bi_yue_hu", "boyblack", "boy_black_slim", "boy_fadered",
        "boy_flannel_slim", "boy_greenblack", "boy_grey_slim", "boy_pink_slim",
        "boy_white", "boy_yellow_slim", "cashier_slim", "chickendino_slim",
        "creepergirl1_slim", "creepergirl2_slim", "creepergirl3_slim", "creepergirl4_slim",
        "Cuptakat1_slim", "Cuptakat2_slim", "Cuptakat3_slim", "Cuptakat4_slim",
        "Cuptakat5_slim", "Cuptakat6_slim", "CuteGirlQiQi", "dachengzi1_slim",
        "dachengzi2_slim", "darkgirl_black_slim", "darkgirl_pastel_slim", "darkgirl_white_slim",
        "deepseek_slim", "GeorgeNotFound", "girlcap", "girlombre",
        "girl_black_slim", "girl_blue_slim", "girl_chrismas_slim", "girl_pastel_slim",
        "girl_pinkcat_slim", "girl_pinkschool_slim", "guard-villager", "Kaqiu_slim",
        "Melor_", "Muchuanbei", "pinkboy_slim", "Si_Rui",
        "Uncle_Red", "vensgr0307_slim", "vensgr_endmin_slim", "WHITEPOW_DER_slim",
        "xiaozongz_slim", "yecheyyy_slim", "Zi_Min");

    /** 旧版默认池、现已移除（启动时从 defaults/ 里删掉，免得老存档残留；randoms 不受影响）。 */
    private static final List<String> REMOVED_BUILT_INS = List.of(
        "alex", "ari_slim", "efe_slim", "kai_slim", "makena_slim",
        "noor_slim", "steve_slim", "sunny_slim", "zuri_slim");

    private static final String RESOURCE_ROOT = "/assets/worldplayers/skins/";
    private static final String PNG = ".png";

    /**
     * randoms 池的「已安装」标记（2026-11-22 主人定）：第一次启动铺 55 张，之后永不再补，
     * 删掉的文件就是不补；想恢复就删掉这个标记文件，下次启动只补缺失的。
     */
    private static final String RANDOMS_MARKER = ".randoms_installed";

    /** 目录列表缓存时长：1 分钟（主人 2026-11-22 定，避免皮肤栏每帧扫盘）。 */
    private static final long POOL_CACHE_MS = 60_000L;

    private static final Pool DEFAULTS_POOL = new Pool();
    private static final Pool RANDOMS_POOL = new Pool();
    private static final Pool CUSTOMS_POOL = new Pool();

    /** 一个皮肤目录的缓存：1 分钟内重复读直接用上次结果。 */
    private static final class Pool {
        private List<String> names;
        private long loadedAt;

        synchronized List<String> get(Path dir) {
            long now = System.currentTimeMillis();
            if (names == null || now - loadedAt >= POOL_CACHE_MS) {
                names = listPng(dir);
                loadedAt = now;
            }
            return names;
        }

        synchronized void invalidate() {
            names = null;
        }
    }

    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve(WorldPlayers.MOD_ID).resolve("skins");
    }

    public static Path defaults() {
        return root().resolve("defaults");
    }

    public static Path randoms() {
        return root().resolve("randoms");
    }

    public static Path customs() {
        return root().resolve("customs");
    }

    /** 启动时准备目录、迁移旧版散落在 skins/ 下的皮肤、补齐内置默认与随机皮肤。 */
    public static void installDefaults() {
        Path root = root();
        Path defaults = defaults();
        Path randoms = randoms();
        Path customs = customs();
        boolean firstRandomsInstall;
        try {
            Files.createDirectories(defaults);
            Files.createDirectories(randoms);
            Files.createDirectories(customs);
            migrateLegacy(defaults);
            // randoms 只在第一次（还没有标记）铺 55 张；之后无论删了什么都不补。
            firstRandomsInstall = !Files.isRegularFile(root.resolve(RANDOMS_MARKER));
        } catch (IOException e) {
            WorldPlayers.LOGGER.error("World Players: 无法创建皮肤池目录", e);
            return;
        }
        for (String name : REMOVED_BUILT_INS) {
            if (!DEFAULT_BUILT_INS.contains(name)) {
                try {
                    Files.deleteIfExists(defaults.resolve(name + PNG));
                } catch (IOException e) {
                    WorldPlayers.LOGGER.warn("World Players: 删除旧内置皮肤 {} 失败", name, e);
                }
            }
        }
        // defaults 每次启动都补齐（缺哪张补哪张，不覆盖）
        copyBuiltIns(DEFAULT_BUILT_INS, defaults, "");
        if (firstRandomsInstall) {
            if (copyBuiltIns(RANDOM_BUILT_INS, randoms, "randoms/")) {
                try {
                    Files.writeString(root.resolve(RANDOMS_MARKER),
                        "World Players randoms pool installed. Delete this file to let the mod fill in missing randoms again.\n");
                } catch (IOException e) {
                    WorldPlayers.LOGGER.warn("World Players: 写入 randoms 标记失败（下次启动会重试铺池）", e);
                }
            } else {
                WorldPlayers.LOGGER.warn("World Players: randoms 池没有铺完整，下次启动会重试");
            }
        }
        DEFAULTS_POOL.invalidate();
        RANDOMS_POOL.invalidate();
        CUSTOMS_POOL.invalidate();
    }

    /** 把 jar 里的内置皮肤铺到目标目录：只补缺失文件，不覆盖玩家改过的版本。返回是否全部成功。 */
    private static boolean copyBuiltIns(List<String> names, Path targetDir, String resourceFolder) {
        boolean ok = true;
        for (String name : names) {
            Path target = targetDir.resolve(name + PNG);
            if (Files.isRegularFile(target)) {
                continue;
            }
            try (InputStream in = ResidentSkins.class.getResourceAsStream(
                    RESOURCE_ROOT + resourceFolder + name + PNG)) {
                if (in == null) {
                    WorldPlayers.LOGGER.warn("World Players: jar 内缺少皮肤 {}", resourceFolder + name);
                    ok = false;
                    continue;
                }
                Files.copy(in, target);
            } catch (IOException e) {
                WorldPlayers.LOGGER.error("World Players: 写入皮肤 {} 失败", resourceFolder + name, e);
                ok = false;
            }
        }
        return ok;
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

    /** 默认池（随机生成用），按文件名排序；1 分钟目录缓存。 */
    public static List<String> defaultsPool() {
        return DEFAULTS_POOL.get(defaults());
    }

    /** 内置随机池，按文件名排序；1 分钟目录缓存。 */
    public static List<String> randomsPool() {
        return RANDOMS_POOL.get(randoms());
    }

    /** 自定义池，按文件名排序；1 分钟目录缓存。 */
    public static List<String> customsPool() {
        return CUSTOMS_POOL.get(customs());
    }

    /** GUI 列表顺序：自定义在前、内置随机居中、默认在后。 */
    public static List<String> all() {
        List<String> names = new ArrayList<>(customsPool());
        names.addAll(randomsPool());
        names.addAll(defaultsPool());
        return names;
    }

    /** 找皮肤文件：customs 优先，其次 randoms，最后 defaults。 */
    public static Optional<Path> locate(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Path custom = customs().resolve(name + PNG);
        if (Files.isRegularFile(custom)) {
            return Optional.of(custom);
        }
        Path random = randoms().resolve(name + PNG);
        if (Files.isRegularFile(random)) {
            return Optional.of(random);
        }
        Path builtin = defaults().resolve(name + PNG);
        if (Files.isRegularFile(builtin)) {
            return Optional.of(builtin);
        }
        return Optional.empty();
    }

    /**
     * 生成居民时的真随机：customs + randoms + defaults 三池合并后均匀抽
     * （2026-11-22 主人定：不做权重，按文件名合并等概率）。
     * 池子空则返回空（渲染会落到 steve）。
     */
    public static Optional<String> pickRandom(RandomSource random) {
        List<String> names = new ArrayList<>(customsPool());
        names.addAll(randomsPool());
        names.addAll(defaultsPool());
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
