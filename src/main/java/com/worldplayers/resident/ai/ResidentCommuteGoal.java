package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import java.util.EnumSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * 通勤：走向指定目的地（调试棒当前唯一的下达方式，以后接工作区域/日程）。
 *
 * <p>行为约定（主人 2026-11-19）：</p>
 * <ul>
 *   <li>工作状态显示「通勤」，浮窗里再显示一行**没有前置标题**的目的地名（现在写「调试棒」）；</li>
 *   <li>速度按心情档位：红（&lt;50）步行 90%、黄（50~75）4 格/秒、绿（&gt;=75）5 格/秒，封顶玩家奔跑；</li>
 *   <li>到达（1.5 格内）或超时（45 秒）后结束，恢复游荡速度、清掉目的地；</li>
 *   <li>被战斗/玩家翻背包打断时**保留目标**，打完了继续走（不丢指令）。</li>
 * </ul>
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

    public ResidentCommuteGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!this.resident.isAlive() || this.resident.isDeadOrDying() || !this.resident.canLookAround()) {
            return false;
        }
        BlockPos target = this.resident.commuteTarget();
        return target != null && this.distanceSqTo(target) > ARRIVE_SQ;
    }

    @Override
    public void start() {
        this.elapsed = 0;
        this.repathTicks = 0;
        this.finished = false;
        this.resident.setActivity(ResidentJobs.COMMUTE);
        this.resident.applyMovementSpeed(ResidentEntity.moodMovementSpeed(this.resident.mood()));
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished && this.resident.commuteTarget() != null;
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
            this.resident.applyMovementSpeed(ResidentEntity.moodMovementSpeed(this.resident.mood()));
            this.resident.getNavigation().moveTo(
                target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
        }
    }

    @Override
    public void stop() {
        this.resident.applyMovementSpeed(ResidentEntity.BASE_MOVEMENT_SPEED);
        if (this.finished) {
            // 到达/超时：任务结束，清掉目的地
            this.resident.clearCommute();
        } else {
            // 被战斗/背包打断：保留目标与目的地，结束后会重新启动
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
