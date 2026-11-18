package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;

/** 闲逛：只有「闲逛」模式才会自己遛弯；跟随/站立时完全不动。 */
public class ResidentStrollGoal extends WaterAvoidingRandomStrollGoal {
    private final ResidentEntity resident;

    public ResidentStrollGoal(ResidentEntity resident) {
        super(resident, 0.6D);
        this.resident = resident;
    }

    @Override
    public boolean canUse() {
        return ResidentJobs.IDLE.equals(this.resident.jobKey()) && super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return ResidentJobs.IDLE.equals(this.resident.jobKey()) && super.canContinueToUse();
    }
}
