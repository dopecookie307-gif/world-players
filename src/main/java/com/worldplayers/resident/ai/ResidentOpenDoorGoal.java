package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;

/**
 * 居民开木门（主人 2026-11-20）。
 *
 * <p>和原版 {@code OpenDoorGoal} 一样的行为，只有两处不同：**关门等待从 20 tick 缩短到 10 tick**、
 * **站立模式不开关门**（主人 2026-11-20：站着的居民不该去碰门）。原版那个 {@code forgetTime} 是 private，
 * 只能自己写一份。</p>
 *
 * <p>铁门 / 其它打不开的方块由父类 {@code DoorInteractGoal} 里的 {@code DoorBlock.isWoodenDoor} 挡掉。</p>
 */
public class ResidentOpenDoorGoal extends DoorInteractGoal {
    /** 原版 20 tick，主人要求缩短一半。 */
    private static final int CLOSE_DELAY = 10;
    private int forgetTime;

    public ResidentOpenDoorGoal(Mob mob) {
        super(mob);
    }

    /** 站立模式：钉在原地，连门都不碰。 */
    private boolean standing() {
        return this.mob instanceof ResidentEntity resident
            && ResidentJobs.STAND.equals(resident.jobKey());
    }

    @Override
    public boolean canUse() {
        return !this.standing() && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return this.forgetTime > 0 && !this.standing() && super.canContinueToUse();
    }

    @Override
    public void start() {
        this.forgetTime = CLOSE_DELAY;
        this.setOpen(true);
    }

    @Override
    public void stop() {
        this.setOpen(false);
    }

    @Override
    public void tick() {
        this.forgetTime--;
        super.tick();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
