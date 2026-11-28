package com.worldplayers.resident.ai;

import com.worldplayers.resident.ColonyAnchors;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import java.util.EnumSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * 「打完架回家」（主人 2026-11-20），管两件事：
 *
 * <ol>
 *   <li><b>站立模式</b>：站岗居民会去援助附近的居民/玩家（见 {@link ResidentCombatGoal}），
 *       打完走回当初站的位置（{@link ResidentEntity#standHome()}），不原地散开。</li>
 *   <li><b>无业 / 流浪居民</b>（{@link ColonyAnchors#mustReturnAfterCombat}）：援助战斗结束后
 *       走回**殖民地边境的 16 格内**（边境定义见 {@link ColonyAnchors}）。
 *       「边境」是区域包围盒 / 床的 16 格光环，所以站在超长道路边上也算在境内。</li>
 * </ol>
 *
 * <p>通用规则：</p>
 * <ul>
 *   <li>只在闲逛（无业/流浪）或站立模式下跑，不在战斗、不在进食时跑；</li>
 *   <li>站岗点 1.5 格内什么都不做（避免原地抖）；</li>
 *   <li>站岗点超过 48 格（被传送、走丢）就地重新记点，不横穿世界；</li>
 *   <li>30 秒还没走到就歇一会儿（20 秒后重试），路被堵死时别每 tick 重算一次 A*。</li>
 * </ul>
 */
public class ResidentReturnHomeGoal extends Goal {
    /** 站岗点的到达判定（1.5 格）。 */
    private static final double ARRIVE_SQ = 2.25D;
    /** 站岗点太远就不要了（48 格）。 */
    private static final double ABANDON_SQ = 48.0D * 48.0D;
    /** 走路超时（30 秒）。 */
    private static final int TIMEOUT_TICKS = 30 * 20;
    /** 重新寻路的间隔。 */
    private static final int REPATH_TICKS = 20;
    /** 走不到边境就先歇 20 秒再试（别每 tick 都开一次寻路）。 */
    private static final int GIVE_UP_TICKS = 20 * 20;
    /** 走到边境的最近距离：进 16 格内就算到家，不用挤到区域中心。 */
    private static final double BORDER_DONE_SQ = 2.25D;

    private final ResidentEntity resident;
    /** 无业/流浪居民这次要走的边境落点（{@link ColonyAnchors#entryPoint}）。 */
    @Nullable
    private BlockPos borderTarget;
    private int repath;
    private int elapsed;
    private int giveUpUntil;

    public ResidentReturnHomeGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!this.alive() || this.resident.isEatingFood()) {
            return false;
        }
        if (ResidentJobs.STAND.equals(this.resident.jobKey())) {
            if (this.resident.isInCombat()) {
                return false;
            }
            BlockPos home = this.resident.standHome();
            if (home == null) {
                return false;
            }
            double distance = this.distanceSq(home);
            if (distance > ABANDON_SQ) {
                // 离家太远：就地重新记点，别横穿世界
                this.resident.setStandHome(this.resident.blockPosition());
                return false;
            }
            return distance > ARRIVE_SQ;
        }
        return this.canReturnToBorder();
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.alive()) {
            return false;
        }
        if (ResidentJobs.STAND.equals(this.resident.jobKey())) {
            if (this.resident.isInCombat() || this.elapsed >= TIMEOUT_TICKS) {
                return false;
            }
            BlockPos home = this.resident.standHome();
            return home != null && this.distanceSq(home) > ARRIVE_SQ;
        }
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey())
            || !this.resident.combatSettled()
            || this.elapsed >= TIMEOUT_TICKS) {
            return false;
        }
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        if (this.borderTarget == null) {
            return false;
        }
        // 回到边境（16 格内）就算到家：不用挤到区域中心，也不用踩在区域里
        return !ColonyAnchors.inside(level, this.resident.position())
            && this.distanceSq(this.borderTarget) > BORDER_DONE_SQ;
    }

    @Override
    public void start() {
        this.repath = 0;
        this.elapsed = 0;
        if (ResidentJobs.STAND.equals(this.resident.jobKey())) {
            this.resident.setActivity(ResidentJobs.STAND);
        }
        this.resident.applyTravelSpeed();
    }

    @Override
    public void stop() {
        this.resident.getNavigation().stop();
        this.borderTarget = null;
    }

    @Override
    public void tick() {
        this.elapsed++;
        BlockPos target = this.target();
        if (target == null) {
            return;
        }
        if (this.elapsed >= TIMEOUT_TICKS) {
            // 走不到就先歇一会儿（路被堵死 / 被水卡住），别每 tick 重算寻路
            this.giveUpUntil = this.resident.tickCount + GIVE_UP_TICKS;
            return;
        }
        if (--this.repath > 0) {
            return;
        }
        this.repath = REPATH_TICKS;
        this.resident.getNavigation().moveTo(
            target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
            ResidentJobs.STAND.equals(this.resident.jobKey()) ? 1.0D : 0.9D);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /** 这次要走回的点：站立 = 站岗点；无业/流浪 = 殖民地最近的入境点。 */
    @Nullable
    private BlockPos target() {
        if (ResidentJobs.STAND.equals(this.resident.jobKey())) {
            return this.resident.standHome();
        }
        return this.borderTarget;
    }

    /** 无业 / 流浪居民：战斗刚结束、人已在边境 16 格之外 → 走回去。 */
    private boolean canReturnToBorder() {
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey())
            || !this.resident.combatSettled()
            || this.resident.tickCount < this.giveUpUntil) {
            return false;
        }
        // 正在去娱乐 / 正在娱乐：别把人从娱乐区拖回边境（2026-11-23，日程第 2 包）
        if (ResidentJobs.ENTERTAINING.equals(this.resident.activityKey())
            || ResidentJobs.STROLLING.equals(this.resident.activityKey())) {
            return false;
        }
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!ColonyAnchors.mustReturnAfterCombat(level, this.resident)) {
            return false;
        }
        if (ColonyAnchors.inside(level, this.resident.position())) {
            return false;
        }
        this.borderTarget = ColonyAnchors.entryPoint(level, this.resident.blockPosition());
        return this.borderTarget != null;
    }

    /** 活着 + 没死透 + 战斗结束（站立模式另外要求「不在战斗」）。 */
    private boolean alive() {
        return this.resident.isAlive() && !this.resident.isDeadOrDying();
    }

    private double distanceSq(BlockPos pos) {
        double dx = this.resident.getX() - (pos.getX() + 0.5D);
        double dy = this.resident.getY() - pos.getY();
        double dz = this.resident.getZ() - (pos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }
}
