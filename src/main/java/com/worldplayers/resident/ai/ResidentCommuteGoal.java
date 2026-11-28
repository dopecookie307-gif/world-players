package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import java.util.EnumSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * 通勤：走向指定目的地（调试棒 / 回床）。
 *
 * <p>行为约定（主人 2026-11-19）：</p>
 * <ul>
 *   <li>工作状态显示「通勤」，浮窗里再显示一行**没有前置标题**的目的地名；</li>
 *   <li>速度按心情档位：红（&lt;50）步行 90%、黄（50~75）4 格/秒、绿（&gt;=75）5 格/秒，封顶玩家奔跑；</li>
 *   <li>到达（1.5 格内）或超时（45 秒）后结束，恢复游荡速度、清掉目的地；</li>
 *   <li>被战斗/玩家翻背包打断时**保留目标**，打完了继续走（不丢指令）。</li>
 * </ul>
 *
 * <p>**2026-11-24（`0.3.1-alpha.5`）两条让路规则：**</p>
 * <ol>
 *   <li>**跟随 / 站立永远最高优先级**：本 goal 只在「自由活动」（{@code IDLE}）里跑，
 *       玩家切模式时立刻停手，切回来再按原目标继续走；</li>
 *   <li>**路网能走就让路**：同优先级的 {@link ResidentPathNetGoal} 谁也顶不掉谁，
 *       所以这里主动问 {@code pathNetGoal().handlesTrip()} —— 有路线就让它走，走完 / 走不通再接管最后几格。</li>
 * </ol>
 */
public class ResidentCommuteGoal extends Goal {
    /** 到达判定：1.5 格内。 */
    private static final double ARRIVE_SQ = 1.5D * 1.5D;
    /** 重新寻路的间隔（同时用来跟随心情档位变化）。 */
    private static final int REPATH_INTERVAL = 20;
    /** 45 秒还到不了就放弃，避免一直卡在某个角落。 */
    private static final int TIMEOUT_TICKS = 20 * 45;

    private final ResidentEntity resident;
    private int repathTicks;
    private int elapsed;
    private boolean finished;
    /** 目标旁边找出来的落脚点（目标不可站时用；40 tick 缓存）。 */
    @Nullable
    private BlockPos besideSpot;
    @Nullable
    private BlockPos besideSpotFor;
    private int besideSpotAge;

    public ResidentCommuteGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!this.resident.isAlive() || this.resident.isDeadOrDying() || !this.resident.canLookAround()) {
            return false;
        }
        // 跟随 / 站立（玩家指令）永远最高优先级，自动通勤让位
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey())) {
            return false;
        }
        // 路网能走这一趟：让路给 ResidentPathNetGoal（它走完 / 走不通会自动把控制权还回来）
        if (this.resident.pathTripActive() || this.pathNetHandles()) {
            return false;
        }
        BlockPos target = this.resident.commuteTarget();
        return target != null && this.distanceSqTo(target) > ARRIVE_SQ;
    }

    private boolean pathNetHandles() {
        return this.resident.pathNetGoal() != null && this.resident.pathNetGoal().handlesTrip();
    }

    @Override
    public void start() {
        this.elapsed = 0;
        this.repathTicks = 0;
        this.finished = false;
        this.resident.setActivity(ResidentJobs.COMMUTE);
        this.resident.applyTravelSpeed();
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.finished && this.resident.commuteTarget() != null) {
            // 切到跟随 / 站立 → 立刻停手（不只是「不继续」，而是真的让位给玩家指令）
            if (!ResidentJobs.IDLE.equals(this.resident.jobKey())) {
                return false;
            }
            return !this.pathNetHandles();
        }
        return false;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (++this.elapsed >= TIMEOUT_TICKS) {
            this.finished = true;
            return;
        }
        BlockPos target = this.resident.commuteTarget();
        if (target == null || this.distanceSqTo(target) <= ARRIVE_SQ) {
            this.finished = true;
            return;
        }
        // v0.2.11：路径走完就立刻续上，不再死等 20 tick 的节流
        // （以前的表现是「走一段、停一下」；只在真的走完时才提前重算，寻路失败仍按 20 tick 重试）
        boolean pathFinished = this.resident.getNavigation().isDone()
            && this.resident.getNavigation().getPath() != null
            && this.repathTicks <= REPATH_INTERVAL - 2;
        if (--this.repathTicks <= 0 || pathFinished) {
            this.repathTicks = REPATH_INTERVAL;
            this.resident.applyTravelSpeed();
            this.walkTo(target);
        }
    }

    /**
     * 走向目标；**目标本身不可站**（床 / 方块）时改走它旁边的安全格。
     *
     * <p>2026-11-24 主人报「回床时站着不动」（`navDone=true pathEnd=自己位置`）：原版寻路以床那一格为目标时
     * 常常直接给不出路，居民就原地卡住。这里退一步找床边能站的格子，最后 1~2 格照旧交回原版。</p>
     */
    private boolean walkTo(BlockPos target) {
        if (this.resident.getNavigation().moveTo(
                target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D)) {
            this.besideSpot = null;
            return true;
        }
        BlockPos beside = this.besideTarget(target);
        if (beside == null) {
            return false;
        }
        return this.resident.getNavigation().moveTo(
            beside.getX() + 0.5D, beside.getY(), beside.getZ() + 0.5D, 1.0D);
    }

    /** 目标旁边「能站 + 能找到路」的最近一格（结果缓存 40 tick）。 */
    @Nullable
    private BlockPos besideTarget(BlockPos target) {
        if (this.besideSpot != null && target.equals(this.besideSpotFor)
                && this.resident.tickCount - this.besideSpotAge < 40) {
            this.besideSpotAge = this.resident.tickCount;
            return this.besideSpot;
        }
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        BlockPos[] offsets = {
            target.north(), target.south(), target.east(), target.west(),
            target.north().above(), target.south().above(),
            target.east().above(), target.west().above(),
            target.north().below(), target.south().below(),
            target.east().below(), target.west().below(),
        };
        for (BlockPos candidate : offsets) {
            if (!ResidentEntity.isSafeStand(level, candidate)) {
                continue;
            }
            double distance = this.resident.blockPosition().distSqr(candidate);
            if (distance >= bestDistance) {
                continue;
            }
            net.minecraft.world.level.pathfinder.Path path =
                this.resident.getNavigation().createPath(candidate, 1);
            if (path == null || !path.canReach()) {
                continue;
            }
            bestDistance = distance;
            best = candidate;
        }
        this.besideSpot = best;
        this.besideSpotFor = target;
        this.besideSpotAge = this.resident.tickCount;
        return best;
    }

    @Override
    public void stop() {
        this.resident.applyMovementSpeed(ResidentEntity.BASE_MOVEMENT_SPEED);
        if (this.finished) {
            // 到达/超时：任务结束，清掉目的地
            this.resident.clearCommute();
        } else {
            // 被战斗/背包/模式打断：保留目标与目的地，结束后会重新启动
            this.resident.setActivity(null);
        }
    }

    private double distanceSqTo(BlockPos pos) {
        return this.resident.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    /** 诊断用：现在有没有在通勤。 */
    public boolean running() {
        return this.resident.commuteTarget() != null;
    }

    @Nullable
    public static BlockPos targetOf(ResidentEntity resident) {
        return resident.commuteTarget();
    }
}
