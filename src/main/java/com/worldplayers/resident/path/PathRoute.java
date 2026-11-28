package com.worldplayers.resident.path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * 路网寻路（第二轮，`0.3.1-alpha.4`，2026-11-24）。
 *
 * <p>「大头走路网，小头用原版」（{@code 路径网系统_设计草案.md} 第 1 节）：</p>
 * <ol>
 *   <li>找**入口点**：离居民最近的节点（{@link #ENTRY_RANGE} 格内）；</li>
 *   <li>找**出口点**：离目标最近的节点（{@link #EXIT_RANGE} 格内）；</li>
 *   <li>在段上跑 Dijkstra（节点几十～几百个，开销可以忽略），拿回一串要依次走过去的点；</li>
 *   <li>到了出口点就交还原版寻路走最后几格。</li>
 * </ol>
 *
 * <p>**段一律按双向走**（第一轮的数据里 {@code bidirectional} 还没有界面能改，第三轮做排队时再区分单向）。</p>
 *
 * <p>没有路网 / 没连上 / 太近 → 返回 null，调用方回落老行为。</p>
 */
public final class PathRoute {
    /** 入口点搜索范围（格）。 */
    public static final int ENTRY_RANGE = 48;
    /** 出口点搜索范围（格）。 */
    public static final int EXIT_RANGE = 48;
    /** 起点到目的地直线距离小于这个就别管路网了（原版寻路够用）。 */
    public static final double MIN_TRIP_DISTANCE = 12.0D;
    /** 一条路线最长多少格（防止数据坏掉绕地球一圈）。 */
    public static final double MAX_ROUTE_LENGTH = 1024.0D;
    /** 入口点最多试几个（每个都要验「原版走得到」，别拖慢规划）。 */
    private static final int MAX_ENTRY_TRIES = 4;

    /** 认为「同一层」的垂直差（格）。 */
    private static final int SAME_FLOOR_DY = 2;
    /** 垂直距离的折算权重：跨一层 ≈ 多走 4 格水平距离。 */
    private static final double VERTICAL_WEIGHT = 4.0D;
    /** 「就在附近」的水平距离（优先挑同层又近的入口，别让人为了上路网横穿半栋楼）。 */
    private static final double NEAR_HORIZONTAL = 12.0D;

    /** 规划一条从 {@code from} 到 {@code to} 的路网路线（入口点不做可达验证）。 */
    @Nullable
    public static List<PathNode> plan(ServerLevel level, BlockPos from, BlockPos to) {
        return plan(level, from, to, null);
    }

    /**
     * 规划一条从 {@code from} 到 {@code to} 的路网路线。
     *
     * @param entryReachable 可选：验证「居民能不能用原版寻路走到这个入口点」。
     *                       2026-11-24 主人报「只能把居民拖到楼梯上才肯走」：入口点离得近但被墙挡住时，
     *                       它会一直卡在原地 —— 这里按「同层就近」的顺序逐个试，挑第一个走得到的入口。
     * @return 依次要走过的点（含入口点和出口点）；不需要 / 走不通时返回 null
     */
    @Nullable
    public static List<PathNode> plan(ServerLevel level, BlockPos from, BlockPos to,
                                      @Nullable Predicate<PathNode> entryReachable) {
        if (from.distSqr(to) < MIN_TRIP_DISTANCE * MIN_TRIP_DISTANCE) {
            return null;
        }
        PathNetworkData data = PathNetworkData.get(level);
        ResourceLocation dimension = level.dimension().location();
        List<PathNode> nodes = new ArrayList<>();
        for (PathNode node : data.nodes()) {
            if (node.dimension().equals(dimension)) {
                nodes.add(node);
            }
        }
        if (nodes.size() < 2) {
            return null;
        }
        PathNode end = nearest(nodes, to, EXIT_RANGE);
        if (end == null) {
            return null;
        }
        Map<Integer, List<PathSegment>> edges = new HashMap<>();
        for (PathSegment segment : data.segments()) {
            edges.computeIfAbsent(segment.from(), key -> new ArrayList<>()).add(segment);
            edges.computeIfAbsent(segment.to(), key -> new ArrayList<>()).add(segment);
        }
        Map<Integer, PathNode> byId = new HashMap<>();
        for (PathNode node : nodes) {
            byId.put(node.id(), node);
        }
        int tried = 0;
        for (PathNode start : orderedCandidates(nodes, from, ENTRY_RANGE)) {
            if (start.id() == end.id()) {
                continue;
            }
            if (entryReachable != null && !entryReachable.test(start)) {
                continue;
            }
            if (++tried > MAX_ENTRY_TRIES) {
                break;
            }
            List<PathNode> route = shortest(edges, byId, start, end);
            if (route != null) {
                return route;
            }
        }
        return null;
    }

    /** 从 {@code start} 到 {@code end} 的 Dijkstra（双向段）。没连上 / 太远返回 null。 */
    @Nullable
    private static List<PathNode> shortest(Map<Integer, List<PathSegment>> edges,
                                           Map<Integer, PathNode> byId,
                                           PathNode start, PathNode end) {
        Map<Integer, Double> best = new HashMap<>();
        Map<Integer, Integer> prev = new HashMap<>();
        // 优先队列里存 {节点 id, 已走代价 ×1000 的整数}，避免自定义 record
        PriorityQueue<int[]> queue = new PriorityQueue<>(
            Comparator.comparingDouble((int[] entry) -> (double) entry[1]));
        best.put(start.id(), 0.0D);
        queue.add(new int[]{start.id(), 0});
        while (!queue.isEmpty()) {
            int[] top = queue.poll();
            int currentId = top[0];
            PathNode current = byId.get(currentId);
            if (current == null) {
                continue;
            }
            double currentCost = best.getOrDefault(currentId, Double.MAX_VALUE);
            if (top[1] > currentCost * 1000.0D + 1.0D) {
                continue;
            }
            if (currentId == end.id()) {
                break;
            }
            for (PathSegment segment : edges.getOrDefault(currentId, List.of())) {
                int nextId = segment.from() == currentId ? segment.to() : segment.from();
                PathNode next = byId.get(nextId);
                if (next == null) {
                    continue;
                }
                double step = Math.sqrt(current.pos().distSqr(next.pos()));
                double cost = currentCost + step;
                if (cost < best.getOrDefault(nextId, Double.MAX_VALUE) - 1.0E-6D) {
                    best.put(nextId, cost);
                    prev.put(nextId, currentId);
                    queue.add(new int[]{nextId, (int) Math.round(cost * 1000.0D)});
                }
            }
        }
        if (!best.containsKey(end.id()) || best.get(end.id()) > MAX_ROUTE_LENGTH) {
            return null;
        }
        List<PathNode> route = new ArrayList<>();
        Integer cursor = end.id();
        int guard = 0;
        while (cursor != null && guard++ < 512) {
            PathNode node = byId.get(cursor);
            if (node == null) {
                return null;
            }
            route.add(0, node);
            if (cursor == start.id()) {
                break;
            }
            cursor = prev.get(cursor);
        }
        if (route.isEmpty() || route.get(0).id() != start.id()) {
            return null;
        }
        return List.copyOf(route);
    }

    /**
     * 范围内离 {@code pos} 最近的节点（同维度由调用方先过滤过）。
     *
     * <p>**必须优先同层**（2026-11-24 主人报「回床跑到自己房间底下那间」）：纯 3D 最近会把
     * 「楼上正下方」的锚点算得比同层隔几格的那个更近，居民就会走到下一层的同名房间再卡住。
     * 三档：同层且水平 ≤12 格 → 同层 → 全部（打分 = 水平 + 4×垂直）。</p>
     */
    @Nullable
    private static PathNode nearest(List<PathNode> nodes, BlockPos pos, int range) {
        List<PathNode> ordered = orderedCandidates(nodes, pos, range);
        return ordered.isEmpty() ? null : ordered.get(0);
    }

    /** 按「同层就近 → 同层 → 其他」排好序的候选节点（入口点按这个顺序逐个试）。 */
    private static List<PathNode> orderedCandidates(List<PathNode> nodes, BlockPos pos, int range) {
        List<PathNode> near = new ArrayList<>();
        List<PathNode> same = new ArrayList<>();
        List<PathNode> rest = new ArrayList<>();
        Map<PathNode, Double> score = new HashMap<>();
        double limit = (double) range * range;
        for (PathNode node : nodes) {
            if (node.pos().distSqr(pos) > limit) {
                continue;
            }
            double dx = node.pos().getX() - pos.getX();
            double dz = node.pos().getZ() - pos.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            int dy = Math.abs(node.pos().getY() - pos.getY());
            double value = horizontal + VERTICAL_WEIGHT * dy;
            score.put(node, value);
            if (dy > SAME_FLOOR_DY) {
                rest.add(node);
            } else if (horizontal <= NEAR_HORIZONTAL) {
                near.add(node);
            } else {
                same.add(node);
            }
        }
        Comparator<PathNode> byScore =
            Comparator.comparingDouble(node -> score.getOrDefault(node, Double.MAX_VALUE));
        near.sort(byScore);
        same.sort(byScore);
        rest.sort(byScore);
        List<PathNode> ordered = new ArrayList<>(near);
        ordered.addAll(same);
        ordered.addAll(rest);
        return ordered;
    }

    private PathRoute() {
    }
}
