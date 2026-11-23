package com.worldplayers.resident.project;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Path;

/**
 * 工程区**站位点**（规格 5.2）：员工站在区域**外面**、够得着里面每一格的位置。
 *
 * <p>从外环 1 格开始往外找（最多外扩 3 层），要求安全可站、不在禁行区、不在任何工程区内、
 * 寻路可达，并且避开同区域其他员工已经站着的格子。</p>
 */
public final class ProjectStance {
    /** 最多往外找几层（规格 5.2 / 11.7）。 */
    private static final int MAX_RINGS = 3;
    /** 每层最多验几条寻路（防止大区域一次 createPath 太多次）。 */
    private static final int MAX_PATH_CHECKS = 24;

    /**
     * 找站位点。
     *
     * @param taken 同区域其他员工占着的格（他们的脚下方块坐标）
     */
    @Nullable
    public static BlockPos find(ServerLevel level, WorkZone zone, ResidentEntity resident,
                                Set<BlockPos> taken) {
        BlockPos self = resident.blockPosition();
        WorkZoneData data = WorkZoneData.get(level);
        for (int ring = 1; ring <= MAX_RINGS; ring++) {
            List<BlockPos> candidates = ringCells(level, zone, ring);
            candidates.removeIf(pos -> taken.contains(pos)
                || !ResidentEntity.isSafeStand(level, pos)
                || data.isForbidden(pos)
                || data.isProjectArea(pos));
            candidates.sort(Comparator.comparingDouble(pos -> self.distSqr(pos)));
            int checks = 0;
            for (BlockPos pos : candidates) {
                if (checks++ >= MAX_PATH_CHECKS) {
                    break;
                }
                Path path = resident.getNavigation().createPath(pos, 1);
                if (path != null && path.canReach()) {
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    /** 第 ring 层外环上的所有格子（区域外一圈，y 方向上下各多 1 格）。 */
    private static List<BlockPos> ringCells(ServerLevel level, WorkZone zone, int ring) {
        List<BlockPos> out = new ArrayList<>();
        int minX = zone.min().getX() - ring;
        int maxX = zone.max().getX() + ring;
        int minZ = zone.min().getZ() - ring;
        int maxZ = zone.max().getZ() + ring;
        int minY = zone.min().getY() - 1;
        int maxY = zone.max().getY() + 1;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // 只取「环带」：x 或 z 在区域范围之外
                boolean outside = x < zone.min().getX() || x > zone.max().getX()
                    || z < zone.min().getZ() || z > zone.max().getZ();
                if (!outside) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    private ProjectStance() {
    }
}
