package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * 跟随模式：照狼/狗的思路——快步贴着玩家走，离开太远就直接传送到玩家附近的**安全方块**上。
 *
 * <p>跟随时不捡东西、不闲逛（这个 goal 占用 MOVE，闲逛那一层进不来）。</p>
 */
public class ResidentFollowGoal extends Goal {
    private static final double STOP_DISTANCE = 4.0D;
    private static final double TELEPORT_DISTANCE = 24.0D;
    private static final double STUCK_DISTANCE = 12.0D;
    private static final int STUCK_TICKS = 60;

    private final ResidentEntity resident;
    private int stuckTicks;

    public ResidentFollowGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        return ResidentJobs.FOLLOW.equals(this.resident.jobKey()) && this.resident.nearestPlayer(160.0D) != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public void tick() {
        Player owner = this.resident.nearestPlayer(160.0D);
        if (owner == null) {
            return;
        }
        double distance = this.resident.distanceToSqr(owner);
        if (distance > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            this.teleportNear(owner);
            return;
        }
        if (distance > STUCK_DISTANCE * STUCK_DISTANCE && this.resident.getNavigation().isDone()) {
            if (++this.stuckTicks > STUCK_TICKS) {
                this.teleportNear(owner);
                return;
            }
        } else if (distance <= STOP_DISTANCE * STOP_DISTANCE) {
            this.stuckTicks = 0;
            this.resident.getNavigation().stop();
            return;
        } else {
            this.stuckTicks = 0;
        }
        if (distance > STOP_DISTANCE * STOP_DISTANCE && this.resident.getNavigation().isDone()) {
            this.resident.getNavigation().moveTo(owner, 1.3D);
        }
    }

    /** 在玩家周围 ±2 格找一个能站人的位置传送过去；**找不到安全点就不传送**（玩家在天上时别反复摔死）。 */
    private void teleportNear(Player owner) {
        Level level = this.resident.level();
        BlockPos origin = owner.blockPosition();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (this.isSafe(level, pos)) {
                        this.resident.teleportTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
                        this.resident.getNavigation().stop();
                        this.stuckTicks = 0;
                        return;
                    }
                }
            }
        }
        // 没有安全落点：不传送，等玩家落地
        this.stuckTicks = 0;
    }

    private boolean isSafe(Level level, BlockPos pos) {
        return level.getBlockState(pos).isEmpty()
            && level.getBlockState(pos.above()).isEmpty()
            && level.getBlockState(pos.below()).blocksMotion();
    }
}
