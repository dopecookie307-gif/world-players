package com.worldplayers.client;

import com.worldplayers.resident.path.PathNode;
import com.worldplayers.resident.path.PathSegment;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** 客户端缓存：服务端同步过来的整张路径网（登录时 + 每次改动后）。 */
public final class PathStore {
    private static volatile List<PathNode> nodes = List.of();
    private static volatile List<PathSegment> segments = List.of();

    public static void set(List<PathNode> newNodes, List<PathSegment> newSegments) {
        nodes = newNodes == null ? List.of() : List.copyOf(newNodes);
        segments = newSegments == null ? List.of() : List.copyOf(newSegments);
    }

    public static List<PathNode> nodes() {
        return nodes;
    }

    public static List<PathSegment> segments() {
        return segments;
    }

    /** 当前维度（没有世界时返回 null）。 */
    @Nullable
    public static ResourceLocation currentDimension() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : minecraft.level.dimension().location();
    }

    /** 这个位置（当前维度）上的节点；没有返回 null。 */
    @Nullable
    public static PathNode nodeAt(BlockPos pos) {
        ResourceLocation dimension = currentDimension();
        if (dimension == null) {
            return null;
        }
        for (PathNode node : nodes) {
            if (node.pos().equals(pos) && node.dimension().equals(dimension)) {
                return node;
            }
        }
        return null;
    }

    @Nullable
    public static PathNode byId(int id) {
        for (PathNode node : nodes) {
            if (node.id() == id) {
                return node;
            }
        }
        return null;
    }

    /** 当前维度、以 center 为中心 radius 格内的节点（渲染只取这一批）。 */
    public static List<PathNode> near(BlockPos center, int radius) {
        ResourceLocation dimension = currentDimension();
        if (dimension == null) {
            return List.of();
        }
        double limit = (double) radius * radius;
        List<PathNode> found = new ArrayList<>();
        for (PathNode node : nodes) {
            if (node.dimension().equals(dimension)
                    && node.pos().distSqr(center) <= limit) {
                found.add(node);
            }
        }
        return found;
    }

    /** 这一段两端的节点都在（当前维度）时返回它们，否则 null。 */
    @Nullable
    public static PathNode[] segmentNodes(PathSegment segment) {
        PathNode from = byId(segment.from());
        PathNode to = byId(segment.to());
        if (from == null || to == null) {
            return null;
        }
        ResourceLocation dimension = currentDimension();
        if (dimension == null
                || !from.dimension().equals(dimension) || !to.dimension().equals(dimension)) {
            return null;
        }
        return new PathNode[]{from, to};
    }

    private PathStore() {
    }
}
