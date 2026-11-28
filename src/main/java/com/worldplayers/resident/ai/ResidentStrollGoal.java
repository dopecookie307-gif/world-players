package com.worldplayers.resident.ai;

import com.worldplayers.resident.ColonyAnchors;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import com.worldplayers.resident.path.PathNetworkData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.phys.Vec3;

/**
 * 闲逛：只有「闲逛」模式才会自己遛弯；跟随/站立时完全不动。
 *
 * <p>范围（主人 2026-11-20）：**任意工作区域 / 道路区域 / 已绑定床的 16 格之内**
 * —— 定义在 {@link ColonyAnchors}，按整个区域包围盒算，不再只在区域中心点量 16 格。</p>
 *
 * <p>世界里一个殖民地要素都没有时（野外召唤的居民），用居民自己的**持久闲逛锚点**
 * （{@link ResidentEntity#strollAnchor()}，第一次需要时记在脚下）当中心，半径同样是 16 格
 * —— 也就是「（流浪）居民也配上闲逛锚点」，不会出现「没有锚点就不限范围」。</p>
 *
 * <p>已经走出范围时这里**不自己往回走**：无业 / 流浪居民由
 * {@link ResidentReturnHomeGoal} 负责送回边境（打完架也走这条路），这里只是不去下一步。</p>
 */
public class ResidentStrollGoal extends WaterAvoidingRandomStrollGoal {
    /** 挑点的最大次数：全落在范围外就不走了（下次再说）。 */
    private static final int TARGET_TRIES = 12;

    private final ResidentEntity resident;

    public ResidentStrollGoal(ResidentEntity resident) {
        super(resident, 0.6D);
        this.resident = resident;
    }

    @Override
    public boolean canUse() {
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey())) {
            return false;
        }
        // 日程不在自由时间（例如刚被指派工作）→ 别占着 MOVE 让工作 goal（同优先级）启动不了
        if (!this.resident.schedule().isFree()) {
            return false;
        }
        // 体力见底又有床：回床待着，别在床边晃来晃去（没床的照旧闲逛；已放弃睡觉的也照旧）
        if (this.resident.stamina() <= ResidentEntity.EXHAUSTED_STAMINA_THRESHOLD
                && this.resident.bedPos() != null && !this.resident.bedReturnGivenUp()) {
            return false;
        }
        if (this.resident.level() instanceof ServerLevel level && !withinRange(level)) {
            return false;
        }
        // 路网第三轮（2026-11-24）：**不在路段上逗留**。站在窄楼梯 / 走廊中间闲逛，
        // 会把正在赶路的居民挡在段口（主人要的「一楼十户同时下楼不推挤」）。
        if (this.resident.level() instanceof ServerLevel level && onTravelLane(level)) {
            return false;
        }
        return super.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey())) {
            return false;
        }
        if (!this.resident.schedule().isFree()) {
            return false;
        }
        if (this.resident.stamina() <= ResidentEntity.EXHAUSTED_STAMINA_THRESHOLD
                && this.resident.bedPos() != null && !this.resident.bedReturnGivenUp()) {
            return false;
        }
        if (this.resident.level() instanceof ServerLevel level && !withinRange(level)) {
            return false;
        }
        if (this.resident.level() instanceof ServerLevel level && onTravelLane(level)) {
            return false;
        }
        return super.canContinueToUse();
    }

    /** 目标准星点也只能挑在范围之内（原版随机漫步会挑 10 格外的点，可能一步跨出边境）。 */
    @Override
    protected Vec3 getPosition() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return super.getPosition();
        }
        boolean colony = ColonyAnchors.hasColony(level);
        BlockPos personal = colony ? null : ColonyAnchors.personalAnchor(this.resident);
        PathNetworkData pathData = PathNetworkData.get(level);
        for (int i = 0; i < TARGET_TRIES; i++) {
            Vec3 candidate = super.getPosition();
            if (candidate == null) {
                return null;
            }
            boolean inBounds = colony
                ? ColonyAnchors.inside(level, candidate)
                : candidate.distanceToSqr(Vec3.atCenterOf(personal))
                    <= ColonyAnchors.PERSONAL_RANGE * ColonyAnchors.PERSONAL_RANGE;
            if (!inBounds) {
                continue;
            }
            // 第三轮：目的地点也避开路段（离段中心线 3 格内算「在路上」），别把人引到路中间去
            if (pathData.isTravelLane(BlockPos.containing(candidate),
                level.dimension().location())) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /** 现在是不是正站在路网的路段中间（停靠点不算）。 */
    private boolean onTravelLane(ServerLevel level) {
        PathNetworkData data = PathNetworkData.get(level);
        if (data.segments().isEmpty()) {
            return false;
        }
        return data.isTravelLane(this.resident.blockPosition(),
            level.dimension().location());
    }

    /** 现在还在不在「可以闲逛的范围」里。 */
    private boolean withinRange(ServerLevel level) {
        if (ColonyAnchors.hasColony(level)) {
            return ColonyAnchors.inside(level, this.resident.position());
        }
        BlockPos anchor = ColonyAnchors.personalAnchor(this.resident);
        return this.resident.blockPosition().distSqr(anchor)
            <= ColonyAnchors.PERSONAL_RANGE * ColonyAnchors.PERSONAL_RANGE;
    }
}
