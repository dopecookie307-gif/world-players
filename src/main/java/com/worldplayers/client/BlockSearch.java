package com.worldplayers.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.worldplayers.resident.project.ProjectRules;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 填充方块的搜索数据源（规格 4）：缓存所有可放置方块（中文名 / 英文名 / tag / 拼音），
 * 提供「一次匹配最多 200 条」的过滤查询。
 *
 * <p>英文名单独读 {@code assets/<ns>/lang/en_us.json}（因为中文环境里 {@code block.getName()} 是中文，
 * 而主人会直接打英文 id 或英文名）；读不到就退回 registry path。</p>
 *
 * <p>拼音搜索**不自己硬编字表**：装了 JEC（通用拼音搜索）就借它的 PinIn 匹配
 * （{@code shizhuan} / {@code szz} 都能搜到「石砖」），没装就只支持中文原文 / 英文 / id。</p>
 */
public final class BlockSearch {
    /** 一条候选。 */
    public record Entry(Block block, String id, String path, String name, String enName,
                        String tag, int order) {
        /** 输入框回填用的文本：不带 {@code minecraft:} 前缀（更好看、也更好打）。 */
        public String shortId() {
            return this.id.startsWith("minecraft:") ? this.id.substring("minecraft:".length()) : this.id;
        }
    }

    /**
     * 是否装了 JEC（通用拼音搜索）。这里**直接查 ModList**，不碰 {@code JecMatch}：
     * 没装 JEC 时 {@code JecMatch} 类根本不会被加载，也就不会有类加载错误。
     */
    private static final boolean JEC = net.neoforged.fml.ModList.get().isLoaded("jecharacters");

    /** 一次查询最多要多少条（规格 4.5：过滤池最多 200，渲染只取 8）。 */
    public static final int MATCH_LIMIT = 200;
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9_#@:./-]");
    /** 英文语言文件缓存：命名空间 → (descriptionId → 英文名)。 */
    private static final Map<String, Map<String, String>> LANG_CACHE = new HashMap<>();
    private static final Set<String> TAG_NAMES = new HashSet<>();
    private static List<Entry> all;

    /** 资源重载（语言 / 材质 / 模组变动）后清缓存。 */
    public static void invalidate() {
        all = null;
        LANG_CACHE.clear();
        TAG_NAMES.clear();
    }

    /** 全部可放置方块（第一次调用时建表）。 */
    public static List<Entry> all() {
        List<Entry> cached = all;
        if (cached != null) {
            return cached;
        }
        List<Entry> list = new ArrayList<>();
        // tag 名集合（用于「输入恰好是 tag 名」的优先列表）
        for (TagKey<Block> tag : BuiltInRegistries.BLOCK.getTagNames().toList()) {
            TAG_NAMES.add(tag.location().toString());
        }
        int order = 0;
        for (ResourceLocation location : BuiltInRegistries.BLOCK.keySet()) {
            Block block = BuiltInRegistries.BLOCK.get(location);
            if (block == null || !ProjectRules.isValidFillTarget(block)) {
                continue;
            }
            String id = location.toString();
            String path = location.getPath();
            String name = block.getName().getString();
            String enName = englishName(block, location, path);
            String tag = firstTag(block);
            list.add(new Entry(block, id, path, name, enName, tag, order++));
        }
        all = List.copyOf(list);
        return all;
    }

    /** 按 id 精确找一个（输入框文字 → 方块）；找不到 / 非法 → null。 */
    @Nullable
    public static Block resolve(String text) {
        String id = normalizeId(text);
        if (id.isEmpty()) {
            return null;
        }
        return ProjectRules.blockById(id);
    }

    /** 按 id 找 Entry（用于显示当前方块的名称 / 图标）。 */
    @Nullable
    public static Entry byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalized = normalizeId(id);
        for (Entry entry : all()) {
            if (entry.id().equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * 搜索（规格 4.3）：空输入 → 最近使用 + 注册顺序补；
     * {@code #tag} → tag 内方块；{@code @modid} → 该命名空间；
     * 其余按「id / 英文名 / tag / 中文名 完全相等 → 前缀 → 包含 → 拼音」排序。
     */
    public static List<Entry> search(String raw) {
        String input = raw == null ? "" : raw.strip();
        if (input.isEmpty()) {
            List<Entry> recent = recentEntries();
            return recent.size() > MATCH_LIMIT ? recent.subList(0, MATCH_LIMIT) : recent;
        }
        String query = normalizeQuery(input);
        if (query.startsWith("#")) {
            return byTag(query.substring(1));
        }
        if (query.startsWith("@")) {
            return byNamespace(query.substring(1));
        }
        List<Entry> tagFirst = new ArrayList<>();
        if (TAG_NAMES.contains(withNamespace(query))) {
            tagFirst.addAll(byTag(query));
        }
        List<Scored> scored = new ArrayList<>();
        for (Entry entry : all()) {
            int score = score(entry, query);
            if (score < 99) {
                scored.add(new Scored(entry, score));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score)
            .thenComparingInt(s -> s.entry().order()));
        List<Entry> out = new ArrayList<>(tagFirst);
        for (Scored item : scored) {
            // tag 优先列表里已经有的不重复出现
            if (tagFirst.isEmpty() || !tagFirst.remove(item.entry())) {
                out.add(item.entry());
            }
            if (out.size() >= MATCH_LIMIT) {
                break;
            }
        }
        return out;
    }

    private record Scored(Entry entry, int score) {
    }

    /** 逐项打分（越小越优先，99 = 不匹配）。 */
    private static int score(Entry entry, String query) {
        String path = entry.path();
        String en = normalizeName(entry.enName());
        if (entry.id().equals(query) || path.equals(query)) {
            return 0;
        }
        if (en.equals(query)) {
            return 1;
        }
        if (tagMatches(entry.tag(), query)) {
            return 2;
        }
        if (entry.name().equals(query)) {
            return 3;
        }
        if (path.startsWith(query) || en.startsWith(query) || entry.name().startsWith(query)
                || tagPath(entry.tag()).startsWith(query)) {
            return 4;
        }
        if (path.contains(query) || en.contains(query) || entry.name().contains(query)) {
            return 5;
        }
        // 拼音：借 JEC 自带的 PinIn（全拼 / 首字母 / 简繁都能匹配）；没装 JEC 就没有这一档
        if (JEC && (com.worldplayers.compat.jec.JecMatch.startsWith(entry.name(), query)
                || com.worldplayers.compat.jec.JecMatch.contains(entry.name(), query))) {
            return 6;
        }
        return 99;
    }

    private static List<Entry> byTag(String tagQuery) {
        String tagName = withNamespace(normalizeId(tagQuery));
        ResourceLocation location = ResourceLocation.tryParse(tagName);
        if (location == null) {
            return List.of();
        }
        TagKey<Block> tag = TagKey.create(net.minecraft.core.registries.Registries.BLOCK, location);
        List<Entry> out = new ArrayList<>();
        for (Entry entry : all()) {
            BlockState state = entry.block().defaultBlockState();
            if (state.is(tag)) {
                out.add(entry);
            }
        }
        return out.size() > MATCH_LIMIT ? out.subList(0, MATCH_LIMIT) : out;
    }

    private static List<Entry> byNamespace(String namespace) {
        String ns = normalizeId(namespace);
        if (ns.isEmpty()) {
            return List.of();
        }
        List<Entry> out = new ArrayList<>();
        for (Entry entry : all()) {
            if (entry.id().startsWith(ns + ":")) {
                out.add(entry);
            }
        }
        return out.size() > MATCH_LIMIT ? out.subList(0, MATCH_LIMIT) : out;
    }

    /** 最近使用（配置里记的 id）→ Entry；逐个配注册顺序补满一屏也够用的量。 */
    public static List<Entry> recentEntries() {
        List<Entry> out = new ArrayList<>();
        for (String id : com.worldplayers.config.WPClientConfig.recentFillBlocks()) {
            Entry entry = byId(id);
            if (entry != null) {
                out.add(entry);
            }
        }
        int limit = Math.min(MATCH_LIMIT, 64);
        for (Entry entry : all()) {
            if (out.size() >= limit) {
                break;
            }
            if (!out.contains(entry)) {
                out.add(entry);
            }
        }
        return out;
    }

    /** 记一次使用（最近使用清单）。 */
    public static void rememberUsed(@Nullable String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return;
        }
        com.worldplayers.config.WPClientConfig.addRecentFillBlock(normalizeId(blockId));
    }

    // ------------------------------------------------------------------
    // 名字 / 规范化
    // ------------------------------------------------------------------

    /** 输入规范化：小写、中间空格换下划线、去 {@code minecraft:} 前缀。 */
    public static String normalizeQuery(String input) {
        String text = input.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (text.startsWith("#") || text.startsWith("@")) {
            String prefix = text.substring(0, 1);
            String rest = text.substring(1).strip();
            return prefix + normalizeId(rest);
        }
        return normalizeId(text);
    }

    /** id 规范化：小写、空格换下划线、去掉 {@code minecraft:}。 */
    public static String normalizeId(String input) {
        if (input == null) {
            return "";
        }
        String text = input.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        text = NON_ALNUM.matcher(text).replaceAll("");
        if (text.startsWith("minecraft:")) {
            text = text.substring("minecraft:".length());
        }
        return text;
    }

    /** 英文名 → 匹配用的下划线形式（「Stone Bricks」→ stone_bricks）。 */
    private static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static String withNamespace(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static boolean tagMatches(String tag, String query) {
        if (tag == null || tag.isEmpty()) {
            return false;
        }
        return tag.equals(query) || tag.equals(withNamespace(query));
    }

    private static String tagPath(String tag) {
        if (tag == null || tag.isEmpty()) {
            return "";
        }
        int colon = tag.indexOf(':');
        return colon < 0 ? tag : tag.substring(colon + 1);
    }

    /** 第一个 {@code minecraft:} 命名空间的 tag（排序后）；没有 → 空。 */
    private static String firstTag(Block block) {
        try {
            TreeSet<String> tags = new TreeSet<>();
            for (TagKey<Block> tag : block.builtInRegistryHolder().tags().toList()) {
                if ("minecraft".equals(tag.location().getNamespace())) {
                    tags.add(tag.location().toString());
                }
            }
            return tags.isEmpty() ? "" : tags.first();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** 英文名：读 {@code assets/<ns>/lang/en_us.json}；取不到就退回 registry path。 */
    private static String englishName(Block block, ResourceLocation location, String path) {
        Map<String, String> lang = langCache(location.getNamespace());
        String value = lang.get(block.getDescriptionId());
        return value == null || value.isBlank() ? path : value;
    }

    private static Map<String, String> langCache(String namespace) {
        Map<String, String> cached = LANG_CACHE.get(namespace);
        if (cached != null) {
            return cached;
        }
        Map<String, String> values = new HashMap<>();
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
                ResourceLocation file = ResourceLocation.fromNamespaceAndPath(namespace, "lang/en_us.json");
                var resource = minecraft.getResourceManager().getResource(file).orElse(null);
                if (resource != null) {
                    try (InputStream stream = resource.open();
                         InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                        JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                        for (String key : json.keySet()) {
                            if (json.get(key).isJsonPrimitive()) {
                                values.put(key, json.get(key).getAsString());
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不到就用 registry path 兜底
        }
        LANG_CACHE.put(namespace, values);
        return values;
    }

    private BlockSearch() {
    }
}
