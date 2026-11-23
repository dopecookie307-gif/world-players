package com.worldplayers.client;

import com.worldplayers.resident.WorkZone;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemNameBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/** 客户端缓存：服务端同步过来的已储存工作区域 + 农田作物统计。 */
public final class ZoneStore {
    /** 一次扫描最多看多少方块（超大的区域就按「没有作物」处理，防止打开手册卡死）。 */
    private static final long MAX_SCAN_VOLUME = 2_000_000L;
    private static volatile List<WorkZone> zones = List.of();
    /** 农田区域 key → 右下角小图标候选（泥土 / **并列最高产量**的作物产物，可能多个轮换显示）。 */
    private static final Map<String, List<ItemStack>> CROP_BADGES = new HashMap<>();
    /** 农田区域 key → 详情页的作物清单（产物图标 + 种子 + 数量，按数量降序）。 */
    private static final Map<String, List<CropLine>> CROP_STATS = new HashMap<>();
    /** 工程区域 key → {已完成, 总数}（服务端进度包同步过来，规格 2.3 / 6）。 */
    private static final Map<String, long[]> PROJECT_PROGRESS = new HashMap<>();

    /** 详情页「农田详情」的一行：作物产物图标 + 种子名 + 格数。 */
    public record CropLine(ItemStack icon, ItemStack seed, int count) {
    }
    /** 种子物品缓存：方块 → 能种它的种子物品（第一次用到时从物品注册表建一次）。 */
    private static Map<Block, ItemStack> seedCache;
    /** 作物产物缓存：方块 → 产物物品（查掉落表，一次一块，查不到再退回种子）。 */
    private static final Map<Block, ItemStack> PRODUCT_CACHE = new HashMap<>();
    private static long lastRefresh;

    public static void set(List<WorkZone> list) {
        zones = list == null ? List.of() : List.copyOf(list);
        CROP_BADGES.clear();
        CROP_STATS.clear();
        PROJECT_PROGRESS.clear();
    }

    /** 工程进度包（服务端 → 客户端，5 秒节流）。 */
    public static void setProgress(String zoneKey, long done, long total) {
        if (zoneKey == null) {
            return;
        }
        PROJECT_PROGRESS.put(zoneKey, new long[]{Math.max(0L, done), Math.max(0L, total)});
    }

    /**
     * 工程进度 {已完成, 总数}：优先用服务端推过来的实时值，否则退回区域里存的上次进度。
     */
    public static long[] progress(String zoneKey) {
        long[] live = PROJECT_PROGRESS.get(zoneKey);
        if (live != null) {
            return live.clone();
        }
        WorkZone zone = byKey(zoneKey);
        return zone == null ? new long[]{0L, 0L}
            : new long[]{zone.projectDone(), zone.projectTotal()};
    }

    public static List<WorkZone> zones() {
        return zones;
    }

    /** 按唯一键找区域；找不到返回 null。 */
    public static WorkZone byKey(String key) {
        for (WorkZone zone : zones) {
            if (zone.key().equals(key)) {
                return zone;
            }
        }
        return null;
    }

    /** 农田区域右下角的小图标（可多个并列最高，手册会轮换显示；不是农田/还没刷新 → 空）。 */
    public static List<ItemStack> cropBadges(String zoneKey) {
        return CROP_BADGES.getOrDefault(zoneKey, List.of());
    }

    /**
     * 重新统计所有农田区域的作物（打开手册时调一次，顺带 500ms 节流避免窗口缩放重复扫）。
     *
     * <p>规则（主人 2026-11-20）：区域里没有作物 → 泥土图标；
     * 有作物 → **数量最多的那种作物的产物**物品图标（西瓜种子多 → 显示西瓜片，而不是种子）。
     * 「作物产物」优先查方块的掉落表，取第一个**不是种子**的掉落物；查不到才退回种子/方块物品。
     * 数量并列最高的多种作物会一起返回，手册每 2 秒轮换一个。</p>
     */
    public static void refreshCrops() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < 500L) {
            return;
        }
        lastRefresh = now;
        CROP_BADGES.clear();
        CROP_STATS.clear();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        for (WorkZone zone : zones) {
            if (!WorkZone.TYPE_FARM.equals(zone.type())) {
                continue;
            }
            CROP_BADGES.put(zone.key(), countCrops(minecraft, zone));
        }
    }

    /** 详情页「农田详情」：区域内所有作物（按数量从多到少）。 */
    public static List<CropLine> cropStats(String zoneKey) {
        return CROP_STATS.getOrDefault(zoneKey, List.of());
    }

    private static List<ItemStack> countCrops(Minecraft minecraft, WorkZone zone) {
        if (zone.volume() <= 0L || zone.volume() > MAX_SCAN_VOLUME) {
            return List.of(new ItemStack(Items.DIRT));
        }
        Map<Block, Integer> counts = new HashMap<>();
        // 每种作物留一个**真实坐标**：查掉落表时 ORIGIN 必须落在该方块上，
        // 因为瓜类茎的掉落表带 location_check（origin 处的方块得是它自己），传 (0,0,0) 会掉种子。
        Map<Block, BlockPos> samples = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = zone.min().getX(); x <= zone.max().getX(); x++) {
            for (int y = zone.min().getY(); y <= zone.max().getY(); y++) {
                for (int z = zone.min().getZ(); z <= zone.max().getZ(); z++) {
                    pos.set(x, y, z);
                    BlockState state = minecraft.level.getBlockState(pos);
                    if (isCrop(state)) {
                        counts.merge(state.getBlock(), 1, Integer::sum);
                        samples.putIfAbsent(state.getBlock(), pos.immutable());
                    }
                }
            }
        }
        int bestCount = 0;
        for (int count : counts.values()) {
            bestCount = Math.max(bestCount, count);
        }
        if (bestCount <= 0) {
            return List.of(new ItemStack(Items.DIRT));
        }
        List<ItemStack> badges = new ArrayList<>();
        for (Map.Entry<Block, Integer> entry : counts.entrySet()) {
            if (entry.getValue() == bestCount) {
                badges.add(productIcon(entry.getKey(), samples.get(entry.getKey())));
            }
        }
        // 详情页的完整清单（所有作物，数量从多到少）
        List<CropLine> stats = new ArrayList<>();
        for (Map.Entry<Block, Integer> entry : counts.entrySet()) {
            Block block = entry.getKey();
            stats.add(new CropLine(productIcon(block, samples.get(block)), seedIcon(block), entry.getValue()));
        }
        stats.sort((a, b) -> Integer.compare(b.count(), a.count()));
        CROP_STATS.put(zone.key(), stats);
        return badges.isEmpty() ? List.of(new ItemStack(Items.DIRT)) : badges;
    }

    /**
     * 作物判定：原版 crops 标签 / CropBlock 子类（多数模组作物都会走这两条之一），
     * 外加西瓜、南瓜这种**果实方块**（收获后留在地里的那一格，茎本身也在 crops 标签里）。
     */
    private static boolean isCrop(BlockState state) {
        Block block = state.getBlock();
        return state.is(BlockTags.CROPS) || block instanceof CropBlock
            || block == Blocks.MELON || block == Blocks.PUMPKIN;
    }

    /**
     * 方块 → 「种子会种出来什么」的图标（主人 2026-11-20 要求：不能显示种子本身）。
     *
     * <p>顺序：成熟状态查掉落表取第一个**非种子**掉落 → 还是种子就退回种子/方块物品。
     * 之前一直显示种子，是因为拿 {@code defaultBlockState()}（age=0）去查掉落表，age=0 的作物只掉种子。</p>
     */
    private static ItemStack productIcon(Block block, @Nullable BlockPos sample) {
        ItemStack cached = PRODUCT_CACHE.get(block);
        if (cached != null) {
            return cached.copy();
        }
        ItemStack product = lootProduct(block, sample);
        // 掉落表手里回来的还是「这个方块自己的种子」→ 等于没拿到产物，试原版果实兜底
        if (!product.isEmpty() && product.getItem() instanceof ItemNameBlockItem seed
                && seed.getBlock() == block) {
            ItemStack fruit = vanillaFruit(block);
            if (!fruit.isEmpty()) {
                product = fruit;
            }
        }
        if (product.isEmpty()) {
            product = vanillaFruit(block);
        }
        if (product.isEmpty()) {
            product = seedIcon(block);
        }
        PRODUCT_CACHE.put(block, product);
        return product.copy();
    }

    /**
     * 拿不到掉落表时（多人客户端）对原版瓜类茎的兜底：茎成熟 → 结的就是这个果实。
     * 只覆盖原版两种；模组作物退回种子图标（这是已知限制）。
     */
    private static ItemStack vanillaFruit(Block block) {
        if (block == Blocks.MELON_STEM) {
            return new ItemStack(Items.MELON);
        }
        if (block == Blocks.PUMPKIN_STEM) {
            return new ItemStack(Items.PUMPKIN);
        }
        return ItemStack.EMPTY;
    }

    /**
     * 方块 → 成熟状态的 BlockState：把身上所有 `age` 属性的值拉到最大。
     *
     * <p>不能直接用 {@code CropBlock.AGE}——模组作物可能用 AGE_3/AGE_5 等别的属性，
     * 也可能根本没有 age（西瓜/南瓜的果实方块）。</p>
     */
    private static BlockState matureState(Block block) {
        BlockState state = block.defaultBlockState();
        for (Property<?> property : state.getProperties()) {
            if (property instanceof IntegerProperty age && "age".equals(age.getName())) {
                state = state.setValue(age, Collections.max(age.getPossibleValues()));
            }
        }
        return state;
    }

    /** 读方块掉落表（只在单人环境可用；多人客户端拿不到服务端战利品表，退回空）。 */
    private static ItemStack lootProduct(Block block, @Nullable BlockPos sample) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return ItemStack.EMPTY;
        }
        try {
            ServerLevel level = server.overworld();
            LootTable table = server.reloadableRegistries().getLootTable(block.getLootTable());
            // BLOCK 参数集必须带 BLOCK_STATE（少一个会抛异常）；
            // 状态要用**成熟**状态（age=0 只掉种子）；ORIGIN 要用**该方块真实所在的坐标**——
            // 瓜类茎的掉落表带 location_check，origin 处不是它自己就只给种子。
            Vec3 origin = sample == null ? Vec3.ZERO : Vec3.atCenterOf(sample);
            LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.BLOCK_STATE, matureState(block))
                .withParameter(LootContextParams.ORIGIN, origin)
                .withParameter(LootContextParams.TOOL, new ItemStack(Items.DIAMOND_HOE))
                .create(LootContextParamSets.BLOCK);
            for (ItemStack drop : table.getRandomItems(params)) {
                if (!drop.isEmpty() && !(drop.getItem() instanceof ItemNameBlockItem)) {
                    return drop.copy();
                }
            }
            for (ItemStack drop : table.getRandomItems(params)) {
                if (!drop.isEmpty()) {
                    return drop.copy();
                }
            }
        } catch (Throwable ignored) {
            // 掉落表读不到就算了，退回种子图标
        }
        return ItemStack.EMPTY;
    }

    /** 方块 → 种子图标；找不到种子就用方块自己的物品（比如胡萝卜这种没有「种子」的）。 */
    private static ItemStack seedIcon(Block block) {
        ItemStack seed = seedCache().get(block);
        if (seed != null) {
            return seed.copy();
        }
        Item item = block.asItem();
        return item == Items.AIR ? new ItemStack(Items.DIRT) : new ItemStack(item);
    }

    private static Map<Block, ItemStack> seedCache() {
        if (seedCache == null) {
            seedCache = new HashMap<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item instanceof ItemNameBlockItem seed && seed.getBlock() != null) {
                    seedCache.putIfAbsent(seed.getBlock(), new ItemStack(item));
                }
            }
        }
        return seedCache;
    }

    private ZoneStore() {
    }
}
