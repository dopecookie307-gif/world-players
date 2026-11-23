package com.worldplayers.resident;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * 居民饥饿：消耗与补充（v0.2.11）。
 *
 * <p>数值沿用 UI 的 0..100 制，**100% = 玩家那样的 20 点饥饿值**（1 点 = 5%），
 * 所以显示时一律按 {@code 点数 = 百分比 / 5} 换算。</p>
 *
 * <p>消耗取 fw（{@code vanilla/person/living/Hunger.java}，MIT © IzakyL）的数值 <b>×1.5</b>：</p>
 * <ul>
 *   <li>走路：fw 0.0375/格（0.03 疲劳 × 换算）× 1.5 = <b>0.05625/格</b>；</li>
 *   <li>劳动：fw 0.0625/秒 × 1.5 = <b>0.09375/秒</b>（现在只有「合成」算劳动）；</li>
 *   <li>补充：食物营养 × 5（20 点制 → 100 点制），上限 100%；</li>
 *   <li>回血：饥饿 ≥90% 时每 80 tick 回 1 HP（同时耗 1.5% 饥饿，和 fw 的 6 点疲劳一致）；</li>
 *   <li>饿死：饥饿 = 0 时每 80 tick 掉 1 HP（和平难度不掉，困难一直掉，其余血量安全线同 fw）。</li>
 * </ul>
 *
 * <p>能吃的判断照 fw 的 {@code canEatMeal}：只有在「吃了不会撑过上限太多」时才吃，
 * 所以面包（5 点 = 25%）会在 75% 以下才动口，不会把一整叠食物浪费在饱着的时候。</p>
 */
public final class ResidentHunger {
    /** 满值 = 100% = 20 点（与玩家一致）。 */
    public static final float MAX = 100.0F;
    /** 1 点饥饿值 = 5%。 */
    public static final float PERCENT_PER_POINT = 5.0F;

    /** 走一格消耗（fw 0.0375 × 1.5）。 */
    public static final float WALK_COST_PER_BLOCK = 0.05625F;
    /** 劳动一秒消耗（fw 0.0625 × 1.5），按 tick 摊开。 */
    public static final float LABOR_COST_PER_TICK = 0.09375F / 20.0F;
    /** 回血门槛：fw 18/20 = 90%。 */
    public static final float REGEN_THRESHOLD = 90.0F;
    /** 回血间隔（fw 慢回血 80 tick 1 点）。 */
    public static final int REGEN_INTERVAL_TICKS = 80;
    /** 饿死掉血间隔（fw 80 tick）。 */
    public static final int STARVE_INTERVAL_TICKS = 80;
    /** 吃到饥饿伤害后给的高亮时长：10 秒（主人 2026-11-20）。 */
    public static final int STARVE_GLOW_TICKS = 10 * 20;
    /** 回 1 HP 要消耗的饥饿值（fw 6 点疲劳 = 1.5 点饱食）。 */
    public static final float HUNGER_PER_REGEN = 1.5F;
    /** 吃完一口后的冷却：别刚咽下去就立刻又举起来（原版进食本身就有 32 tick）。 */
    private static final int EAT_COOLDOWN_TICKS = 10;
    /** 不满血时的进食检查间隔：10 秒一次（主人要求别太快）。 */
    private static final int HURT_EAT_CHECK_INTERVAL = 200;
    /** 数据包可以往里加「负面食物」黑名单（运行时还会自动排除带负面效果的食物）。 */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> NEGATIVE_FOOD_TAG =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            com.worldplayers.WorldPlayers.id("negative_food"));

    /**
     * 生肉黑名单（主人 2026-11-23）：居民**自己不吃生肉**，但**不影响招募**（招募只看物品栏里有吃的）。
     *
     * <p>四层判定，任一层命中就不当饭：</p>
     * <ol>
     *   <li>{@code #worldplayers:raw_meat}（数据包可加，整合包自定义生肉往这里塞）；</li>
     *   <li>社区标签 {@code #c:foods/raw_meat}（NeoForge 惯例，装了对应的肉类模组自动生效）；</li>
     *   <li>旧社区标签 {@code #c:raw_meats}（1.20 时代）；</li>
     *   <li>原版五种生肉兜底（牛肉 / 猪排 / 生鸡肉 / 羊肉 / 兔肉）——没装任何数据包也生效。</li>
     * </ol>
     */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> RAW_MEAT_TAG =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            com.worldplayers.WorldPlayers.id("raw_meat"));
    private static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> RAW_MEAT_COMMON =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("c", "foods/raw_meat"));
    private static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> RAW_MEAT_COMMON_LEGACY =
        net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("c", "raw_meats"));
    private static final java.util.Set<net.minecraft.world.item.Item> RAW_MEAT_VANILLA =
        java.util.Set.of(
            net.minecraft.world.item.Items.BEEF,
            net.minecraft.world.item.Items.PORKCHOP,
            net.minecraft.world.item.Items.CHICKEN,
            net.minecraft.world.item.Items.MUTTON,
            net.minecraft.world.item.Items.RABBIT);

    /** 这件物品是不是生肉（进食黑名单用；招募不查它）。 */
    public static boolean isRawMeat(net.minecraft.world.item.ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.is(RAW_MEAT_TAG) || stack.is(RAW_MEAT_COMMON)
            || stack.is(RAW_MEAT_COMMON_LEGACY) || RAW_MEAT_VANILLA.contains(stack.getItem());
    }

    private final ResidentEntity resident;
    private double lastX;
    private double lastZ;
    private boolean positioned;
    private int regenTimer;
    private int starveTimer;
    private int eatCooldown;
    /** 不满血时「优先吃食物补血」的检查节流（10 秒）。 */
    private int hurtEatCooldown;
    /** 上一轮找到的食物在 41 槽里的位置（-1 = 没找到）。 */
    private int foodSlot = -1;
    /** 上一次因饥饿伤害说话的时间（tick）：2 秒 CD（主人 2026-11-23）。 */
    private int lastStarveChatTick = Integer.MIN_VALUE;

    ResidentHunger(ResidentEntity resident) {
        this.resident = resident;
    }

    /** 每 tick 调一次（服务端）。 */
    public void tick(ServerLevel level) {
        if (this.resident.isDeadOrDying() || !this.resident.isAlive()) {
            return;
        }
        // 和平难度：饥饿**固定为满**，不消耗、不回血、不饿死（主人 2026-11-19 要求）
        if (level.getDifficulty() == Difficulty.PEACEFUL) {
            if (this.resident.hunger() < MAX) {
                this.resident.setHunger(MAX);
            }
            return;
        }
        this.tickWalk();
        this.tickLabor();
        this.tickEatingEffects(level);
        this.tickEat();
        this.tickVitals(level);
        // 饥饿 ≤30% 的限速在 applyMovementSpeed 里算，饥饿变了要立刻重算（主人 2026-11-20）
        this.resident.refreshMovementSpeed();
    }

    /**
     * 持续进食表现：每 4 tick 5 个食物碎屑 + 一口音效。
     *
     * <p>位置照玩家吃东西的样子——**眼睛高度、往视线前方一点**，不是头顶也不是脚底。
     * 原版 `LivingEntity#spawnItemParticles` 是 private，服务端又没法让它替我们播，
     * 所以这里用 `ServerLevel#sendParticles` 自己喷（粒子和音效跟玩家同一个节奏）。</p>
     */
    private void tickEatingEffects(ServerLevel level) {
        if (!this.resident.isEatingFood() || this.resident.tickCount % 4 != 0) {
            return;
        }
        ItemStack food = this.resident.getUseItem();
        if (food.isEmpty()) {
            return;
        }
        net.minecraft.world.phys.Vec3 look = this.resident.getViewVector(1.0F);
        double px = this.resident.getX() + look.x * 0.3D;
        double py = this.resident.getEyeY() - 0.25D;
        double pz = this.resident.getZ() + look.z * 0.3D;
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, food),
            px, py, pz, 5, 0.08D, 0.08D, 0.08D, 0.0D);
        // 音效交给原版 LivingEntity 播（再播一次就会听到两声，v0.2.11-alpha.5 修）
    }

    /** 走路：按水平位移，单 tick 位移超过 1 格（传送/活塞）不按异常距离扣。 */
    private void tickWalk() {
        double x = this.resident.getX();
        double z = this.resident.getZ();
        if (!this.positioned) {
            this.lastX = x;
            this.lastZ = z;
            this.positioned = true;
            return;
        }
        double dx = x - this.lastX;
        double dz = z - this.lastZ;
        this.lastX = x;
        this.lastZ = z;
        double moved = Math.min(Math.sqrt(dx * dx + dz * dz), 1.0D);
        if (moved <= 1.0E-4D) {
            return;
        }
        this.resident.addHunger(-(float) (moved * WALK_COST_PER_BLOCK));
    }

    /** 劳动：现在只有「合成」算劳动（以后接挖矿/砍树时把工作状态都算进来）。 */
    private void tickLabor() {
        if (!ResidentJobs.CRAFTING.equals(this.resident.activityKey())) {
            return;
        }
        this.resident.addHunger(-LABOR_COST_PER_TICK);
    }

    /**
     * 吃东西的判定（主人 2026-11-19 第三轮定稿）：
     *
     * <ul>
     *   <li><b>战斗时禁止进食</b>（有目标 / 被仇恨 / 去援助的路上都算），
     *       除非血量掉到 75% 以下 —— 逃跑途中允许吃（速度自然是 0.2 倍）；</li>
     *   <li><b>满血</b>：只在「吃了不会溢出」时吃，并且越饿越挑大的；
     *       背包里最小的也会溢出 → 省着不吃；</li>
     *   <li><b>不满血</b>：就算浪费也优先吃（靠饱腹度给的恢复效果补血），10 秒才检查一次。</li>
     * </ul>
     *
     * <p>进食本身走原版 `startUsingItem`，时长/消耗/药水效果都是原版的；
     * 持续的声音和碎屑由 {@link #tickEatingEffects} 自己补（服务端播）。</p>
     */
    private void tickEat() {
        // 已经在吃了：交给原版推进
        if (this.resident.isEatingFood()) {
            return;
        }
        if (this.eatCooldown > 0) {
            this.eatCooldown--;
            return;
        }
        boolean inCombat = this.resident.isInCombat();
        boolean recovering = this.resident.needsCombatRecovery();
        if (inCombat && !recovering) {
            return;
        }
        // 低血量逃跑中：先拉开距离，安全了才停手吃东西（v0.2.11-alpha.5）
        if (recovering && !safeEnoughToEat()) {
            return;
        }
        float hunger = this.resident.hunger();
        // 逃跑续命时**不看饥饿值**（主人 2026-11-19）：血不够就吃，哪怕不饿
        if (!recovering && hunger >= MAX - 0.5F) {
            return;
        }
        boolean hurt = this.resident.getHealth() < this.resident.getMaxHealth();
        if (hurt) {
            // 补血优先：饿着也要吃，但别每 tick 都扫背包
            if (this.hurtEatCooldown > 0) {
                this.hurtEatCooldown--;
                return;
            }
        } else if (this.resident.tickCount % 20 != 0) {
            // 满血：每 20 tick 检查一次
            return;
        }
        int slot = this.chooseFood(hurt);
        if (slot < 0) {
            if (hurt) {
                this.hurtEatCooldown = HURT_EAT_CHECK_INTERVAL;
            }
            return;
        }
        this.foodSlot = slot;
        this.startEating();
        if (hurt) {
            this.hurtEatCooldown = HURT_EAT_CHECK_INTERVAL;
        }
    }

    /**
     * 逃跑途中够不够安全（离最近敌人 ≥16 格）——不够就先跑，不举食物。
     * 和 `ResidentCombatGoal` 里停下来吃东西的距离保持一致。
     */
    private boolean safeEnoughToEat() {
        java.util.List<net.minecraft.world.entity.Mob> threats =
            com.worldplayers.resident.ai.ResidentCombat.threatsWithin(this.resident, 32.0D);
        double nearest = Double.MAX_VALUE;
        for (net.minecraft.world.entity.Mob threat : threats) {
            nearest = Math.min(nearest, Math.sqrt(this.resident.distanceToSqr(threat)));
        }
        if (nearest == Double.MAX_VALUE) {
            return true;
        }
        return nearest >= 16.0D;
    }

    /**
     * 挑食物。
     *
     * <p>{@code hurt = true}（不满血）：直接挑**回复最多**的（营养最大 → 饱腹度也大 → 恢复更久）。</p>
     * <p>{@code hurt = false}（满血）：只挑**填得进空缺**的，并且尽量大（越饿越吃大的）；
     * 全都会溢出就不吃。</p>
     *
     * <p>分级（主人 2026-11-20）：**只要背包里有任何普通食物，就只在普通食物里挑**——
     * 挑不到（比如只有会溢出的大份食物）宁可饿着，也不去啃农作物；农作物留给玩家做更好的菜。
     * 只有一件普通食物都没有时才吃农作物。等级判定见 {@link ResidentFoodTier}。</p>
     */
    private int chooseFood(boolean hurt) {
        float gap = MAX - this.resident.hunger();
        if (hasFoodOfTier(ResidentFoodTier.NORMAL)) {
            return pickFood(ResidentFoodTier.NORMAL, hurt, gap);
        }
        return pickFood(ResidentFoodTier.CROP, hurt, gap);
    }

    /** 背包里有没有这个等级的可吃食物（不管吃了会不会溢出）。 */
    private boolean hasFoodOfTier(int tier) {
        ResidentInventory inventory = this.resident.inventory();
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (usableFood(stack) != null && ResidentFoodTier.tier(stack) == tier) {
                return true;
            }
        }
        return false;
    }

    /** 在指定等级里挑：hurt 挑营养最大，满血挑「填得进空缺且最大」的。 */
    private int pickFood(int tier, boolean hurt, float gap) {
        ResidentInventory inventory = this.resident.inventory();
        int bestSlot = -1;
        float bestGain = -1.0F;
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            FoodProperties properties = usableFood(stack);
            if (properties == null || ResidentFoodTier.tier(stack) != tier) {
                continue;
            }
            float gain = properties.nutrition() * PERCENT_PER_POINT;
            if (hurt) {
                if (gain > bestGain) {
                    bestGain = gain;
                    bestSlot = slot;
                }
            } else if (gain <= gap + 0.5F && gain > bestGain) {
                bestGain = gain;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    /**
     * 这个物品能不能当饭吃：**至少 1 点饥饿值**、不带负面药水效果、不在黑名单 tag 里。
     *
     * <p>负面效果是运行时检查 {@code FoodProperties.effects()} 的，
     * 所以任何模组食物只要有负面效果都会自动被排除；`#worldplayers:negative_food` 留给数据包手动补。</p>
     */
    private FoodProperties usableFood(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        if (stack.is(NEGATIVE_FOOD_TAG)) {
            return null;
        }
        // 生肉不吃（主人 2026-11-23）：只影响进食，招募照旧
        if (isRawMeat(stack)) {
            return null;
        }
        FoodProperties properties = stack.get(DataComponents.FOOD);
        if (properties == null || properties.nutrition() < 1) {
            return null;
        }
        for (FoodProperties.PossibleEffect possible : properties.effects()) {
            if (!possible.effect().getEffect().value().isBeneficial()) {
                return null;
            }
        }
        return properties;
    }

    /**
     * 开始进食：食物**拿在副手**（像战斗时盾牌优先那样，需要进食时食物优先级临时提高）；
     * 战斗/逃跑中换成主手（主人 2026-11-19 要求：战斗途中主手进食，吃完才允许格挡）。
     */
    private void startEating() {
        ResidentInventory inventory = this.resident.inventory();
        boolean inCombat = this.resident.isInCombat();
        int handSlot = inCombat ? ResidentInventory.MAIN_HAND : ResidentInventory.OFFHAND;
        if (this.foodSlot != handSlot) {
            ItemStack food = inventory.getItem(this.foodSlot);
            ItemStack hand = inventory.getItem(handSlot);
            inventory.setItem(handSlot, food);
            inventory.setItem(this.foodSlot, hand);
            this.foodSlot = handSlot;
        }
        this.resident.startUsingItem(handSlot == ResidentInventory.MAIN_HAND
            ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
    }

    /** 一口吃完：补饥饿值（营养 × 5），并留一点点冷却，别吃完立刻又举起来。 */
    void onAte(ItemStack food) {
        FoodProperties properties = food.get(DataComponents.FOOD);
        if (properties == null) {
            return;
        }
        this.resident.setHunger(this.resident.hunger() + properties.nutrition() * PERCENT_PER_POINT);
        this.resident.inventoryChanged();
        this.eatCooldown = EAT_COOLDOWN_TICKS;
    }

    /** 回血 / 饿死。 */
    private void tickVitals(ServerLevel level) {
        float hunger = this.resident.hunger();
        float health = this.resident.getHealth();
        float maxHealth = this.resident.getMaxHealth();

        if (hunger >= REGEN_THRESHOLD && health < maxHealth) {
            if (++this.regenTimer >= REGEN_INTERVAL_TICKS) {
                this.regenTimer = 0;
                this.resident.heal(1.0F);
                // fw 回血也吃疲劳：每点血 6 疲劳 = 1.5 点饱食
                this.resident.addHunger(-HUNGER_PER_REGEN);
            }
        } else {
            this.regenTimer = 0;
        }

        if (hunger <= 0.0F && starvationBites(level)) {
            if (++this.starveTimer >= STARVE_INTERVAL_TICKS) {
                this.starveTimer = 0;
                if (this.resident.hurt(level.damageSources().starve(), 1.0F)) {
                    this.onStarveDamage(level);
                }
            }
        } else {
            this.starveTimer = 0;
        }
    }

    /**
     * 每次真的吃到饥饿伤害：广播一条带坐标的测试文本，并给 10 秒发光（主人 2026-11-20 要求）。
     *
     * <p>发光 = 原版 {@link MobEffects#GLOWING}，方便在主世界一眼找到是哪个居民在掉血；
     * 文字走 {@link ResidentChat}，所以和居民平时说话一样带名字（装了 Chat Heads 还会带头像）。</p>
     */
    private void onStarveDamage(ServerLevel level) {
        // 每次真的挨饿伤：心情 -1%（主人 2026-11-20）
        this.resident.setMood(this.resident.mood() - 1.0F);
        // 触发 B（v0.3.0 T4）：饿到掉血 → 立刻挂「需要被给饭」的需求，
        // 附近的居民会当场走过来分享，不等触发 A 的 10 秒检查。
        this.resident.socialSystem().requestHelp(true);
        BlockPos pos = this.resident.blockPosition();
        // 饿伤台词：2 秒 CD（CD 内再挨饿也闭嘴，主人 2026-11-23）；
        // 正在分享食物（社交状态）时不喊——正在被救，别在旁边念。发光效果不受这两条影响。
        int now = this.resident.tickCount;
        boolean social = ResidentJobs.SOCIAL.equals(this.resident.activityKey());
        boolean canSpeak = !social && (this.lastStarveChatTick == Integer.MIN_VALUE
            || now - this.lastStarveChatTick >= 40);
        if (canSpeak) {
            this.lastStarveChatTick = now;
            // 饿伤台词：从对白池随机挑一句，坐标拼在后方（主人 2026-11-21）
            Component line = ResidentChatPool.pick(ResidentChatPool.Kind.STARVE,
                this.resident.getRandom());
            ResidentChat.say(level, this.resident, Component.translatable("chat.worldplayers.starve.post",
                line, pos.getX(), pos.getY(), pos.getZ()));
        }
        this.resident.addEffect(new MobEffectInstance(
            MobEffects.GLOWING, STARVE_GLOW_TICKS, 0, false, false));
    }

    /** fw 的饿死安全线：困难一直掉，普通只剩 5% 血才停，简单 50% 以下就停（按血量比例，不写死数值）。 */
    private boolean starvationBites(ServerLevel level) {
        Difficulty difficulty = level.getDifficulty();
        if (difficulty == Difficulty.PEACEFUL) {
            return false;
        }
        if (difficulty == Difficulty.HARD) {
            return true;
        }
        float ratio = this.resident.healthRatio();
        return ratio > (difficulty == Difficulty.NORMAL ? 0.05F : 0.50F);
    }

    /** 调试用：把饥饿值直接设成百分比。 */
    public void setPercent(float percent) {
        this.resident.setHunger(Math.clamp(percent, 0.0F, MAX));
    }

    /** 给测试/以后存档迁移用：重新从当前位置起算走路距离。 */
    public void resetPosition() {
        this.positioned = false;
    }
}

