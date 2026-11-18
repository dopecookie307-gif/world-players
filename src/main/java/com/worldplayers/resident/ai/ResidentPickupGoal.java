package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import java.util.Comparator;
import javax.annotation.Nullable;
import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * 拾取：没有任何优先级、没有冷却、没有动画。
 * 唯一限制：物品**丢出 0.5 秒之后**才能被居民捡（避免和玩家抢刚丢出来的东西）。
 */
public class ResidentPickupGoal extends Goal {
    /** 0.5 秒：物品丢出后要等一下才能被居民捡。 */
    public static final int PICKUP_DELAY_TICKS = ResidentEntity.PICKUP_DELAY_TICKS;

    private static final double SEARCH_RADIUS = 2.5D;
    private static final double REACH_SQR = 1.6D * 1.6D;

    private final ResidentEntity resident;
    @Nullable
    private ItemEntity target;

    public ResidentPickupGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // 只有闲逛且**没有战斗目标**时才"专门走过去"捡东西。
        // 战斗/赶路/站立时不动腿，但脚边的东西由 ResidentEntity#pickUpAtFeet 照捡不误。
        if (!com.worldplayers.resident.ResidentJobs.IDLE.equals(this.resident.jobKey())) {
            return false;
        }
        if (this.resident.getTarget() != null) {
            return false;
        }
        this.target = this.findItem();
        return this.target != null;
    }

    @Nullable
    private ItemEntity findItem() {
        double radius = Math.max(SEARCH_RADIUS, this.resident.pickupRadius());
        if (radius <= 0.0D) {
            return null;
        }
        return this.resident.level()
            .getEntitiesOfClass(ItemEntity.class, this.resident.getBoundingBox().inflate(radius),
                item -> item.isAlive()
                    && item.getAge() >= PICKUP_DELAY_TICKS
                    && this.resident.canPickupNow(item)
                    && this.resident.inventory().canAccept(item.getItem()))
            .stream()
            .min(Comparator.comparingDouble(this.resident::distanceToSqr))
            .orElse(null);
    }

    @Override
    public void start() {
        if (this.target != null) {
            this.resident.getNavigation().moveTo(this.target, 1.0D);
        }
    }

    @Override
    public void tick() {
        if (this.target == null || !this.target.isAlive()) {
            return;
        }
        if (this.resident.distanceToSqr(this.target) > REACH_SQR) {
            this.resident.getNavigation().moveTo(this.target, 1.0D);
            return;
        }
        this.resident.getNavigation().stop();
        this.resident.tryPickUp(this.target);
    }

    @Override
    public boolean canContinueToUse() {
        if (this.resident.getTarget() != null) {
            return false;
        }
        return this.target != null && this.target.isAlive()
            && this.resident.distanceToSqr(this.target) <= (SEARCH_RADIUS + 1.0D) * (SEARCH_RADIUS + 1.0D);
    }

    @Override
    public void stop() {
        this.target = null;
        this.resident.getNavigation().stop();
    }
}
