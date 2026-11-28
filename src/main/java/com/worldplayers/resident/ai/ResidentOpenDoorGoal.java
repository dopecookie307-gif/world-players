package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentDoors;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.DoorInteractGoal;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 居民开木门（主人 2026-11-20）。
 *
 * <p>和原版 {@code OpenDoorGoal} 一样的行为，但修了两个问题（2026-11-24 主人报「卡在打开的门框里，
 * 不停开关门 + 音效」）：</p>
 * <ol>
 *   <li><b>门本来就开着时不启动</b>：原版这个 goal 只看「路径上有门」，门开着也会 start → stop → 关门，
 *       下个 tick 又觉得要开 → 无限开关；</li>
 *   <li><b>人还在门框里时不关门</b>：以前 {@code stop()} 无条件下关门，居民正卡在 3 像素厚的门板碰撞箱上，
 *       一关门就被夹住、再开门、再关门…… 现在只要居民还停在门口（1.6 格内）就保持开着，
 *       交给 {@link ResidentDoors#deferClose} 在它真的走远 / 穿过去之后再关。</li>
 * </ol>
 */
public class ResidentOpenDoorGoal extends DoorInteractGoal {
    /** 原版 20 tick；这里再拉长到 1.5 秒，保证人过完门框再考虑关。 */
    private static final int CLOSE_DELAY = 30;
    /** 居民离门中心多近算「还在门口」——这时候不许关门。 */
    private static final double STILL_AT_DOOR = 1.6D;

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
        if (this.standing() || !super.canUse()) {
            return false;
        }
        // 门已经开着：没必要再进这个 goal（否则 start 开门 → stop 关门 → 循环）
        BlockState state = this.mob.level().getBlockState(this.doorPos);
        return state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN);
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
        if (this.mob.level().isClientSide || !(this.mob instanceof ResidentEntity resident)) {
            this.setOpen(false);
            return;
        }
        if (horizontalDistance(resident) < STILL_AT_DOOR) {
            // 人还卡在门框里：门保持开着，等它走开 / 穿过去再由 ResidentDoors 关
            if (this.mob.level() instanceof ServerLevel level) {
                ResidentDoors.deferClose(level, this.doorPos, resident);
            }
            return;
        }
        this.setOpen(false);
        // 父类的 setOpen 只在它自己「开过门」时才真的写方块；这里再兜一次底（已关则空转）
        if (this.mob.level() instanceof ServerLevel level) {
            ResidentDoors.close(level, this.doorPos);
        }
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

    /** 居民到门中心的水平距离。 */
    private double horizontalDistance(ResidentEntity resident) {
        double dx = resident.getX() - (this.doorPos.getX() + 0.5D);
        double dz = resident.getZ() - (this.doorPos.getZ() + 0.5D);
        return Math.sqrt(dx * dx + dz * dz);
    }
}
