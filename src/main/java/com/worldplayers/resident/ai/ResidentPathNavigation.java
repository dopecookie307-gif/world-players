package com.worldplayers.resident.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;

/** 地面寻路，把节点评估器换成会避开危险方块的版本。 */
public class ResidentPathNavigation extends GroundPathNavigation {
    public ResidentPathNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        ResidentNodeEvaluator evaluator = new ResidentNodeEvaluator();
        evaluator.setCanPassDoors(true);
        evaluator.setCanOpenDoors(false);
        this.nodeEvaluator = evaluator;
        return new PathFinder(evaluator, maxVisitedNodes);
    }
}
