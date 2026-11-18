package com.worldplayers.resident;

import com.worldplayers.registry.WPItems;
import com.worldplayers.resident.ai.ResidentPathNavigation;
import com.worldplayers.resident.ai.ResidentPickupGoal;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 居民（World Players 的 NPC）。
 *
 * <p>一个"表现无限接近于玩家的生物"：玩家模型、玩家皮肤池、玩家式背包/装备槽、玩家式经验修补，
 * 拾取优先级低于玩家。物品栏见 {@link ResidentInventory}。</p>
 */
public class ResidentEntity extends PathfinderMob {
    /** 物品落地 0.5 秒后才允许被捡（其余交给 ResidentPickupGoal）。 */
    public static final int PICKUP_DELAY_TICKS = 10;

    public static final int MODEL_WIDE = 1;
    public static final int MODEL_SLIM = 2;
    public static final int MODEL_KID_WIDE = 3;
    public static final int MODEL_KID_SLIM = 4;

    /** 工作状态 id（文字由 {@link ResidentJobs} 统一登记，UI 不写死文案）。 */
    public static final String JOB_IDLE = ResidentJobs.IDLE;
    /** 跟随玩家：快步跟上、太远传送、替玩家打架。 */
    public static final String JOB_FOLLOW = ResidentJobs.FOLLOW;
    /** 站立：站在原地，但会攻击攻击范围内 / 射程内的敌人。 */
    public static final String JOB_STAND = ResidentJobs.STAND;

    private static final String TAG_INVENTORY = "Inventory";
    private static final String TAG_SKIN = "Skin";
    private static final String TAG_MODEL = "Model";
    private static final String TAG_EXPERIENCE = "Experience";
    private static final String TAG_HUNGER = "Hunger";
    private static final String TAG_STAMINA = "Stamina";
    private static final String TAG_MOOD = "Mood";
    private static final String TAG_JOB = "Job";
    /** 固定工作场所（现在只是预留：切回「闲逛」就解绑）。 */
    private static final String TAG_WORK_SITE = "WorkSite";

    /** 左撇子概率：10%。 */
    public static final float LEFT_HANDED_CHANCE = 0.10F;

    /** 拾取欲望模式：不同状态下"看到多远的东西会去捡"。 */
    public enum PickupDuty {
        /** 闲逛：只捡脚边（2.5 格）。 */
        IDLE,
        /** 跟随：不特意捡。 */
        FOLLOW,
        /** 战斗：附近还有仇人才先不捡。 */
        COMBAT,
        /** 工作：整个工作区域都算它的拾取范围。 */
        WORK
    }

    private static final EntityDataAccessor<Integer> DATA_DUTY =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<String> DATA_SKIN =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> DATA_MODEL =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_LEFT_HANDED =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.BOOLEAN);
    /** 饥饿值 0..100（目前不消耗，只作为可观测数值）。 */
    private static final EntityDataAccessor<Float> DATA_HUNGER =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.FLOAT);
    /** 体力值 0..100（同上）。 */
    private static final EntityDataAccessor<Float> DATA_STAMINA =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_MOOD =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.FLOAT);
    /** 当前在干什么（浮窗里的一行字）。 */
    private static final EntityDataAccessor<String> DATA_JOB =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);

    private final ResidentInventory inventory = new ResidentInventory(this);

    /** 正在翻他背包的玩家（服务端）。 */
    private final java.util.Set<java.util.UUID> viewers = new java.util.HashSet<>();

    private int experiencePoints;

    /** 玩家式的攻击充能：每 tick +1，挥击后清零（和主手武器的 ATTACK_SPEED 挂钩）。 */
    private int attackStrengthTicker;

    /** 被斧头破盾后的冷却（tick）：这段时间里不举盾，和玩家被破盾的 5 秒一样。 */
    private int shieldDisabledTicks;

    /**
     * 这一次伤害是被盾牌挡下来的（记录发生时的 {@link #tickCount}）。
     *
     * <p>原版 {@code LivingEntity#hurt} 在格挡成功之后**照样**会推 0.4 格、照样播受击音；
     * 主人要求"挡住了就是挡住了"，所以在 {@link #knockback} / {@link #playHurtSound} 里按这个标记静音。</p>
     */
    private int shieldBlockedAt = -1000;

    /** 固定工作场所（预留）：一旦绑定，来回切「跟随/站立」不会解绑，只有切回「闲逛」才解除。 */
    @javax.annotation.Nullable
    private net.minecraft.core.BlockPos workSite;

    public ResidentEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false);
        this.xpReward = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.setDropChance(slot, 0.0F);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.ARMOR, 0.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.28D)
            .add(Attributes.FOLLOW_RANGE, 32.0D)
            .add(Attributes.ATTACK_DAMAGE, 1.0D)
            .add(Attributes.STEP_HEIGHT, 0.6D)
            // 玩家的触及范围属性是 player.entity_interaction_range，生物默认没有；必须自己注册，
            // 否则 getAttributeValue 会抛 "Can't find attribute"（v0.1.15 崩溃的根因）。
            .add(Attributes.ENTITY_INTERACTION_RANGE, 3.0D)
            // 攻击冷却同理：生物默认没有 ATTACK_SPEED，但武器的攻击速度修饰符是挂在它上面的，
            // 不注册的话拿剑/斧也就没有"真实冷却"可言（v0.1.20）。
            .add(Attributes.ATTACK_SPEED, 4.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN, "");
        builder.define(DATA_MODEL, MODEL_WIDE);
        builder.define(DATA_LEFT_HANDED, false);
        builder.define(DATA_DUTY, PickupDuty.IDLE.ordinal());
        builder.define(DATA_HUNGER, 100.0F);
        builder.define(DATA_STAMINA, 100.0F);
        builder.define(DATA_MOOD, 100.0F);
        builder.define(DATA_JOB, JOB_IDLE);
    }

    @Override
    protected void registerGoals() {
        // 视线完全交回原版：看玩家 + 随机环顾（和普通生物一样，天然平滑）
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 战斗中优先级最高：一个 goal 统管近战/远程切换（6 格切近战、远了用弓弩）
        this.goalSelector.addGoal(1, new com.worldplayers.resident.ai.ResidentCombatGoal(this));
        // 跟随模式：狼狗那种跟法，离太远直接传送到身边的安全方块
        this.goalSelector.addGoal(3, new com.worldplayers.resident.ai.ResidentFollowGoal(this));
        this.goalSelector.addGoal(4, new ResidentPickupGoal(this));
        this.goalSelector.addGoal(5, new com.worldplayers.resident.ai.ResidentStrollGoal(this));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    /** 最近的非旁观玩家（跟随/战斗都靠它找人）。 */
    @javax.annotation.Nullable
    public Player nearestPlayer(double range) {
        if (!(this.level() instanceof ServerLevel level)) {
            return null;
        }
        Player best = null;
        double bestDistance = range * range;
        for (Player player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            double distance = this.distanceToSqr(player);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    /** 用主手打人（伤害 / 附魔 / 触及距离全走原版属性）。 */
    public boolean attackWithMainHand(net.minecraft.world.entity.Entity target) {
        return this.doHurtTarget(target);
    }

    /** 被人翻背包时不许自己乱看（要盯着那个人）。 */
    public boolean canLookAround() {
        return this.viewers.isEmpty();
    }

    /**
     * 挥击动画。
     *
     * <p>原版里 {@code attackAnim}（手臂挥动的进度）只在 {@code Monster#aiStep} 和
     * {@code Player#aiStep} 里通过 {@code updateSwingTime()} 推进；{@code PathfinderMob} 没有这一步，
     * 所以居民之前"会砍人但手不动"。手动补上。</p>
     */
    @Override
    public void aiStep() {
        this.updateSwingTime();
        // 攻击冷却和玩家同款：每 tick +1，打到人后清零
        if (this.attackStrengthTicker < 1000) {
            this.attackStrengthTicker++;
        }
        if (this.level() instanceof ServerLevel) {
            this.tickShield();
        }
        super.aiStep();
    }

    /**
     * 战斗举盾：副手有盾、手上没拿着弓弩、有战斗目标时把盾举起来。
     *
     * <p>举盾走原版 `startUsingItem(OFF_HAND)`，于是原版的格挡减伤、耐久消耗、附魔、被斧破盾全都自动生效；
     * 破盾后 {@link #shieldDisabledTicks} 让居民歇 5 秒（和玩家一样）。</p>
     */
    private void tickShield() {
        if (this.shieldDisabledTicks > 0) {
            this.shieldDisabledTicks--;
        }
        boolean shieldInOffhand = this.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem;
        boolean rangedInHand = this.getMainHandItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem;
        boolean want = shieldInOffhand && !rangedInHand && this.getTarget() != null
            && this.shieldDisabledTicks <= 0 && this.isAlive() && !this.isDeadOrDying();
        if (want) {
            if (!this.isUsingItem()) {
                this.startUsingItem(InteractionHand.OFF_HAND);
            }
        } else if (this.isUsingItem()
                && this.getUseItem().getItem() instanceof net.minecraft.world.item.ShieldItem) {
            this.stopUsingItem();
        }
    }

    /**
     * 盾牌格挡判定：原版是"攻击者在视线前方 180°"，主人要 **正面 270°**（左右各 135°）。
     *
     * <p>只放宽方向；伤害减免、盾耐久、附魔、斧头破盾全部继续走原版 {@code LivingEntity#hurt}。</p>
     */
    @Override
    public boolean isDamageSourceBlocked(DamageSource source) {
        net.minecraft.world.entity.Entity direct = source.getDirectEntity();
        boolean piercing = direct instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow
            && arrow.getPierceLevel() > 0;
        if (source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_SHIELD) || !this.isBlocking() || piercing) {
            return false;
        }
        net.minecraft.world.phys.Vec3 sourcePos = source.getSourcePosition();
        if (sourcePos == null) {
            return false;
        }
        net.minecraft.world.phys.Vec3 toAttacker = sourcePos.vectorTo(this.position()).normalize();
        toAttacker = new net.minecraft.world.phys.Vec3(toAttacker.x, 0.0D, toAttacker.z);
        // 原版：dot < 0（背后不挡）。放宽到 135°：dot < cos(45°)
        return toAttacker.dot(this.getViewVector(1.0F)) < 0.7071D;
    }

    /**
     * 被斧头破盾。
     *
     * <p>原版这个逻辑写在 {@code Player#blockedByShield} 里（玩家有盾牌冷却）；生物没有玩家的冷却系统，
     * 所以这里自己补：记 100 tick（5 秒）不举盾 + 播破盾音。</p>
     */
    @Override
    public void blockUsingShield(net.minecraft.world.entity.LivingEntity attacker) {
        super.blockUsingShield(attacker);
        if (this.isBlocking()
                && attacker.getMainHandItem().getItem()
                    instanceof net.minecraft.world.item.AxeItem) {
            this.shieldDisabledTicks = 100;
            this.stopUsingItem();
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.SHIELD_BREAK, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
    }

    /**
     * 盾牌耐久。
     *
     * <p>根因：原版 {@code LivingEntity#hurtCurrentlyUsedShield} 是**空实现**，
     * 只有 {@code Player} 重写了它 —— 所以任何生物举盾都永远不掉耐久（居民之前就是这样）。</p>
     *
     * <p>这里照抄 {@code Player#hurtCurrentlyUsedShield}：伤害 ≥3 才磨损 1+floor(damage) 点，
     * 护具附魔/经验修补照常生效；盾被打爆就收起来并播破盾音（和玩家一模一样）。</p>
     */
    @Override
    protected void hurtCurrentlyUsedShield(float amount) {
        if (!this.useItem.is(net.minecraft.world.item.Items.SHIELD) || amount < 3.0F) {
            return;
        }
        int wear = 1 + net.minecraft.util.Mth.floor(amount);
        InteractionHand hand = this.getUsedItemHand();
        this.useItem.hurtAndBreak(wear, this, getSlotForHand(hand));
        if (this.useItem.isEmpty()) {
            if (hand == InteractionHand.MAIN_HAND) {
                this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            } else {
                this.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            }
            this.useItem = ItemStack.EMPTY;
            this.playSound(SoundEvents.SHIELD_BREAK, 0.8F,
                0.8F + this.level().random.nextFloat() * 0.4F);
        }
    }

    /**
     * 格挡成功的这一下不播受击声。
     *
     * <p>原版流程：{@code hurt} 里格挡后 amount 归零并 {@code broadcastEntityEvent(29)}（盾牌音，客户端播），
     * 但紧接着的 {@code playHurtSound} 照样执行 → 玩家听起来是"受击声"，不像挡住了。</p>
     */
    @Override
    protected void playHurtSound(DamageSource source) {
        if (this.shieldBlockedAt == this.tickCount) {
            return;
        }
        super.playHurtSound(source);
    }

    /** 距离下一次"满力挥击"还需要几 tick（武器攻速越高越短）。 */
    public double attackStrengthDelay() {
        double speed = this.getAttributeValue(Attributes.ATTACK_SPEED);
        return 20.0D / Math.max(0.1D, speed);
    }

    /** 是不是已经"充能满"可以挥了（玩家那种白条回满的感觉）。 */
    public boolean hasFullAttackStrength() {
        return this.attackStrengthTicker >= Math.ceil(this.attackStrengthDelay()) - 1.0D;
    }

    /** 打到人（或挥空）之后清零，重新开始充能。 */
    public void resetAttackStrengthTicker() {
        this.attackStrengthTicker = 0;
    }

    /**
     * 有人正在翻他的背包：原地不动、不干活、头转向那个人。
     *
     * <p>用原版 {@code setNoAi} 暂停 AI；这里只冻结"行为"，不动任何计时器 ——
     * 以后加行动日程时，日程要照常计时。</p>
     */
    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel serverLevel) {
            if (!this.viewers.isEmpty()) {
                this.getNavigation().stop();
                this.setDeltaMovement(Vec3.ZERO);
                Player viewer = this.firstViewer();
                if (viewer != null) {
                    this.getLookControl().setLookAt(viewer, 40.0F, 40.0F);
                }
                this.getLookControl().tick();
            }
            if (this.tickCount % 20 == 0) {
                this.pinSkin(serverLevel);
                // 和玩家一致：和平难度 + 自然回血规则开启时，每 20 tick 回 1 点血
                if (serverLevel.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL
                        && serverLevel.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION)
                        && this.getHealth() < this.getMaxHealth()) {
                    this.heal(1.0F);
                }
            }
            if (this.tickCount % 10 == 0 && this.viewers.isEmpty()) {
                this.absorbNearbyExperience(serverLevel);
            }
            if (this.viewers.isEmpty() && !this.isDeadOrDying()) {
                // 弩：上弦完成判定 + 空闲时永远优先上弦（每 tick 判一次，很便宜）
                this.tickReload();
                // 脚下的东西任何时候都捡（战斗中/跟随中也捡，只是不主动走过去）
                if (this.tickCount % 5 == 0) {
                    this.pickUpAtFeet(serverLevel);
                }
                // 定期整理装备：背包里有更好的就换上
                if (this.tickCount % 20 == 0) {
                    this.tidyEquipment();
                }
            }
        }
    }

    /**
     * 弩的装填。
     *
     * <p>正在上弦且计时到了 → 收尾（挂箭 + 完成音）；**不管有没有敌人都先把弦上完**。</p>
     * <p>没有仇恨时：只要背包里有弩和箭就永远优先上弦。上完弦后主手保持弩
     * （以后接工作系统时，这里要把主手换回工作要用的工具/物品）。</p>
     */
    private void tickReload() {
        ItemStack main = this.getItemBySlot(EquipmentSlot.MAINHAND);
        boolean reloading = this.isUsingItem()
            && main.getItem() instanceof net.minecraft.world.item.CrossbowItem
            && !net.minecraft.world.item.CrossbowItem.isCharged(main);
        if (reloading) {
            if (this.getTicksUsingItem() >= net.minecraft.world.item.CrossbowItem.getChargeDuration(main, this)) {
                this.stopUsingItem();
                com.worldplayers.resident.ai.ResidentCombat.finishCrossbowLoad(this, main);
            }
            return;
        }
        // 空闲（没有仇恨、手里也没在干别的）才主动上弦
        if (this.getTarget() != null || this.isUsingItem() || this.tickCount % 10 != 0) {
            return;
        }
        // 已经上好弦的弩 = 普通物品：不换、不重复上弦、不播音效
        if (main.getItem() instanceof net.minecraft.world.item.CrossbowItem
                && net.minecraft.world.item.CrossbowItem.isCharged(main)) {
            return;
        }
        if (main.getItem() instanceof net.minecraft.world.item.CrossbowItem
                && !net.minecraft.world.item.CrossbowItem.isCharged(main)) {
            if (com.worldplayers.resident.ai.ResidentCombat.hasAmmo(this.inventory,
                    (net.minecraft.world.item.ProjectileWeaponItem) main.getItem(), ResidentInventory.MAIN_HAND)) {
                this.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
            }
            return;
        }
        int crossbowSlot = com.worldplayers.resident.ai.ResidentCombat.findCrossbowSlot(this.inventory);
        if (crossbowSlot >= 0) {
            com.worldplayers.resident.ai.ResidentCombat.equipSlot(this, crossbowSlot);
            this.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    /**
     * 脚边的东西：不管在战斗、赶路还是干活，够得着就捡。
     * （"主动走过去捡"仍然由 {@code ResidentPickupGoal} 管，只在闲逛时用）
     */
    private void pickUpAtFeet(ServerLevel level) {
        // 死都死了就别捡了：死亡掉落的那一瞬间捡回来会导致"东西消失"（v0.1.20 修）
        if (this.isDeadOrDying() || this.isRemoved()) {
            return;
        }
        for (net.minecraft.world.entity.item.ItemEntity item : level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                this.getBoundingBox().inflate(1.8D),
                e -> e.isAlive() && e.getAge() >= PICKUP_DELAY_TICKS && this.inventory.canAccept(e.getItem()))) {
            this.tryPickUp(item);
        }
    }

    /** 从背包里挑最好的装备穿上 / 换掉；主手空着（且没在打架）就顺手拿把武器。 */
    private void tidyEquipment() {
        this.inventory.equipBestGear();
        if (this.getTarget() == null && this.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty()) {
            com.worldplayers.resident.ai.ResidentCombat.equipMeleeWeapon(this);
        }
    }

    /**
     * 站立模式：钉在原地——不被击退、不被推开。
     * 盾牌挡下的那一下也不该被推（原版格挡后仍会推 0.4 格，主人要求改成"挡住就是挡住"）。
     * （原版 {@code Entity#knockback} / {@code push} 是「我被别人撞/打飞」的入口）
     */
    @Override
    public void knockback(double strength, double x, double z) {
        if (this.shieldBlockedAt == this.tickCount) {
            return;
        }
        if (JOB_STAND.equals(this.jobKey())) {
            return;
        }
        super.knockback(strength, x, z);
    }

    @Override
    public void push(double x, double y, double z) {
        if (JOB_STAND.equals(this.jobKey())) {
            return;
        }
        super.push(x, y, z);
    }

    @Override
    public boolean isPushable() {
        return !JOB_STAND.equals(this.jobKey());
    }

    @Nullable
    private Player firstViewer() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        for (java.util.UUID id : this.viewers) {
            if (serverLevel.getPlayerByUUID(id) instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    /** 菜单打开时登记（服务端）：停止移动与工作，原地站好。 */
    public void startViewing(Player player) {
        this.viewers.add(player.getUUID());
        this.getNavigation().stop();
        this.setDeltaMovement(Vec3.ZERO);
        this.setNoAi(true);
    }

    /** 菜单关闭时注销（服务端）：没有人再看的话恢复 AI。 */
    public void stopViewing(Player player) {
        this.viewers.remove(player.getUUID());
        if (this.viewers.isEmpty()) {
            // 工作状态不动：跟随/以后的工作不会被"关背包"顺手改掉
            this.setNoAi(false);
        }
    }

    /**
     * 盔甲耐久：照抄玩家，让护甲真的会磨损（LivingEntity 默认是空实现，Mob 不磨装备）。
     */
    @Override
    protected void hurtArmor(DamageSource source, float amount) {
        this.doHurtEquipment(source, amount,
            new EquipmentSlot[]{EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD});
    }

    /**
     * 受伤事件 2：显式把 hurtTime 设上。
     *
     * <p>原版受击闪红看的就是 {@code hurtTime > 0}（overlay 红色遮罩），
     * 但同步偶尔会漏，这里兜底保证客户端一定拿到。</p>
     */
    @Override
    public void handleEntityEvent(byte id) {
        if (id == 2) {
            this.hurtDuration = 10;
            this.hurtTime = this.hurtDuration;
            this.hurtMarked = true;
        }
        super.handleEntityEvent(id);
    }

    /**
     * 受伤：先看会不会被不死图腾救回来；救回来了就不算受伤。
     * 否则照常受伤，并把正在看背包的玩家踢出界面、手里抓着的东西丢在他面前。
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        // 用居民手册敲居民：在「闲逛 → 跟随 → 站立 → 闲逛」之间循环，且不造成任何伤害。
        if (!this.level().isClientSide()
                && source.getEntity() instanceof Player attacker
                && (attacker.getMainHandItem().is(WPItems.RESIDENT_MANUAL.get())
                    || attacker.getOffhandItem().is(WPItems.RESIDENT_MANUAL.get()))) {
            String next = switch (this.jobKey()) {
                case JOB_FOLLOW -> JOB_STAND;
                case JOB_STAND -> JOB_IDLE;
                default -> JOB_FOLLOW;
            };
            this.setJobKey(next);
            return false;
        }

        // 盾牌格挡：**独立处理，绝不进下面的伤害流程**（v0.1.24，见 blockWithShield 的说明）
        if (!this.level().isClientSide() && amount > 0.0F && this.isDamageSourceBlocked(source)) {
            return this.blockWithShield(source, amount);
        }

        if (!this.level().isClientSide()
                && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)
                && amount > 0.0F
                && this.getHealth() - amount <= 0.0F) {
            ItemStack totem = this.takeTotem();
            if (!totem.isEmpty()) {
                this.setHealth(1.0F);
                // 和玩家一样清掉身上的负面效果
                for (var instance : new java.util.ArrayList<>(this.getActiveEffects())) {
                    if (!instance.getEffect().value().isBeneficial()) {
                        this.removeEffect(instance.getEffect());
                    }
                }
                this.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.REGENERATION, 900, 1));
                this.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.ABSORPTION, 100, 1));
                this.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE, 800, 0));
                if (this.level() instanceof ServerLevel serverLevel) {
                    // 和玩家一样：广播 35 号事件让客户端放不死图腾的动画/粒子
                    serverLevel.broadcastEntityEvent(this, (byte) 35);
                }
                return false;
            }
        }

        boolean damaged = super.hurt(source, amount);
        if (damaged && this.level() instanceof ServerLevel serverLevel) {
            // 显式广播受伤事件，保证客户端一定拿到 hurtTime（原版偶尔会漏）
            serverLevel.broadcastEntityEvent(this, (byte) 2);
            for (ServerPlayer player : serverLevel.getServer().getPlayerList().getPlayers()) {
                if (!(player.containerMenu instanceof com.worldplayers.menu.ResidentMenu menu)
                        || menu.resident() != this) {
                    continue;
                }
                ItemStack carried = menu.getCarried();
                if (!carried.isEmpty()) {
                    ItemStack thrown = carried.copy();
                    menu.setCarried(ItemStack.EMPTY);
                    player.drop(thrown, true);
                }
                player.closeContainer();
            }
            this.viewers.clear();
            this.setNoAi(false);
            // 被玩家（近战或远程，source.getEntity() 都是开火的人）打中时，在聊天室发一条演示文本
            this.broadcastHitTest(serverLevel, source);
            // 被环境（岩浆块、火、仙人掌、甜浆果…）烫到：立刻找个安全地方挪过去
            if (source.getEntity() == null) {
                this.fleeToSafety(serverLevel);
            }
        }
        // 以后加"逃离危险"时：若伤害来源是自己殖民地的主持人/管理员，不进入逃离状态。
        return damaged;
    }

    /**
     * 盾牌格挡：**把"格挡"从受击流水线里彻底拆出来**（v0.1.24）。
     *
     * <p>原版 {@code LivingEntity#hurt} 的格挡分支把格挡和伤害结算写在同一段里，
     * 格挡成功（amount 归零）之后还会继续往下跑：</p>
     * <ul>
     *   <li>{@code invulnerableTime > 10}（刚被别的怪打过 / 被同一只怪连击，还在无敌帧里）时
     *       走 {@code amount <= lastHurt → return false} 提前返回 —— 盾牌音（29 号实体事件）、
     *       受击提示、破盾判定**全都不广播**，看着就是"挡住了却照样掉血 / 没声音"；</li>
     *   <li>客户端能听到盾牌音也依赖这段的 {@code flag1}，没走到就只剩受击声。</li>
     * </ul>
     *
     * <p>所以这里自己把格挡做完整：磨盾（耐久照玩家规则）→ 斧头破盾 → 广播盾牌音 →
     * 给 20 tick 无敌帧 + 闪红，然后**直接返回 false**（和玩家格挡成功一样：不掉血、不被击退、
     * 不触发攻击者的后续效果，所以"第一下破盾、第二下才砍死"的节奏和玩家一致）。</p>
     */
    private boolean blockWithShield(DamageSource source, float amount) {
        this.shieldBlockedAt = this.tickCount;
        // 1) 磨盾：伤害 ≥3 才掉耐久，盾被打爆时自己会播破盾音
        this.hurtCurrentlyUsedShield(amount);
        // 2) 斧头破盾（内部播 SHIELD_BREAK + 100 tick 不举盾）
        net.minecraft.world.entity.Entity direct = source.getDirectEntity();
        if (!source.is(net.minecraft.tags.DamageTypeTags.IS_PROJECTILE)
                && direct instanceof net.minecraft.world.entity.LivingEntity living) {
            this.blockUsingShield(living);
        }
        // 3) 照原版记仇恨来源（不然挨打了也不知道是谁）
        if (source.getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker) {
            this.setLastHurtByMob(attacker);
        }
        this.noActionTime = 0;
        // 4) 无敌帧 + 闪红：玩家的格挡节奏就是这个，第二下要等无敌帧过去才会掉血
        this.invulnerableTime = 20;
        this.hurtDuration = 10;
        this.hurtTime = this.hurtDuration;
        // 5) 盾牌音：**直接在服务端播**（v0.1.25 修）
        //    之前广播 29 号实体事件让客户端播，实测没有声音；而破盾音走的是服务端
        //    `level.playSound(...)`，一直正常 —— 那就用这条确定有效的通道。
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
            SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 1.0F,
            0.8F + this.level().random.nextFloat() * 0.4F);
        return false;
    }

    /** 在 2~6 格内找一个能站人、且脚下/脚下方块都不危险的位置，走（或立刻传）过去。 */
    private void fleeToSafety(ServerLevel level) {        net.minecraft.core.BlockPos origin = this.blockPosition();
        for (int radius = 2; radius <= 6; radius++) {
            for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
                    origin.offset(-radius, -1, -radius), origin.offset(radius, 1, radius))) {
                if (!isSafeStand(level, pos)) {
                    continue;
                }
                if (this.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 1.0D)) {
                    return;
                }
            }
        }
    }

    /** 这一格能不能安全站人（脚下有实体方块、本人与头顶两格都空、上下都不是危险方块）。 */
    public static boolean isSafeStand(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState below = level.getBlockState(pos.below());
        return level.getBlockState(pos).isEmpty()
            && level.getBlockState(pos.above()).isEmpty()
            && below.blocksMotion()
            && !com.worldplayers.resident.ai.ResidentNodeEvaluator.isDangerous(below)
            && !com.worldplayers.resident.ai.ResidentNodeEvaluator.isDangerous(level.getBlockState(pos));
    }

    /** 居民像玩家一样发一条聊天：<名字> 受击测试（文本由客户端按语言翻译）。 */
    private void broadcastHitTest(ServerLevel level, DamageSource source) {
        if (!(source.getEntity() instanceof Player)) {
            return;
        }
        // 统一走 ResidentChat：自动加名字 + 聊天头像标记，以后加对白也只用它
        ResidentChat.say(level, this, Component.translatable("chat.worldplayers.hit_test"));
    }

    /** 死亡时像玩家一样在聊天栏报一条死亡信息：名字 + 死因全由原版死亡消息提供。 */
    @Override
    public void die(DamageSource source) {
        if (this.level() instanceof ServerLevel serverLevel) {
            // getLocalizedDeathMessage 会自动套原版 death.attack.* 文本（含凶手名字），不带聊天头像
            ResidentChat.announce(serverLevel, source.getLocalizedDeathMessage(this));
        }
        super.die(source);
    }

    /** 从物品栏（含装备槽）里拿走一个不死图腾；没有就返回空。 */
    private ItemStack takeTotem() {
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            ItemStack stack = this.inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) {
                ItemStack one = stack.copyWithCount(1);
                this.inventory.removeItem(slot, 1);
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 生成的一瞬间就把默认皮肤抽好，并决定是不是左撇子，而不是等几秒。 */
    @Override
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(
            net.minecraft.world.level.ServerLevelAccessor level,
            net.minecraft.world.DifficultyInstance difficulty,
            net.minecraft.world.entity.MobSpawnType spawnType,
            @Nullable net.minecraft.world.entity.SpawnGroupData spawnGroupData) {
        if (this.skinName().isEmpty()) {
            ResidentSkins.pickRandom(this.random).ifPresent(this::setSkinName);
        }
        if (this.random.nextFloat() < LEFT_HANDED_CHANCE) {
            this.setLeftHanded(true);
        }
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
    }

    public boolean isLeftHanded() {
        return this.entityData.get(DATA_LEFT_HANDED);
    }

    public PickupDuty pickupDuty() {
        int ordinal = this.entityData.get(DATA_DUTY);
        PickupDuty[] values = PickupDuty.values();
        return values[Math.max(0, Math.min(values.length - 1, ordinal))];
    }

    public void setPickupDuty(PickupDuty duty) {
        this.entityData.set(DATA_DUTY, duty.ordinal());
    }

    /** 当前状态下"多远的东西才会去捡"。 */
    public double pickupRadius() {
        return switch (this.pickupDuty()) {
            case FOLLOW -> 0.0D;
            case WORK -> 16.0D;   // 工作区域（等真正的工作系统接进来后改成区域半径）
            default -> 2.5D;
        };
    }

    /** 这件掉在地上的东西现在值得去捡吗？ */
    public boolean canPickupNow(ItemEntity item) {
        double radius = this.pickupRadius();
        if (radius <= 0.0D) {
            return false;
        }
        return this.distanceToSqr(item) <= radius * radius;
    }

    public void setLeftHanded(boolean leftHanded) {
        this.entityData.set(DATA_LEFT_HANDED, leftHanded);
    }

    /** 主手在哪只手上：左撇子会把主手物品画到左手。 */
    @Override
    public net.minecraft.world.entity.HumanoidArm getMainArm() {
        return this.isLeftHanded()
            ? net.minecraft.world.entity.HumanoidArm.LEFT
            : net.minecraft.world.entity.HumanoidArm.RIGHT;
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new ResidentPathNavigation(this, level);
    }

    // ------------------------------------------------------------------
    // 皮肤 / 模型
    // ------------------------------------------------------------------

    public String skinName() {
        return this.entityData.get(DATA_SKIN);
    }

    public void setSkinName(String name) {
        this.entityData.set(DATA_SKIN, name);
    }

    /** 0 = 自动（按皮肤图判断 slim/wide），1 = 强制 wide，2 = 强制 slim。 */
    public int modelType() {
        return this.entityData.get(DATA_MODEL);
    }

    public void setModelType(int model) {
        this.entityData.set(DATA_MODEL, Math.clamp(model, MODEL_WIDE, MODEL_KID_SLIM));
        this.refreshDimensions();
    }

    /** kid 模型：碰撞箱只有一格高。 */
    public boolean isKid() {
        return this.modelType() >= MODEL_KID_WIDE;
    }

    @Override
    protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        if (this.isKid()) {
            return net.minecraft.world.entity.EntityDimensions.fixed(0.55F, 1.0F);
        }
        return super.getDefaultDimensions(pose);
    }

    /** 模型类型由服务端同步过来：客户端也要刷新碰撞箱，否则放置方块时判定会错。 */
    @Override
    public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_MODEL.equals(accessor)) {
            this.refreshDimensions();
        }
    }

    // ---- 可观测数值（浮窗 / Jade / GUI 用） ----

    public float hunger() {
        return this.entityData.get(DATA_HUNGER);
    }

    public void setHunger(float value) {
        this.entityData.set(DATA_HUNGER, Math.clamp(value, 0.0F, 100.0F));
    }

    public float stamina() {
        return this.entityData.get(DATA_STAMINA);
    }

    public void setStamina(float value) {
        this.entityData.set(DATA_STAMINA, Math.clamp(value, 0.0F, 100.0F));
    }

    /** 心情值 0..100（背包 / Jade 显示百分比，浮窗显示一张表情）。目前没有增减逻辑，默认满分。 */
    public float mood() {
        return this.entityData.get(DATA_MOOD);
    }

    public void setMood(float mood) {
        this.entityData.set(DATA_MOOD, Math.clamp(mood, 0.0F, 100.0F));
    }

    /** 当前工作（idle 闲逛 / follow 跟随 / stand 站立）。 */
    public String jobKey() {
        return this.entityData.get(DATA_JOB);
    }

    public void setJobKey(String job) {
        // 从「跟随 / 站立」切回「闲逛」= 解除工作场所绑定（跟随/站立本身不解绑）
        if (JOB_IDLE.equals(job)) {
            this.workSite = null;
        }
        this.entityData.set(DATA_JOB, job);
    }

    /** 固定工作场所（现在只是预留，还没有工作系统去绑定它）。 */
    @javax.annotation.Nullable
    public net.minecraft.core.BlockPos workSite() {
        return this.workSite;
    }

    public void setWorkSite(@javax.annotation.Nullable net.minecraft.core.BlockPos pos) {
        this.workSite = pos;
    }

    private void pinSkin(ServerLevel level) {
        if (!this.skinName().isEmpty()) {
            return;
        }
        ResidentSkins.pickRandom(this.random).ifPresent(this::setSkinName);
    }

    // ------------------------------------------------------------------
    // 物品栏
    // ------------------------------------------------------------------

    public ResidentInventory inventory() {
        return this.inventory;
    }

    /** 容器内容变化时的钩子（GUI 同步/存档脏标记将来挂这里）。 */
    public void inventoryChanged() {
        // 目前什么都不用做：装备槽与背包各自有权威来源
    }

    /**
     * 把 ItemEntity 塞进背包。只有真正拿走东西才返回 true。
     */
    public boolean tryPickUp(ItemEntity itemEntity) {
        // 死亡过程中不许再捡（掉落物会被自己捡回去然后实体消失 → 东西彻底没了）
        if (this.isDeadOrDying() || this.isRemoved()) {
            return false;
        }
        ItemStack offered = itemEntity.getItem();
        if (offered.isEmpty()) {
            return false;
        }
        int before = offered.getCount();
        ItemStack rest = this.inventory.addItem(offered);
        if (rest.getCount() == before) {
            return false;
        }
        if (rest.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setItem(rest);
        }
        // 捡完继续看那个位置一小会儿由视线行为段落自己管，这里不再干预
        this.level().playSound(null, this.getX(), this.getY(0.5D), this.getZ(),
            SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
            ((this.random.nextFloat() - this.random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
        // 捡起来顺手比一比：背包里有更好的装备就直接换上身
        this.inventory.equipBestGear();
        return true;
    }

    // ------------------------------------------------------------------
    // 经验 / 经验修补
    // ------------------------------------------------------------------

    public int experiencePoints() {
        return this.experiencePoints;
    }

    public void giveExperience(int amount) {
        if (amount <= 0) {
            return;
        }
        int left = this.repairMending(amount);
        this.experiencePoints += left;
    }

    private int repairMending(int experience) {
        int budget = experience;
        for (ItemStack stack : this.itemsWithMending()) {
            if (budget <= 0) {
                break;
            }
            if (!stack.isDamaged()) {
                continue;
            }
            int repairPerPoint = 2;
            int need = (stack.getMaxDamage() - stack.getDamageValue() + repairPerPoint - 1) / repairPerPoint;
            int used = Math.min(need, budget);
            stack.setDamageValue(Math.max(0, stack.getDamageValue() - used * repairPerPoint));
            budget -= used;
        }
        return budget;
    }

    private List<ItemStack> itemsWithMending() {
        List<ItemStack> stacks = new ArrayList<>();
        Holder<net.minecraft.world.item.enchantment.Enchantment> mending = this.mendingHolder();
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            ItemStack stack = this.inventory.getItem(slot);
            if (stack.isEmpty() || !stack.isDamaged()) {
                continue;
            }
            if (EnchantmentHelper.getItemEnchantmentLevel(mending, stack) > 0) {
                stacks.add(stack);
            }
        }
        return stacks;
    }

    private Holder<net.minecraft.world.item.enchantment.Enchantment> mendingHolder() {
        return this.level().registryAccess()
            .lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
            .getOrThrow(Enchantments.MENDING);
    }

    /**
     * 经验球吸收：和玩家同一套规则，没有等待期与优先级判定，附近就直接吸。
     */
    private void absorbNearbyExperience(ServerLevel level) {
        for (ExperienceOrb orb : level.getEntitiesOfClass(ExperienceOrb.class,
                this.getBoundingBox().inflate(2.5D), orb -> orb.isAlive())) {
            this.giveExperience(orb.getValue());
            orb.discard();
        }
    }

    // ------------------------------------------------------------------
    // 交互（右键打开背包 GUI）
    // ------------------------------------------------------------------

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (this.level().isClientSide()) {
            return InteractionResult.sidedSuccess(true);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(
                new SimpleMenuProvider(
                    (containerId, playerInventory, openingPlayer) ->
                        new com.worldplayers.menu.ResidentMenu(containerId, playerInventory, this),
                    this.getDisplayName()),
                buffer -> buffer.writeVarInt(this.getId()));
        }
        return InteractionResult.sidedSuccess(false);
    }

    public void setDisplayName(String name) {
        if (name == null || name.isBlank()) {
            this.setCustomName(null);
            this.setCustomNameVisible(false);
        } else {
            this.setCustomName(Component.literal(name));
            this.setCustomNameVisible(true);
        }
    }

    // ------------------------------------------------------------------
    // 存读档
    // ------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        // 冻结是"临时的 GUI 状态"，不要写进存档（否则重进世界后居民永远不动）
        boolean frozen = !this.viewers.isEmpty();
        if (frozen) {
            this.setNoAi(false);
        }
        super.addAdditionalSaveData(tag);
        if (frozen) {
            this.setNoAi(true);
        }
        NonNullList<ItemStack> list = NonNullList.withSize(ResidentInventory.BACKPACK_START + 27, ItemStack.EMPTY);
        for (int slot = 0; slot < list.size(); slot++) {
            list.set(slot, this.inventory.getItem(slot));
        }
        ContainerHelper.saveAllItems(tag, list, this.registryAccess());
        tag.putString(TAG_SKIN, this.skinName());
        tag.putInt(TAG_MODEL, this.modelType());
        tag.putInt(TAG_EXPERIENCE, this.experiencePoints);
        tag.putFloat(TAG_HUNGER, this.hunger());
        tag.putFloat(TAG_STAMINA, this.stamina());
        tag.putFloat(TAG_MOOD, this.mood());
        tag.putString(TAG_JOB, this.jobKey());
        if (this.workSite != null) {
            tag.putLong(TAG_WORK_SITE, this.workSite.asLong());
        }
        tag.putBoolean("LeftHanded", this.isLeftHanded());
        tag.putInt("Duty", this.pickupDuty().ordinal());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(TAG_INVENTORY)) {
            NonNullList<ItemStack> list =
                NonNullList.withSize(ResidentInventory.BACKPACK_START + 27, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag, list, this.registryAccess());
            for (int slot = 0; slot < list.size(); slot++) {
                this.inventory.setItem(slot, list.get(slot));
            }
        }
        this.setSkinName(tag.getString(TAG_SKIN));
        this.setModelType(tag.contains(TAG_MODEL) ? tag.getInt(TAG_MODEL) : MODEL_WIDE);
        this.experiencePoints = tag.getInt(TAG_EXPERIENCE);
        if (tag.contains(TAG_HUNGER)) {
            this.setHunger(tag.getFloat(TAG_HUNGER));
        }
        if (tag.contains(TAG_STAMINA)) {
            this.setStamina(tag.getFloat(TAG_STAMINA));
        }
        if (tag.contains(TAG_MOOD, net.minecraft.nbt.Tag.TAG_FLOAT)) {
            // 旧存档里 Mood 是字符串状态（content/happy/…），类型不对就直接用默认值
            this.setMood(tag.getFloat(TAG_MOOD));
        }
        if (tag.contains(TAG_JOB)) {
            this.setJobKey(tag.getString(TAG_JOB));
        }
        this.workSite = tag.contains(TAG_WORK_SITE)
            ? net.minecraft.core.BlockPos.of(tag.getLong(TAG_WORK_SITE))
            : null;
        this.setLeftHanded(tag.getBoolean("LeftHanded"));
        int duty = tag.getInt("Duty");
        PickupDuty[] duties = PickupDuty.values();
        this.setPickupDuty(duties[Math.max(0, Math.min(duties.length - 1, duty))]);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        this.inventory.dropAll(level);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    /** 名字牌交给 {@code ResidentNameplate} 处理（空名字不显示，拿手册时换成状态卡片）。 */
    @Override
    public boolean shouldShowName() {
        return false; // 名字牌完全由 ResidentNameplate 自绘
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }
}
