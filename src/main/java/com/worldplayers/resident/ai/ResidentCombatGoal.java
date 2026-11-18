package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentInventory;
import com.worldplayers.resident.ResidentJobs;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;

/**
 * 居民战斗：一个 goal 同时管近战和远程（以前是两个 goal 抢 MOVE 标志，互相打断）。
 *
 * <p>选武器的规则：</p>
 * <ul>
 *   <li>6 格以内且背包里有近战武器 → 换近战武器上去打；</li>
 *   <li>超过 6 格（或者干脆没有近战武器）且背包里有远程武器 + 弹药 → 用远程；</li>
 *   <li>只有远程武器时，怪物进了 3 格就面朝它倒退着跑（骷髅那种游走射击）；</li>
 *   <li>弩要上弦：3 格内没有怪才开始拉弦；**已经开始拉就必须拉完**，中途不换武器也不收弩；</li>
 *   <li>**被围**（4.5 格内 ≥2 个威胁）时先朝远离人群的方向脱离，贴脸那只照砍。</li>
 * </ul>
 *
 * <p>近战命中后会先面朝敌人倒退约 1 格，等武器冷却（和玩家攻击冷却的意思一样）。</p>
 */
public class ResidentCombatGoal extends Goal {
    /** 进这个距离就切近战；出去马上回远程（主人要的 6 格）。 */
    private static final double MELEE_ENGAGE = 6.0D;
    /** 追出去的上限，超过就放弃。 */
    private static final double CHASE_RANGE = 40.0D;

    /** 攻击后倒退的 tick 数（0.25 速度 × 14 tick ≈ 1 格）。 */
    private static final int BACKUP_TICKS = 14;
    /** 只有远程武器时：怪物进这个距离就倒退（留 1 格缓冲，好让 3 格内上弦判定过得去）。 */
    private static final double KEEP_AWAY = 4.0D;
    /** 超过这个距离就往前靠，保持在 [KEEP_AWAY, KEEP_FAR] 的射击距离内。 */
    private static final double KEEP_FAR = 12.0D;
    /** 弓拉满要 20 tick（和原版一致）。 */
    private static final int BOW_DRAW_TICKS = 20;
    private static final int BOW_INTERVAL = 16;
    private static final int CROSSBOW_INTERVAL = 12;
    private static final int OTHER_RANGED_INTERVAL = 20;
    /** 上弦时怪的扫描半径。 */
    private static final double LOAD_SAFE_RADIUS = 3.0D;
    /** 被围判定：这个半径内有 ≥2 个威胁，且最近的比自己 4.5 格还近。 */
    private static final double CROWD_RADIUS = 5.0D;
    private static final double CROWD_DANGER = 4.5D;

    private final ResidentEntity resident;
    private LivingEntity target;
    private int retargetCooldown;

    private int backupTicks;
    private int rangedCooldown;
    private int equipCooldown;
    private int strafeTicks;
    private boolean strafeClockwise;
    /** 追目标进度检测（每 20 tick 一轮）。 */
    private int checkTicks;
    private double lastCheckDistance = Double.MAX_VALUE;
    private int stuckRounds;
    /** 最近一次真的打到/射出箭的 tick（用来区分"站着打"和"站着愣"）。 */
    private int lastAttackTick = -1000;
    /** 放弃过的目标 id + 忽略截止 tick。 */
    private int ignoredTargetId = -1;
    private int ignoreUntil;
    /** 被围判定用的威胁清单缓存（每 4 tick 重算，别每 tick 扫一遍实体表）。 */
    private List<Mob> cachedThreats = List.of();
    private int threatCacheTicks;
    /** 本 tick 看不看得见目标（每 tick 只 raycast 一次，别各处重复算）。 */
    private boolean seesTarget;
    /** 连续看不见目标的 tick 数（拉弓时短暂闪断不算，连续久了才收弓）。 */
    private int lostSightTicks;
    /** 视线被挡时正走向的射击位（null = 没有）。 */
    private net.minecraft.core.BlockPos shootingSpot;
    private int shootingSpotUntil;
    private int spotCooldown;
    /** 远程走位的"走这一步还射得到吗"检查缓存（每 5 tick 判一次，别每 tick 三次 raycast）。 */
    private int moveCheckTicks;
    private float goodForward;
    private float goodSide;
    /** 寻路重算节流：没路可走时别每 tick 都跑一次 A*。 */
    private int retryMoveCooldown;

    public ResidentCombatGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.retargetCooldown > 0) {
            this.retargetCooldown--;
            return false;
        }
        LivingEntity found = ResidentCombat.findTarget(this.resident);
        if (found == null) {
            this.retargetCooldown = 10;
            return false;
        }
        // 刚放弃过的"够不着"的目标：晾它一会儿，别下一 tick 又选回来
        if (found.getId() == this.ignoredTargetId && this.resident.tickCount < this.ignoreUntil) {
            this.retargetCooldown = 20;
            return false;
        }
        this.target = found;
        this.resident.setTarget(found);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.target == null || !this.target.isAlive()) {
            return false;
        }
        return this.resident.distanceToSqr(this.target) <= CHASE_RANGE * CHASE_RANGE;
    }

    @Override
    public void start() {
        this.rangedCooldown = 0;
        this.equipCooldown = 0;
        this.backupTicks = 0;
        this.checkTicks = 0;
        this.lastCheckDistance = Double.MAX_VALUE;
        this.stuckRounds = 0;
        this.lostSightTicks = 0;
        this.clearShootingSpot();
        this.spotCooldown = 0;
        this.moveCheckTicks = 0;
        this.retryMoveCooldown = 0;
    }

    @Override
    public void stop() {
        this.target = null;
        this.resident.setTarget(null);
        this.resident.getNavigation().stop();
        this.clearShootingSpot();
        // 正在给弩上弦时不打断：交给 ResidentEntity#tickReload 把弦上完
        if (!this.isReloading()) {
            this.stopRanged();
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }

        if (this.rangedCooldown > 0) {
            this.rangedCooldown--;
        }
        if (this.equipCooldown > 0) {
            this.equipCooldown--;
        }
        // 战斗时把盾牌放到副手（非战斗时不主动碰它，就是普通物品）
        if (this.resident.tickCount % 10 == 0) {
            ResidentCombat.equipShield(this.resident);
        }

        double distance = Math.sqrt(this.resident.distanceToSqr(this.target));
        // 每 tick 只算一次视线：远程射不射、走位会不会挡自己视线，全看它
        this.seesTarget = ResidentCombat.hasLineOfSight(this.resident, this.target);
        if (this.seesTarget) {
            this.lostSightTicks = 0;
        } else if (this.lostSightTicks < 1000) {
            this.lostSightTicks++;
        }
        boolean stand = ResidentJobs.STAND.equals(this.resident.jobKey());
        ResidentInventory inventory = this.resident.inventory();
        boolean hasMelee = ResidentCombat.hasMeleeWeapon(inventory);
        int rangedSlot = ResidentCombat.bestRangedSlot(inventory);

        // 追不动（地底 / 墙后 / 上不去的高台）就先放弃，别死盯着一个到不了的目标
        this.trackProgress(distance);
        if (this.target == null) {
            return;
        }

        // 1) 上弦不可打断：怪物贴脸也先把弦上完，上完再按距离决定近战/远程
        if (this.isReloading()) {
            this.resident.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
            if (stand) {
                this.resident.getNavigation().stop();
            } else if (distance < KEEP_AWAY) {
                this.strafeAway(List.of(this.target));
            } else {
                this.resident.getNavigation().stop();
                this.lockFacing();
            }
            return;
        }

        // 2) 被围：**只在攻击冷却期**才脱离包围圈（v0.1.25）
        //    旧版不管冷却好没好都先 `return`，结果"冷却好了却站在 4~4.5 格举盾发呆/一直后撤"。
        //    主人的规则：保持距离只发生在冷却期间，冷却好了就上去打。
        if (!stand) {
            if (--this.threatCacheTicks <= 0) {
                this.cachedThreats = ResidentCombat.threatsWithin(this.resident, CROWD_RADIUS);
                this.threatCacheTicks = 4;
            }
            List<Mob> threats = this.cachedThreats;
            boolean coolingDown = !this.resident.hasFullAttackStrength();
            if (threats.size() >= 2 && distance < CROWD_DANGER && coolingDown) {
                this.evadeTick(threats, hasMelee);
                return;
            }
        }

        // 3) 正常交战
        this.resident.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
        // 空手也算近战（拳头），不能因为"背包里没有近战武器"就退化成远程/干站
        boolean meleeMode = distance <= MELEE_ENGAGE && (hasMelee || rangedSlot < 0);
        if (meleeMode || rangedSlot < 0) {
            this.meleeTick(distance, stand);
        } else {
            this.rangedTick(distance, stand, rangedSlot, hasMelee);
        }
    }

    /**
     * 追目标的进度检测：每 20 tick 看一次，连续 8 次（≈8 秒）都没靠近就放弃这个目标。
     *
     * <p>专门治"玩家在地底打了只僵尸，居民站在坑边永远够不着"这种。</p>
     */
    private void trackProgress(double distance) {
        if (++this.checkTicks < 20) {
            return;
        }
        this.checkTicks = 0;
        // 距离有没有实质变化（怪在动 / 自己在走都算）
        boolean moved = Math.abs(distance - this.lastCheckDistance) > 0.35D;
        // 最近 6 秒内有没有真的打到它 / 朝它射出去（站着打的时候距离本来就不变）
        boolean attacking = this.resident.tickCount - this.lastAttackTick < 120;
        this.lastCheckDistance = distance;
        if (moved || attacking) {
            this.stuckRounds = 0;
            return;
        }
        if (++this.stuckRounds < 8) {
            return;
        }
        // 放弃：忽略它一段时间，免得下一 tick 又选回来
        this.stuckRounds = 0;
        this.ignoredTargetId = this.target.getId();
        this.ignoreUntil = this.resident.tickCount + 200;
        this.target = null;
        this.resident.setTarget(null);
        this.resident.getNavigation().stop();
        this.retargetCooldown = 40;
    }

    /** 身体正对目标（后退/横移时也保持面向敌人，不然会侧着身子倒着走）。 */
    private void lockFacing() {
        if (this.target == null) {
            return;
        }
        double dx = this.target.getX() - this.resident.getX();
        double dz = this.target.getZ() - this.resident.getZ();
        float yaw = (float) (net.minecraft.util.Mth.atan2(-dx, dz) * (180.0D / Math.PI));
        this.resident.setYRot(yaw);
        this.resident.yBodyRot = yaw;
        this.resident.yBodyRotO = yaw;
        this.resident.yHeadRot = yaw;
        this.resident.yHeadRotO = yaw;
    }

    // ------------------------------------------------------------------
    // 近战
    // ------------------------------------------------------------------

    private void meleeTick(double distance, boolean stand) {
        if (!this.isReloading()) {
            this.stopRanged();
        }
        if (this.equipCooldown <= 0) {
            ResidentCombat.equipMeleeWeapon(this.resident);
            this.equipCooldown = 10;
        }

        double reach = this.reach() + this.target.getBbWidth() * 0.5D;

        // 攻击冷却完全照玩家：主手武器的 ATTACK_SPEED 决定多久能再挥一次
        if (!this.resident.hasFullAttackStrength()) {
            // 冷却中：面朝敌人倒退约 1 格（站立模式钉在原地）
            if (!stand && this.backupTicks > 0 && distance < MELEE_ENGAGE) {
                this.backupTicks--;
                this.lockFacing();
                this.resident.getMoveControl().strafe(-0.8F, 0.0F);
                return;
            }
            if (distance > reach && !stand && this.resident.getNavigation().isDone()) {
                this.resident.getNavigation().moveTo(this.target, 1.15D);
            }
            return;
        }

        if (distance > reach) {
            this.backupTicks = 0;
            if (!stand && this.resident.getNavigation().isDone()) {
                this.resident.getNavigation().moveTo(this.target, 1.15D);
            }
            return;
        }

        this.resident.getNavigation().stop();
        // 3 格以内也要有直视，不许隔墙打
        if (!ResidentCombat.hasLineOfSight(this.resident, this.target)) {
            if (!stand && this.resident.getNavigation().isDone()) {
                this.resident.getNavigation().moveTo(this.target, 1.15D);
            }
            return;
        }

        this.meleeStrike(this.target);
    }

    /** 挥一下（近战和"被围时砍贴脸那只"共用）。冷却没好就直接不打。 */
    private void meleeStrike(LivingEntity victim) {
        if (!this.resident.hasFullAttackStrength()) {
            return;
        }
        this.resident.swing(InteractionHand.MAIN_HAND);
        boolean hurt = this.resident.attackWithMainHand(victim);
        this.lastAttackTick = this.resident.tickCount;
        // 和玩家一样：挥空/打不动也重置冷却，避免"切换目标就连环砍"
        this.resident.resetAttackStrengthTicker();
        if (!hurt) {
            return;
        }
        this.backupTicks = BACKUP_TICKS;
        // 武器耐久：和玩家一样，一刀扣 1 点（附魔/经验修补照常走原版）
        ResidentCombat.wearMainHand(this.resident, 1);
        if (ResidentCombat.isSword(this.resident.getMainHandItem())) {
            ResidentCombat.sweep(this.resident, victim);
        } else {
            ResidentCombat.playMeleeHitSound(this.resident);
        }
    }

    // ------------------------------------------------------------------
    // 被围时脱离
    // ------------------------------------------------------------------

    /**
     * 被围时的应对：**冷却好了就打，没冷却好才拉开一点**（v0.1.25）。
     *
     * <p>主人原话：居民的最终目的是击杀怪物，不是永远拉扯距离往后退。
     * 所以这里只在攻击冷却期间后退；冷却一好就贴上去砍（够不着就走过去，绝不后退）。</p>
     */
    private void evadeTick(List<Mob> threats, boolean hasMelee) {
        LivingEntity nearest = threats.get(0);
        this.resident.getLookControl().setLookAt(nearest, 30.0F, 30.0F);

        double nearestDistance = Math.sqrt(this.resident.distanceToSqr(nearest));
        double reach = this.reach() + nearest.getBbWidth() * 0.5D;
        boolean ready = this.resident.hasFullAttackStrength();
        boolean sees = ResidentCombat.hasLineOfSight(this.resident, nearest);

        if (hasMelee) {
            if (ready && nearestDistance <= reach && sees) {
                // 冷却好了 + 够得着：砍贴脸那只，不后退
                this.resident.getNavigation().stop();
                this.meleeStrike(nearest);
                return;
            }
            if (ready) {
                // 冷却好了但够不着：贴过去（这就是"击杀优先"）
                if (this.resident.getNavigation().isDone() && --this.retryMoveCooldown <= 0) {
                    this.retryMoveCooldown = 10;
                    this.resident.getNavigation().moveTo(nearest, 1.15D);
                }
                return;
            }
            // 冷却中：贴太近才拉开一点，已经不近就站住等冷却
            if (nearestDistance < 3.5D) {
                this.strafeAway(threats);
            } else {
                this.resident.getNavigation().stop();
                this.lockFacing();
            }
            return;
        }

        // 只有远程武器：能射就站定射，冷却中才边退边射
        ItemStack main = this.resident.getMainHandItem();
        if (main.getItem() instanceof CrossbowItem crossbow) {
            this.crossbowTick(nearestDistance, false, main, crossbow);
        } else if (main.getItem() instanceof BowItem) {
            this.bowTick(main);
        } else if (main.getItem() instanceof ProjectileWeaponItem weaponItem) {
            this.simpleShotTick(main, weaponItem);
        }
        if (!ready && nearestDistance < KEEP_AWAY) {
            this.strafeAway(threats);
        } else {
            this.resident.getNavigation().stop();
            this.lockFacing();
        }
    }

    /** 朝"远离所有威胁的质心"的方向移动（倒退 + 侧移，身体仍然正对敌人）。 */
    private void strafeAway(List<? extends LivingEntity> threats) {
        this.lockFacing();
        double centerX = 0.0D;
        double centerZ = 0.0D;
        for (LivingEntity threat : threats) {
            centerX += threat.getX();
            centerZ += threat.getZ();
        }
        centerX /= threats.size();
        centerZ /= threats.size();

        double awayX = this.resident.getX() - centerX;
        double awayZ = this.resident.getZ() - centerZ;
        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length < 1.0E-4D) {
            // 正好站在人群正中央：直接往身后退
            this.resident.getMoveControl().strafe(-1.0F, 0.0F);
            return;
        }
        awayX /= length;
        awayZ /= length;

        double yaw = Math.toRadians(this.resident.getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);

        float forward = (float) (awayX * forwardX + awayZ * forwardZ);
        float side = (float) (awayX * rightX + awayZ * rightZ);
        if (Math.abs(forward) < 0.15F && Math.abs(side) < 0.15F) {
            forward = -1.0F;
        }
        this.resident.getMoveControl().strafe(forward, side);
    }

    // ------------------------------------------------------------------
    // 远程
    // ------------------------------------------------------------------

    private void rangedTick(double distance, boolean stand, int rangedSlot, boolean hasMelee) {
        // 主手必须是**首选**的远程武器（弓优先于弩）；不是就换过来
        if (rangedSlot > 0) {
            this.stopRanged();
            ResidentCombat.equipSlot(this.resident, rangedSlot);
        }
        ItemStack weapon = this.resident.getMainHandItem();
        if (!(weapon.getItem() instanceof ProjectileWeaponItem weaponItem)) {
            // 远程武器换不上来（比如弹药刚好没了）：退回近战/空手
            this.meleeTick(distance, stand);
            return;
        }

        if (weaponItem instanceof CrossbowItem crossbow) {
            this.crossbowTick(distance, stand, weapon, crossbow);
        } else if (weaponItem instanceof BowItem) {
            this.bowTick(weapon);
        } else {
            this.simpleShotTick(weapon, weaponItem);
        }

        this.rangedMovement(distance, stand, hasMelee);
    }

    /** 弓：拉满 20 tick 再放；动画靠 {@code startUsingItem} 的 BOW_AND_ARROW 姿势。 */
    private void bowTick(ItemStack weapon) {
        if (this.resident.isUsingItem()) {
            if (!this.seesTarget) {
                // 短暂闪断（怪在动/自己走位）先别收弓，连续看不见才收
                if (this.lostSightTicks > 40) {
                    this.stopRanged();
                }
                return;
            }
            if (this.resident.getTicksUsingItem() >= BOW_DRAW_TICKS) {
                this.stopRanged();
                this.shootArrow(weapon, BowItem.getPowerForTime(BOW_DRAW_TICKS));
                this.rangedCooldown = BOW_INTERVAL;
            }
            return;
        }
        if (this.rangedCooldown > 0 || !this.seesTarget) {
            return;
        }
        if (ResidentCombat.findAmmo(this.resident.inventory(), (ProjectileWeaponItem) weapon.getItem(),
                ResidentInventory.MAIN_HAND).isEmpty()) {
            return;
        }
        this.resident.startUsingItem(InteractionHand.MAIN_HAND);
    }

    /** 弩：上弦 → 上满挂箭 → 发射；上弦进度用原版的 useItem 计时（音效也由它播）。 */
    private void crossbowTick(double distance, boolean stand, ItemStack weapon, CrossbowItem crossbow) {
        if (CrossbowItem.isCharged(weapon)) {
            if (this.resident.isUsingItem()) {
                this.stopRanged();
            }
            if (this.rangedCooldown > 0 || !this.seesTarget) {
                return;
            }
            if (this.resident.level() instanceof ServerLevel level) {
                crossbow.performShooting(level, this.resident, InteractionHand.MAIN_HAND, weapon, 3.15F, 1.0F, this.target);
                ResidentCombat.wearMainHand(this.resident, 1);
            }
            this.rangedCooldown = CROSSBOW_INTERVAL;
            return;
        }

        if (this.isReloading()) {
            // 进度与完成由 ResidentEntity#tickReload 统一推进，这里只管别打断
            return;
        }

        if (this.rangedCooldown > 0) {
            return;
        }
        // 3 格内还有怪就不拉弦（站立模式动不了，只能硬上）
        if (!stand && ResidentCombat.threatWithin(this.resident, LOAD_SAFE_RADIUS)) {
            return;
        }
        if (ResidentCombat.findAmmo(this.resident.inventory(),
                (ProjectileWeaponItem) weapon.getItem(), ResidentInventory.MAIN_HAND).isEmpty()) {
            return;
        }
        this.resident.startUsingItem(InteractionHand.MAIN_HAND);
    }

    /** 模组远程武器：没有专门的弹道 API 就直接射一支箭（至少能用）。 */
    private void simpleShotTick(ItemStack weapon, ProjectileWeaponItem weaponItem) {
        if (this.rangedCooldown > 0) {
            return;
        }
        if (ResidentCombat.findAmmo(this.resident.inventory(), weaponItem, ResidentInventory.MAIN_HAND).isEmpty()) {
            return;
        }
        if (!this.seesTarget) {
            return;
        }
        this.shootArrow(weapon, 1.0F);
        this.rangedCooldown = OTHER_RANGED_INTERVAL;
    }

    /**
     * 射箭：**朝目标点算弹道**（不是按身体朝向），所以箭一定飞向目标。
     *
     * <p>早期用 {@code shootFromRotation(getXRot, getYRot)} 时，生物的 {@code yRot} 是身体朝向，
     * 头和身体不同步 → 箭会朝身后/侧面飞。改成目标向量后就稳了。</p>
     */
    private void shootArrow(ItemStack weapon, float power) {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack ammo = ResidentCombat.findAmmo(this.resident.inventory(),
            (ProjectileWeaponItem) weapon.getItem(), ResidentInventory.MAIN_HAND);
        if (ammo.isEmpty()) {
            return;
        }
        Arrow arrow = new Arrow(EntityType.ARROW, level);
        arrow.setOwner(this.resident);
        arrow.setPos(this.resident.getX(), this.resident.getEyeY() - 0.1D, this.resident.getZ());
        double dx = this.target.getX() - this.resident.getX();
        double dz = this.target.getZ() - this.resident.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float velocity = power * 3.0F;
        // 瞄怪的身体中段，并按重力补下坠（箭每 tick 掉 0.05 格；箭有 0.99 阻尼，乘 1.3 修正）
        double flightTicks = horizontal / Math.max(0.1D, velocity);
        double drop = 0.5D * 0.05D * flightTicks * flightTicks * 1.3D;
        double dy = this.target.getY(this.target.getBbHeight() * 0.5D) - arrow.getY() + drop;
        // inaccuracy 从 1.0 收到 0.2：原来的散布会让箭从怪头上飞过去
        arrow.shoot(dx, dy, dz, velocity, 0.2F);
        if (power >= 1.0F) {
            arrow.setCritArrow(true);
        }
        level.addFreshEntity(arrow);
        ammo.shrink(1);
        this.lastAttackTick = this.resident.tickCount;
        ResidentCombat.wearMainHand(this.resident, 1);
        level.playSound(null, this.resident.getX(), this.resident.getY(), this.resident.getZ(),
            SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1.0F,
            1.0F / (this.resident.getRandom().nextFloat() * 0.4F + 1.2F) + power * 0.5F);
    }

    /**
     * 远程时的走位：**保持距离 + 永远不把自己的视线走没**（v0.1.23）。
     *
     * <p>教训：旧版一有空就横移，横移到墙边 → 墙挡住它与尸壳的视线 → 射不出去 →
     * 8 秒后被判"追不动"直接放弃。现在：</p>
     * <ul>
     *   <li>有视线：3 格内后退、12 格外前压、中间横移（原版骷髅的走位节奏），
     *       但**每一步都先用射线确认"走这步之后还看得见目标"**，会挡住视线就换边、再不行就原地站定射；</li>
     *   <li>没视线：不硬站，去找能射的位置（{@link ResidentCombat#findShootingSpot}）；
     *       实在找不到就朝目标走，靠原版寻路绕墙。</li>
     * </ul>
     */
    private void rangedMovement(double distance, boolean stand, boolean hasMelee) {
        if (stand) {
            this.resident.getNavigation().stop();
            this.clearShootingSpot();
            return;
        }
        if (hasMelee && distance > CHASE_RANGE * 0.9D) {
            // 有近战武器只是"太远够不着"，走过去（同样节流，别每 tick 重算寻路）
            if (this.resident.getNavigation().isDone() && --this.retryMoveCooldown <= 0) {
                this.retryMoveCooldown = 10;
                this.resident.getNavigation().moveTo(this.target, 1.15D);
            }
            return;
        }

        // 没有视线：先找一个射得到的位置，而不是原地站着干等
        if (!this.seesTarget) {
            this.approachShootingSpot();
            return;
        }
        this.clearShootingSpot();

        this.resident.getNavigation().stop();
        this.lockFacing();
        if (--this.strafeTicks <= 0) {
            this.strafeTicks = 20;
            if (this.resident.getRandom().nextFloat() < 0.3F) {
                this.strafeClockwise = !this.strafeClockwise;
            }
        }

        // 5 tick 内复用同一次"这一步还射得到吗"的判定，别每 tick 三次 raycast
        if (--this.moveCheckTicks <= 0) {
            this.moveCheckTicks = 5;
            // **走位只在射击冷却期间/装填时发生**（v0.1.25）：
            // 主人要求"保持距离只在攻击冷却期间"，能射就站定射，别一直后退。
            boolean cooling = this.rangedCooldown > 0 || this.isReloading();
            float forward = 0.0F;
            if (distance > KEEP_FAR) {
                forward = 0.5F;                       // 太远：无论冷却都往前压（靠近，不是后退）
            } else if (cooling && distance < KEEP_AWAY) {
                forward = -0.6F;                      // 冷却期 + 贴太近：后退一点
            }
            float side = cooling ? (this.strafeClockwise ? 0.3F : -0.3F) : 0.0F;
            if (!this.moveKeepsSight(forward, side)) {
                side = -side;                              // 这一边走会挡视线：反着绕
                if (!this.moveKeepsSight(forward, side)) {
                    side = 0.0F;                           // 两边都不行：只走前后
                    if (!this.moveKeepsSight(forward, side)) {
                        forward = 0.0F;                    // 连前后都不行：原地站定射
                    }
                }
            }
            this.goodForward = forward;
            this.goodSide = side;
        }

        // 注意：MoveControl 的 STRAFE 会一直沿用上一次的分量，所以 0/0 也要显式打一次才真的站住
        this.resident.getMoveControl().strafe(this.goodForward, this.goodSide);
    }

    /**
     * 走一步（{@code forward}/{@code side} 是相对身体朝向的分量）之后还看得见目标吗？
     *
     * <p>两个条件：这一步不会撞墙（碰撞箱不重合），且从新位置的眼高到目标身体中段的射线通畅。
     * 这是"游移把自己的视线走没"的正面修复。</p>
     */
    private boolean moveKeepsSight(float forward, float side) {
        if (this.target == null) {
            return false;
        }
        double yaw = Math.toRadians(this.resident.getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        double step = 0.4D;
        double dx = (forwardX * forward + rightX * side) * step;
        double dz = (forwardZ * forward + rightZ * side) * step;
        net.minecraft.world.level.Level level = this.resident.level();
        net.minecraft.world.phys.AABB moved = this.resident.getBoundingBox().move(dx, 0.0D, dz);
        if (!level.noCollision(this.resident, moved)) {
            return false;
        }
        net.minecraft.world.phys.Vec3 eye = this.resident.getEyePosition().add(dx, 0.0D, dz);
        return ResidentCombat.canSeeFrom(level, this.resident, eye, ResidentCombat.aimPoint(this.target));
    }

    /**
     * 视线被挡时：走向最近的"能射到目标"的位置；没有就朝目标走（原版骷髅那样靠寻路绕墙）。
     *
     * <p>注意这里**不 {@link #lockFacing()}** —— 走向射击位时朝向要交给寻路/移动控制，
     * 身体正对目标会让它朝着目标直线撞墙。</p>
     */
    private void approachShootingSpot() {
        if (this.shootingSpot != null && this.resident.tickCount < this.shootingSpotUntil) {
            net.minecraft.world.phys.Vec3 at = net.minecraft.world.phys.Vec3.atBottomCenterOf(this.shootingSpot);
            if (this.resident.distanceToSqr(at) > 1.0D) {
                if (this.resident.getNavigation().isDone()) {
                    this.resident.getNavigation().moveTo(at.x, at.y, at.z, 1.15D);
                }
                this.resident.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
                return;
            }
            // 已经站到射击位上却还是看不见（怪走开了 / 点选错了）：这个点作废，重找
        }
        this.shootingSpot = null;

        if (--this.spotCooldown <= 0) {
            this.spotCooldown = 8 + this.resident.getRandom().nextInt(8);
            net.minecraft.core.BlockPos spot = ResidentCombat.findShootingSpot(this.resident, this.target);
            if (spot != null) {
                this.shootingSpot = spot;
                this.shootingSpotUntil = this.resident.tickCount + 120;
                this.resident.getNavigation().moveTo(
                    spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 1.15D);
                this.resident.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
                return;
            }
        }

        if (this.resident.getNavigation().isDone() && --this.retryMoveCooldown <= 0) {
            this.retryMoveCooldown = 10;
            this.resident.getNavigation().moveTo(this.target, 1.15D);
        }
        this.resident.getLookControl().setLookAt(this.target, 30.0F, 30.0F);
    }

    private void clearShootingSpot() {
        this.shootingSpot = null;
        this.shootingSpotUntil = 0;
    }

    /** 是不是"正在给弩上弦"（主手弩、未装填、使用中）。 */
    private boolean isReloading() {
        ItemStack main = this.resident.getMainHandItem();
        return this.resident.isUsingItem()
            && main.getItem() instanceof CrossbowItem
            && !CrossbowItem.isCharged(main);
    }

    /** 只停"远程武器的使用状态"，别把副手的举盾（也是 isUsingItem）一起停了。 */
    private void stopRanged() {
        if (this.resident.isUsingItem()
                && this.resident.getUseItem().getItem() instanceof ProjectileWeaponItem) {
            this.resident.stopUsingItem();
        }
    }

    private double reach() {
        return this.resident.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
    }
}
