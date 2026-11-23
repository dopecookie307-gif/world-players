package com.worldplayers.resident.project;

import com.worldplayers.resident.WorkZone;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 工程区的**区域级待处理队列**（规格 2.4）：挖掘 / 填充共用。
 *
 * <ul>
 *   <li>按 <b>第一选择点（origin）BFS 向外</b>排序：同距离按 y（低→高）→ x → z；</li>
 *   <li>按格「先到先领」（{@link #nextFor}），多人不会重复干同一格；</li>
 *   <li>缓存在区域 key 上，开工 / 改方块 / 体积变了就重建（签名失效）。</li>
 * </ul>
 *
 * <p>「要处理的格」由 {@link ProjectRules} 的判定决定：挖掘 = 可挖格，填充 = 可填格。</p>
 */
public final class ProjectQueue {
    private static final Map<String, ProjectQueue> CACHE = new HashMap<>();

    private String signature = "";
    private List<BlockPos> ordered = List.of();
    private final Map<BlockPos, UUID> claims = new HashMap<>();
    private final Set<BlockPos> finished = new HashSet<>();
    private long doneCount;
    private long total;

    private ProjectQueue() {
    }

    /** 取这个区域对应的队列（没有就建一个空的，等 {@link #rebuild} 填）。 */
    public static ProjectQueue get(WorkZone zone) {
        return CACHE.computeIfAbsent(zone.key(), key -> new ProjectQueue());
    }

    /** 区域删了 / 换了方块：整个缓存作废。 */
    public static void invalidate(String zoneKey) {
        CACHE.remove(zoneKey);
    }

    /** 读档 / 换世界：全部作废（静态缓存跨存档会串数据）。 */
    public static void clearAll() {
        CACHE.clear();
    }

    public List<BlockPos> ordered() {
        return this.ordered;
    }

    public long done() {
        return this.doneCount;
    }

    public long total() {
        return this.total;
    }

    public long remaining() {
        return Math.max(0L, this.ordered.size() - this.finished.size());
    }

    /** 队列里某一格被谁领着（没人领 → null）。 */
    @Nullable
    public UUID ownerOf(BlockPos pos) {
        return this.claims.get(pos);
    }

    /**
     * 重建队列：签名一样就直接用旧的（员工来回走动不会每次都全量扫）。
     *
     * <p>签名 = 区域 key + 方块 + 体积；同时重扫一次进度（挖掘 = 空气数，填充 = 体积 − 可填格数）。</p>
     */
    public void rebuild(ServerLevel level, WorkZone zone) {
        this.rebuild(level, zone, false);
    }

    /** 强制重扫（队列被领空但区域里还有该处理的格时用）。 */
    public void forceRebuild(ServerLevel level, WorkZone zone) {
        this.rebuild(level, zone, true);
    }

    private void rebuild(ServerLevel level, WorkZone zone, boolean force) {
        String newSignature = zone.key() + "|" + zone.projectSignature();
        if (!force && newSignature.equals(this.signature) && !this.ordered.isEmpty()) {
            return;
        }
        this.signature = newSignature;
        boolean fill = zone.isFill();
        List<BlockPos> cells = new ArrayList<>();
        BlockPos origin = zone.origin();
        net.minecraft.world.level.block.Block target = fill ? ProjectRules.blockById(zone.projectBlock()) : null;
        for (BlockPos pos : BlockPos.betweenClosed(zone.min(), zone.max())) {
            BlockPos immutable = pos.immutable();
            boolean needs = fill
                ? (target != null && ProjectRules.isFillable(level, immutable, target))
                : ProjectRules.isDiggable(level, immutable);
            if (needs) {
                cells.add(immutable);
            }
        }
        cells.sort(Comparator
            .comparingInt((BlockPos pos) -> chebyshev(origin, pos))
            .thenComparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ));
        this.ordered = List.copyOf(cells);
        this.claims.clear();
        this.finished.clear();
        this.total = Math.min(Integer.MAX_VALUE, zone.volume());
        this.doneCount = Math.max(0L, zone.volume() - cells.size());
    }

    /** 先到先领：返回下一个待处理格（已经属于自己就继续用它）。全干完 → null。 */
    @Nullable
    public BlockPos nextFor(UUID who) {
        for (BlockPos pos : this.ordered) {
            if (this.finished.contains(pos)) {
                continue;
            }
            UUID owner = this.claims.get(pos);
            if (owner == null) {
                this.claims.put(pos, who);
                return pos;
            }
            if (owner.equals(who)) {
                return pos;
            }
        }
        return null;
    }

    /** 放掉一格（背包满 / 被中断）。 */
    public void release(BlockPos pos) {
        this.claims.remove(pos);
    }

    /** 放掉这个员工领着的所有格（离开区域 / 结束工作）。 */
    public void releaseAll(UUID who) {
        this.claims.entrySet().removeIf(entry -> entry.getValue().equals(who));
    }

    /** 这一格处理完了（进度 +1；重复调用不会加两次）。 */
    public void markDone(BlockPos pos) {
        this.claims.remove(pos);
        if (this.finished.add(pos)) {
            this.doneCount++;
        }
    }

    /** 完成判定复核：全扫一遍区域内还有没有该处理的格（被玩家/水流改过也算）。 */
    public static boolean anyLeft(ServerLevel level, WorkZone zone) {
        boolean fill = zone.isFill();
        net.minecraft.world.level.block.Block target = fill ? ProjectRules.blockById(zone.projectBlock()) : null;
        if (fill && target == null) {
            return false;
        }
        for (BlockPos pos : BlockPos.betweenClosed(zone.min(), zone.max())) {
            BlockPos immutable = pos.immutable();
            boolean needs = fill
                ? ProjectRules.isFillable(level, immutable, target)
                : ProjectRules.isDiggable(level, immutable);
            if (needs) {
                return true;
            }
        }
        return false;
    }

    private static int chebyshev(BlockPos origin, BlockPos pos) {
        return Math.max(Math.abs(pos.getX() - origin.getX()),
            Math.max(Math.abs(pos.getY() - origin.getY()), Math.abs(pos.getZ() - origin.getZ())));
    }
}
