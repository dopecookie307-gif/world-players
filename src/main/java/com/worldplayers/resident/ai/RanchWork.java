package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentChat;
import com.worldplayers.resident.ResidentChatPool;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentInventory;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.ranch.RanchCycle;
import com.worldplayers.resident.ranch.RanchType;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;

/**
 * 牧场工作（v0.3.0，主人 2026-11-23 定）：剪毛 → 繁殖 → 屠宰，循环记在区域上
 * （{@link RanchCycle}），这里只做**员工自己的动作**：走过去 / 剪毛 / 喂食 / 击杀。
 *
 * <p>规则要点：</p>
 * <ul>
 *   <li>剪毛：有毛的绵羊 + 背包有剪刀 → 剪光所有毛（产物直接进背包）；没剪刀 → 喊一句并跳过这轮；</li>
 *   <li>喂养：每 6 分钟判断数量 / 饲料；一次挥臂给区域内**所有未在繁殖冷却的成年动物**各喂一份，
 *       饲料拿在手上、触及 3 格；没饲料 → 喊一句并跳过这轮；</li>
 *   <li>屠宰：数量达上限才动手，只杀成年、优先已进入繁殖冷却的；**用主手武器真实攻击**
 *       （伤害含武器与附魔、扣耐久、触发物品打中效果、掉经验），但不经过战斗 goal →
 *       不触发剑横扫；牧场工作动物在 {@code ResidentCombat#isRanchWorkAnimal} 里也永远不算战斗目标。</li>
 * </ul>
 */
final class RanchWork {
    /** 喂食 / 屠宰的触及距离：3 格（主人定）。 */
    private static final double REACH_SQR = 3.0D * 3.0D;
    /** 剪毛沿用工作区通用的 4.5 格操作范围。 */
    private static final double SHEAR_REACH_SQR = ResidentWorkGoal.RANGE * ResidentWorkGoal.RANGE;
    /** 走路多久还没到位就换目标（10 秒）。 */
    private static final int WALK_TIMEOUT = 200;
    /** 一击没死时的间隔（真实武器伤害下通常一两下就够）。 */
    private static final int SLAUGHTER_CD = 10;
    /** 收掉落物的搜索半径（刚生成、还没被玩家或别人捡走）。 */
    private static final double DROP_RADIUS = 2.5D;
    /** 社区通用的「刀」物品标签（NeoForge / Fabric 惯例）：屠宰优先用刀。 */
    private static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> KNIFE_TAG =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("c", "tools/knife"));

    private final ResidentWorkGoal owner;
    private final ResidentEntity resident;
    /** 正在剪毛的那只羊（只在剪毛阶段用；失效就重扫）。 */
    @Nullable
    private Animal target;
    /** 动作冷却。 */
    private int actionCd;
    /** 走向目标已经走了多久。 */
    private int walkTicks;
    /** 换武器的检查节流（1 秒）。 */
    private int weaponCheckCd;

    RanchWork(ResidentWorkGoal owner, ResidentEntity resident) {
        this.owner = owner;
        this.resident = resident;
    }

    /** 工作 goal 开始 / 结束：清掉临时目标（区域级循环不动，交给 {@link RanchCycle}）。 */
    void reset() {
        this.target = null;
        this.actionCd = 0;
        this.walkTicks = 0;
        this.weaponCheckCd = 0;
    }

    void tick(ServerLevel level, WorkZone zone) {
        long now = level.getGameTime();
        RanchCycle cycle = RanchCycle.get(zone);
        if (this.actionCd > 0) {
            this.actionCd--;
        }
        switch (cycle.poll(zone, now)) {
            case SHEAR -> this.tickShear(level, zone, cycle, now);
            case BREED -> this.tickBreed(level, zone, cycle, now);
            case BUTCHER -> this.tickButcher(level, zone, cycle, now);
        }
    }

    // ------------------------------------------------------------------
    // 剪毛
    // ------------------------------------------------------------------

    private void tickShear(ServerLevel level, WorkZone zone, RanchCycle cycle, long now) {
        RanchType type = zone.ranchType();
        // 不是羊 / 没勾剪毛：直接进繁殖
        if (!zone.animalShear() || !type.canShear()) {
            cycle.enterBreed(now);
            return;
        }
        // 每轮剪毛间隔 1 分钟：没到点就在区域里踱步
        if (!cycle.shearDue(now)) {
            this.owner.wander(level);
            return;
        }
        Sheep sheep = this.shearTarget(level, zone);
        if (sheep == null) {
            // 没有有毛的羊：这一轮剪毛到此为止
            cycle.enterBreed(now);
            return;
        }
        if (!this.hasShears()) {
            // 没剪刀：喊一句，跳过这一轮剪毛
            if (cycle.chatAllowed(now)) {
                ResidentChat.say(level, this.resident, ResidentChatPool.pick(
                    ResidentChatPool.Kind.NO_SHEARS, this.resident.getRandom()));
            }
            cycle.enterBreed(now);
            return;
        }
        if (!this.walkTo(level, zone, sheep, SHEAR_REACH_SQR) || this.actionCd > 0) {
            return;
        }
        this.shear(level, sheep);
    }

    /** 区域里最近的、有毛可剪的绵羊（目标还有效就继续用，省一次全扫）。 */
    @Nullable
    private Sheep shearTarget(ServerLevel level, WorkZone zone) {
        if (this.target instanceof Sheep sheep && sheep.isAlive() && sheep.readyForShearing()
                && zone.containsBody(sheep)) {
            return sheep;
        }
        Sheep best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Animal animal : this.animals(level, zone)) {
            if (animal instanceof Sheep sheep && sheep.readyForShearing()) {
                double distance = this.resident.distanceToSqr(sheep);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = sheep;
                }
            }
        }
        this.target = best;
        return best;
    }

    /** 剪一只羊：手持剪刀、挥手、羊毛直接进背包、剪刀 -1 耐久。 */
    private void shear(ServerLevel level, Sheep sheep) {
        if (!sheep.readyForShearing()) {
            this.target = null;
            return;
        }
        if (!this.resident.inventory().holdInMainHand(
                stack -> stack.getItem() instanceof ShearsItem)) {
            this.target = null;
            return;
        }
        ItemStack shears = this.resident.getMainHandItem();
        this.resident.swing(InteractionHand.MAIN_HAND, true);
        sheep.shear(SoundSource.PLAYERS);
        this.collectNewDrops(level, sheep.getBoundingBox().inflate(DROP_RADIUS));
        ResidentWorkGoal.hurtTool(this.resident, shears);
        this.resident.setStamina(this.resident.stamina()
            - ResidentWorkGoal.staminaCost(this.resident, ResidentWorkGoal.STAMINA_PER_LABOR));
        this.actionCd = ResidentWorkGoal.laborCooldown(this.resident);
        this.target = null;
    }

    /** 背包（主手 + 快捷栏 + 主背包）里有没有剪刀。 */
    private boolean hasShears() {
        for (int slot = 0; slot < com.worldplayers.resident.ResidentInventory.BACKPACK_START + 27; slot++) {
            if (this.resident.inventory().getItem(slot).getItem() instanceof ShearsItem) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 繁殖（喂养）
    // ------------------------------------------------------------------

    private void tickBreed(ServerLevel level, WorkZone zone, RanchCycle cycle, long now) {
        if (!cycle.breedDue(now)) {
            this.owner.wander(level);
            return;
        }
        List<Animal> animals = this.animals(level, zone);
        // 已经到最大数量：跳过喂养（也不翻背包），直接进屠宰
        if (animals.size() >= zone.animalMax()) {
            cycle.enterButcher(now);
            return;
        }
        Animal feedTarget = this.findFeedTarget(animals);
        if (feedTarget == null) {
            // 没有可喂的成年动物（都冷却中 / 只有幼崽）：这一轮算完成
            cycle.enterButcher(now);
            return;
        }
        // 原版繁殖要**两只**同时进入恋爱状态：能喂的成年动物不足两只时喂了也生不出，
        // 2026-11-23 主人报「只有一头成年动物却每隔几秒喂一次」→ 直接跳过这一轮。
        int breedable = 0;
        for (Animal animal : animals) {
            if (animal.isAlive() && !animal.isBaby() && !animal.isInLove()) {
                breedable++;
            }
        }
        if (breedable < 2) {
            cycle.enterButcher(now);
            return;
        }
        if (!this.hasFoodFor(feedTarget)) {
            if (cycle.chatAllowed(now)) {
                ResidentChat.say(level, this.resident, ResidentChatPool.pick(
                    ResidentChatPool.Kind.NO_FEED, this.resident.getRandom(),
                    zone.ranchType().displayName()));
            }
            cycle.enterButcher(now);
            return;
        }
        if (!this.walkTo(level, zone, feedTarget, REACH_SQR) || this.actionCd > 0) {
            return;
        }
        this.feedAll(level, zone, animals, cycle, now);
    }

    /**
     * 一次挥臂：给区域内**所有未处于繁殖冷却的成年动物**各喂一份饲料（饲料拿在手上）。
     * 饲料中途用光 → 喊一句（跳过剩下的），这一轮喂养结束。
     */
    private void feedAll(ServerLevel level, WorkZone zone, List<Animal> animals,
                         RanchCycle cycle, long now) {
        if (!this.resident.inventory().holdInMainHand(stack -> this.isFoodForAny(stack, animals))) {
            return;
        }
        ItemStack held = this.resident.getMainHandItem();
        if (held.isEmpty() || !this.isFoodForAny(held, animals)) {
            return;
        }
        this.resident.swing(InteractionHand.MAIN_HAND, true);
        boolean fedAny = false;
        boolean ranOut = false;
        for (Animal animal : animals) {
            if (!animal.isAlive() || animal.isBaby() || animal.isInLove()) {
                continue;
            }
            ItemStack food = this.foodFor(animal);
            if (food.isEmpty()) {
                ranOut = true;
                break;
            }
            food.shrink(1);
            // 原版喂食 = 进入 600 tick（30 秒）的繁殖冷却。以前这里写的是 setInLove(null)，
            // 那是**清除**恋爱状态 → 动物永远不冷却、每一轮都能再喂，玩家看到的就是「每隔几秒喂一次」。
            animal.setInLoveTime(600);
            level.playSound(null, animal.blockPosition(), SoundEvents.GENERIC_EAT,
                SoundSource.NEUTRAL, 1.0F, 1.0F);
            fedAny = true;
        }
        if (fedAny) {
            this.resident.setStamina(this.resident.stamina()
                - ResidentWorkGoal.staminaCost(this.resident, ResidentWorkGoal.STAMINA_PER_LABOR));
            this.actionCd = ResidentWorkGoal.laborCooldown(this.resident);
        }
        if (ranOut && cycle.chatAllowed(now)) {
            ResidentChat.say(level, this.resident, ResidentChatPool.pick(
                ResidentChatPool.Kind.NO_FEED, this.resident.getRandom(),
                zone.ranchType().displayName()));
        }
        cycle.enterButcher(now);
    }

    /** 最近的、能吃饲料的成年动物（未在繁殖冷却）。 */
    @Nullable
    private Animal findFeedTarget(List<Animal> animals) {
        Animal best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Animal animal : animals) {
            if (!animal.isAlive() || animal.isBaby() || animal.isInLove()) {
                continue;
            }
            if (!this.hasFoodFor(animal)) {
                continue;
            }
            double distance = this.resident.distanceToSqr(animal);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = animal;
            }
        }
        return best;
    }

    /** 背包里有没有这只动物能吃的饲料。 */
    private boolean hasFoodFor(Animal animal) {
        for (int slot = 0; slot < com.worldplayers.resident.ResidentInventory.BACKPACK_START + 27; slot++) {
            ItemStack stack = this.resident.inventory().getItem(slot);
            if (!stack.isEmpty() && animal.isFood(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 这件物品是不是这群动物里任意一种能吃的。 */
    private boolean isFoodForAny(ItemStack stack, List<Animal> animals) {
        if (stack.isEmpty()) {
            return false;
        }
        for (Animal animal : animals) {
            if (!animal.isAlive() || animal.isBaby() || animal.isInLove()) {
                continue;
            }
            if (animal.isFood(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 给这只动物取一份饲料（先看主手，不够再翻背包换到主手）；没有返回空栈。 */
    private ItemStack foodFor(Animal animal) {
        ItemStack main = this.resident.getMainHandItem();
        if (!main.isEmpty() && animal.isFood(main)) {
            return main;
        }
        if (this.resident.inventory().holdInMainHand(stack -> animal.isFood(stack))) {
            return this.resident.getMainHandItem();
        }
        return ItemStack.EMPTY;
    }

    // ------------------------------------------------------------------
    // 屠宰
    // ------------------------------------------------------------------

    private void tickButcher(ServerLevel level, WorkZone zone, RanchCycle cycle, long now) {
        List<Animal> animals = this.animals(level, zone);
        // 数量**不超过**上限就不杀（2026-11-23 主人：上限 4、实际 4 只时居民还在攻击）。
        // 以前写的是 `< max` → 正好等于上限时还会多杀一只（4 只杀到 3 只）。只有超编才屠宰。
        if (animals.size() <= zone.animalMax()) {
            cycle.enterShear(now);
            return;
        }
        if (this.weaponCheckCd-- <= 0) {
            // 屠宰前换好武器：优先 #c:tools/knife，其次攻击力最高（2026-11-23 主人要求）
            this.equipBestWeapon();
            this.weaponCheckCd = 20;
        }
        Animal victim = this.findVictim(animals);
        if (victim == null) {
            // 只剩幼崽 / 都不可杀：等它们长大或不再满足条件
            cycle.enterShear(now);
            return;
        }
        if (!this.walkTo(level, zone, victim, REACH_SQR) || this.actionCd > 0) {
            return;
        }
        // 多员工共用一把屠宰锁：同一时刻只让一个人动手（主人 2026-11-23：上限 4 只被杀到 3 只）
        if (!cycle.tryButcherLock(now)) {
            return;
        }
        this.slaughter(level, victim);
    }

    /**
     * 选屠宰目标：**先锁住上一次的目标**，还活着、还是成年、还在区域里就继续砍它
     * （2026-11-23 主人：以前每次重新挑最近的，动物一动就换目标 → 看着像随机乱砍，谁也砍不死）；
     * 目标丢了才重新挑：优先已进入繁殖冷却的成年动物，其次最近的成年动物。幼崽不杀。
     */
    @Nullable
    private Animal findVictim(List<Animal> animals) {
        Animal locked = this.target;
        if (locked != null && locked.isAlive() && !locked.isBaby() && animals.contains(locked)) {
            return locked;
        }
        BlockPos self = this.resident.blockPosition();
        Animal fallback = null;
        double fallbackDistance = Double.MAX_VALUE;
        Animal cooling = null;
        double coolingDistance = Double.MAX_VALUE;
        for (Animal animal : animals) {
            if (!animal.isAlive() || animal.isBaby()) {
                continue;
            }
            double distance = self.distSqr(animal.blockPosition());
            if (animal.isInLove()) {
                if (distance < coolingDistance) {
                    coolingDistance = distance;
                    cooling = animal;
                }
            } else if (distance < fallbackDistance) {
                fallbackDistance = distance;
                fallback = animal;
            }
        }
        Animal chosen = cooling != null ? cooling : fallback;
        this.target = chosen;
        return chosen;
    }

    /**
     * 普通击杀：**用主手武器真实攻击**（主人 2026-11-23 改回攻击）。
     *
     * <p>以前为了防「全殖民地增援 + 剑横扫」改用直接 {@code hurt} 且不扣耐久，现在那个坑已经在
     * {@code ResidentCombat#isRanchWorkAnimal}（牧场动物永远不算战斗目标 / 不援助 / 不被横扫）里堵住了，
     * 所以屠宰恢复成正常攻击：</p>
     * <ul>
     *   <li>伤害 = 居民攻击力（含主手武器加成）+ {@code EnchantmentHelper#modifyDamage}（锋利 / 亡灵杀手…）；</li>
     *   <li>主手挥动 + {@code weapon.hurtEnemy(target, resident)}：扣耐久，并触发物品自己实现的「打中生物」效果
     *       （很多模组武器用它做特殊行为）；</li>
     *   <li>攻击后效果（火焰附加等）走 {@code EnchantmentHelper#doPostAttackEffects}；</li>
     *   <li>击杀后照常掉经验（和玩家击杀同一个量），居民会自己吸走。</li>
     * </ul>
     *
     * <p>仍然**不经过战斗 goal**，所以不会触发剑的横扫。</p>
     */
    private void slaughter(ServerLevel level, Animal animal) {
        if (!animal.isAlive()) {
            this.target = null;
            return;
        }
        ItemStack weapon = this.resident.getMainHandItem();
        this.resident.swing(InteractionHand.MAIN_HAND, true);
        DamageSource source = level.damageSources().mobAttack(this.resident);
        float damage = (float) Math.max(1.0D, this.resident.getAttributeValue(Attributes.ATTACK_DAMAGE));
        damage = EnchantmentHelper.modifyDamage(level, weapon, animal, source, damage);
        boolean died = animal.hurt(source, damage);
        if (!weapon.isEmpty()) {
            // 1) 物品自己的「打中生物」钩子（模组武器 / 菜刀之类在这里做特殊行为）
            weapon.getItem().hurtEnemy(weapon, animal, this.resident);
            // 2) 扣耐久（工作 ×2 奖励时照已有规则减半）
            ResidentWorkGoal.hurtTool(this.resident, weapon);
            // 3) 攻击后效果（火焰附加等）
            EnchantmentHelper.doPostAttackEffects(level, animal, source);
        }
        this.collectNewDrops(level, animal.getBoundingBox().inflate(DROP_RADIUS));
        if (died) {
            this.awardExperience(level, animal);
            this.resident.setStamina(this.resident.stamina()
                - ResidentWorkGoal.staminaCost(this.resident, ResidentWorkGoal.STAMINA_PER_LABOR));
            this.actionCd = ResidentWorkGoal.laborCooldown(this.resident);
            this.target = null;
        } else {
            this.actionCd = SLAUGHTER_CD;
        }
    }

    /** 击杀经验（和玩家击杀同一个量；经验球落在动物位置，居民会自己吸）。 */
    private void awardExperience(ServerLevel level, Animal animal) {
        int xp = animal.getExperienceReward(level, this.resident);
        if (xp > 0) {
            net.minecraft.world.entity.ExperienceOrb.award(level, animal.position(), xp);
        }
    }

    // ------------------------------------------------------------------
    // 屠宰武器
    // ------------------------------------------------------------------

    /**
     * 屠宰武器优先级（主人 2026-11-23）：{@code #c:tools/knife} 的刀 > 其它攻击力更高的武器 > 现在手里的。
     *
     * <p>以前员工从不会自己换武器，背包里放着菜刀 / 剑也空着手去砍，特殊掉落自然吃不到。
     * 现在屠宰前扫一遍主手 + 快捷栏 + 主背包，挑得分最高的换到主手。</p>
     */
    private void equipBestWeapon() {
        ResidentInventory inventory = this.resident.inventory();
        ItemStack current = this.resident.getMainHandItem();
        int bestSlot = -1;
        double bestScore = weaponScore(current);
        for (int slot = 0; slot < ResidentInventory.BACKPACK_START + 27; slot++) {
            if (slot == ResidentInventory.MAIN_HAND) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            double score = weaponScore(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot < 0) {
            return;
        }
        ItemStack best = inventory.getItem(bestSlot);
        inventory.setItem(bestSlot, current);
        inventory.setItem(ResidentInventory.MAIN_HAND, best);
    }

    /** 屠宰得分：刀标签给一个压倒性的加权，其余按 ATTACK_DAMAGE 加值比。 */
    private static double weaponScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }
        double score = 0.0D;
        for (var entry : stack.getAttributeModifiers().modifiers()) {
            if (entry.attribute().value()
                    == net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE.value()) {
                score += entry.modifier().amount();
            }
        }
        if (stack.is(KNIFE_TAG)) {
            score += 1000.0D;
        }
        return score;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 区域里属于这个牧场的动物（含幼崽，数量判断用；**只算身体真在区域里的**）。 */
    private List<Animal> animals(ServerLevel level, WorkZone zone) {
        RanchType type = zone.ranchType();
        List<Animal> list = new ArrayList<>();
        // 2026-11-23 三修：以前用 zone.box()（浮点包围盒），贴着围栏站在区域**外面**的动物也会被算进来，
        // 员工就会一路走到栅栏外去追它（玩家看到的「工作期间打开栅栏门想出去」）。
        // 现在跟剪毛目标用同一套判定：用方块坐标算「在不在区域里」。
        for (Animal animal : level.getEntitiesOfClass(Animal.class, zone.box())) {
            if (type.isRanchAnimal(animal) && zone.containsBody(animal)) {
                list.add(animal);
            }
        }
        return list;
    }

    /**
     * 走向目标；到了返回 true（可以动手），还在走 / 放弃返回 false。
     *
     * <p>2026-11-23 三修：移动目标**必须落在工作区里**（动物挤在围栏外沿 / 区域边界上时，
     * 走到动物身上等于往区域外走）。这里取「区域内离动物最近的可站点」当导航终点。</p>
     */
    private boolean walkTo(ServerLevel level, WorkZone zone, Animal animal, double reachSqr) {
        if (this.resident.distanceToSqr(animal) <= reachSqr) {
            this.walkTicks = 0;
            return true;
        }
        if (++this.walkTicks > WALK_TIMEOUT) {
            // 走不过去（栅栏 / 卡住）：放弃，10 秒后再试
            this.target = null;
            this.walkTicks = 0;
            this.actionCd = 20;
            return false;
        }
        // 动物会动：每 20 tick 重新寻路到它当前的位置
        if (this.resident.getNavigation().isDone() || this.resident.tickCount % 20 == 0) {
            BlockPos spot = this.owner.findStandSpotNear(level, zone, animal.blockPosition());
            if (spot == null) {
                return false;
            }
            this.owner.moveTo(
                spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 0.9D);
        }
        return false;
    }

    /** 把这一击 / 这一剪之后**刚生成**的掉落塞进背包（剩下的留在地上）。 */
    private void collectNewDrops(ServerLevel level, AABB box) {
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (!item.isAlive() || item.tickCount > 1) {
                continue;
            }
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack rest = this.resident.inventory().addItem(stack);
            if (rest.isEmpty()) {
                item.discard();
            } else {
                item.setItem(rest);
            }
        }
    }
}
