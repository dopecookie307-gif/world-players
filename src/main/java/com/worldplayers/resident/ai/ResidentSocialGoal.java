package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import com.worldplayers.resident.ResidentSocial;
import java.util.EnumSet;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;

/**
 * 社交（v0.3.0 T4，主人 2026-11-21 简化版）：**不加任何数值计算，只是把食物从一个人的背包搬到另一个人的背包**。
 *
 * <p>触发（见 {@link ResidentSocial}）：</p>
 * <ul>
 *   <li><b>A 没饭吃</b>：背包食物为 0 的居民每 10 秒检查一次，挂上需求；</li>
 *   <li><b>B 被饿伤</b>：{@code ResidentHunger} 的饥饿伤害里立刻挂需求（同样的 16 格内找给者，但不等 A 的 10 秒检查）。</li>
 * </ul>
 *
 * <p>流程（服务端跑，整段预算 200 tick）：</p>
 * <ol>
 *   <li><b>接近</b>：{@code navigation.moveTo(对方, 1.0)}，3 格内就停；超过 8 秒（160 tick）放弃；</li>
 *   <li><b>对视</b>：40 tick，两人都 {@code setLookAt} 对方；</li>
 *   <li><b>轮流挥手</b>：给者 1~2 次、被给者 1~2 次，每次间隔 10 tick，用 {@code swing(MAIN_HAND)}；</li>
 *   <li><b>转食物</b>：给者按比例（{@code clamp(ceil(食物数 × 20%), 1, 4)}）把食物搬进被给者背包；</li>
 *   <li><b>双方心情 +5%</b>，状态清空、散开（各自回到原本的行为）。</li>
 * </ol>
 *
 * <p>打断：打起来（任一方进战斗）或被玩家翻背包 → 立刻中止、清状态、散开。被给者自己不吃不喝，
 * 只负责站着、看对方、挥手——食物进背包后自然会走既有的吃饭逻辑。</p>
 */
public class ResidentSocialGoal extends Goal {
    /** 接近到 3 格内就停（主人 2026-11-21 定）。 */
    private static final double STOP_DISTANCE = 3.0D;
    /** 找「需要被给饭」的邻居：16 格内。 */
    private static final double SEARCH_RANGE = 16.0D;
    /** 接近超过 8 秒就放弃。 */
    private static final int APPROACH_TIMEOUT = 160;
    /** 对视 40 tick（2 秒）。 */
    private static final int LOOK_TICKS = 40;
    /** 挥手间隔 10 tick。 */
    private static final int WAVE_INTERVAL = 10;
    /** 找目标的节流（每 10 tick 扫一次邻居）。 */
    private static final int SCAN_INTERVAL = 10;
    /** 整段硬上限：对视 40 + 挥手最多 40 已经够 200 的预算，这里给接近留足余量。 */
    private static final int MAX_TOTAL_TICKS = 240;
    /** 分享后的心情收益：+5%（心情是 0..100，所以直接 +5）。 */
    private static final float MOOD_GAIN = 5.0F;

    private enum Phase { APPROACH, LOOK, WAVE, TRANSFER }

    private final ResidentEntity resident;
    /** 本次社交的对方（给者 = 被给者 / 被给者 = 给者）。 */
    @Nullable
    private ResidentEntity partner;
    /** canUse 里挑好的候选，start 时才正式锁定（canUse 不产生副作用）。 */
    @Nullable
    private ResidentEntity pendingPartner;
    /** 自己是"给者"还是"被给者"。 */
    private boolean giver;
    private Phase phase;
    private int ticks;
    private int phaseTicks;
    /** 挥手：当前是谁在挥（0 = 给者，1 = 被给者）、还要挥几次、下次挥手倒计时。 */
    private int waveSide;
    private int waveRemaining;
    private int waveTicks;
    private int scanTimer;
    private boolean done;

    public ResidentSocialGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey()) || this.resident.isInCombat()
                || !this.resident.canLookAround()) {
            return false;
        }
        ResidentSocial self = this.resident.socialSystem();
        // 被点名：对方已经把配对写到我身上了（不检查冷却——被给者可能是 B 触发的紧急情况）
        ResidentEntity requested = self.partner();
        if (requested != null && requested.isAlive() && !requested.isRemoved()
                && requested.level() == this.resident.level()
                && requested.socialSystem().partner() == this.resident) {
            this.pendingPartner = requested;
            this.giver = false;
            return true;
        }
        // 娱乐中的人**不主动发起**分享食物（14 号文档 2.12）；被点名的路径在上面已经放行
        if (ResidentJobs.ENTERTAINING.equals(this.resident.activityKey())) {
            return false;
        }
        // 自己是给者候选：自己不在冷却、自己没饿着（不抢自己的饭）
        if (self.isOnCooldown() || self.hasNeed()) {
            return false;
        }
        if (++this.scanTimer < SCAN_INTERVAL) {
            return false;
        }
        this.scanTimer = 0;
        if (ResidentSocial.foodCount(this.resident) < ResidentSocial.GIVER_MIN_FOOD) {
            return false;
        }
        ResidentEntity target = this.findNeedy(level);
        if (target == null) {
            return false;
        }
        this.pendingPartner = target;
        this.giver = true;
        return true;
    }

    @Override
    public void start() {
        this.done = false;
        this.ticks = 0;
        this.phaseTicks = 0;
        this.waveSide = 0;
        this.waveRemaining = 0;
        this.waveTicks = 0;
        this.partner = this.pendingPartner;
        this.pendingPartner = null;
        ResidentEntity claimed = this.partner == null ? null : this.partner.socialSystem().partner();
        if (this.partner == null || !this.partner.isAlive() || this.partner.isRemoved()
                || (claimed != null && claimed != this.resident)) {
            // 配对失效（对方死了 / 已经跟别人跑了）→ 下一 tick 直接结束，不碰对方
            this.partner = null;
            this.done = true;
            this.phase = Phase.APPROACH;
            return;
        }
        // 正式锁定双向配对
        this.resident.socialSystem().setPartner(this.partner);
        this.partner.socialSystem().setPartner(this.resident);
        this.phase = this.giver ? Phase.APPROACH : Phase.LOOK;
        // 浮窗 / Jade / 手册自动跟着显示「社交」（以后其他社交行为共用这个状态）
        this.resident.setWorkActivity(ResidentJobs.SOCIAL, "");
        this.partner.setWorkActivity(ResidentJobs.SOCIAL, "");
        this.resident.getNavigation().stop();
        this.partner.getNavigation().stop();
    }

    @Override
    public boolean canContinueToUse() {
        if (this.done || this.partner == null) {
            return false;
        }
        if (!this.partner.isAlive() || this.partner.isRemoved()
                || this.partner.level() != this.resident.level()) {
            return false;
        }
        // 双向配对才算数（对方被战斗/别的 goal 抢走时这里会失败 → 立刻散开）
        if (this.partner.socialSystem().partner() != this.resident
                || this.resident.socialSystem().partner() != this.partner) {
            return false;
        }
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey())) {
            return false;
        }
        // 打起来 / 被玩家翻背包 → 中止
        if (this.resident.isInCombat() || this.partner.isInCombat()
                || !this.resident.canLookAround() || !this.partner.canLookAround()) {
            return false;
        }
        return true;
    }

    @Override
    public void tick() {
        if (this.partner == null) {
            return;
        }
        this.ticks++;
        // 对视：两人互看（被给者也在自己的 goal 里看着给者）
        this.resident.getLookControl().setLookAt(this.partner, 30.0F, 30.0F);
        if (!this.giver) {
            // 被给者：站着、看着、不干活（挥手由给者那边统一驱动）
            this.resident.getNavigation().stop();
            return;
        }
        if (this.ticks > MAX_TOTAL_TICKS) {
            this.done = true;
            return;
        }
        switch (this.phase) {
            case APPROACH -> this.tickApproach();
            case LOOK -> this.tickLook();
            case WAVE -> this.tickWave();
            case TRANSFER -> {
                this.transfer();
                this.done = true;
            }
        }
    }

    /** 接近：3 格内停；8 秒还没到就放弃。 */
    private void tickApproach() {
        ResidentEntity target = this.partner;
        if (target == null) {
            this.done = true;
            return;
        }
        double distance = this.resident.distanceToSqr(target);
        if (distance <= STOP_DISTANCE * STOP_DISTANCE) {
            this.resident.getNavigation().stop();
            this.phase = Phase.LOOK;
            this.phaseTicks = 0;
            return;
        }
        if (this.ticks > APPROACH_TIMEOUT) {
            this.done = true;
            return;
        }
        if (this.resident.getNavigation().isDone()) {
            this.resident.getNavigation().moveTo(target.getX(), target.getY(), target.getZ(), 1.0D);
        }
    }

    /** 对视 40 tick。 */
    private void tickLook() {
        if (++this.phaseTicks < LOOK_TICKS) {
            return;
        }
        this.phase = Phase.WAVE;
        this.waveSide = 0;
        this.waveRemaining = 1 + this.resident.getRandom().nextInt(2);
        this.waveTicks = 0;
    }

    /** 轮流挥手：给者 1~2 次 → 被给者 1~2 次，每次间隔 10 tick。 */
    private void tickWave() {
        if (--this.waveTicks > 0) {
            return;
        }
        this.waveTicks = WAVE_INTERVAL;
        this.swing(this.waveSide == 0 ? this.resident : this.partner);
        if (--this.waveRemaining > 0) {
            return;
        }
        if (this.waveSide == 0) {
            this.waveSide = 1;
            this.waveRemaining = 1 + this.resident.getRandom().nextInt(2);
        } else {
            this.phase = Phase.TRANSFER;
        }
    }

    private void swing(@Nullable ResidentEntity entity) {
        if (entity == null) {
            return;
        }
        entity.swing(InteractionHand.MAIN_HAND, true);
        entity.level().playSound(null, entity.getX(), entity.getY(), entity.getZ(),
            SoundEvents.PLAYER_ATTACK_NODAMAGE, SoundSource.PLAYERS, 0.2F, 1.4F);
    }

    /**
     * 转食物（主人 2026-11-21 定 A 方案）：**每 8 个食物分享 1 个**，最少 1、最多 16；
     * 上限再夹到自己的总数，普通食物优先、副手也算。对方背包塞不下的掉在他脚下（不凭空消失）。
     * 最后双方心情 +5%、清需求、进 10 秒冷却。
     */
    private void transfer() {
        ResidentEntity target = this.partner;
        if (target == null) {
            return;
        }
        int total = ResidentSocial.foodCount(this.resident);
        if (total > 0) {
            // ceil(total / 8)，整数算法：2→1、8→1、9→2、32→4、128→16（封顶）
            int count = Math.clamp((total + 7) / 8, 1, 16);
            count = Math.min(count, total);
            List<ItemStack> taken = ResidentSocial.takeFood(this.resident, count);
            for (ItemStack stack : taken) {
                ItemStack rest = target.inventory().addItem(stack);
                if (!rest.isEmpty()) {
                    target.spawnAtLocation(rest);
                }
            }
        }
        this.resident.setMood(this.resident.mood() + MOOD_GAIN);
        target.setMood(target.mood() + MOOD_GAIN);
        this.resident.socialSystem().clearNeed();
        target.socialSystem().clearNeed();
        this.resident.socialSystem().startCooldown();
        target.socialSystem().startCooldown();
    }

    @Override
    public void stop() {
        // 不管怎么结束（完成 / 被打断 / 超时）都要解开双向配对、清掉「社交」状态
        if (this.partner != null) {
            if (this.partner.socialSystem().partner() == this.resident) {
                this.partner.socialSystem().setPartner(null);
                if (ResidentJobs.SOCIAL.equals(this.partner.activityKey())) {
                    this.partner.setActivity(null);
                }
                this.partner.getNavigation().stop();
            }
            this.partner = null;
        }
        this.resident.socialSystem().setPartner(null);
        if (ResidentJobs.SOCIAL.equals(this.resident.activityKey())) {
            this.resident.setActivity(null);
        }
        this.resident.getNavigation().stop();
    }

    /** 16 格内找一个「挂了需求、还没被人认领」的居民；B（饿伤）的需求优先，其次最近的。 */
    @Nullable
    private ResidentEntity findNeedy(ServerLevel level) {
        ResidentEntity best = null;
        boolean bestUrgent = false;
        double bestDistance = Double.MAX_VALUE;
        for (ResidentEntity other : level.getEntitiesOfClass(ResidentEntity.class,
                this.resident.getBoundingBox().inflate(SEARCH_RANGE),
                other -> other != this.resident && other.isAlive())) {
            if (!ResidentJobs.IDLE.equals(other.jobKey()) || other.isInCombat()) {
                continue;
            }
            ResidentSocial social = other.socialSystem();
            if (!social.hasNeed() || social.isOnCooldown() || social.partner() != null) {
                continue;
            }
            double distance = this.resident.distanceToSqr(other);
            if (distance > SEARCH_RANGE * SEARCH_RANGE) {
                continue;
            }
            boolean urgent = social.isUrgent();
            if (urgent && !bestUrgent) {
                best = other;
                bestUrgent = true;
                bestDistance = distance;
            } else if (urgent == bestUrgent && distance < bestDistance) {
                best = other;
                bestDistance = distance;
            }
        }
        return best;
    }
}
