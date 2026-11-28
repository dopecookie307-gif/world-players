package com.worldplayers.resident.path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 居民路径网（锚点 + 路径点 + 路段），存主世界的存档数据里。
 *
 * <p>与 {@code WorkZoneData} / {@code BedBindingData} 同级：**没有路网数据时一律走老行为**，
 * 老存档打开就是一张空网，不崩、不丢东西。</p>
 *
 * <p>第一轮只做「画 + 看」，居民还不读这张网（第二轮才接执行）。</p>
 */
public class PathNetworkData extends SavedData {
    private static final String DATA_NAME = "worldplayers_path_network";
    private static final String TAG_NODES = "Nodes";
    private static final String TAG_SEGMENTS = "Segments";
    private static final String TAG_NEXT_ID = "NextId";

    /** 一条段最长多少格（只防手滑/坏包，路径点正常 2~3 格一个）。 */
    public static final double MAX_SEGMENT_LENGTH = 64.0D;
    /**
     * 「在路段上」的判定距离（格，第三轮 2026-11-24）：离最近路段中心线 3 格以内算在路上。
     * 闲逛 / 逗留的点要避开这个范围，居民才不会在窄楼梯上挡着赶路的人。
     */
    public static final double LANE_CLEARANCE = 3.0D;
    /** 节点附近多少格算「停靠点」：在路上也可以站，不算逗留。 */
    public static final double LANDING_RADIUS = 2.5D;

    private final List<PathNode> nodes = new ArrayList<>();
    private final List<PathSegment> segments = new ArrayList<>();
    private int nextId = 1;

    /** 一次编辑的结果（用于给玩家发提示）。 */
    public enum EditKind {
        /** 新建了一个路径点。 */
        CREATED,
        /** 路径点升级成了锚点。 */
        UPGRADED,
        /** 删掉了一个点。 */
        REMOVED,
        /** 改了动作。 */
        ACTION_CHANGED,
        /** 什么都没做（重复操作 / 找不到）。 */
        NONE
    }

    /** 编辑结果：类型 + 受影响的那个点（可能为 null）。 */
    public record Edit(EditKind kind, @Nullable PathNode node) {
    }

    public PathNetworkData() {
    }

    public static PathNetworkData get(ServerLevel anyLevel) {
        ServerLevel overworld = anyLevel.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(PathNetworkData::new, PathNetworkData::load), DATA_NAME);
    }

    public List<PathNode> nodes() {
        return List.copyOf(this.nodes);
    }

    public List<PathSegment> segments() {
        return List.copyOf(this.segments);
    }

    /** 这个位置（这个维度）上的点；没有返回 null。 */
    @Nullable
    public PathNode find(BlockPos pos, ResourceLocation dimension) {
        for (PathNode node : this.nodes) {
            if (node.pos().equals(pos) && node.dimension().equals(dimension)) {
                return node;
            }
        }
        return null;
    }

    @Nullable
    public PathNode byId(int id) {
        for (PathNode node : this.nodes) {
            if (node.id() == id) {
                return node;
            }
        }
        return null;
    }

    private PathNode replace(PathNode node) {
        for (int i = 0; i < this.nodes.size(); i++) {
            if (this.nodes.get(i).id() == node.id()) {
                this.nodes.set(i, node);
                this.setDirty();
                return node;
            }
        }
        return node;
    }

    /**
     * 左键三态：第一次放路径点 → 第二次升级成锚点 → 第三次删除。
     *
     * @param action   新点要用的动作（客户端滚轮选的）
     * @param linkFrom 上一个放下的点（0 = 不连段）
     */
    public Edit cycle(BlockPos pos, ResourceLocation dimension, PathAction action, int linkFrom) {
        PathNode existing = find(pos, dimension);
        if (existing == null) {
            PathNode node = new PathNode(this.nextId++, pos, dimension, false,
                action, 1, true);
            this.nodes.add(node);
            this.setDirty();
            // 优先接「上一个操作过的点」；没有（编辑过别的点 / 删除过 / 刚进世界）就接空间上最近的节点，
            // 保证画出来的点之间一定有细线（主人 2026-11-24 反馈：只在连续放点时才连线）
            int target = byId(linkFrom) != null ? linkFrom : nearestNode(node);
            if (target > 0) {
                link(target, node.id());
            }
            return new Edit(EditKind.CREATED, node);
        }
        if (!existing.anchor()) {
            PathNode anchor = replace(existing.asAnchor());
            if (byId(linkFrom) != null) {
                link(linkFrom, anchor.id());
            }
            return new Edit(EditKind.UPGRADED, anchor);
        }
        // 锚点再点一次 = 删除（连着它的段一起删）
        this.nodes.removeIf(node -> node.id() == existing.id());
        this.segments.removeIf(segment -> segment.touches(existing.id()));
        this.setDirty();
        return new Edit(EditKind.REMOVED, existing);
    }

    /**
     * 32 格内、同维度最近的节点（优先「还没有任何段」的孤立点，让孤立点被接上；没有就退化成最近点）。
     *
     * <p>**2026-11-24 追加：优先同层**。纯直线最近会把「楼梯上下正对」的两个点连成一段，
     * 居民照着走就会卡在跨楼层的高差上（主人报「跑到自己房间底下那间、上不去」）。
     * 现在按「同层（|dy|≤2）且未连的点 → 任何未连的点 → 同层的点 → 任何点」挑，
     * 打分 = 水平距离 + 4×垂直距离。没有就返回 -1。</p>
     */
    private int nearestNode(PathNode node) {
        int unlinkedSameFloor = -1;
        int unlinkedAny = -1;
        int anySameFloor = -1;
        int anyNode = -1;
        double unlinkedSameScore = Double.MAX_VALUE;
        double unlinkedAnyScore = Double.MAX_VALUE;
        double anySameScore = Double.MAX_VALUE;
        double anyScore = Double.MAX_VALUE;
        for (PathNode other : this.nodes) {
            if (other.id() == node.id() || !other.dimension().equals(node.dimension())) {
                continue;
            }
            if (other.pos().distSqr(node.pos()) > 32.0D * 32.0D) {
                continue;
            }
            double dx = other.pos().getX() - node.pos().getX();
            double dz = other.pos().getZ() - node.pos().getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            int dy = Math.abs(other.pos().getY() - node.pos().getY());
            double score = horizontal + 4.0D * dy;
            boolean sameFloor = dy <= 2;
            if (!isLinked(other.id())) {
                if (score < unlinkedAnyScore) {
                    unlinkedAnyScore = score;
                    unlinkedAny = other.id();
                }
                if (sameFloor && score < unlinkedSameScore) {
                    unlinkedSameScore = score;
                    unlinkedSameFloor = other.id();
                }
            }
            if (score < anyScore) {
                anyScore = score;
                anyNode = other.id();
            }
            if (sameFloor && score < anySameScore) {
                anySameScore = score;
                anySameFloor = other.id();
            }
        }
        if (unlinkedSameFloor > 0) {
            return unlinkedSameFloor;
        }
        if (unlinkedAny > 0) {
            return unlinkedAny;
        }
        return anySameFloor > 0 ? anySameFloor : anyNode;
    }

    private boolean isLinked(int nodeId) {
        for (PathSegment segment : this.segments) {
            if (segment.touches(nodeId)) {
                return true;
            }
        }
        return false;
    }

    /** 直接删一个点（Shift+右键确认后走这条）。 */
    public Edit delete(BlockPos pos, ResourceLocation dimension) {
        PathNode node = find(pos, dimension);
        if (node == null) {
            return new Edit(EditKind.NONE, null);
        }
        this.nodes.removeIf(entry -> entry.id() == node.id());
        this.segments.removeIf(segment -> segment.touches(node.id()));
        this.setDirty();
        return new Edit(EditKind.REMOVED, node);
    }

    /** 右键点一个已有的点：动作循环切换。 */
    public Edit cycleAction(BlockPos pos, ResourceLocation dimension) {
        PathNode node = find(pos, dimension);
        if (node == null) {
            return new Edit(EditKind.NONE, null);
        }
        PathNode changed = replace(node.withAction(node.action().next()));
        return new Edit(EditKind.ACTION_CHANGED, changed);
    }

    /** 直接把某个点的动作设成指定值（Alt+滚轮改已选中的点）。 */
    public Edit setAction(BlockPos pos, ResourceLocation dimension, PathAction action) {
        PathNode node = find(pos, dimension);
        if (node == null) {
            return new Edit(EditKind.NONE, null);
        }
        return new Edit(EditKind.ACTION_CHANGED, replace(node.withAction(action)));
    }

    /** 改宽度（以后右键设置界面用；先留服务端入口免得以后改协议）。 */
    public Edit setWidth(BlockPos pos, ResourceLocation dimension, int width) {
        PathNode node = find(pos, dimension);
        if (node == null) {
            return new Edit(EditKind.NONE, null);
        }
        return new Edit(EditKind.ACTION_CHANGED, replace(node.withWidth(width)));
    }

    // ------------------------------------------------------------------
    // 第三轮：排队 / 不逗留的查询（2026-11-24）
    // ------------------------------------------------------------------

    /** 这两个点之间有没有段（没有 = 不归路网管，直接放行）。 */
    public boolean hasSegment(int a, int b) {
        for (PathSegment segment : this.segments) {
            if ((segment.from() == a && segment.to() == b)
                || (segment.from() == b && segment.to() == a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个方块格离**最近路段中心线**有多远（格，3D）。没有段 / 不同维度 → {@link Double#MAX_VALUE}。
     *
     * <p>闲逛选点时用它避开「在路上」的位置（{@link #LANE_CLEARANCE} 以内）。</p>
     */
    public double distanceToLane(BlockPos pos, ResourceLocation dimension) {
        if (this.segments.isEmpty() || this.nodes.isEmpty()) {
            return Double.MAX_VALUE;
        }
        Map<Integer, PathNode> byId = indexById();
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        double best = Double.MAX_VALUE;
        for (PathSegment segment : this.segments) {
            PathNode from = byId.get(segment.from());
            PathNode to = byId.get(segment.to());
            if (from == null || to == null || !from.dimension().equals(dimension)) {
                continue;
            }
            best = Math.min(best, distanceToSegment(px, py, pz, from.pos(), to.pos()));
        }
        return best;
    }

    /** 离最近的节点（停靠点）有多远（格，3D）。 */
    public double distanceToLanding(BlockPos pos, ResourceLocation dimension) {
        double best = Double.MAX_VALUE;
        double px = pos.getX() + 0.5D;
        double py = pos.getY() + 0.5D;
        double pz = pos.getZ() + 0.5D;
        for (PathNode node : this.nodes) {
            if (!node.dimension().equals(dimension)) {
                continue;
            }
            double dx = node.pos().getX() + 0.5D - px;
            double dy = node.pos().getY() + 0.5D - py;
            double dz = node.pos().getZ() + 0.5D - pz;
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    /**
     * 这个位置是不是**路段中间**（在路上、又不在停靠点上）——闲逛 / 逗留要避开的地方。
     *
     * <p>「已经在段上的居民只有停靠点（路径点 / 锚点）可以停，不在段中间逗留」（16 号文档第 4 节）。</p>
     */
    public boolean isTravelLane(BlockPos pos, ResourceLocation dimension) {
        if (this.segments.isEmpty()) {
            return false;
        }
        if (distanceToLanding(pos, dimension) <= LANDING_RADIUS) {
            return false;
        }
        return distanceToLane(pos, dimension) < LANE_CLEARANCE;
    }

    private Map<Integer, PathNode> indexById() {
        Map<Integer, PathNode> map = new HashMap<>();
        for (PathNode node : this.nodes) {
            map.put(node.id(), node);
        }
        return map;
    }

    /** 点到线段（3D）的最短距离。 */
    private static double distanceToSegment(double px, double py, double pz,
                                            BlockPos a, BlockPos b) {
        double ax = a.getX() + 0.5D;
        double ay = a.getY() + 0.5D;
        double az = a.getZ() + 0.5D;
        double bx = b.getX() + 0.5D;
        double by = b.getY() + 0.5D;
        double bz = b.getZ() + 0.5D;
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        double lengthSq = dx * dx + dy * dy + dz * dz;
        double t = 0.0D;
        if (lengthSq > 1.0E-6D) {
            t = ((px - ax) * dx + (py - ay) * dy + (pz - az) * dz) / lengthSq;
            t = Math.max(0.0D, Math.min(1.0D, t));
        }
        double cx = ax + dx * t;
        double cy = ay + dy * t;
        double cz = az + dz * t;
        double ex = px - cx;
        double ey = py - cy;
        double ez = pz - cz;
        return Math.sqrt(ex * ex + ey * ey + ez * ez);
    }

    /** 连一段；不合法（不存在 / 自己连自己 / 太远 / 已连过）就什么都不做。 */
    public boolean link(int fromId, int toId) {
        if (fromId <= 0 || toId <= 0 || fromId == toId) {
            return false;
        }
        PathNode from = byId(fromId);
        PathNode to = byId(toId);
        if (from == null || to == null || !from.dimension().equals(to.dimension())) {
            return false;
        }
        if (from.pos().distSqr(to.pos()) > MAX_SEGMENT_LENGTH * MAX_SEGMENT_LENGTH) {
            return false;
        }
        for (PathSegment segment : this.segments) {
            boolean same = (segment.from() == fromId && segment.to() == toId)
                || (segment.from() == toId && segment.to() == fromId);
            if (same) {
                return false;
            }
        }
        this.segments.add(new PathSegment(fromId, toId));
        this.setDirty();
        return true;
    }

    /** 把整张网发给所有在线玩家（改动后 / 登录时）。 */
    public static void sync(MinecraftServer server) {
        if (server == null) {
            return;
        }
        PathNetworkData data = get(server.overworld());
        net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(
            new com.worldplayers.network.PathSyncPayload(data.nodes(), data.segments()));
    }

    // ------------------------------------------------------------------
    // 存档
    // ------------------------------------------------------------------

    public static PathNetworkData load(CompoundTag tag, HolderLookup.Provider registries) {
        PathNetworkData data = new PathNetworkData();
        data.nextId = tag.contains(TAG_NEXT_ID, Tag.TAG_INT) ? Math.max(1, tag.getInt(TAG_NEXT_ID)) : 1;
        ListTag nodeList = tag.getList(TAG_NODES, Tag.TAG_COMPOUND);
        for (int i = 0; i < nodeList.size(); i++) {
            CompoundTag entry = nodeList.getCompound(i);
            ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("Dim"));
            if (dimension == null) {
                continue;
            }
            data.nodes.add(new PathNode(
                entry.getInt("Id"),
                BlockPos.of(entry.getLong("Pos")),
                dimension,
                entry.getBoolean("Anchor"),
                PathAction.byName(entry.getString("Action")),
                Math.max(PathNode.WIDTH_MIN, Math.min(PathNode.WIDTH_MAX, entry.getInt("Width"))),
                !entry.contains("Bidirectional") || entry.getBoolean("Bidirectional")));
        }
        ListTag segmentList = tag.getList(TAG_SEGMENTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < segmentList.size(); i++) {
            CompoundTag entry = segmentList.getCompound(i);
            data.segments.add(new PathSegment(entry.getInt("From"), entry.getInt("To")));
        }
        // 防御：段引用了不存在的点（手工改档 / 旧数据）→ 丢掉，避免渲染空指针
        data.segments.removeIf(segment ->
            data.byId(segment.from()) == null || data.byId(segment.to()) == null);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag nodeList = new ListTag();
        for (PathNode node : this.nodes) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Id", node.id());
            entry.putLong("Pos", node.pos().asLong());
            entry.putString("Dim", node.dimension().toString());
            entry.putBoolean("Anchor", node.anchor());
            entry.putString("Action", node.action().name());
            entry.putInt("Width", node.width());
            entry.putBoolean("Bidirectional", node.bidirectional());
            nodeList.add(entry);
        }
        tag.put(TAG_NODES, nodeList);
        ListTag segmentList = new ListTag();
        for (PathSegment segment : this.segments) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("From", segment.from());
            entry.putInt("To", segment.to());
            segmentList.add(entry);
        }
        tag.put(TAG_SEGMENTS, segmentList);
        tag.putInt(TAG_NEXT_ID, this.nextId);
        return tag;
    }
}
