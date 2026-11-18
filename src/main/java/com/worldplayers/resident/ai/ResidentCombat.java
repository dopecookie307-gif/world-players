package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentInventory;
import java.util.function.Predicate;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.phys.AABB;

/**
 * 居民战斗的公共逻辑：找目标、判中立、挑武器、剑类横扫、视线判断。
 *
 * <p>伤害/附魔/额外触及距离全部走原版属性 —— 把武器放进主手后，
 * {@code getAttributeValue(ATTACK_DAMAGE)} 自动包含基础伤害与锋利等附魔。</p>
 */
public final class ResidentCombat {
    /** 原版玩家的横扫伤害系数（{@code Attributes.SWEEP_DAMAGE_RATIO} 默认 0.5）。 */
    private static final float SWEEP_RATIO = 0.5F;

    /** 主动巡逻半径：进入这个范围**且看得见**的敌对怪物一律优先击杀。 */
    private static final double AGGRESSIVE_RANGE = 8.0D;
    /** 帮忙半径：32 格内的居民会来支援"正在挨打的居民/玩家"。 */
    private static final double ASSIST_RANGE = 32.0D;


    /**
     * 中立生物白名单（数据包可扩）：默认装的是原版"被惹才动手"的那些。
     * 模组中立生物除了自己实现 {@link NeutralMob}，也可以往这个 tag 里加 id。
     */
    public static final TagKey<EntityType<?>> NEUTRAL_TAG =
        TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("worldplayers", "neutral"));
    /** 绝对不打名单（数据包可扩）：铁傀儡以及以后任何"居民永远不许还手"的生物。 */
    public static final TagKey<EntityType<?>> PROTECTED_TAG =
        TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.fromNamespaceAndPath("worldplayers", "protected"));

    /**
     * 中立 / 被动生物：居民**不主动**打它们，除非它们已经仇恨居民、仇恨附近玩家，或被玩家攻击。
     *
     * <p>原版末影人、僵尸猪灵、狼、蜜蜂、北极熊、羊驼这些都实现了 {@link NeutralMob}；
     * 另外再用 {@link #NEUTRAL_TAG} 留一个给模组和数据包的入口。</p>
     */
    public static boolean isNeutral(Entity entity) {
        if (entity instanceof NeutralMob) {
            return true;
        }
        EntityType<?> type = entity.getType();
        return type.is(NEUTRAL_TAG)
            // 兜底：tag 被数据包覆盖掉时也不至于去打末影人/僵尸猪灵
            || type == EntityType.ENDERMAN
            || type == EntityType.ZOMBIFIED_PIGLIN
            || type == EntityType.WOLF
            || type == EntityType.BEE
            || type == EntityType.POLAR_BEAR
            || type == EntityType.LLAMA
            || type == EntityType.TRADER_LLAMA;
    }

    /**
     * 绝对不打：铁傀儡不论野生还是玩家造的，居民都绝不还手；铁傀儡也不会把居民当目标
     * （{@code ResidentHate} 里同一个判定）。
     */
    public static boolean isProtected(Entity entity) {
        return entity instanceof IronGolem
            || entity instanceof ResidentEntity
            || entity.getType().is(PROTECTED_TAG);
    }

    /**
     * 找该打的目标。**不再透视**（v0.1.20）：眼睛看不到的怪，除非它先动手，否则不仇恨。
     *
     * <ul>
     *   <li>8 格内的敌对怪物（{@link Enemy}）——**必须在直接视野内**才主动击杀；</li>
     *   <li>正在攻击任何居民（含自己）的生物 —— 32 格内的居民都会过来帮忙；</li>
     *   <li>正在攻击附近玩家 / 被附近玩家打过的生物 —— 32 格内的居民都会过来帮忙；</li>
     *   <li>中立生物只在"被惹了"之后才算目标；</li>
     *   <li>居民之间永远互不仇恨；铁傀儡永远不打。</li>
     * </ul>
     *
     * <p>也就是说：未攻击任何居民 + 未攻击玩家 + 未被玩家攻击 + 看不见 → 不仇恨。</p>
     */
    public static LivingEntity findTarget(ResidentEntity resident) {
        Player owner = resident.nearestPlayer(ASSIST_RANGE);
        AABB box = resident.getBoundingBox().inflate(ASSIST_RANGE);
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Mob mob : resident.level().getEntitiesOfClass(Mob.class, box, m -> m != resident && m.isAlive())) {
            if (isProtected(mob) || !resident.canAttack(mob)) {
                continue;
            }
            double distance = resident.distanceToSqr(mob);
            boolean attackingResident = mob.getTarget() == resident
                || mob.getTarget() instanceof ResidentEntity
                || mob.getLastHurtByMob() instanceof ResidentEntity;
            boolean attackingOwner = owner != null
                && (mob.getTarget() == owner || mob.getLastHurtByMob() == owner);
            boolean playerAttackedIt = owner != null && mob.getLastHurtByMob() == owner;
            boolean helping = attackingResident || attackingOwner || playerAttackedIt;

            boolean wanted;
            if (mob instanceof Enemy) {
                // 主动清怪：8 格内**且看得见**；帮忙则不限视线
                wanted = helping || (distance <= AGGRESSIVE_RANGE * AGGRESSIVE_RANGE
                    && hasLineOfSight(resident, mob));
            } else {
                wanted = helping;
            }
            if (!wanted) {
                continue;
            }
            // **谁最近先打谁**（主人要求：以离自己最近的怪物判断，别死咬尸群最后方那只）
            if (distance < bestScore) {
                bestScore = distance;
                best = mob;
            }
        }
        return best;
    }

    /**
     * 半径内的威胁清单，按距离由近到远。
     *
     * <p>"威胁"= 敌对怪物（{@link Enemy}）、已经仇恨居民/附近玩家的、被玩家打过的中立生物。
     * 用来判断"被几个怪同时围住了"，好让居民先脱离包围圈。</p>
     */
    public static java.util.List<Mob> threatsWithin(ResidentEntity resident, double radius) {
        Player owner = resident.nearestPlayer(64.0D);
        AABB box = resident.getBoundingBox().inflate(radius);
        java.util.List<Mob> found = new java.util.ArrayList<>();
        for (Mob mob : resident.level().getEntitiesOfClass(Mob.class, box, m -> m != resident && m.isAlive())) {
            if (isProtected(mob) || !resident.canAttack(mob)) {
                continue;
            }
            boolean hostile = mob instanceof Enemy
                || mob.getTarget() == resident
                || mob.getTarget() instanceof ResidentEntity
                || mob.getLastHurtByMob() == resident
                || (owner != null && (mob.getTarget() == owner || mob.getLastHurtByMob() == owner));
            if (hostile) {
                found.add(mob);
            }
        }
        found.sort(java.util.Comparator.comparingDouble(resident::distanceToSqr));
        return found;
    }

    /** 居民脚下 3 格内有没有"会打人的东西"（上弩弦前先看这个）。 */
    public static boolean threatWithin(ResidentEntity resident, double radius) {
        AABB box = resident.getBoundingBox().inflate(radius);
        for (Mob mob : resident.level().getEntitiesOfClass(Mob.class, box, m -> m != resident && m.isAlive())) {
            if (isProtected(mob)) {
                continue;
            }
            if (mob instanceof Enemy || mob.getTarget() == resident) {
                return true;
            }
        }
        return false;
    }

    /** 视线：隔着墙不许打（带原版可见性缓存，够用且便宜）。 */
    public static boolean hasLineOfSight(ResidentEntity resident, LivingEntity target) {
        return resident.getSensing().hasLineOfSight(target);
    }

    // ------------------------------------------------------------------
    // 远程：找"射得到目标"的位置
    // ------------------------------------------------------------------

    /** 射箭/弩时要瞄的点：目标身体中段（和 {@code ResidentCombatGoal#shootArrow} 一致）。 */
    public static net.minecraft.world.phys.Vec3 aimPoint(LivingEntity target) {
        return new net.minecraft.world.phys.Vec3(
            target.getX(), target.getY(target.getBbHeight() * 0.5D), target.getZ());
    }

    /** 从 {@code from} 到 {@code to} 之间有没有方块挡着（MISS = 通透）。 */
    public static boolean canSeeFrom(net.minecraft.world.level.Level level, Entity viewer,
                                     net.minecraft.world.phys.Vec3 from,
                                     net.minecraft.world.phys.Vec3 to) {
        return level.clip(new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, viewer))
            .getType() == net.minecraft.world.phys.HitResult.Type.MISS;
    }

    /**
     * 找一个"站得住、又射得到目标"的方块位置（远程居民视线被挡时用）。
     *
     * <p>思路和原版骷髅/掠夺者一样是"有视线才射"，但它们没有主动换位逻辑；
     * 这里补一层低成本的位置搜索：</p>
     * <ol>
     *   <li>先看是什么方块挡住了射线 —— 绕到它左/右各 2~3 格处试试（贴着障碍物绕）；</li>
     *   <li>再以自己为圆心环形采样（4.5 / 7.5 格各 8 个方向），应付厚墙和拐角；</li>
     *   <li>候选点必须：能站人、从那里的眼高能直视目标、到目标的水平距离在 3~14 格。</li>
     * </ol>
     *
     * <p>评分偏好"到目标 7 格左右、离自己近"的点 —— 就是弓/弩最舒服的射击距离。</p>
     *
     * @return 找不到返回 {@code null}（调用方就退回"朝目标走，靠寻路绕墙"）
     */
    public static net.minecraft.core.BlockPos findShootingSpot(ResidentEntity resident, LivingEntity target) {
        net.minecraft.world.level.Level level = resident.level();
        net.minecraft.world.phys.Vec3 eye = resident.getEyePosition();
        net.minecraft.world.phys.Vec3 aim = aimPoint(target);
        if (canSeeFrom(level, resident, eye, aim)) {
            return null;
        }

        net.minecraft.core.BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        // 1) 贴着挡视线的方块绕
        net.minecraft.world.phys.BlockHitResult hit = level.clip(
            new net.minecraft.world.level.ClipContext(eye, aim,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, resident));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            net.minecraft.core.BlockPos obstacle = hit.getBlockPos();
            double dx = aim.x - eye.x;
            double dz = aim.z - eye.z;
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 1.0E-4D) {
                double perpX = -dz / length;
                double perpZ = dx / length;
                for (int sign = -1; sign <= 1; sign += 2) {
                    for (int step = 2; step <= 3; step++) {
                        net.minecraft.core.BlockPos around = obstacle.offset(
                            (int) Math.round(perpX * step * sign), 0,
                            (int) Math.round(perpZ * step * sign));
                        Double score = scoreShootingSpot(level, resident, target, around);
                        if (score < bestScore) {
                            bestScore = score;
                            best = standableNear(level, around);
                        }
                    }
                }
            }
        }
        if (best != null) {
            return best;
        }

        // 2) 环形兜底
        for (int ring = 0; ring < 2; ring++) {
            double radius = ring == 0 ? 4.5D : 7.5D;
            for (int i = 0; i < 8; i++) {
                double angle = Math.PI * 2.0D * i / 8.0D;
                net.minecraft.core.BlockPos around = net.minecraft.core.BlockPos.containing(
                    resident.getX() + Math.cos(angle) * radius,
                    resident.getY(),
                    resident.getZ() + Math.sin(angle) * radius);
                Double score = scoreShootingSpot(level, resident, target, around);
                if (score < bestScore) {
                    bestScore = score;
                    best = standableNear(level, around);
                }
            }
        }
        return best;
    }

    /** 候选点打分；{@code Double.MAX_VALUE} = 这个点不能用。 */
    private static Double scoreShootingSpot(net.minecraft.world.level.Level level, ResidentEntity resident,
                                            LivingEntity target, net.minecraft.core.BlockPos around) {
        net.minecraft.core.BlockPos stand = standableNear(level, around);
        if (stand == null) {
            return Double.MAX_VALUE;
        }
        net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(
            stand.getX() + 0.5D, stand.getY() + resident.getEyeHeight(), stand.getZ() + 0.5D);
        net.minecraft.world.phys.Vec3 aim = aimPoint(target);
        double horizontal = Math.sqrt((aim.x - eye.x) * (aim.x - eye.x) + (aim.z - eye.z) * (aim.z - eye.z));
        if (horizontal < 3.0D || horizontal > 14.0D) {
            return Double.MAX_VALUE;
        }
        if (!canSeeFrom(level, resident, eye, aim)) {
            return Double.MAX_VALUE;
        }
        double away = Math.sqrt(resident.distanceToSqr(eye.x, eye.y, eye.z));
        return Math.abs(horizontal - 7.0D) + away * 0.4D;
    }

    /** 在给定位置上下 2 格内找一个安全可站的格子。 */
    private static net.minecraft.core.BlockPos standableNear(net.minecraft.world.level.Level level,
                                                              net.minecraft.core.BlockPos around) {
        for (int dy = 0; dy >= -2; dy--) {
            net.minecraft.core.BlockPos pos = around.offset(0, dy, 0);
            if (com.worldplayers.resident.ResidentEntity.isSafeStand(level, pos)) {
                return pos;
            }
        }
        for (int dy = 1; dy <= 2; dy++) {
            net.minecraft.core.BlockPos pos = around.offset(0, dy, 0);
            if (com.worldplayers.resident.ResidentEntity.isSafeStand(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 武器
    // ------------------------------------------------------------------

    /**
     * 近战：**优先剑，其次攻击力最高的其它物品**（斧头伤害更高也只当没有剑时才用）。
     * 远程武器（弓/弩）不算近战。
     */
    public static int bestMeleeSlot(ResidentInventory inventory) {
        int bestSword = -1;
        double bestSwordDamage = -1.0D;
        int bestOther = -1;
        double bestOtherDamage = -1.0D;
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            if (!ResidentInventory.isStorageSlot(slot)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.getItem() instanceof ProjectileWeaponItem) {
                continue;
            }
            double damage = attackDamage(stack);
            if (damage <= 0.0D) {
                continue;
            }
            if (stack.is(ItemTags.SWORDS)) {
                if (damage > bestSwordDamage) {
                    bestSwordDamage = damage;
                    bestSword = slot;
                }
            }
            if (damage > bestOtherDamage) {
                bestOtherDamage = damage;
                bestOther = slot;
            }
        }
        return bestSword >= 0 ? bestSword : bestOther;
    }

    public static boolean hasMeleeWeapon(ResidentInventory inventory) {
        return bestMeleeSlot(inventory) >= 0;
    }

    /**
     * 远程武器槽：**弓优先**，然后弩，最后才是其它模组远程武器；
     * 而且必须背包里有匹配的弹药才算数。
     */
    public static int bestRangedSlot(ResidentInventory inventory) {
        int bow = -1;
        int crossbow = -1;
        int other = -1;
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            if (!ResidentInventory.isStorageSlot(slot)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof ProjectileWeaponItem weapon)) {
                continue;
            }
            if (!hasAmmo(inventory, weapon, slot)) {
                continue;
            }
            if (stack.getItem() instanceof BowItem) {
                if (bow < 0) {
                    bow = slot;
                }
            } else if (stack.getItem() instanceof CrossbowItem) {
                if (crossbow < 0) {
                    crossbow = slot;
                }
            } else if (other < 0) {
                other = slot;
            }
        }
        if (bow >= 0) {
            return bow;
        }
        return crossbow >= 0 ? crossbow : other;
    }

    /**
     * 背包里第一把**空弩（还没上弦）且背包有箭**的槽位（空闲装填用，跟弓谁优先无关）。
     *
     * <p>已经上弦的弩不算候选 —— 主人要求"把已上弦的弩视为普通物品"，
     * 不然居民会反复把它换到主手、再播一遍上弦音效。</p>
     */
    public static int findCrossbowSlot(ResidentInventory inventory) {
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            if (!ResidentInventory.isStorageSlot(slot)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !(stack.getItem() instanceof ProjectileWeaponItem weapon)
                    || !(stack.getItem() instanceof CrossbowItem)) {
                continue;
            }
            if (CrossbowItem.isCharged(stack)) {
                continue;
            }
            if (hasAmmo(inventory, weapon, slot)) {
                return slot;
            }
        }
        return -1;
    }

    /** 上弦完成：往弩里挂一支箭 + 播上弦完成音。成功返回 true。 */
    public static boolean finishCrossbowLoad(ResidentEntity resident, ItemStack weapon) {
        if (!(weapon.getItem() instanceof ProjectileWeaponItem weaponItem)) {
            return false;
        }
        ItemStack ammo = findAmmo(resident.inventory(), weaponItem, ResidentInventory.MAIN_HAND);
        if (ammo.isEmpty()) {
            return false;
        }
        weapon.set(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.of(ammo.copyWithCount(1)));
        ammo.shrink(1);
        resident.level().playSound(null, resident.getX(), resident.getY(), resident.getZ(),
            SoundEvents.CROSSBOW_LOADING_END, SoundSource.NEUTRAL, 1.0F, 1.0F);
        return true;
    }

    /** 主手武器掉耐久（和玩家一样：1 次攻击/1 发箭扣 1 点，走原版附魔与保护）。 */
    public static void wearMainHand(ResidentEntity resident, int amount) {
        ItemStack main = resident.getMainHandItem();
        if (!main.isEmpty() && main.isDamageableItem()) {
            main.hurtAndBreak(amount, resident, EquipmentSlot.MAINHAND);
        }
    }

    /**
     * 战斗时把盾牌换到副手（非战斗时不主动碰它，当普通物品）。
     *
     * <p>副手已经有盾就不动；盾牌走 {@link ItemStack#hurtAndBreak} 所以耐久附魔照常生效。</p>
     */
    public static void equipShield(ResidentEntity resident) {
        ResidentInventory inventory = resident.inventory();
        if (inventory.getItem(ResidentInventory.OFFHAND).getItem() instanceof ShieldItem) {
            return;
        }
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            if (slot == ResidentInventory.OFFHAND || !ResidentInventory.isStorageSlot(slot)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (!(stack.getItem() instanceof ShieldItem)) {
                continue;
            }
            ItemStack previous = inventory.getItem(ResidentInventory.OFFHAND);
            inventory.setItem(ResidentInventory.OFFHAND, stack);
            inventory.setItem(slot, previous);
            return;
        }
    }

    /** 背包里第一份能配这把武器用的弹药（跳过武器自己所在的槽）。 */
    public static ItemStack findAmmo(ResidentInventory inventory, ProjectileWeaponItem weapon, int weaponSlot) {
        Predicate<ItemStack> supported = weapon.getAllSupportedProjectiles();
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            if (slot == weaponSlot || !ResidentInventory.isStorageSlot(slot)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && supported.test(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public static boolean hasAmmo(ResidentInventory inventory, ProjectileWeaponItem weapon, int weaponSlot) {
        return !findAmmo(inventory, weapon, weaponSlot).isEmpty();
    }

    /**
     * 把某个槽位的物品换到主手（原主手的东西放回那个槽）。
     * 槽位 0 表示已经在主手，直接返回。
     */
    public static void equipSlot(ResidentEntity resident, int slot) {
        if (slot <= 0) {
            return;
        }
        ResidentInventory inventory = resident.inventory();
        ItemStack chosen = inventory.getItem(slot);
        if (chosen.isEmpty()) {
            return;
        }
        ItemStack previous = inventory.getItem(ResidentInventory.MAIN_HAND);
        inventory.setItem(ResidentInventory.MAIN_HAND, chosen);
        inventory.setItem(slot, previous);
    }

    /** 把近战武器换到主手（已经拿着近战武器就不动）。 */
    public static void equipMeleeWeapon(ResidentEntity resident) {
        ItemStack main = resident.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!main.isEmpty() && !(main.getItem() instanceof ProjectileWeaponItem)
                && attackDamage(main) > 0.0D) {
            return;
        }
        equipSlot(resident, bestMeleeSlot(resident.inventory()));
    }

    /** 物品在主手时的攻击伤害（含附魔词条）。 */
    public static double attackDamage(ItemStack stack) {
        double damage = 0.0D;
        for (var entry : stack.getAttributeModifiers().modifiers()) {
            if (entry.attribute().value() == Attributes.ATTACK_DAMAGE.value()) {
                damage += entry.modifier().amount();
            }
        }
        return damage;
    }

    /** 剑类：触发横扫。 */
    public static boolean isSword(ItemStack stack) {
        return stack.is(ItemTags.SWORDS);
    }

    // ------------------------------------------------------------------
    // 打击效果
    // ------------------------------------------------------------------

    /** 近战命中音效（原版玩家打人的 strong / weak 两档）。 */
    public static void playMeleeHitSound(ResidentEntity resident) {
        float pitch = 1.0F / (resident.getRandom().nextFloat() * 0.4F + 0.8F);
        boolean strong = attackDamage(resident.getMainHandItem()) >= 5.0D
            && resident.getRandom().nextFloat() < 0.7F;
        resident.level().playSound(null, resident.getX(), resident.getY(), resident.getZ(),
            strong ? SoundEvents.PLAYER_ATTACK_STRONG : SoundEvents.PLAYER_ATTACK_WEAK,
            SoundSource.PLAYERS, 1.0F, pitch);
    }

    /** 横扫：对目标周围的其他生物造成一半伤害并击退（和玩家一样，带音效和弧形粒子）。 */
    public static void sweep(ResidentEntity resident, LivingEntity mainTarget) {
        float damage = (float) (resident.getAttributeValue(Attributes.ATTACK_DAMAGE) * SWEEP_RATIO);
        AABB box = mainTarget.getBoundingBox().inflate(1.0D, 0.25D, 1.0D);
        for (LivingEntity other : resident.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != resident && e != mainTarget && e.isAlive() && e instanceof Mob)) {
            if (isProtected(other) || !resident.canAttack(other)) {
                continue;
            }
            other.hurt(resident.damageSources().mobAttack(resident), damage);
            double dx = other.getX() - resident.getX();
            double dz = other.getZ() - resident.getZ();
            other.knockback(0.4D, -dx, -dz);
        }
        if (resident.level() instanceof ServerLevel serverLevel) {
            // 完全照原版 Player#sweepAttack / Player#attack：音效 + 身前那道弧
            serverLevel.playSound(null, resident.getX(), resident.getY(), resident.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 1.0F);
            double dx = -Mth.sin(resident.getYRot() * (float) (Math.PI / 180.0));
            double dz = Mth.cos(resident.getYRot() * (float) (Math.PI / 180.0));
            serverLevel.sendParticles(ParticleTypes.SWEEP_ATTACK,
                resident.getX() + dx, resident.getY(0.5D), resident.getZ() + dz,
                0, dx, 0.0D, dz, 0.0D);
        }
    }

    private ResidentCombat() {
    }
}

