package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentChat;
import com.worldplayers.resident.ResidentChatPool;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentInventory;
import com.worldplayers.resident.ResidentJobs;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.fishery.Fishery;
import com.worldplayers.resident.fishery.FisheryCache;
import com.worldplayers.resident.fishery.FisherySpot;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;

/**
 * 渔场工作（v0.3.0，规则见 `纸上谈兵\15-渔场规则.md`）。
 *
 * <p>只做**员工自己的状态机**，找水 / 视线 / 咬钩时间 / 战利品全在
 * {@link Fishery} 与 {@link FisheryCache} 里（规则第六节：工作 goal 只加一个分支）。</p>
 *
 * <pre>
 * 有竿？ ──否──▶ 喊一句「没竿」→ 原地等 60 秒 → 再看背包（循环，不设上限）
 *   │是
 *   ▼
 * 走到站位 → 面向水 → 抛竿 → 等咬钩（随机 + 饵钓）→ 收杆结算 → 回开头
 * </pre>
 *
 * <ul>
 *   <li><b>水</b>：框里只标站位，水在框外 4.5 格内找；每次**抛竿前**才检查水还在不在
 *       （规则 3.2 / 3.6：等待咬钩期间水变了不打断）；</li>
 *   <li><b>副手钓竿</b>：算有竿，工作时与主手交换；</li>
 *   <li><b>干涸</b>：区域里踱步 + 喊一句，每 60 秒重试一次；</li>
 *   <li><b>消耗</b>：每次结算体力 −0.1、钓竿耐久 −1（附魔照常生效）；背包满就原地暂停。</li>
 * </ul>
 */
final class FisheryWork {
    /** 没竿：原地等 60 秒再翻一次背包（规则 3.1）。 */
    private static final int NO_ROD_WAIT = 20 * 60;
    /** 干涸：每 60 秒重试 / 再喊一句（规则 2.3）。 */
    private static final int DRY_RETRY = 20 * 60;
    /** 走向站位超过 10 秒（按 20 tick 的检查节拍算，10 次 × 20 = 200 tick）：放弃这个钓点、重新解析。 */
    private static final int WALK_TIMEOUT = 10;
    /** 「已经在站位上」的容差：1.5 格。 */
    private static final double SPOT_RANGE_SQR = 1.5D * 1.5D;
    /** 翻背包找竿的节流（1 秒）。 */
    private static final int ROD_CHECK_CD = 20;

    private final ResidentWorkGoal owner;
    private final ResidentEntity resident;

    /** 等待咬钩中（这段时间什么都不检查，规则 3.6）。 */
    private boolean casting;
    /** 咬钩剩余 tick。 */
    private int waitTicks;
    /** 找竿的节流。 */
    private int rodCheckCd;
    /** 走向站位已经走了多久。 */
    private int walkTicks;
    /** 结算后的动作冷却。 */
    private int actionCd;
    /** 背包满暂停（规则 3.6）。 */
    private boolean bagPaused;
    /** 这一竿抛在哪个水方块（结算的战利品原点）。 */
    @Nullable
    private BlockPos water;
    private long lastRodChat = Long.MIN_VALUE;
    private long lastDryChat = Long.MIN_VALUE;

    FisheryWork(ResidentWorkGoal owner, ResidentEntity resident) {
        this.owner = owner;
        this.resident = resident;
    }

    /** 工作 goal 开始 / 结束 / 被打断：清掉临时状态（钓点缓存不动，交给 {@link FisheryCache}）。 */
    void reset() {
        this.casting = false;
        this.waitTicks = 0;
        this.rodCheckCd = 0;
        this.walkTicks = 0;
        this.actionCd = 0;
        this.bagPaused = false;
        this.water = null;
    }

    void tick(ServerLevel level, WorkZone zone) {
        long now = level.getGameTime();
        if (this.actionCd > 0) {
            this.actionCd--;
        }

        // 背包满：停止工作、原地站着、每 5 秒探一次（规则 3.6）
        if (this.bagPaused) {
            this.owner.handleBagFull(ResidentJobs.FISHING);
            if (this.owner.tickBagPause(() -> this.resident.inventory().hasRoomForAnything())) {
                this.bagPaused = false;
                this.rodCheckCd = 0;
            }
            return;
        }

        // 等咬钩：不检查水、不检查站位、不走动（规则 3.6）
        if (this.casting) {
            // 主人 2026-11-23：钓鱼期间**始终**看向正在钓的那格水
            this.lookAtWater();
            if (this.waitTicks > 0) {
                this.waitTicks--;
                return;
            }
            this.reelIn(level);
            return;
        }

        // 每 20 tick 才翻一次背包，免得每 tick 扫 36 格
        if (this.rodCheckCd > 0) {
            this.rodCheckCd--;
            return;
        }
        this.rodCheckCd = ROD_CHECK_CD;

        if (!this.holdRod()) {
            // 没竿：喊一句（60 秒一句），原地站着等（规则 3.1）
            this.resident.getNavigation().stop();
            this.walkTicks = 0;
            if (this.lastRodChat == Long.MIN_VALUE || now - this.lastRodChat >= NO_ROD_WAIT) {
                this.lastRodChat = now;
                ResidentChat.say(level, this.resident, ResidentChatPool.pick(
                    ResidentChatPool.Kind.TOOL_ROD, this.resident.getRandom()));
            }
            return;
        }
        this.goFish(level, zone);
    }

    // ------------------------------------------------------------------
    // 走到站位 / 抛竿 / 收杆
    // ------------------------------------------------------------------

    private void goFish(ServerLevel level, WorkZone zone) {
        long now = level.getGameTime();
        FisheryCache.Entry entry = FisheryCache.get(level, zone, this.resident, now, false);
        if (entry.dry()) {
            // 干涸：留在区域内踱步，每 60 秒重试时再喊一句（规则 2.3）
            if (this.lastDryChat == Long.MIN_VALUE || now - this.lastDryChat >= DRY_RETRY) {
                this.lastDryChat = now;
                ResidentChat.say(level, this.resident, ResidentChatPool.pick(
                    ResidentChatPool.Kind.TOOL_ROD_DRY, this.resident.getRandom()));
            }
            this.walkTicks = 0;
            this.owner.wander(level);
            return;
        }
        FisherySpot spot = entry.spot();
        // 走向站位的路上也把脸朝着水面（主人 2026-11-23：钓鱼的居民始终看向水）
        this.water = spot.water();
        this.lookAtWater();
        if (!this.atSpot(spot)) {
            if (++this.walkTicks > WALK_TIMEOUT) {
                // 走不过去（被围栏 / 水挡住）：丢掉这个钓点，重新解析
                this.walkTicks = 0;
                FisheryCache.invalidate(zone.key());
                return;
            }
            if (this.resident.getNavigation().isDone()) {
                this.owner.moveTo(spot.stand().getX() + 0.5D, spot.stand().getY(),
                    spot.stand().getZ() + 0.5D, 0.9D);
            }
            return;
        }
        this.walkTicks = 0;
        // 抛竿前才侦测一次（规则 3.2 第 1 条 / 3.6：水被填掉就别对着空气抛竿）
        if (!Fishery.isWater(level.getBlockState(spot.water()))
                || !Fishery.hasLineOfSight(level, spot.stand(), spot.water(), this.resident)) {
            FisheryCache.invalidate(zone.key());
            return;
        }
        if (this.actionCd > 0) {
            return;
        }
        this.cast(level, spot);
    }

    /** 站在这个钓点的站位上了吗（1.5 格容差，够一个人站的范围）。 */
    private boolean atSpot(FisherySpot spot) {
        BlockPos stand = spot.stand();
        return this.resident.distanceToSqr(
            stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D) <= SPOT_RANGE_SQR;
    }

    /** 让居民看向正在钓的那格水（抛竿前后每 tick 调）。 */
    private void lookAtWater() {
        BlockPos target = this.water;
        if (target == null) {
            return;
        }
        this.resident.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D,
            target.getZ() + 0.5D, 30.0F, 30.0F);
    }

    /** 抛竿：面向水、挥手、播抛竿音效（不做浮漂实体，规则 3.2 第 4 条）。 */
    private void cast(ServerLevel level, FisherySpot spot) {
        ItemStack rod = this.resident.getMainHandItem();
        if (!(rod.getItem() instanceof FishingRodItem)) {
            return;
        }
        BlockPos target = spot.water();
        this.resident.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D,
            target.getZ() + 0.5D, 30.0F, 30.0F);
        this.resident.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, this.resident.blockPosition(), SoundEvents.FISHING_BOBBER_THROW,
            SoundSource.NEUTRAL, 1.0F, 1.0F);
        this.casting = true;
        this.water = target;
        this.waitTicks = Fishery.biteTicks(level, rod);
    }

    /**
     * 收杆 + 结算（规则 3.2 第 6~7 条、3.5）：播收杆音效 → 原版钓鱼战利品 → 体力 −0.1、耐久 −1。
     * 塞不下的掉落掉在地上，并进入背包满暂停。
     */
    private void reelIn(ServerLevel level) {
        this.casting = false;
        this.waitTicks = 0;
        ItemStack rod = this.resident.getMainHandItem();
        BlockPos target = this.water;
        this.water = null;
        if (target == null || !(rod.getItem() instanceof FishingRodItem)) {
            return;
        }
        level.playSound(null, this.resident.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE,
            SoundSource.NEUTRAL, 1.0F, 1.0F);
        List<ItemStack> drops = Fishery.rollLoot(level, rod, this.resident, target,
            Fishery.luck(level, rod));
        boolean overflow = false;
        for (ItemStack drop : drops) {
            ItemStack rest = this.resident.inventory().addItem(drop);
            if (!rest.isEmpty()) {
                overflow = true;
                this.resident.spawnAtLocation(rest);
            }
        }
        this.resident.setStamina(this.resident.stamina()
            - ResidentWorkGoal.staminaCost(this.resident, ResidentWorkGoal.STAMINA_PER_LABOR));
        ResidentWorkGoal.hurtTool(this.resident, rod);
        this.actionCd = ResidentWorkGoal.laborCooldown(this.resident);
        if (overflow) {
            this.bagPaused = true;
            this.owner.handleBagFull(ResidentJobs.FISHING);
        }
    }

    // ------------------------------------------------------------------
    // 钓竿
    // ------------------------------------------------------------------

    /**
     * 保证主手拿着钓竿（规则 3.1）：
     * 主手 / 快捷栏 / 主背包有竿 → 换到主手；**只有副手有竿**也算有竿，和主手交换。
     *
     * @return false = 这一处都没有竿
     */
    private boolean holdRod() {
        if (this.resident.getMainHandItem().getItem() instanceof FishingRodItem) {
            return true;
        }
        if (this.resident.inventory().holdInMainHand(
                stack -> stack.getItem() instanceof FishingRodItem)) {
            return true;
        }
        ItemStack offhand = this.resident.inventory().getItem(ResidentInventory.OFFHAND);
        if (offhand.getItem() instanceof FishingRodItem) {
            ItemStack main = this.resident.getMainHandItem();
            this.resident.inventory().setItem(ResidentInventory.MAIN_HAND, ItemStack.EMPTY);
            this.resident.inventory().setItem(ResidentInventory.OFFHAND, main);
            this.resident.inventory().setItem(ResidentInventory.MAIN_HAND, offhand);
            return true;
        }
        return false;
    }
}
