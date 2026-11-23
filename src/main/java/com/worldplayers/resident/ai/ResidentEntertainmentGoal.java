package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import java.util.EnumSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

/**
 * 娱乐（2026-11-22 建立，2026-11-23 改成**日程驱动**）。
 *
 * <p>谁能来、什么时候来、去哪个区，全部由 {@link com.worldplayers.resident.schedule.ResidentSchedule}
 * 决定：自由时间里游荡满 1 分钟、剩余自由 ≥6 分钟、心情 &lt;90 → 挑一个 128 格内的娱乐区，
 * 然后把区交给这个 goal。**挑不到就继续游荡**（goal 不启动 = 原版漫步照常跑）。</p>
 *
 * <p>这里只负责四件事：</p>
 * <ol>
 *   <li>在区域包围盒里找一个**可站且寻路可达**的点（多楼层靠随机采样）；</li>
 *   <li>走过去，状态「闲逛」+ 区域名；进了区域切「娱乐」（不显示地点）；</li>
 *   <li>待满 **5 分钟**（唯一结束条件），每 **10 秒 +0.5%** 心情；时不时在区域内换位置；</li>
 *   <li>同区域遇到另一个也在娱乐的居民 → 对视 + 挥手，双方 **+2%**、各 **40 秒**冷却。</li>
 * </ol>
 *
 * <p>打断：跟随/站立、战斗、被翻背包、体力 ≤10%、娱乐区被删、调试棒通勤。被打断后回到游荡，
 * 由日程重新计时（1 分钟后再考虑）。</p>
 */
public class ResidentEntertainmentGoal extends Goal {
    /** 娱乐时长：5 分钟（14 号文档 2.4）。 */
    private static final int DURATION_TICKS = 20 * 60 * 5;
    /** 心情收益间隔：10 秒 +0.5%（14 号文档 4.2）。 */
    private static final int MOOD_INTERVAL = 20 * 10;
    private static final float MOOD_GAIN = 0.5F;
    /** 区域内挑点的尝试次数 / 每条路径验证的上限。 */
    private static final int SPOT_TRIES = 8;
    private static final int MAX_PATH_CHECKS = 4;
    /** 区域内换位置的间隔。 */
    private static final int WANDER_INTERVAL = 80;
    /** 社交：查找范围 / 动作时长。 */
    private static final double SOCIAL_RANGE = 8.0D;
    private static final int SOCIAL_TICKS = 30;

    private enum Phase {
        APPROACH, ENTERTAIN
    }

    private final ResidentEntity resident;
    @Nullable
    private WorkZone zone;
    @Nullable
    private BlockPos target;
    private Phase phase = Phase.APPROACH;
    /** 进区域之后过了多久（5 分钟到就结束）。 */
    private int entertainTicks;
    private int moodTicks;
    private int wanderCd;
    private int socialCheckCd;

    // 社交动作（对视 + 挥手 + 结算）
    private int socialTicks;
    @Nullable
    private ResidentEntity socialPartner;

    public ResidentEntertainmentGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey())) {
            return false;
        }
        if (this.resident.isInCombat() || !this.resident.canLookAround()) {
            return false;
        }
        if (this.resident.commuteTarget() != null) {
            return false;
        }
        // 日程已经进入工作阶段：娱乐让位（否则同优先级会一直占着 MOVE，工作 goal 启动不了）
        if (!this.resident.schedule().isFree()) {
            return false;
        }
        if (this.resident.stamina() <= ResidentEntity.EXHAUSTED_STAMINA_THRESHOLD) {
            return false;
        }
        // 日程挑好的区域（没有 = 不启动，让原版漫步跑）
        WorkZone planned = this.resident.schedule().entertainmentTarget();
        if (planned == null
                || WorkZoneData.get(level).findByKey(planned.key()) == null) {
            return false;
        }
        BlockPos spot = pickSpot(level, planned);
        if (spot == null) {
            // 这个区里找不到能站能走的点：放弃这次，回游荡，1 分钟后再试
            this.resident.schedule().failEntertainmentPlan();
            return false;
        }
        this.zone = planned;
        this.target = spot;
        return true;
    }

    /**
     * 在区域包围盒里找一个可站、寻路可达的点（随机采样，天然支持多楼层）。
     *
     * @return 找不到返回 null
     */
    @Nullable
    private BlockPos pickSpot(ServerLevel level, WorkZone zone) {
        BlockPos min = zone.min();
        BlockPos max = zone.max();
        int width = max.getX() - min.getX() + 1;
        int height = max.getY() - min.getY() + 1;
        int depth = max.getZ() - min.getZ() + 1;
        if (width <= 0 || height <= 0 || depth <= 0) {
            return null;
        }
        int checks = 0;
        for (int i = 0; i < SPOT_TRIES; i++) {
            BlockPos pos = new BlockPos(
                min.getX() + this.resident.getRandom().nextInt(width),
                min.getY() + this.resident.getRandom().nextInt(height),
                min.getZ() + this.resident.getRandom().nextInt(depth));
            // 区域方块本身能站就站，否则试它上面一格（区域只有一层地面方块时）
            BlockPos stand = ResidentEntity.isSafeStand(level, pos) ? pos
                : ResidentEntity.isSafeStand(level, pos.above()) ? pos.above() : null;
            if (stand == null) {
                continue;
            }
            if (checks++ >= MAX_PATH_CHECKS) {
                break;
            }
            Path path = this.resident.getNavigation().createPath(stand, 1);
            if (path != null && path.canReach()) {
                return stand.immutable();
            }
        }
        return null;
    }

    @Override
    public void start() {
        this.phase = Phase.APPROACH;
        this.entertainTicks = 0;
        this.moodTicks = 0;
        this.wanderCd = 0;
        this.socialCheckCd = 0;
        this.socialTicks = 0;
        this.socialPartner = null;
        this.resident.schedule().onEntertainmentStarted();
        if (this.zone != null) {
            // 前往途中：「闲逛」+ 目的地名（14 号文档 2.2）
            this.resident.setWorkActivity(ResidentJobs.STROLLING, this.zone.name());
        }
        if (this.target != null) {
            this.resident.getNavigation().moveTo(this.target.getX() + 0.5D,
                this.target.getY(), this.target.getZ() + 0.5D, 1.0D);
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey())) {
            return false;
        }
        if (this.resident.isInCombat() || !this.resident.canLookAround()) {
            return false;
        }
        if (this.resident.commuteTarget() != null) {
            return false;
        }
        if (!this.resident.schedule().isFree()) {
            return false;
        }
        if (this.resident.stamina() <= ResidentEntity.EXHAUSTED_STAMINA_THRESHOLD
                && !this.resident.bedReturnGivenUp()) {
            return false;
        }
        if (this.zone == null) {
            return false;
        }
        // 娱乐区被删 = 娱乐时间被中断（14 号文档 1.10 / 2.9）
        if (WorkZoneData.get(level).findByKey(this.zone.key()) == null) {
            return false;
        }
        // 只有 5 分钟到才结束（心情满了也不提前走，14 号文档 2.7）
        return this.phase != Phase.ENTERTAIN || ++this.entertainTicks <= DURATION_TICKS;
    }

    @Override
    public void tick() {
        if (!(this.resident.level() instanceof ServerLevel level) || this.zone == null) {
            return;
        }
        if (this.phase == Phase.APPROACH) {
            this.tickApproach(level);
            return;
        }
        this.tickEntertain(level);
    }

    private void tickApproach(ServerLevel level) {
        double distance = this.target == null ? 0.0D
            : this.resident.distanceToSqr(this.target.getX() + 0.5D, this.target.getY(),
                this.target.getZ() + 0.5D);
        boolean arrived = this.target != null && distance <= 2.5D * 2.5D;
        if (arrived) {
            enterEntertainment();
            return;
        }
        // 寻路走完还没到 → 再挑一个点；挑不到就放弃（下次再说）
        if (this.resident.getNavigation().isDone()) {
            BlockPos spot = pickSpot(level, this.zone);
            if (spot == null) {
                this.stop();
                return;
            }
            this.target = spot;
            this.resident.getNavigation().moveTo(spot.getX() + 0.5D, spot.getY(),
                spot.getZ() + 0.5D, 1.0D);
        }
    }

    private void enterEntertainment() {
        this.phase = Phase.ENTERTAIN;
        this.entertainTicks = 0;
        this.moodTicks = 0;
        this.wanderCd = WANDER_INTERVAL;
        this.resident.getNavigation().stop();
        // 到了：日程从这一刻开始照常计自由时间（14 号文档 3.9）
        this.resident.schedule().onEntertainmentArrived();
        // 抵达后状态「娱乐」，不显示地点名（14 号文档 2.3）
        this.resident.setWorkActivity(ResidentJobs.ENTERTAINING, "");
    }

    private void tickEntertain(ServerLevel level) {
        // 每 10 秒 +0.5% 心情
        if (++this.moodTicks >= MOOD_INTERVAL) {
            this.moodTicks = 0;
            this.resident.setMood(this.resident.mood() + MOOD_GAIN);
        }
        // 社交优先：动作期间不换位置
        if (this.socialTicks > 0) {
            this.tickSocialAction();
            return;
        }
        this.tickSocialSearch(level);
        // 在区域内换位置（多楼层尽量支持）
        if (--this.wanderCd <= 0) {
            this.wanderCd = WANDER_INTERVAL;
            BlockPos spot = pickSpot(level, this.zone);
            if (spot != null) {
                this.resident.getNavigation().moveTo(spot.getX() + 0.5D, spot.getY(),
                    spot.getZ() + 0.5D, 0.6D);
            }
        }
    }

    /** 找同区域里另一个也在娱乐的居民，开始对视 + 挥手。 */
    private void tickSocialSearch(ServerLevel level) {
        if (--this.socialCheckCd > 0) {
            return;
        }
        this.socialCheckCd = 20;
        if (!this.resident.entertainmentSocialReady()) {
            return;
        }
        for (ResidentEntity other : level.getEntitiesOfClass(ResidentEntity.class,
                this.resident.getBoundingBox().inflate(SOCIAL_RANGE),
                other -> other != this.resident && other.isAlive())) {
            if (!ResidentJobs.ENTERTAINING.equals(other.activityKey())) {
                continue;
            }
            if (other.distanceToSqr(this.resident) > SOCIAL_RANGE * SOCIAL_RANGE) {
                continue;
            }
            // 必须在同一个娱乐区里
            if (!this.zone.containsBody(other)) {
                continue;
            }
            if (!other.entertainmentSocialReady()) {
                continue;
            }
            this.socialPartner = other;
            this.socialTicks = SOCIAL_TICKS;
            this.resident.getNavigation().stop();
            return;
        }
    }

    /** 对视（30 tick）→ 各挥一次手 → 双方 +2%、各 40 秒冷却。 */
    private void tickSocialAction() {
        ResidentEntity partner = this.socialPartner;
        if (partner == null || !partner.isAlive()
                || !ResidentJobs.ENTERTAINING.equals(partner.activityKey())) {
            this.socialTicks = 0;
            this.socialPartner = null;
            return;
        }
        this.resident.getLookControl().setLookAt(partner, 40.0F, 40.0F);
        partner.getLookControl().setLookAt(this.resident, 40.0F, 40.0F);
        if (--this.socialTicks == 20 || this.socialTicks == 8) {
            this.swing(this.resident);
            this.swing(partner);
        }
        if (this.socialTicks <= 0) {
            // 14 号文档 2.8：动作同分享食物，但**不握物品、不转食物**，双方 +2%
            this.resident.setMood(this.resident.mood() + 2.0F);
            partner.setMood(partner.mood() + 2.0F);
            this.resident.startEntertainmentSocialCooldown();
            partner.startEntertainmentSocialCooldown();
            this.socialPartner = null;
        }
    }

    private void swing(ResidentEntity entity) {
        entity.swing(InteractionHand.MAIN_HAND, true);
    }

    @Override
    public void stop() {
        this.resident.getNavigation().stop();
        // 前往 / 娱乐两种临时状态都清掉，回到「闲逛」
        this.resident.clearWorkActivity();
        // 告诉日程：这次娱乐结束了（回到游荡，1 分钟后再考虑）
        this.resident.schedule().onEntertainmentEnded();
        this.zone = null;
        this.target = null;
        this.phase = Phase.APPROACH;
        this.socialTicks = 0;
        this.socialPartner = null;
        this.entertainTicks = 0;
    }

    @Override
    public boolean isInterruptable() {
        // 战斗 / 跟随 / 起床这类高优先级 goal 可以随时顶掉它；恢复后由日程重新决定
        return true;
    }

    /** 这个居民现在是不是「娱乐中」（浮窗 / 日程判断用）。 */
    public static boolean isEntertaining(ResidentEntity resident) {
        return ResidentJobs.ENTERTAINING.equals(resident.activityKey());
    }
}
