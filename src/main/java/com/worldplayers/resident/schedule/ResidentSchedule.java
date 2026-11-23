package com.worldplayers.resident.schedule;

import com.worldplayers.resident.ResidentChatPool;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;

/**
 * 每居民的日程状态机（2026-11-23，14 号文档第三节 = 第 2 包）。
 *
 * <p>这一包把「天」这个概念补上，所有计时都在这里：</p>
 * <ul>
 *   <li><b>WORK</b>：**走进工作区域**才开始计（10 分钟）。跟随 / 站立 / 调试棒 / 被翻背包 / 战斗 / 社交
 *       都会暂停工作计时（事件结束后继续）；员工被撤或工作区被删 → 立刻进自由时间。</li>
 *   <li><b>FREE</b>：有工作 10 分钟、无业 20 分钟。**只有游荡和娱乐计时，去娱乐区的通勤不计**；
 *       游荡满 1 分钟且剩余自由 ≥6 分钟、心情 &lt;90 → 挑一个 128 格内的娱乐区过去；
 *       找不到就继续游荡。娱乐 5 分钟也计入自由时间。自由时间结束 → 回床。</li>
 *   <li><b>SLEEP</b>：回床睡到体力满（{@code ResidentSleepGoal}），自然醒 = 新一天；
 *       回笼觉失败也算新一天（跳过剩余）。没有床（流浪）不进休息节点，直接开下一轮。</li>
 * </ul>
 *
 * <p>冻结规则：**跟随 / 站立**、**调试棒通勤中**、**被玩家翻背包** → 所有计时冻结（连回笼觉窗口一起，
 * 见 {@code ResidentEntity#tickBedReturn} 的窗口刷新）。战斗与社交只暂停**工作**计时，自由时间照走。</p>
 *
 * <p>存读档：阶段 / 三个计时器都进 NBT（读档从 0 开始本来也能接受，存了体验更连贯）。</p>
 */
public final class ResidentSchedule {
    /** 工作时间：10 分钟。 */
    public static final int WORK_TICKS = 20 * 60 * 10;
    /** 自由时间：有工作 10 分钟。 */
    public static final int FREE_WORK_TICKS = 20 * 60 * 10;
    /** 自由时间：无业 20 分钟。 */
    public static final int FREE_IDLE_TICKS = 20 * 60 * 20;
    /** 游荡多久才去检测一次娱乐区：1 分钟。 */
    public static final int STROLL_CHECK_TICKS = 20 * 60;
    /** 去娱乐要留足的时间：游荡 1 分钟 + 娱乐 5 分钟 = 6 分钟。 */
    public static final int MIN_FOR_ENTERTAINMENT = 20 * 60 * 6;
    /** 最远 128 格（14 号文档 2.13）。 */
    private static final double MAX_ENTERTAINMENT_DISTANCE = 128.0D;
    /** 工作区缓存刷新间隔。 */
    private static final int ZONE_CACHE_TICKS = 40;

    /** 日程阶段。 */
    public enum Phase {
        /** 工作（走进区域才开始计）。 */
        WORK,
        /** 自由时间（游荡 / 娱乐）。 */
        FREE,
        /** 回床休息（睡眠由 SleepGoal 执行）。 */
        SLEEP
    }

    private final ResidentEntity resident;
    private Phase phase = Phase.FREE;
    /** 这一轮工作已经计了多少 tick（走进区域才算）。 */
    private int workTicks;
    /** 这一轮自由时间已经用了多少 tick。 */
    private int freeTicks;
    /** 当前游荡段已经多久（满 1 分钟去检测娱乐区）。 */
    private int strollTicks;
    /** 已经决定去娱乐、正在路上（通勤不计自由时间）。 */
    private boolean entertainPlanned;
    /** 去娱乐的路上已经多久（超时兜底，防止 goal 因别的原因不启动时卡死）。 */
    private int entertainPlanTicks;
    @Nullable
    private WorkZone entertainmentTarget;
    /** 日程要求回床睡觉（体力满之前不结束）。 */
    private boolean sleepRequested;
    /** 工作区缓存。 */
    @Nullable
    private WorkZone workZone;
    private boolean hasWork;
    private boolean initialized;
    private int zoneCacheCd;
    /** 流浪聊天：已经发到第几档（0 = 没发过；每天重置）。 */
    private int homelessChatLevel;

    public ResidentSchedule(ResidentEntity resident) {
        this.resident = resident;
    }

    public Phase phase() {
        return this.phase;
    }

    /** 日程要求回床休息（SleepGoal 用它放行「体力没见底也睡」）。 */
    public boolean wantsSleep() {
        return this.phase == Phase.SLEEP && this.sleepRequested;
    }

    /** 今天已经工作多久（tick）。 */
    public int workTicks() {
        return this.workTicks;
    }

    /** 这一轮自由时间剩下多少 tick。 */
    public int remainingFreeTicks() {
        return Math.max(0, freeDuration() - this.freeTicks);
    }

    /** 有工作的居民现在是不是在自由时间（娱乐 goal 用）。 */
    public boolean isFree() {
        return this.phase == Phase.FREE;
    }

    /** 现在是不是工作阶段（工作 goal 用）。 */
    public boolean isWorkPhase() {
        return this.phase == Phase.WORK;
    }

    /** 日程挑好的娱乐区（没挑好 = null）。 */
    @Nullable
    public WorkZone entertainmentTarget() {
        return this.entertainPlanned ? this.entertainmentTarget : null;
    }

    /** 娱乐 goal 答应去（离开游荡阶段，通勤不计时间）。 */
    public void onEntertainmentStarted() {
        this.entertainPlanned = true;
        this.entertainPlanTicks = 0;
        this.strollTicks = 0;
    }

    /** 已抵达娱乐区：从这一刻起自由时间照常计时（14 号文档 3.9）。 */
    public void onEntertainmentArrived() {
        this.entertainPlanned = false;
        this.entertainmentTarget = null;
        this.entertainPlanTicks = 0;
    }

    /** 娱乐 goal 放弃（去不了 / 区没了）：回到游荡，1 分钟后再试。 */
    public void failEntertainmentPlan() {
        this.entertainPlanned = false;
        this.entertainmentTarget = null;
        this.entertainPlanTicks = 0;
        this.strollTicks = 0;
    }

    /** 娱乐 goal 结束（5 分钟到 / 被打断）：回到游荡 1 分钟 → 再检测。 */
    public void onEntertainmentEnded() {
        this.entertainPlanned = false;
        this.entertainmentTarget = null;
        this.entertainPlanTicks = 0;
        this.strollTicks = 0;
    }

    // ------------------------------------------------------------------
    // 每 tick
    // ------------------------------------------------------------------

    /** 每 tick 调一次（服务端）。 */
    public void tick(ServerLevel level) {
        if (!this.initialized) {
            this.init(level);
        }
        // 跟随 / 站立：所有计时冻结（14 号文档 3.11）
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey())) {
            return;
        }
        // 调试棒通勤中：暂停所有计时，结束后回到原有行为（问题 9 取的方案）
        if (this.resident.commuteTarget() != null) {
            return;
        }
        // 被玩家翻背包：视同事件，冻结
        if (!this.resident.canLookAround()) {
            return;
        }
        this.refreshWork(level);
        switch (this.phase) {
            case WORK -> this.tickWork(level);
            case FREE -> this.tickFree(level);
            case SLEEP -> this.tickSleep();
            default -> {
            }
        }
        this.tickHomelessChat(level);
    }

    private void init(ServerLevel level) {
        this.initialized = true;
        this.refreshWork(level);
        this.phase = this.hasWork ? Phase.WORK : Phase.FREE;
    }

    /** 刷新「有没有工作区」（每 2 秒一次，别每 tick 遍历）。 */
    private void refreshWork(ServerLevel level) {
        if (--this.zoneCacheCd > 0) {
            return;
        }
        this.zoneCacheCd = ZONE_CACHE_TICKS;
        WorkZone zone = WorkZoneData.get(level).findByEmployee(this.resident.getUUID().toString());
        boolean hadWork = this.hasWork;
        // 道路 / 禁行 / 娱乐都不算「工作场所」
        this.hasWork = zone != null && !WorkZone.TYPE_ROAD.equals(zone.type())
            && !WorkZone.TYPE_FORBIDDEN.equals(zone.type())
            && !zone.isEntertainment();
        this.workZone = this.hasWork ? zone : null;
        // 新指派到工作区：**立刻去上班**（2026-11-23 主人报「被指派后继续游荡」：
        // 以前要等自由时间走完 / 下一轮才开始，流浪居民又没有床，体感就是"永远不上班"）
        if (!hadWork && this.hasWork && this.phase == Phase.FREE) {
            this.phase = Phase.WORK;
            this.workTicks = 0;
            this.entertainPlanned = false;
            this.entertainmentTarget = null;
        }
        // 工作被撤 / 工作区被删：立刻进自由时间（规则 5）
        // 2026-11-23 第 10 轮（14 号文档问题清单）：**今日工作累计清零**（视为今日工作结束；
        // 重新指派从 0 开始），否则新工作区一上任就会被上一条「工作时间已满」直接判定下班。
        if (!this.hasWork && this.phase == Phase.WORK) {
            this.workTicks = 0;
            this.enterFree();
        }
    }

    // ------------------------------------------------------------------
    // 工作
    // ------------------------------------------------------------------

    private void tickWork(ServerLevel level) {
        if (!this.hasWork) {
            // 员工被撤 / 工作区被删 → 立刻结束工作阶段（规则 5）；今日工作累计清零（第 10 轮）
            this.workTicks = 0;
            this.enterFree();
            return;
        }
        // 流浪（无床）累瘫：没有床可以恢复，按 14 号文档 3.22 离开工作、在殖民地游荡，不再上班
        if (this.resident.bedPos() == null
                && this.resident.stamina() <= ResidentEntity.EXHAUSTED_STAMINA_THRESHOLD) {
            this.enterFree();
            return;
        }
        if (this.workZone == null
                || WorkZoneData.get(level).findByKey(this.workZone.key()) == null) {
            this.enterFree();
            return;
        }
        // 走进去才开始计时（规则 1）；工作区外 = 通勤，不计
        // 2026-11-23：树场允许在区域外几格里工作（树叶 / 树苗占满时），用 inWorkRangeBody 统一口径
        if (!this.workZone.inWorkRangeBody(this.resident)) {
            return;
        }
        // 战斗 / 社交：暂停工作计时，事件结束后继续（规则 2）
        if (this.resident.isInCombat()) {
            return;
        }
        if (ResidentJobs.SOCIAL.equals(this.resident.activityKey())) {
            return;
        }
        if (++this.workTicks >= WORK_TICKS) {
            this.enterFree();
        }
    }

    // ------------------------------------------------------------------
    // 自由
    // ------------------------------------------------------------------

    private void tickFree(ServerLevel level) {
        // 正在去娱乐区的路上：通勤**不减少**自由时间（规则 8）
        if (this.entertainPlanned) {
            if (this.entertainmentTarget == null
                    || WorkZoneData.get(level).findByKey(this.entertainmentTarget.key()) == null) {
                this.failEntertainmentPlan();
                return;
            }
            // 兜底：3 分钟还没开始娱乐（goal 没启动 / 走不到），就当这次失败，回游荡重来
            if (++this.entertainPlanTicks > 20 * 180) {
                this.failEntertainmentPlan();
            }
            return;
        }
        // 游荡 / 娱乐 / 战斗：自由时间**持续计时**（规则 9、10）
        this.freeTicks++;
        if (this.freeTicks >= this.freeDuration()) {
            this.enterSleep(level);
            return;
        }
        // 娱乐中：计时已计入，结束由娱乐 goal 通知
        if (ResidentJobs.ENTERTAINING.equals(this.resident.activityKey())) {
            return;
        }
        if (this.resident.isInCombat()) {
            return;
        }
        // 游荡满 1 分钟 → 检测要不要去娱乐
        if (++this.strollTicks < STROLL_CHECK_TICKS) {
            return;
        }
        this.strollTicks = 0;
        if (this.remainingFreeTicks() < MIN_FOR_ENTERTAINMENT) {
            return;
        }
        // 2026-11-23 主人定：**心情满也照常按日程去娱乐**（旧的「≥90 不去」作废）
        WorkZone zone = this.findEntertainment(level);
        if (zone != null) {
            this.entertainmentTarget = zone;
            this.entertainPlanned = true;
        }
    }

    /** 工作结束 / 被撤 → 自由时间（规则 5）。 */
    private void enterFree() {
        this.phase = Phase.FREE;
        this.freeTicks = 0;
        this.strollTicks = 0;
        this.entertainPlanned = false;
        this.entertainmentTarget = null;
    }

    /**
     * 挑一个娱乐区：**所有已保存的娱乐区**里，取 128 格内最近的那个
     * （主人 2026-11-23：先按「所有已保存」找，再加 128 格限制；找不到就继续游荡）。
     */
    @Nullable
    private WorkZone findEntertainment(ServerLevel level) {
        WorkZone best = null;
        double bestDistance = MAX_ENTERTAINMENT_DISTANCE * MAX_ENTERTAINMENT_DISTANCE;
        for (WorkZone zone : WorkZoneData.get(level).zones()) {
            if (!zone.isEntertainment()) {
                continue;
            }
            double distance = zone.box().distanceToSqr(this.resident.position());
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = zone;
            }
        }
        return best;
    }

    /** 回床休息（规则 14）：有床就回去睡到体力满；没床（流浪）直接开下一轮。 */
    private void enterSleep(ServerLevel level) {
        this.phase = Phase.SLEEP;
        this.sleepRequested = true;
        this.entertainPlanned = false;
        this.entertainmentTarget = null;
        BlockPos bed = this.resident.bedPos();
        if (bed != null && isRealBed(level, bed)) {
            // 走到床边交给 tickBedReturn / 通勤 goal
            return;
        }
        // 没有（有效的）床：不进入休息节点，直接下一轮
        this.sleepRequested = false;
        this.startNextRound();
    }

    /** 流浪 / 无床：本轮结束后的下一轮（工作 → 自由，或继续自由）。 */
    private void startNextRound() {
        this.workTicks = 0;
        this.freeTicks = 0;
        this.strollTicks = 0;
        this.entertainPlanned = false;
        this.entertainmentTarget = null;
        this.phase = this.hasWork ? Phase.WORK : Phase.FREE;
    }

    // ------------------------------------------------------------------
    // 睡眠
    // ------------------------------------------------------------------

    private void tickSleep() {
        // 兜底：体力已经满、SleepGoal 却没接管（例如躺在满体力时被叫去睡觉）→ 直接新一天
        if (this.sleepRequested
                && this.resident.stamina()
                    >= com.worldplayers.resident.ai.ResidentSleepGoal.WAKE_THRESHOLD) {
            this.onNaturalWake();
        }
    }

    /** 自然睡醒 = 新一天（14 号文档 3.16）。 */
    public void onNaturalWake() {
        this.sleepRequested = false;
        this.homelessChatLevel = 0;
        this.resident.clearCommute();
        this.startNextRound();
    }

    /** 回笼觉失败 = 新一天，跳过剩余（14 号文档 3.15 / 3.16）。 */
    public void onSleepGaveUp() {
        this.onNaturalWake();
    }

    /** 这个居民现在是不是在 SLEEP 阶段且还没睡着（要回床）。 */
    public boolean needsBed() {
        return this.phase == Phase.SLEEP && this.sleepRequested;
    }

    // ------------------------------------------------------------------
    // 流浪聊天
    // ------------------------------------------------------------------

    /** 流浪（无床）居民按体力档位喊话，**每一档每天只发一次**（14 号文档 3.23 + 问题 6 的建议）。 */
    private void tickHomelessChat(ServerLevel level) {
        if (this.resident.bedPos() != null) {
            return;
        }
        int levelIndex = ResidentChatPool.homelessLevel(this.resident.stamina());
        if (levelIndex <= this.homelessChatLevel) {
            return;
        }
        this.homelessChatLevel = levelIndex;
        ResidentChatPool.sayHomeless(level, this.resident, levelIndex);
    }

    // ------------------------------------------------------------------
    // 存读档
    // ------------------------------------------------------------------

    public void save(net.minecraft.nbt.CompoundTag tag) {
        tag.putString("SchedulePhase", this.phase.name());
        tag.putInt("ScheduleWorkTicks", this.workTicks);
        tag.putInt("ScheduleFreeTicks", this.freeTicks);
        tag.putInt("ScheduleStrollTicks", this.strollTicks);
        tag.putBoolean("ScheduleSleepRequested", this.sleepRequested);
        tag.putInt("ScheduleHomelessChat", this.homelessChatLevel);
    }

    public void load(net.minecraft.nbt.CompoundTag tag) {
        if (!tag.contains("SchedulePhase")) {
            // 旧存档：第一次 tick 时按「有没有工作区」自动初始化
            return;
        }
        try {
            this.phase = Phase.valueOf(tag.getString("SchedulePhase"));
        } catch (IllegalArgumentException unknown) {
            this.phase = Phase.FREE;
        }
        this.workTicks = Math.max(0, tag.getInt("ScheduleWorkTicks"));
        this.freeTicks = Math.max(0, tag.getInt("ScheduleFreeTicks"));
        this.strollTicks = Math.max(0, tag.getInt("ScheduleStrollTicks"));
        this.sleepRequested = tag.getBoolean("ScheduleSleepRequested");
        this.homelessChatLevel = tag.getInt("ScheduleHomelessChat");
        this.initialized = true;
    }

    private int freeDuration() {
        return this.hasWork ? FREE_WORK_TICKS : FREE_IDLE_TICKS;
    }

    private static boolean isRealBed(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() instanceof BedBlock;
    }
}
