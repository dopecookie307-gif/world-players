package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import java.util.EnumSet;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * 睡觉（v0.3.0 T8 的第一块，主人 2026-11-21）：体力见底回床之后**睡下并恢复体力**。
 *
 * <p>数值（主人拍板）：睡觉时 **0.3 / 秒**（每 tick 0.015），体力回到 **90%** 自动起床。
 * 10% → 90% 需要 80 点 ≈ **267 秒（约 4 分 27 秒）**。这比原版 MC 的一晚（约 7 分钟）短，
 * 所以一个晚上足够回满；如果想要「睡满一夜才满」，把 {@link #REST_PER_TICK} 改成 0.01 即可（400 秒）。</p>
 *
 * <p>表现：</p>
 * <ul>
 *   <li><b>普通玩家模型</b>：照搬玩家/村民的睡眠——{@link ResidentEntity#startSleeping(BlockPos)}
 *       （原版会摆出 SLEEPING 姿势、把床标成占用、位置贴到床上；渲染由原版
 *       {@code LivingEntityRenderer#setupRotations} 自动躺平）。</li>
 *   <li><b>YSM 模型</b>：**不进入原版睡眠姿势**，只在原地站着恢复体力、状态显示「睡觉」。
 *       这是最保守的做法：YSM 渲染完全自己画几何、不经过原版 pose 处理，强行躺下会牵扯渲染链。
 *       随时可回退——把 {@link #YSM_USES_VANILLA_SLEEP} 改成 {@code true} 就是试着让它也躺下。</li>
 * </ul>
 *
 * <p>只在「自由活动」里睡：跟随 / 站立 / 战斗中 / 被玩家翻背包 / 床没了 都会立刻起床。</p>
 */
public class ResidentSleepGoal extends Goal {
    /** 睡觉回体力速率：0.015 / tick = 0.3 / 秒（主人 2026-11-21 定）。 */
    public static final float REST_PER_TICK = 0.015F;
    /**
     * 体力回到这个值就醒。
     *
     * <p>**2026-11-22 改（14 号文档 5.2）**：旧的「90% 醒」作废，改成**体力满 100%** 醒；
     * 10% → 100% 约 **300 秒（5 分钟）**。</p>
     */
    public static final float WAKE_THRESHOLD = 100.0F;
    /**
     * 自然睡醒回复的心情（14 号文档 4 / 数值定稿）：每次 **28~32 随机**（平均 30）。
     *
     * <p>被打断 / 回笼觉失败**不给**；睡觉过程本身不扣心情。</p>
     */
    public static final int WAKE_MOOD_MIN = 28;
    public static final int WAKE_MOOD_MAX = 32;
    /** 离床多近才睡（和「回床」的停止距离一致）。 */
    private static final double SLEEP_RANGE = 2.5D;

    /**
     * YSM 居民是否也用原版睡眠姿势。
     *
     * <p>**2026-11-21 主人要求打开（true）实测效果**：YSM 模型自己绘制几何、不走原版 {@code setupRotations}，
     * 所以可能「站着躺在床的位置上」。效果不满意就把这一项改回 {@code false}（一行回退，只影响 YSM）。</p>
     */
    public static final boolean YSM_USES_VANILLA_SLEEP = true;

    private final ResidentEntity resident;
    @Nullable
    private BlockPos bed;
    /** 本次是否用了原版睡眠姿势（决定起床时要不要 stopSleeping / 挪出床）。 */
    private boolean vanillaSleep;

    public ResidentSleepGoal(ResidentEntity resident) {
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
        if (this.resident.stamina() >= WAKE_THRESHOLD) {
            return false;
        }
        // 触发条件：累瘫（≤10%）、睡眠被打断后还在 30 秒续睡窗口里（体力 <90% 都算），
        // 或者**日程到了回床休息的时间**（14 号文档第三节：自由时间结束 → 睡到体力满）
        boolean requested = this.resident.schedule().wantsSleep();
        if (this.resident.stamina() > ResidentEntity.EXHAUSTED_STAMINA_THRESHOLD
                && !this.resident.sleepInterruptActive() && !requested) {
            return false;
        }
        // 这轮睡觉已经放弃（打断 30 秒都回不去）→ 照常工作/闲逛，不再硬拉回去睡
        if (this.resident.bedReturnGivenUp()) {
            return false;
        }
        if (this.resident.isInCombat() || !this.resident.canLookAround()) {
            return false;
        }
        // 玩家刚用调试棒下达了别的通勤目标 → 先听玩家的，不睡
        if (this.resident.commuteTarget() != null) {
            return false;
        }
        BlockPos bedPos = this.resident.bedPos();
        if (bedPos == null || !isRealBed(level, bedPos)) {
            return false;
        }
        if (this.resident.distanceToSqr(bedPos.getX() + 0.5D, bedPos.getY() + 0.5D,
                bedPos.getZ() + 0.5D) > SLEEP_RANGE * SLEEP_RANGE) {
            return false;
        }
        this.bed = bedPos;
        return true;
    }

    @Override
    public void start() {
        this.vanillaSleep = YSM_USES_VANILLA_SLEEP
            || this.resident.modelType() != ResidentEntity.MODEL_YSM;
        this.resident.setActivity(ResidentJobs.SLEEPING);
        this.resident.getNavigation().stop();
        this.resident.clearCommute();
        if (this.vanillaSleep && this.bed != null && this.resident.level() instanceof ServerLevel level) {
            // 绑定时存的是玩家点的那一半，可能是床头也可能是床尾。
            // 原版渲染的躺姿平移是从**床的朝向**往回推，传入坐标必须给**床头**那一格，
            // 给床尾就会把整段身体铺到床外（2026-11-21 实测「睡在床尾 / 下半身悬空」就是这个）。
            this.resident.startSleeping(headOf(level, this.bed));
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        if (this.bed == null || this.resident.stamina() >= WAKE_THRESHOLD) {
            return false;
        }
        // 2026-11-23 主人定：**跟随 / 站立永远最高优先级** —— 切过去就暂停睡眠（人起床、体力停在原处），
        // 取消跟随 / 站立后回到原有活动，包括接着睡（睡眠进度 = 体力，天然保留）。
        // 注意：这是「暂停」不是「打断」，不会走续睡窗口 / 回笼觉失败那一套（见 stop()）。
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey())) {
            return false;
        }
        if (this.resident.isInCombat() || !this.resident.canLookAround()) {
            return false;
        }
        if (!isRealBed(level, this.bed)) {
            return false;
        }
        // 原版睡眠状态如果被别人打断（死亡/传送），也别硬撑
        return !this.vanillaSleep || this.resident.isSleeping();
    }

    @Override
    public void tick() {
        if (this.resident.stamina() >= WAKE_THRESHOLD) {
            return;
        }
        // 睡觉回体力：0.3 / 秒。最后一次直接顶到 100，避免浮点误差卡在 99.99 永远不醒
        float next = this.resident.stamina() + REST_PER_TICK;
        this.resident.setStamina(next >= WAKE_THRESHOLD ? WAKE_THRESHOLD : next);
    }

    @Override
    public void stop() {
        boolean naturalWake = this.resident.stamina() >= WAKE_THRESHOLD;
        // 2026-11-23 主人定：跟随 / 站立是「暂停睡眠」，不是打断 ——
        // 起床、体力停在原处，**不记续睡窗口、不算回笼觉失败**；
        // 取消跟随 / 站立后日程仍是 SLEEP，tickBedReturn 会带他回床接着睡。
        boolean pausedByMode = !ResidentJobs.IDLE.equals(this.resident.jobKey());
        // 自然睡醒回复心情 +28~32（14 号文档：被打断 / 回笼觉失败不给）
        if (naturalWake) {
            int gain = WAKE_MOOD_MIN
                + this.resident.getRandom().nextInt(WAKE_MOOD_MAX - WAKE_MOOD_MIN + 1);
            this.resident.setMood(this.resident.mood() + gain);
            // 自然睡醒 = 新一天（14 号文档 3.16）：日程回到工作 / 自由
            this.resident.schedule().onNaturalWake();
        }
        // 2026-11-22 修：不管这次是不是「用了原版睡姿」，只要实体还带着睡眠姿势就一定要站起来。
        // 以前只在 vanillaSleep && isSleeping() 时清，漏掉「读档后带着睡眠姿势回来」的情况，
        // 居民会躺着滑行、AI 也无法移动。
        if (this.resident.isSleeping()) {
            // 原版 stopSleeping 自己会把实体摆到床边能站的位置、朝向床外，不要在这里再移动
            this.resident.stopSleeping();
        }
        if (this.resident.isSleeping()) {
            // sleepingPos 丢失时原版 stopSleeping 不会清 pose，这里兜底
            this.resident.setPose(net.minecraft.world.entity.Pose.STANDING);
        }
        // 不是自然睡醒（被打断 / 床没了）：开始 30 秒「回去续睡」窗口。
        // 被跟随 / 站立暂停的这一路不算打断 —— 切回自由活动直接继续睡。
        if (!naturalWake && !pausedByMode && !this.resident.isRemoved()
                && !this.resident.isDeadOrDying()) {
            this.resident.markSleepInterrupted();
        }
        if (ResidentJobs.SLEEPING.equals(this.resident.activityKey())) {
            this.resident.setActivity(null);
        }
        this.resident.getNavigation().stop();
        this.bed = null;
        this.vanillaSleep = false;
    }

    private static boolean isRealBed(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof BedBlock;
    }

    /** 统一换算到**头那一格**（床尾格 → 床头格），原版睡眠姿势要求传入床头坐标。 */
    private static BlockPos headOf(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof BedBlock
                && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return pos.relative(BedBlock.getConnectedDirection(state));
        }
        return pos;
    }
}
