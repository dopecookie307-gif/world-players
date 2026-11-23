package com.worldplayers.resident.ai;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;

/** 地面寻路，把节点评估器换成会避开危险方块的版本；门策略由 {@link ResidentPathPolicy} 统一决定。 */
public class ResidentPathNavigation extends GroundPathNavigation {
    public ResidentPathNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        ResidentNodeEvaluator evaluator = new ResidentNodeEvaluator(this.level);
        // 先挂上再设属性：GroundPathNavigation#setCanOpenDoors 内部直接摸 this.nodeEvaluator，
        // 而 createPathFinder 由 PathNavigation 构造期间调用，那时它还是 null —— alpha.11 全部居民
        // 构造失败（NPE）的直接原因。所以这里只设 evaluator 自己，不调 GroundPathNavigation 的 setter。
        this.nodeEvaluator = evaluator;
        ResidentPathPolicy.apply(evaluator);
        return new PathFinder(evaluator, maxVisitedNodes);
    }
}
