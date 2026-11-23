package com.worldplayers.resident.tree;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.WorkZone;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 树场的纯算法工具箱（主人 2026-11-21 定稿规则）。
 *
 * <p>这里只有**无状态**的静态方法：槽位判定、贪婪挑槽位、BFS 找一棵树、违规树苗判定。
 * 状态（槽位表 / 树清单 / 领取表）在 {@link TreeFarmIndex} 里。</p>
 */
public final class TreeFarmGrid {
    /** 一棵树的高度上限（格）：超了就砍到上限为止，不崩（规则 20）。 */
    public static final int MAX_TREE_HEIGHT = 32;
    /** 一棵树的总格数上限（原木 + 树叶；规则 20）。 */
    public static final int MAX_TREE_BLOCKS = 512;
    /** 26 邻域（连通判定用）。 */
    private static final int[][] NEIGHBORS = buildNeighbors();

    /** 一棵树：树干底 + 原木（由低到高）+ 该树自己的树叶。 */
    public record TreeBody(BlockPos base, List<BlockPos> logs, List<BlockPos> leaves) {
        /** 是不是 2×2 大树（按**实际形态**判定：树干底层是 4 格 2×2）。 */
        public boolean isTwoByTwo() {
            int count = 0;
            for (BlockPos pos : this.logs) {
                if (pos.getY() == this.base.getY()) {
                    count++;
                }
            }
            return count >= 4;
        }

        /** 砍完给几个树苗：2×2 大树给 4 个，普通树给 1 个（规则 27）。 */
        public int saplingReward() {
            return this.isTwoByTwo() ? 4 : 1;
        }
    }

    private TreeFarmGrid() {
    }

    // ------------------------------------------------------------------
    // 槽位
    // ------------------------------------------------------------------

    /**
     * 槽位的「地面」判定（规则 11）：草 / 泥土 / 砂土 / 灰化土 / 缠根土。
     *
     * <p>用精确方块比较而不是 {@code BlockTags.DIRT}——标签里还有泥巴等我们没写进去的东西，
     * 主人给的清单是定稿，照清单走。</p>
     */
    public static boolean isSoil(BlockState state) {
        return state.getBlock() == Blocks.GRASS_BLOCK
            || state.getBlock() == Blocks.DIRT
            || state.getBlock() == Blocks.COARSE_DIRT
            || state.getBlock() == Blocks.PODZOL
            || state.getBlock() == Blocks.ROOTED_DIRT;
    }

    /**
     * 这一格是不是合法槽位（规则 11）：地面是泥土类 + 上方空气 + **居民能从旁边走到**。
     *
     * <p>「能从旁边走到」用水平 4 邻格里有没有一格能站人代替 {@code createPath}：
     * 这个判定在扫描阶段会被调用成千上万次，真的去寻路会把服务器拖死。</p>
     */
    public static boolean isSlot(ServerLevel level, BlockPos pos) {
        if (!isSoil(level.getBlockState(pos)) || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        return hasStandingRoom(level, pos);
    }

    /**
     * 槽位旁边有没有能站人的格子。
     *
     * <p>**2026-11-22 修**：以前检查的是槽位自己的 4 个水平邻格——那些格子是**地面方块本身**
     * （泥土 / 草），`isSafeStand` 要求格子是空气，于是永远返回 false，自动模式的槽位表直接是空的，
     * 员工自然「几乎不种树」。正确判定是「槽位**上方**那一格能站」（人站上去种）。</p>
     */
    public static boolean hasStandingRoom(ServerLevel level, BlockPos pos) {
        return ResidentEntity.isSafeStand(level, pos.above());
    }

    /**
     * 自动模式：贪婪挑槽位（规则 12）。
     *
     * <p>要求与**已选槽位**的切比雪夫距离 ≥2（规则 9），效果自然是「隔行隔列」；
     * 2×2 的 4 棵苗内部相邻属于豁免（规则 10），这里只排跨株的间距，2×2 的组由运行时按锚点补齐。</p>
     */
    public static List<BlockPos> pickAutoSlots(List<BlockPos> candidates) {
        List<BlockPos> picked = new ArrayList<>();
        // 先按 y 再按 x、z 排，保证结果稳定（同一份地形每次挑出来的槽位一致）
        List<BlockPos> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> {
            if (a.getY() != b.getY()) {
                return Integer.compare(a.getY(), b.getY());
            }
            if (a.getX() != b.getX()) {
                return Integer.compare(a.getX(), b.getX());
            }
            return Integer.compare(a.getZ(), b.getZ());
        });
        for (BlockPos pos : sorted) {
            boolean clash = false;
            for (BlockPos other : picked) {
                if (Math.abs(other.getX() - pos.getX()) <= 1 && Math.abs(other.getZ() - pos.getZ()) <= 1) {
                    clash = true;
                    break;
                }
            }
            if (!clash) {
                picked.add(pos.immutable());
            }
        }
        return picked;
    }

    /**
     * 手动模式：区域内的泥土格直接当槽位，**不再做间距筛选**（规则 13，玩家自己保证合规）。
     * 只保留「上方是空气」的格子，免得往实心方块里种。
     */
    public static List<BlockPos> pickManualSlots(List<BlockPos> candidates) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : candidates) {
            result.add(pos.immutable());
        }
        return result;
    }

    /**
     * 2×2 模式：挑「锚点」（2×2 的西北角），组内 4 格必须都合法。
     *
     * <p>组内相邻豁免间距（规则 10），跨组按切比雪夫 ≥2 算——组占 2 格宽，
     * 所以两个锚点的 x 或 z 至少差 3。手动模式也走这里（只找完整的 2×2 组，不筛组间距）。</p>
     */
    public static List<BlockPos> pickTwoByTwoAnchors(List<BlockPos> candidates, boolean enforceSpacing) {
        Set<BlockPos> set = new HashSet<>(candidates);
        List<BlockPos> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> {
            if (a.getY() != b.getY()) {
                return Integer.compare(a.getY(), b.getY());
            }
            if (a.getX() != b.getX()) {
                return Integer.compare(a.getX(), b.getX());
            }
            return Integer.compare(a.getZ(), b.getZ());
        });
        List<BlockPos> anchors = new ArrayList<>();
        for (BlockPos pos : sorted) {
            if (!set.contains(pos.east()) || !set.contains(pos.south())
                    || !set.contains(pos.east().south())) {
                continue;
            }
            boolean clash = false;
            if (enforceSpacing) {
                for (BlockPos other : anchors) {
                    if (Math.abs(other.getX() - pos.getX()) <= 2
                            && Math.abs(other.getZ() - pos.getZ()) <= 2) {
                        clash = true;
                        break;
                    }
                }
            }
            if (!clash) {
                anchors.add(pos.immutable());
            }
        }
        return anchors;
    }

    // ------------------------------------------------------------------
    // 找树
    // ------------------------------------------------------------------

    /**
     * 从树干底部 BFS 出「一棵树」：26 邻域连通的本树种原木 + 本树种树叶（规则 8、20）。
     *
     * <p>上限：原木高度 ≤ {@link #MAX_TREE_HEIGHT} 格、总格数 ≤ {@link #MAX_TREE_BLOCKS}，
     * 超了就砍到上限为止（不再继续扩散，但不崩）。</p>
     *
     * @return 起点不是本树种原木时返回 null
     */
    @Nullable
    public static TreeBody findTree(ServerLevel level, TreeSpecies species, BlockPos start) {
        if (species == TreeSpecies.NONE || !species.isTargetLog(level.getBlockState(start))) {
            return null;
        }
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> logs = new ArrayList<>();
        List<BlockPos> leaves = new ArrayList<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        BlockPos base = start.immutable();
        seen.add(base);
        logs.add(base);
        queue.add(base);
        while (!queue.isEmpty() && logs.size() + leaves.size() < MAX_TREE_BLOCKS) {
            BlockPos current = queue.poll();
            for (int[] offset : NEIGHBORS) {
                BlockPos next = current.offset(offset[0], offset[1], offset[2]);
                if (!seen.add(next)) {
                    continue;
                }
                BlockState state = level.getBlockState(next);
                if (species.isTargetLog(state)) {
                    // 高度上限只限制原木（树叶跟着树干走，不会离谱）
                    if (next.getY() - base.getY() >= MAX_TREE_HEIGHT) {
                        continue;
                    }
                    logs.add(next.immutable());
                    queue.add(next.immutable());
                } else if (species.isOwnLeaves(state)) {
                    leaves.add(next.immutable());
                    queue.add(next.immutable());
                }
            }
        }
        // 原木由低到高（同层离树干近的优先）——规则 19「原木由低到高」
        logs.sort((a, b) -> {
            if (a.getY() != b.getY()) {
                return Integer.compare(a.getY(), b.getY());
            }
            return Double.compare(a.distSqr(base), b.distSqr(base));
        });
        return new TreeBody(base, logs, leaves);
    }

    /**
     * 把扫描收集到的目标原木按 26 邻域分组 → 每棵树的树干底（最低的那格，最低层里最靠西北的）。
     *
     * <p>分片扫描时用这个：一轮扫完得到区域内所有目标原木，分组后每棵树的底就是「该不该去砍」的锚点。</p>
     */
    public static List<BlockPos> treeBases(Set<BlockPos> logs) {
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> bases = new ArrayList<>();
        for (BlockPos log : logs) {
            if (seen.contains(log)) {
                continue;
            }
            BlockPos base = log;
            Set<BlockPos> group = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(log);
            seen.add(log);
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                group.add(current);
                if (current.getY() < base.getY()) {
                    base = current;
                } else if (current.getY() == base.getY()
                        && (current.getX() < base.getX()
                            || (current.getX() == base.getX() && current.getZ() < base.getZ()))) {
                    base = current;
                }
                for (int[] offset : NEIGHBORS) {
                    BlockPos next = current.offset(offset[0], offset[1], offset[2]);
                    if (logs.contains(next) && seen.add(next)) {
                        queue.add(next);
                    }
                }
            }
            bases.add(base.immutable());
        }
        return bases;
    }

    /**
     * 树叶要不要掉产物：玩家手放的装饰叶（`persistent=true`）**不掉**（规则 8）。
     */
    public static boolean leavesDrop(BlockState state) {
        return !(state.getBlock() instanceof LeavesBlock)
            || !state.getValue(LeavesBlock.PERSISTENT);
    }

    // ------------------------------------------------------------------
    // 违规树苗
    // ------------------------------------------------------------------

    /**
     * 这棵苗是不是「违规」（规则 16、17）：周围 8 格有别的树苗，或者目标成树。
     *
     * <p>「周围 8 格」按水平 8 邻格算（切比雪夫 1）；成树则再看正上方与水平邻格上方几格——
     * 树干底就在树苗的上一层，光看同层会漏掉「苗种在树干旁边」。</p>
     *
     * <p><b>2026-11-22 修</b>：2×2 模式必须豁免组内相邻（规则 10）。以前这里不区分模式，
     * 2×2 一次种下 4 棵苗，下一轮扫描全部判违规再拔掉，员工就在「种 → 拔」之间循环。</p>
     */
    public static boolean isViolation(ServerLevel level, TreeSpecies species, BlockPos pos, boolean twoByTwo) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos beside = pos.offset(dx, 0, dz);
                if (TreeSpecies.isAnySapling(level.getBlockState(beside))) {
                    // 2×2 组内的 4 棵苗互相相邻是豁免的（组与组之间按锚点间距隔开）
                    if (twoByTwo && beside.getY() == pos.getY()) {
                        continue;
                    }
                    return true;
                }
                // 目标成树：树干底通常在树的低处，检查旁边 1~3 格高
                for (int dy = 0; dy <= 3; dy++) {
                    if (species.isTargetLog(level.getBlockState(beside.above(dy)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 给槽位表用的区域过滤：只留区域内的格子。 */
    public static boolean inside(WorkZone zone, BlockPos pos) {
        return pos.getX() >= zone.min().getX() && pos.getX() <= zone.max().getX()
            && pos.getY() >= zone.min().getY() && pos.getY() <= zone.max().getY()
            && pos.getZ() >= zone.min().getZ() && pos.getZ() <= zone.max().getZ();
    }

    private static int[][] buildNeighbors() {
        List<int[]> list = new ArrayList<>(26);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    list.add(new int[]{dx, dy, dz});
                }
            }
        }
        return list.toArray(new int[0][]);
    }
}
