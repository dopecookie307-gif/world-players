package com.worldplayers.resident.tree;

import com.worldplayers.resident.WorkZone;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 树场的**区域级共享缓存**（规则 14、16、36）：一个区域只有一份，所有员工共用。
 *
 * <ul>
 *   <li>**槽位表**（可种植位置）：自动模式贪婪挑、手动模式泥土定点；</li>
 *   <li>**树清单**（每棵树树干底）：从扫描到的目标原木 26 邻域分组；</li>
 *   <li>**违规树苗清单**：周围 8 格有别的树苗 / 目标成树的苗；</li>
 *   <li>**领取表**：谁领了哪棵树、还剩哪些方块没砍（多人时平摊）。</li>
 * </ul>
 *
 * <p>扫描是**分片**的（规则 36）：每 tick 只扫区域的一小块，约 200 tick 轮完一整轮；
 * 轮末才重建槽位表与树清单。同一 tick 里多个员工的 goal 调进来只跑一次。</p>
 */
public final class TreeFarmIndex {
    /** 一轮扫描大约分多少 tick 扫完（规则 36）。 */
    private static final int SCAN_TICKS = 200;
    /** 每 tick 最少扫多少格（小区域不必分 200 片）。 */
    private static final int MIN_SLICE = 64;
    /** 每 tick 最多扫多少格（大区域也不要一 tick 扫爆）。 */
    private static final int MAX_SLICE = 1024;
    /** 两轮扫描之间的间隔：10 秒（规则 14②、16）。 */
    private static final int ROUND_INTERVAL = 200;
    /**
     * 树场扫描高度：区域**上方**还要看多少格（2026-11-23 第 10 轮主人定：区域 + 上方 40 格）。
     *
     * <p>以前只扫区域本身的 y 范围。区域常常只框地面一层、树干长在区域上方几格，
     * 结果原木清单永远是空的（员工只能游荡 / 种苗）——这也是「不砍原木」的另一半原因。</p>
     */
    private static final int SCAN_ABOVE = 40;
    /** 一棵树被领走多久内允许别人加入（3 秒，规则 30）。 */
    private static final int JOIN_WINDOW = 60;
    /** 一棵树多久没人动就释放（防止员工走开后整棵树被卡住）。 */
    private static final int CLAIM_IDLE_RELEASE = 20 * 60;

    private static final Map<String, TreeFarmIndex> CACHE = new HashMap<>();

    /** 一棵树已经被谁领了、还剩哪些方块（原木由低到高，树叶最后）。 */
    public static final class Claim {
        private final BlockPos base;
        private final long startedTick;
        private final List<BlockPos> logs;
        private final List<BlockPos> leaves;
        private final int saplingReward;
        private int logCursor;
        private int leafCursor;
        private long lastUseTick;
        private final Set<UUID> workers = new HashSet<>();

        private Claim(BlockPos base, TreeFarmGrid.TreeBody body, long now) {
            this.base = base;
            this.startedTick = now;
            this.lastUseTick = now;
            this.logs = body.logs();
            this.leaves = body.leaves();
            this.saplingReward = body.saplingReward();
        }

        public BlockPos base() {
            return this.base;
        }

        /** 砍完这棵树给几个树苗（2×2 大树 4 个、普通树 1 个，规则 27）。 */
        public int saplingReward() {
            return this.saplingReward;
        }

        /** 还剩哪些原木（给浮窗/调试看）。 */
        public int remainingLogs() {
            return Math.max(0, this.logs.size() - this.logCursor);
        }

        /** 这棵树的所有方块都砍完了吗（树叶也算）。 */
        public boolean done() {
            return this.logCursor >= this.logs.size() && this.leafCursor >= this.leaves.size();
        }
    }

    private String signature = "";
    private List<BlockPos> slots = List.of();
    private List<BlockPos> treeBases = List.of();
    private List<BlockPos> violations = List.of();
    private final Map<BlockPos, Claim> claims = new HashMap<>();

    /** 本轮扫描的进度（线性游标，0..scannedVolume-1）。 */
    private int cursor;
    /** 本轮扫描的总格数（含树场外扩的几格）。 */
    private long scannedVolume = 1L;
    /** 本轮扫描的暂存结果。 */
    private final List<BlockPos> roundSlots = new ArrayList<>();
    private final Set<BlockPos> roundLogs = new HashSet<>();
    private final List<BlockPos> roundSaplings = new ArrayList<>();
    private long lastTick = Long.MIN_VALUE;
    private long nextRoundTick;

    private TreeFarmIndex() {
    }

    /** 取这个区域对应的缓存（按区域唯一键）。 */
    public static TreeFarmIndex get(WorkZone zone) {
        return CACHE.computeIfAbsent(zone.key(), key -> new TreeFarmIndex());
    }

    /** 设定改了 / 区域删了：整个缓存作废（规则 4、14①）。 */
    public static void invalidate(String zoneKey) {
        TreeFarmIndex index = CACHE.remove(zoneKey);
        if (index != null && !index.claims.isEmpty()) {
            index.claims.clear();
        }
    }

    public List<BlockPos> slots() {
        return this.slots;
    }

    public List<BlockPos> treeBases() {
        return this.treeBases;
    }

    public List<BlockPos> violations() {
        return this.violations;
    }

    /** 员工上班时强制重扫一轮（规则 14③）。 */
    public void forceRescan() {
        this.nextRoundTick = 0L;
        this.cursor = 0;
        this.roundSlots.clear();
        this.roundLogs.clear();
        this.roundSaplings.clear();
    }

    // ------------------------------------------------------------------
    // 分片扫描
    // ------------------------------------------------------------------

    /**
     * 每 tick 推一点扫描进度（同一 tick 多个员工调进来只跑一次）。
     *
     * <p>区域设定变了会立刻丢弃缓存并从头重扫。</p>
     */
    public void tick(ServerLevel level, WorkZone zone, long gameTime) {
        if (!this.signature.equals(zone.treeSignature())) {
            this.signature = zone.treeSignature();
            this.slots = List.of();
            this.treeBases = List.of();
            this.violations = List.of();
            this.claims.clear();
            this.forceRescan();
            this.nextRoundTick = gameTime;
        }
        if (this.lastTick == gameTime) {
            return;
        }
        this.lastTick = gameTime;
        if (gameTime < this.nextRoundTick) {
            return;
        }
        this.scanSlice(level, zone);
    }

    private void scanSlice(ServerLevel level, WorkZone zone) {
        // 2026-11-23 主人改口：**外围只用于站立 / 通勤**，不参与砍树和种苗。
        // 但**高度**要往上多看 {@link #SCAN_ABOVE} 格：树场区域常常只框地面一层，
        // 树干 / 树叶都在区域上方（2026-11-23 第 10 轮：区域 + 上方 40 格内所有同类原木都要砍）。
        int width = zone.max().getX() - zone.min().getX() + 1;
        int depth = zone.max().getZ() - zone.min().getZ() + 1;
        int height = zone.max().getY() - zone.min().getY() + 1 + SCAN_ABOVE;
        long volume = (long) width * depth * height;
        if (volume <= 0L) {
            return;
        }
        this.scannedVolume = volume;
        int slice = (int) Math.min(MAX_SLICE, Math.max(MIN_SLICE, volume / SCAN_TICKS + 1));
        TreeSpecies species = zone.species();
        int layer = width * depth;
        for (int i = 0; i < slice; i++) {
            if (this.cursor >= volume) {
                break;
            }
            int index = this.cursor++;
            int y = zone.min().getY() + index / layer;
            int rest = index % layer;
            int x = zone.min().getX() + rest / depth;
            int z = zone.min().getZ() + rest % depth;
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            // 槽位 / 违规苗只在区域自己的高度范围内找（上方 40 格是给树干 / 树叶的）
            if (y <= zone.max().getY()) {
                // 槽位候选：泥土类地面 + 上方空气（间距/可达性在轮末算）
                if (TreeFarmGrid.isSoil(state) && level.getBlockState(pos.above()).isAir()) {
                    this.roundSlots.add(pos.immutable());
                }
                if (species != TreeSpecies.NONE && TreeSpecies.isAnySapling(state)) {
                    this.roundSaplings.add(pos.immutable());
                }
            }
            if (species != TreeSpecies.NONE && species.isTargetLog(state)) {
                this.roundLogs.add(pos.immutable());
            }
        }
        if (this.cursor >= volume) {
            this.finishRound(level, zone);
        }
    }

    /** 一轮扫完：重建槽位表 / 树清单 / 违规清单，然后歇 10 秒。 */
    private void finishRound(ServerLevel level, WorkZone zone) {
        TreeSpecies species = zone.species();
        this.cursor = 0;
        this.nextRoundTick = level.getGameTime() + ROUND_INTERVAL;

        // 槽位表
        boolean twoByTwo = zone.isTwoByTwo();
        if (species == TreeSpecies.NONE) {
            this.slots = List.of();
        } else if (twoByTwo) {
            // 2×2：槽位表存的是「组锚点」（西北角），种的时候再补齐 4 格（规则 10、28）
            List<BlockPos> usable = this.roundSlots;
            if (!zone.isManualPlacement()) {
                List<BlockPos> reachable = new ArrayList<>();
                for (BlockPos pos : this.roundSlots) {
                    // 2026-11-22：自动模式必须避开「已有树苗的 8 邻格」，否则会挨着已经种下的苗
                    // 再种一棵，下一轮扫描判违规拔掉，形成「种 → 拔」循环。
                    if (TreeFarmGrid.hasStandingRoom(level, pos)
                            && !this.nearTree(pos) && !this.nearSapling(pos, true)) {
                        reachable.add(pos);
                    }
                }
                usable = reachable;
            }
            this.slots = TreeFarmGrid.pickTwoByTwoAnchors(usable, !zone.isManualPlacement());
        } else if (zone.isManualPlacement()) {
            // 手动模式：玩家铺好的泥土格直接当槽位（规则 13），不做间距筛选
            this.slots = TreeFarmGrid.pickManualSlots(this.roundSlots);
        } else {
            List<BlockPos> reachable = new ArrayList<>();
            for (BlockPos pos : this.roundSlots) {
                // 2026-11-22：自动槽位避开「周围有目标成树 / 已有树苗」的位置（规则 9），
                // 否则苗种下去下一轮就被判违规拔掉，员工看起来永远在种、永远种不成。
                if (TreeFarmGrid.hasStandingRoom(level, pos)
                        && !this.nearTree(pos) && !this.nearSapling(pos, false)) {
                    reachable.add(pos);
                }
            }
            this.slots = TreeFarmGrid.pickAutoSlots(reachable);
        }

        // 树清单：目标原木分组
        this.treeBases = species == TreeSpecies.NONE
            ? List.of() : TreeFarmGrid.treeBases(this.roundLogs);

        // 违规树苗（规则 16、17）：区域级一份结果，不按员工各扫
        List<BlockPos> bad = new ArrayList<>();
        if (species != TreeSpecies.NONE) {
            for (BlockPos pos : this.roundSaplings) {
                if (!species.isOwnSapling(level.getBlockState(pos))) {
                    // 不是新树种的苗：直接算违规（规则 4 的「改树种要清苗」也靠这条兜底）
                    bad.add(pos);
                } else if (!zone.isManualPlacement()
                        && TreeFarmGrid.isViolation(level, species, pos, twoByTwo)) {
                    // 2026-11-22：**手动模式不再清扫「违规」苗**——规则 13 说手动位置由玩家自己保证，
                    // 以前这里照样按间距判违规，玩家种得密一点就被员工反复拔掉。
                    bad.add(pos);
                }
            }
        }
        this.violations = List.copyOf(bad);

        this.roundSlots.clear();
        this.roundLogs.clear();
        this.roundSaplings.clear();
    }

    /**
     * 这一格周围 8 格有没有目标成树（规则 9：槽位间距要求里「不许有别的树苗或目标成树」）。
     *
     * <p>扫描阶段用 {@link #roundLogs} 判断，不额外读世界。树干通常在槽位上方几格，
     * 所以每个邻格往上查 0~3 格（和违规判定同一个范围）。</p>
     */
    private boolean nearTree(BlockPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos beside = pos.offset(dx, 0, dz);
                for (int dy = 0; dy <= 3; dy++) {
                    if (this.roundLogs.contains(beside.above(dy))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 这一格（槽位）周围 8 格有没有**已经种下的树苗**（规则 9：间距切比雪夫 ≥2）。
     *
     * <p>树苗方块在槽位上方一格，所以用 {@code pos.above()} 做水平 8 邻比较。
     * 自动模式挑槽位时用它避免「挨着已有苗再种一棵」——那会和违规清扫打架。</p>
     */
    private boolean nearSapling(BlockPos pos, boolean twoByTwo) {
        BlockPos sapling = pos.above();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos beside = sapling.offset(dx, 0, dz);
                if (!this.roundSaplings.contains(beside)) {
                    continue;
                }
                // 2×2 组内的苗相邻是豁免的（和违规判定同一个口径）
                if (twoByTwo && beside.getY() == sapling.getY()) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 领取 / 分摊（规则 30）
    // ------------------------------------------------------------------

    /**
     * 领一棵树：谁先到先领整棵；**3 秒内**第二个员工到场 → 加入同一份份额、N 人平摊剩余方块。
     *
     * @return null 表示这棵树已经有别人领走了（且过了 3 秒窗口，不参与）
     */
    @Nullable
    public Claim claim(BlockPos base, TreeFarmGrid.TreeBody body, long now, UUID worker) {
        Claim existing = this.claims.get(base);
        if (existing != null) {
            if (existing.done()) {
                this.claims.remove(base);
            } else if (now - existing.startedTick > JOIN_WINDOW) {
                return null;
            } else {
                existing.workers.add(worker);
                existing.lastUseTick = now;
                return existing;
            }
        }
        Claim claim = new Claim(base, body, now);
        claim.workers.add(worker);
        this.claims.put(base, claim);
        return claim;
    }

    /** 取这个员工下一个要破坏的方块：原木由低到高、树叶最后一并处理；没剩的返回 null。 */
    @Nullable
    public BlockPos nextBlock(Claim claim, long now) {
        claim.lastUseTick = now;
        if (claim.logCursor < claim.logs.size()) {
            return claim.logs.get(claim.logCursor++);
        }
        if (claim.leafCursor < claim.leaves.size()) {
            return claim.leaves.get(claim.leafCursor++);
        }
        return null;
    }

    /** 这棵树砍完了：释放领取。 */
    public void release(Claim claim) {
        this.claims.remove(claim.base);
    }

    /** 员工离开/换目标：如果这棵树没人再动，等到超时才清（这里只把员工标记移除）。 */
    public void abandon(Claim claim, UUID worker) {
        claim.workers.remove(worker);
        if (claim.workers.isEmpty()) {
            this.claims.remove(claim.base);
        }
    }

    /** 清理超时没动的领取（60 秒没人碰就放掉，免得一棵半截树永远占着）。 */
    public void pruneClaims(long now) {
        this.claims.values().removeIf(claim -> now - claim.lastUseTick > CLAIM_IDLE_RELEASE);
    }

    /** 这棵树现在有没有被别人占着（且还在 3 秒窗口外，别人插不进去）。 */
    public boolean isBusyElsewhere(BlockPos base, long now, UUID worker) {
        Claim claim = this.claims.get(base);
        if (claim == null || claim.done()) {
            return false;
        }
        if (claim.workers.contains(worker)) {
            return false;
        }
        return now - claim.startedTick > JOIN_WINDOW;
    }

    /** 换个「人多平摊」的说法：给诊断用的剩余方块快照。 */
    public List<BlockPos> remaining(Claim claim) {
        List<BlockPos> left = new ArrayList<>();
        for (int i = claim.logCursor; i < claim.logs.size(); i++) {
            left.add(claim.logs.get(i));
        }
        for (int i = claim.leafCursor; i < claim.leaves.size(); i++) {
            left.add(claim.leaves.get(i));
        }
        return left;
    }

    /** 区域被删除时顺手清掉缓存（没有区域数据就没人会来 get）。 */
    public static void clearAll() {
        CACHE.clear();
    }

    /** 2×2 的锚点补齐：从锚点出发补齐 4 棵苗的位置（缺苗有几棵种几棵，规则 28）。 */
    public static List<BlockPos> twoByTwoSpots(BlockPos anchor) {
        List<BlockPos> spots = new ArrayList<>(4);
        spots.add(anchor.immutable());
        spots.add(anchor.east().immutable());
        spots.add(anchor.south().immutable());
        spots.add(anchor.east().south().immutable());
        return spots;
    }

    /** 调试/诊断用：当前有几棵树被领着。 */
    public int claimedCount() {
        return this.claims.size();
    }

    /** 扫描轮次进度（0..1），给未来调试命令用。 */
    public double scanProgress(WorkZone zone) {
        return this.scannedVolume <= 0L ? 1.0D
            : Math.min(1.0D, (double) this.cursor / (double) this.scannedVolume);
    }

    /** 下一轮扫描还有多久（tick）。 */
    public long nextRoundIn(long gameTime) {
        return Math.max(0L, this.nextRoundTick - gameTime);
    }
}
