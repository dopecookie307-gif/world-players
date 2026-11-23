package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import java.util.EnumSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.AABB;

/**
 * 「有东西要炸了，快跑」（v0.2.3）。
 *
 * <p>触发：{@link ResidentDanger#isFleeDanger} 认定为危险 —— 点燃的 TNT、玩家点着的苦力怕、
 * 闪电苦力怕（弓弩手除外）。优先级 **0**，比战斗（1）还高，所以**正在打架也会先跑**；
 * 危险消失（炸了 / 被打了 / 走远了）再恢复原来的行为。</p>
 *
 * <p>逃跑方式：朝远离最近危险物的方向找一块能站人的地，用 1.3 的速度跑，每 10 tick 重算一次。
 * 不挖方块、不传送，跑不动就继续重算。</p>
 */
public class ResidentDangerFleeGoal extends Goal {
    /** 进入这个半径就开跑。 */
    private static final double DETECT_RADIUS = 10.0D;
    /** 跑到这个半径以外（或危险消失）才停。 */
    private static final double ESCAPE_RADIUS = 16.0D;
    private static final double SPEED = 1.3D;
    /** 重新找逃跑路线 / 找安全点的间隔。 */
    private static final int REPATH_TICKS = 10;
    /** 危险清单重新扫描的间隔（别每 tick 扫实体表）。 */
    private static final int SCAN_TICKS = 5;

    private final ResidentEntity resident;

    @Nullable
    private Entity danger;
    private int repath;
    private int scanCooldown;

    public ResidentDangerFleeGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!this.canFlee()) {
            return false;
        }
        this.danger = this.findDanger(DETECT_RADIUS);
        return this.danger != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.canFlee()) {
            return false;
        }
        this.danger = this.findDanger(ESCAPE_RADIUS);
        return this.danger != null;
    }

    @Override
    public void start() {
        this.repath = 0;
        this.resident.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (this.danger == null) {
            this.danger = this.findDanger(ESCAPE_RADIUS);
            if (this.danger == null) {
                return;
            }
        }
        if (--this.repath <= 0 || this.resident.getNavigation().isDone()) {
            this.repath = REPATH_TICKS;
            this.runAway();
        }
    }

    @Override
    public void stop() {
        this.danger = null;
        this.resident.getNavigation().stop();
    }

    // ------------------------------------------------------------------

    /** 活着、在服务端、没骑在什么上面（骑乘时交给玩家/载具）。 */
    private boolean canFlee() {
        return this.resident.isAlive()
            && !this.resident.isDeadOrDying()
            && !this.resident.isPassenger()
            && this.resident.level() instanceof ServerLevel;
    }

    /**
     * 最近的危险物。
     *
     * <p>用 {@code PrimedTnt} / {@code Creeper} 两个类查询（比扫全部实体省），
     * 并缓存几 tick —— 最近的那个还活着且在范围内就直接复用。</p>
     */
    @Nullable
    private Entity findDanger(double radius) {
        if (this.scanCooldown > 0 && this.danger != null
                && this.danger.isAlive() && !this.danger.isRemoved()
                && this.resident.distanceToSqr(this.danger) <= radius * radius) {
            this.scanCooldown--;
            return this.danger;
        }
        this.scanCooldown = SCAN_TICKS;
        AABB box = this.resident.getBoundingBox().inflate(radius, radius * 0.5D, radius);
        Entity best = null;
        double bestDistance = radius * radius;
        for (PrimedTnt tnt : this.resident.level().getEntitiesOfClass(PrimedTnt.class, box,
                tnt -> tnt.isAlive() && tnt.getFuse() > 0)) {
            double distance = this.resident.distanceToSqr(tnt);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = tnt;
            }
        }
        for (Creeper creeper : this.resident.level().getEntitiesOfClass(Creeper.class, box,
                creeper -> creeper.isAlive() && ResidentDanger.isFleeDanger(this.resident, creeper))) {
            double distance = this.resident.distanceToSqr(creeper);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = creeper;
            }
        }
        return best;
    }

    /** 朝远离危险物的方向跑，落点优先选能站人的地方。 */
    private void runAway() {
        BlockPos target = this.escapeTarget();
        if (target == null) {
            return;
        }
        this.resident.getNavigation().moveTo(
            target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, SPEED);
    }

    @Nullable
    private BlockPos escapeTarget() {
        Entity danger = this.danger;
        if (danger == null) {
            return null;
        }
        double dx = this.resident.getX() - danger.getX();
        double dz = this.resident.getZ() - danger.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-3D) {
            // 和危险物重叠了：随便挑一个方向
            dx = 1.0D;
            dz = 0.0D;
            length = 1.0D;
        }
        double distance = Math.min(10.0D, Math.max(6.0D, length + 6.0D));
        // 正后方优先，被墙挡了就左右偏 45°/90° 再试
        double[] offsets = {0.0D, Math.PI / 4.0D, -Math.PI / 4.0D, Math.PI / 2.0D, -Math.PI / 2.0D};
        for (double offset : offsets) {
            double cos = Math.cos(offset);
            double sin = Math.sin(offset);
            double ax = (dx * cos - dz * sin) / length;
            double az = (dx * sin + dz * cos) / length;
            double x = this.resident.getX() + ax * distance;
            double z = this.resident.getZ() + az * distance;
            BlockPos safe = this.findSafeGround(BlockPos.containing(x, this.resident.getY(), z));
            if (safe != null) {
                return safe;
            }
        }
        return null;
    }

    /** 以给定位置为中心找一个能站人的方块（脚下实心、两格空、不是危险方块），优先离中心最近的。 */
    @Nullable
    private BlockPos findSafeGround(BlockPos center) {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        double cx = center.getX() + 0.5D;
        double cy = center.getY() + 0.5D;
        double cz = center.getZ() + 0.5D;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!ResidentEntity.isSafeStand(level, pos)) {
                        continue;
                    }
                    double px = pos.getX() + 0.5D - cx;
                    double py = pos.getY() + 0.5D - cy;
                    double pz = pos.getZ() + 0.5D - cz;
                    double distance = px * px + py * py + pz * pz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }
}
