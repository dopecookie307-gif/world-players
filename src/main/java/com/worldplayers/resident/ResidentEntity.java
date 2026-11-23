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
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityAttachments;
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
    /** Yes Steve Model（软兼容）：模型来自 YSM 的 config 目录。 */
    public static final int MODEL_YSM = 5;

    /** 工作状态 id（文字由 {@link ResidentJobs} 统一登记，UI 不写死文案）。 */
    public static final String JOB_IDLE = ResidentJobs.IDLE;
    /** 跟随玩家：快步跟上、太远传送、替玩家打架。 */
    public static final String JOB_FOLLOW = ResidentJobs.FOLLOW;
    /** 站立：站在原地，但会攻击攻击范围内 / 射程内的敌人。 */
    public static final String JOB_STAND = ResidentJobs.STAND;

    /**
     * 背包 NBT 的键名 = {@code ContainerHelper} 用的 {@code "Items"}。
     *
     * <p>坑（v0.2.6 修）：以前这里写的是 {@code "Inventory"}，但保存走的是
     * {@code ContainerHelper.saveAllItems}，它写的是根标签下的 {@code "Items"}；
     * 读取却查 {@code tag.contains("Inventory")} → 永远 false → **每次重进世界背包都被清空**。
     * 主手/副手/盔甲因为原版 {@link net.minecraft.world.entity.Mob#addAdditionalSaveData}
     * 自己的 {@code HandItems}/{@code ArmorItems} 还在，所以只有背包没了。</p>
     */
    private static final String TAG_INVENTORY = "Items";
    private static final String TAG_SKIN = "Skin";
    private static final String TAG_MODEL = "Model";
    private static final String TAG_EXPERIENCE = "Experience";
    private static final String TAG_HUNGER = "Hunger";
    private static final String TAG_STAMINA = "Stamina";
    private static final String TAG_MOOD = "Mood";
    private static final String TAG_JOB = "Job";
    /** 固定工作场所（现在只是预留：切回「闲逛」就解绑）。 */
    private static final String TAG_WORK_SITE = "WorkSite";
    /** 绑定的床（头或脚那一格）；流浪居民没有这个键。 */
    private static final String TAG_BED = "Bed";

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
    /**
     * 临时工作状态（合成/放置等瞬时行为）。空字符串 = 没有，显示回 {@link #jobKey()}。
     * <p>不存 NBT：行为一结束就清空，重进世界由 goal 重新决定。</p>
     */
    private static final EntityDataAccessor<String> DATA_ACTIVITY =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    /**
     * 通勤目的地名字（浮窗里"通勤"下面那一行，没有前置标题）。
     * 以后会是工作区域的名字；调试棒指令写死「调试棒」。空字符串 = 不显示。
     */
    private static final EntityDataAccessor<String> DATA_DESTINATION =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    /**
     * 自然语言指令（v0.3.0，主人 2026-11-21）：一段「想让它做什么」的人话，先只存起来 + 同步。
     * 真正翻译成行为要等未来的「任务规划层」，现在没有任何 AI 读它。
     */
    private static final EntityDataAccessor<String> DATA_INSTRUCTIONS =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);
    /** 绑定的床（同步给客户端：居民背包界面的「分配床铺」按钮 / 名字牌判断要不要显示）。 */
    private static final EntityDataAccessor<java.util.Optional<net.minecraft.core.BlockPos>> DATA_BED =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    /** YSM 模型名（模型类型 = YSM 时用；空 = 默认皮肤）。 */
    private static final EntityDataAccessor<String> DATA_YSM_MODEL =
        SynchedEntityData.defineId(ResidentEntity.class, EntityDataSerializers.STRING);

    private final ResidentInventory inventory = new ResidentInventory(this);

    /** 饥饿的消耗与补充（v0.2.11，见 {@link ResidentHunger}）。 */
    private final ResidentHunger hungerSystem = new ResidentHunger(this);

    /** 社交需求与冷却（v0.3.0 T4，见 {@link ResidentSocial}）；动作在 {@code ResidentSocialGoal}。 */
    private final ResidentSocial socialSystem = new ResidentSocial(this);

    /** 上次因为被玩家打而扣心情的时刻（tick），用来做 1 秒冷却。 */
    private int lastMoodHurtTick = -1000;
    /** 上次发受击台词的时刻（tick）：受击文本也是 1 秒最多一句。 */
    private int lastHitChatTick = -1000;

    /** 正在翻他背包的玩家（服务端）。 */
    private final java.util.Set<java.util.UUID> viewers = new java.util.HashSet<>();

    private int experiencePoints;

    /** 玩家式的攻击充能：每 tick +1，挥击后清零（和主手武器的 ATTACK_SPEED 挂钩）。 */
    private int attackStrengthTicker;

    /** 期望移动速度（不含进食减速）；进食开始时/结束时会用它重算属性。 */
    private double wantedMovementSpeed = BASE_MOVEMENT_SPEED;

    /** 上一 tick 是不是在吃东西：用来在开始/结束时刷新移速。 */
    private boolean eatingLastTick;

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

    /** 站岗点：进入「站立」时记下，去援助/被推走之后用它走回原位（主人 2026-11-20）。 */
    @javax.annotation.Nullable
    private net.minecraft.core.BlockPos standHome;

    /**
     * 闲逛锚点（主人 2026-11-20）：世界里**没有任何工作区域 / 道路 / 已绑定床**时，
     * 居民自己的领地中心（第一次需要时记在脚下，写进 NBT）。
     * 有殖民地要素时不用它——边境就是锚点，见 {@link ColonyAnchors}。
     */
    @javax.annotation.Nullable
    private net.minecraft.core.BlockPos strollAnchor;

    /**
     * 这个居民的床（主人 2026-11-20）：有床 = 死亡 5 秒后在床边复活；
     * 没有床 = 流浪，死亡永久消失且必定掉落全部物品。真实归属查 {@link BedBindingData}。
     */
    @javax.annotation.Nullable
    private net.minecraft.core.BlockPos bedPos;

    public ResidentEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false);
        this.xpReward = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.setDropChance(slot, 0.0F);
        }
    }

    /** 游荡基础速度（属性值）：和 {@code createAttributes} 里注册的一致。 */
    public static final double BASE_MOVEMENT_SPEED = 0.28D;
    /**
     * 玩家步行 4.317 格/秒对应的居民属性值。
     *
     * <p>玩家和生物的移动处理不同（玩家有额外的冲刺/摩擦加成），不能用同一个属性值直接等价格/秒；
     * 这里用经验系数 ×4 换算（玩家步行 0.1 → 居民 0.4）。实机测速若有偏差，只改这几个常量。</p>
     */
    public static final double WALK_MOVEMENT_SPEED = 0.40D;
    /** 玩家奔跑 5.612 格/秒；主人要求绿色心情最高只给 5 格/秒（不超奔跑）。 */
    public static final double SPRINT_MOVEMENT_SPEED = 0.463D;

    /** 进食期间移速降到原来的 0.2 倍（主人 2026-11-19 要求）。 */
    public static final double EATING_SPEED_FACTOR = 0.2D;

    /**
     * 饥饿 ≤30% 时，行走速度封顶到「红脸心情」的速度（主人 2026-11-20 要求）。
     *
     * <p>红脸 = 心情 ≤49%，红脸档的速度见 {@link #lowMoodMovementSpeed()}；
     * 饥饿和心情同时低的时候取更慢的那个。</p>
     */
    public static final float LOW_HUNGER_SPEED_THRESHOLD = 30.0F;

    /** 战斗血量掉到 75% 就逃（按最大生命值比例，不写死数值）。 */
    public static final float LOW_HEALTH_FLEE_RATIO = 0.75F;

    /**
     * 体力低到这个值（≤10%）就**累瘫了**（主人 2026-11-21 定）：
     * 停止工作、离开工作日程、通勤回自己绑定的床，目的地名统一显示「床」。
     *
     * <p>此时也不闲逛（有床的话），免得在床边走来走去；没床的居民照旧闲逛。</p>
     */
    public static final float EXHAUSTED_STAMINA_THRESHOLD = 10.0F;

    /**
     * 回家（体力见底）时的目的地**内部标记**：所有居民统一叫「床」。
     *
     * <p>2026-11-23 主人报「英文界面显示 Commuting：床」：以前这里是硬编码中文「床」，
     * 直接流到浮窗 / 名字牌 / Jade。现在内部用标记 {@code @bed}，显示统一走
     * {@link #destinationText()} 查语言键（旧存档里万一有「床」也照认）。</p>
     */
    public static final String BED_DESTINATION = "@bed";
    /** 旧版硬编码的中文目的地名（兼容用）。 */
    private static final String BED_DESTINATION_LEGACY = "床";
    /** 回家节流：同一次「累瘫」最多 30 秒补一次通勤目标，避免反复下达（主人 2026-11-21）。 */
    private static final int BED_RETURN_RETRY_TICKS = 20 * 30;
    /** 上次下达「回床」通勤目标的 tick。 */
    private int lastBedReturnTick = -BED_RETURN_RETRY_TICKS;
    /** 睡眠被打断的时刻（tick）；-1 = 没有被打断。 */
    private int sleepInterruptedAt = -1;
    /** 打断后 30 秒仍回不到床 → 放弃这轮睡觉，回到睡眠前的日程。 */
    private boolean bedReturnGivenUp;
    /** 放弃这轮睡觉的时刻（tick）：60 秒后自动重试（2026-11-22 修「低体力不回床」）。 */
    private int bedReturnGiveUpAt;
    /** 放弃后的重试间隔：60 秒。 */
    private static final int BED_RETURN_GIVE_UP_RETRY_TICKS = 20 * 60;
    /** 「被打断后还能续睡」的窗口：30 秒（主人 2026-11-21 定）。 */
    public static final int SLEEP_RESUME_WINDOW_TICKS = 20 * 30;
    /**
     * 体力自然衰减：每 4 分钟（4800 tick）−1%。
     *
     * <p>2026-11-22 主人要求「把每分钟自然减少的数值减到现有的一半」：原来每 2 分钟 −1
     * （每分钟 −0.5），现在每 4 分钟 −1（每分钟 −0.25）。</p>
     */
    public static final int STAMINA_DECAY_TICKS = 20 * 60 * 4;
    /** 自然衰减的计时器。 */
    private int staminaDecayTimer;

    /**
     * 心情自然衰减（2026-11-22，14 号文档 4.1 数值定稿）。
     *
     * <p>清醒时 **30 秒 −1%**；**游荡（Wandering）时减半 = 60 秒 −1%**；睡觉不扣。
     * 用一个「双倍累积」的计时器：清醒每 tick +2、游荡每 tick +1，满 {@link #MOOD_DECAY_ACCUM_FULL}
     * 就 −1%（正好 600 / 1200 tick），这样切状态时不会白丢已经走过的进度。</p>
     */
    public static final int MOOD_DECAY_ACCUM_FULL = 20 * 60;
    /** 心情衰减累积（见上）。 */
    private int moodDecayAccum;

    /** 日程状态机（2026-11-23，14 号文档第三节）。 */
    private final com.worldplayers.resident.schedule.ResidentSchedule schedule =
        new com.worldplayers.resident.schedule.ResidentSchedule(this);

    /** 日程状态机（工作 / 自由 / 休息）。 */
    public com.worldplayers.resident.schedule.ResidentSchedule schedule() {
        return this.schedule;
    }

    /** 心情档位 → 通勤速度：红（&lt;50）步行 75%、黄（50~75）4 格/秒、绿（&gt;=75）5 格/秒。 */
    public static double moodMovementSpeed(float mood) {
        if (mood >= 75.0F) {
            return SPRINT_MOVEMENT_SPEED;
        }
        if (mood >= 50.0F) {
            return WALK_MOVEMENT_SPEED * (4.0D / 4.317D);
        }
        return lowMoodMovementSpeed();
    }

    /** 红脸心情（≤49%）档的行走速度：步行 75%。饥饿 ≤30% 时也封顶到这个值。 */
    public static double lowMoodMovementSpeed() {
        return WALK_MOVEMENT_SPEED * 0.75D;
    }

    /**
     * 体力档位 → 通勤速度（v0.3.0，主人 2026-11-21 定）：**阈值和速度完全照搬心情**——
     * 绿（&ge;75）5 格/秒、黄（50~74）4 格/秒、红（&lt;50）步行 75%。
     *
     * <p>和心情一起用时取**更慢**的那个（{@link #applyMovementSpeed} 里做 min），
     * 和劳动冷却「取更慢档」是同一套思路。</p>
     */
    public static double staminaMovementSpeed(float stamina) {
        if (stamina >= 75.0F) {
            return SPRINT_MOVEMENT_SPEED;
        }
        if (stamina >= 50.0F) {
            return WALK_MOVEMENT_SPEED * (4.0D / 4.317D);
        }
        return lowMoodMovementSpeed();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.ARMOR, 0.0D)
            .add(Attributes.MOVEMENT_SPEED, BASE_MOVEMENT_SPEED)
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
        builder.define(DATA_ACTIVITY, "");
        builder.define(DATA_DESTINATION, "");
        builder.define(DATA_INSTRUCTIONS, "");
        builder.define(DATA_BED, java.util.Optional.empty());
        builder.define(DATA_YSM_MODEL, "");
    }

    @Override
    protected void registerGoals() {
        // 视线完全交回原版：看玩家 + 随机环顾（和普通生物一样，天然平滑）
        // 有东西要炸了先跑：优先级 0，比战斗（1）还高，正在打架也会先跑
        this.goalSelector.addGoal(0, new com.worldplayers.resident.ai.ResidentDangerFleeGoal(this));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // 原版开门 goal 的居民版：路径节点是木门时自己打开（关门等待减半），铁门由原版规则挡掉
        this.goalSelector.addGoal(1, new com.worldplayers.resident.ai.ResidentOpenDoorGoal(this));
        // 战斗中优先级最高：一个 goal 统管近战/远程切换（6 格切近战、远了用弓弩）
        this.goalSelector.addGoal(1, new com.worldplayers.resident.ai.ResidentCombatGoal(this));
        // 通勤（调试棒/以后的工作指派）：战斗优先，打完了继续走
        this.goalSelector.addGoal(2, new com.worldplayers.resident.ai.ResidentCommuteGoal(this));
        // 跟随模式：狼狗那种跟法，离太远直接传送到身边的安全方块
        this.goalSelector.addGoal(3, new com.worldplayers.resident.ai.ResidentFollowGoal(this));
        // 站岗被打断之后走回站岗点（主人 2026-11-20）：站立模式的"归位"，战斗优先
        this.goalSelector.addGoal(3, new com.worldplayers.resident.ai.ResidentReturnHomeGoal(this));
        this.goalSelector.addGoal(4, new ResidentPickupGoal(this));
        // 睡觉（v0.3.0 T8 第一块）：体力见底回床后睡下、0.3/秒回体力到 90%。
        // 排在社交前面（同优先级），累到站着都能睡着的人不该先去聊天。
        this.goalSelector.addGoal(4, new com.worldplayers.resident.ai.ResidentSleepGoal(this));
        // 社交（v0.3.0 T4）：有饭的居民被「没饭吃 / 饿伤」的邻居叫过去分享食物。
        // 比工作（5）与闲逛（5）优先，这样正在干活的居民也会先放下手里的活去救急；
        // 战斗（1）/ 通勤（2）/ 跟随（3）仍然压过它——D17：社交只在「自由活动」内部展开。
        this.goalSelector.addGoal(4, new com.worldplayers.resident.ai.ResidentSocialGoal(this));
        // 闲逛时的"手工"：在暗处搓火把并插火把（v0.2.2）。和拾取同优先级、排在它后面；
        // 只用 IDLE 模式，跟随/站立/战斗时都不会触发。
        this.goalSelector.addGoal(4, new com.worldplayers.resident.ai.ResidentCraftingGoal(this));
        // 工作区员工：闲逛状态 + 有绑定工作区 → 在工作区域里走动（主人 2026-11-20），必须排在闲逛之前
        this.goalSelector.addGoal(5, new com.worldplayers.resident.ai.ResidentWorkGoal(this));
        // 娱乐（2026-11-22，14 号文档）：自由活动 + 无工作区的居民去娱乐区恢复心情。
        // 排在闲逛之前、工作之后：有工作的居民照常上班，第 2 包的日程状态机再给他们排自由时间。
        this.goalSelector.addGoal(5, new com.worldplayers.resident.ai.ResidentEntertainmentGoal(this));
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
        // 进食中不许举盾/格挡（副手被食物占着；吃完立刻恢复，主人 2026-11-19 要求）
        boolean want = shieldInOffhand && !rangedInHand && this.getTarget() != null
            && this.shieldDisabledTicks <= 0 && this.isAlive() && !this.isDeadOrDying()
            && !this.isEatingFood();
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
            // 进食开始/结束：移速要在这一 tick 立刻切（0.2 倍 / 恢复）
            boolean eating = this.isEatingFood();
            if (eating != this.eatingLastTick) {
                this.eatingLastTick = eating;
                this.refreshMovementSpeed();
            }
            // 饥饿的消耗与补充：走路按位移、合成按劳动、背包有食物就自己吃
            this.hungerSystem.tick(serverLevel);
            // 社交触发 A：每 10 秒看一次自己还有没有饭吃（B 在 ResidentHunger#onStarveDamage 里即时挂）
            this.socialSystem.tick();
            // 体力自然衰减：每 4 分钟 −1%；**睡觉中 / 站立模式不扣**（2026-11-22 主人要求）
            if (this.isSleeping() || ResidentJobs.SLEEPING.equals(this.activityKey())
                    || ResidentJobs.STAND.equals(this.jobKey())) {
                this.staminaDecayTimer = 0;
            } else if (++this.staminaDecayTimer >= STAMINA_DECAY_TICKS) {
                this.staminaDecayTimer = 0;
                this.setStamina(this.stamina() - 1.0F);
            }
            // 心情自然衰减（14 号文档 4.1）：游荡减半、睡觉不扣
            this.tickMoodDecay();
            // 日程状态机（14 号文档第三节）：工作计时 / 自由计时 / 回床 / 新一天
            this.schedule.tick(serverLevel);
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
                // 牧场循环采样（v0.3.0）：员工在不在区域里决定循环要不要从头开始。
                // 放在这里而不是工作 goal 里，是为了「战斗 / 社交把工作打断」时不会误判成没人。
                com.worldplayers.resident.ranch.RanchCycle.presenceTick(
                    serverLevel, this, serverLevel.getGameTime());
                // 身处禁行区域：不主动进去，但已经在了就自己走出来（主人 2026-11-23）
                this.tickForbiddenEscape(serverLevel);
                // 姿势自愈（2026-11-22 修「躺着行动」）：实体带着 SLEEPING 姿势、但当前状态不是睡觉
                // （读档 / 区块重载后 SleepGoal 没有 start 过，没人调 stopSleeping）→ 站起来。
                // 不清的话原版 Mob#aiStep 会把移动清零，居民会躺着滑行、无法工作。
                if (this.isSleeping() && !ResidentJobs.SLEEPING.equals(this.activityKey())) {
                    this.stopSleeping();
                    if (this.isSleeping()) {
                        // sleepingPos 已经丢了时，原版 stopSleeping 不会清 pose，这里兜底
                        this.setPose(net.minecraft.world.entity.Pose.STANDING);
                    }
                }
                // 体力见底：回自己绑定的床（没有睡觉系统之前，先做到「回去、停下、不干活」）
                this.tickBedReturn();
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
                // 栅栏门：走**正要穿过**的那一扇才开（跟随/游荡/农活/战斗都算），
                // **站立模式一律不碰门**；木门交给 ResidentOpenDoorGoal（主人 2026-11-20）
                if (this.tickCount % 10 == 0) {
                    ResidentDoors.tickNearby(serverLevel, this);
                }
                // 弩：上弦完成判定 + 空闲时永远优先上弦（每 tick 判一次，很便宜）
                this.tickReload();
                // 脚下的东西任何时候都捡（战斗中/跟随中也捡，只是不主动走过去）
                if (this.tickCount % 5 == 0) {
                    this.pickUpAtFeet(serverLevel);
                }
                // 定期整理装备：背包里有更好的就换上（进食中手里的食物不算装备，别换掉）
                if (this.tickCount % 20 == 0 && !this.isEatingFood()) {
                    this.tidyEquipment();
                }
            }
        }
    }

    /**
     * 心情自然衰减（2026-11-22，14 号文档 4.1）：清醒 30 秒 −1%；**游荡时减半**（60 秒 −1%）；
     * 睡觉不扣。「游荡」= 没有任何去处（自由活动里既没在通勤、也没在娱乐/社交/工作），
     * 就是彻底放任的随机行走，所以衰减慢一半。
     *
     * <p>**站立模式（站岗）不自然流失**（主人 2026-11-23 要求）：站着待命的人不该越站越丧。</p>
     */
    private void tickMoodDecay() {
        if (this.isSleeping() || ResidentJobs.SLEEPING.equals(this.activityKey())
                || ResidentJobs.STAND.equals(this.jobKey())) {
            return;
        }
        boolean wandering = this.activityKey().isEmpty()
            && ResidentJobs.IDLE.equals(this.jobKey())
            && this.commuteTarget == null;
        this.moodDecayAccum += wandering ? 1 : 2;
        if (this.moodDecayAccum >= MOOD_DECAY_ACCUM_FULL) {
            this.moodDecayAccum -= MOOD_DECAY_ACCUM_FULL;
            this.setMood(this.mood() - 1.0F);
        }
    }

    /**
     * 体力见底（≤10%）就回床（主人 2026-11-21 定）：放下手里的活、离开工作日程，
     * 通勤回自己绑定的床，浮窗里的目的地名统一显示「床」。
     *
     * <p>**跟随 / 站立模式一律不触发**（主人 2026-11-21：这两个模式的优先级最高，不许被回床打断）；
     * 只有「自由活动」内部才回床。已经有通勤目标时不覆盖，同一次累瘫最多 30 秒补一次，避免反复下达；
     * 战斗中 / 被玩家翻背包 / 已经在睡 / 已经到床边都不动。</p>
     */
    private void tickBedReturn() {
        // 跟随 / 站立期间冻结「回笼觉窗口」（14 号文档 3.15）：窗口 30 秒照算但被不断刷新，
        // 切回自由活动后才真正开始流逝。
        if (!JOB_IDLE.equals(this.jobKey()) && this.sleepInterruptedAt >= 0) {
            this.sleepInterruptedAt = this.tickCount;
        }
        // 体力 ≥90%：这一觉睡够了，清掉所有「打断 / 放弃」标记
        if (this.stamina() >= com.worldplayers.resident.ai.ResidentSleepGoal.WAKE_THRESHOLD) {
            this.sleepInterruptedAt = -1;
            this.bedReturnGivenUp = false;
            return;
        }
        // 打断后超过 30 秒还回不到床：放弃这轮睡觉，回到睡眠前的日程（工作 / 站立）
        if (this.sleepInterruptedAt >= 0
                && this.tickCount - this.sleepInterruptedAt > SLEEP_RESUME_WINDOW_TICKS) {
            this.sleepInterruptedAt = -1;
            this.bedReturnGivenUp = true;
            this.bedReturnGiveUpAt = this.tickCount;
            // 回笼觉失败 = 新一天，跳过剩余日程（14 号文档 3.15 / 3.16）
            this.schedule.onSleepGaveUp();
        }
        if (this.bedReturnGivenUp) {
            if (this.tickCount - this.bedReturnGiveUpAt < BED_RETURN_GIVE_UP_RETRY_TICKS) {
                return;
            }
            // 60 秒后自动重试：以前这里会永久放弃，体力上不去的居民就再也不回床了
            this.bedReturnGivenUp = false;
        }
        // 只有三种情况才去睡：累瘫（≤10%）、被打断后还在 30 秒续睡窗口里，
        // 或者**日程要求回床休息**（14 号文档第三节：自由时间结束 → 睡到体力满 → 新一天）
        boolean tired = this.stamina() <= EXHAUSTED_STAMINA_THRESHOLD;
        boolean scheduleSleep = this.schedule.needsBed();
        if (!tired && this.sleepInterruptedAt < 0 && !scheduleSleep) {
            return;
        }
        if (!JOB_IDLE.equals(this.jobKey())) {
            return;
        }
        net.minecraft.core.BlockPos bed = this.bedPos();
        if (bed == null || this.commuteTarget() != null || this.isSleeping()) {
            return;
        }
        if (this.isInCombat() || !this.canLookAround()) {
            return;
        }
        if (this.tickCount - this.lastBedReturnTick < BED_RETURN_RETRY_TICKS) {
            return;
        }
        double dx = bed.getX() + 0.5D - this.getX();
        double dy = bed.getY() + 0.5D - this.getY();
        double dz = bed.getZ() + 0.5D - this.getZ();
        if (dx * dx + dy * dy + dz * dz <= 2.5D * 2.5D) {
            return;
        }
        this.lastBedReturnTick = this.tickCount;
        this.setCommuteTarget(bed, BED_DESTINATION);
    }

    /** 睡眠被打断（战斗 / 被翻背包 / 切模式 / 床被拆…）：开始 30 秒续睡窗口。 */
    public void markSleepInterrupted() {
        this.sleepInterruptedAt = this.tickCount;
    }

    // ---- 上岗搜工具（空壳，2026-11-21） ----

    /** 有没有待处理的「上岗先搜一次背包工具」请求。 */
    private boolean toolCheckRequested;

    /**
     * 玩家刚把这个居民指派到工作区时调用：请求它上班前**先搜一次背包工具**。
     *
     * <p>**现在只是空壳**：只置标志，没有任何消费者。留着给以后的
     * 「去仓库 / 找合成台 / 自己做工具再上班」用——那时在 {@code ResidentWorkGoal} 开始工作时读这个标志。</p>
     */
    public void requestToolCheck() {
        this.toolCheckRequested = true;
    }

    /** 未来工作 goal 用：有没有待处理的上岗搜工具请求。 */
    public boolean toolCheckRequested() {
        return this.toolCheckRequested;
    }

    /** 未来工作 goal 处理完请求后清掉。 */
    public void clearToolCheckRequest() {
        this.toolCheckRequested = false;
    }

    /** 现在是不是处在「被打断、还想回去续睡」的窗口里。 */
    public boolean sleepInterruptActive() {
        return this.sleepInterruptedAt >= 0;
    }

    /** 这轮睡觉是不是已经放弃（30 秒都没能回去）：放弃后照常工作/闲逛。 */
    public boolean bedReturnGivenUp() {
        // 2026-11-22 修：放弃不是永久的。以前只有体力回到 90% 才清标记，而「不回床 → 体力永远
        // 上不去 → 永远放弃」会把累瘫的居民锁死。现在 60 秒后自动重试一次。
        return this.bedReturnGivenUp
            && this.tickCount - this.bedReturnGiveUpAt < BED_RETURN_GIVE_UP_RETRY_TICKS;
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
                // 从手册点开的只读界面：受伤不关闭（主人 2026-11-20）
                if (menu.remote()) {
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
            // 被玩家打中：心情 -5%（1 秒最多一次），头上冒村民的生气粒子
            if (source.getEntity() instanceof Player) {
                this.hurtMood(serverLevel);
            }
            // 被环境（岩浆块、火、仙人掌、甜浆果、窒息…）伤到：立刻找个安全地方挪过去。
            // 饿死不跑（不然饿到 0 会一直找地方躲）
            if (source.getEntity() == null
                    && !source.is(net.minecraft.world.damagesource.DamageTypes.STARVE)) {
                if (source.is(net.minecraft.world.damagesource.DamageTypes.IN_WALL)) {
                    // 窒息（卡在方块里）：普通寻路在墙里往往起不来，直接找最近的安全落脚点脱困
                    this.escapeSuffocation(serverLevel);
                } else {
                    this.fleeToSafety(serverLevel);
                }
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

    /**
     * 窒息（`DamageTypes.IN_WALL`）兜底：找安全落脚点**直接传送**过去。
     *
     * <p>窒息是环境伤害，本来就会进 {@link #fleeToSafety}；但实体卡在方块里时
     * {@code navigation.moveTo} 往往找不到路（起点不可通行），于是站在墙里一直掉血。
     * 2026-11-22 起统一走 {@link #unstickFromBlocks}，范围 1~8 格（树冠里也够得着）。</p>
     */
    private void escapeSuffocation(ServerLevel level) {
        this.unstickFromBlocks(level);
    }

    /**
     * 站在**禁行区域**里时自己走出来（主人 2026-11-23）：
     * 居民不会主动走进去（寻路把禁行格判 BLOCKED），但被玩家划进来 / 被推进来时不能永久困住。
     * 每 20 tick 找一次最近的可站出口并走过去；找不到就下次再试。
     */
    private void tickForbiddenEscape(ServerLevel level) {
        WorkZoneData data = WorkZoneData.get(level);
        net.minecraft.core.BlockPos feet = this.blockPosition();
        if (!data.isForbidden(feet)) {
            return;
        }
        for (int radius = 1; radius <= 12; radius++) {
            for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
                    feet.offset(-radius, -2, -radius), feet.offset(radius, 2, radius))) {
                if (data.isForbidden(pos) || !isSafeStand(level, pos)) {
                    continue;
                }
                this.getNavigation().moveTo(
                    pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 1.0D);
                return;
            }
        }
    }

    /**
     * 工作时自救（2026-11-22 主人反馈：砍树被树叶/原木蒙住脑袋后停工）：
     * 身体所在的两格被方块占住、或者已经 {@code IN_WALL} → 在 1~8 格内找安全落脚点直接挪过去，
     * 返回 true（调用方应当作废这一 tick 的目标重来）。
     *
     * <p>只检查**身体空间**，不会因为站在地毯/雪片这类没有碰撞的方块上而误触发；
     * 这是「树场停工」和以后任何卡方块问题的统一兜底。</p>
     */
    public boolean unstickFromBlocks(ServerLevel level) {
        net.minecraft.core.BlockPos origin = this.blockPosition();
        // 2026-11-23 二修：只认「真的卡在方块里」（碰撞箱相交）。之前还看「身体两格有没有碰撞方块」，
        // 结果员工站进作物 / 地毯 / 雪片这些没有碰撞的方块时也会被传送走 —— 玩家看到的就是
        // 在农田 / 牧场里被弹开、抽搐。树冠卡人时 isInWall 同样成立，救得回来。
        if (!this.isInWall()) {
            return false;
        }
        // 2026-11-23 第 10 轮：按**距离由近到远**找落脚点，别再按包围盒遍历顺序随便挑一个。
        // 以前可能在 8 格外挑到第一个「安全」格，把员工直接扔出树场 / 扔到区域外，
        // 玩家看到的就是「头被树叶卡住 → 人突然跑到外面去」，还要重新通勤。
        net.minecraft.core.BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int radius = 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    if (dx == 0 && dz == 0 && dy == 0) {
                        continue;
                    }
                    net.minecraft.core.BlockPos pos = origin.offset(dx, dy, dz);
                    if (!isSafeStand(level, pos)) {
                        continue;
                    }
                    // 不往禁行区里塞（居民会自己走出来，但没必要主动送进去）
                    if (com.worldplayers.resident.WorkZoneData.get(level).isForbidden(pos)) {
                        continue;
                    }
                    // 同层最优先，其次水平近，最后才考虑上下层
                    double score = dx * dx + dz * dz + dy * dy * 6.0D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }
        if (best == null) {
            return false;
        }
        this.teleportTo(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D);
        this.getNavigation().stop();
        return true;
    }

    /**
     * 这一格能不能安全站人：脚下有实体方块、**本人与头顶两格没有碰撞**、上下都不是危险方块。
     *
     * <p>2026-11-23 三修：以前要求这两格 {@code isEmpty()}（= 空气），结果**作物 / 花 / 短草 /
     * 地毯 / 雪片**这些没有碰撞的方块都会被判成「不能站」。种满作物的农田 / 温室里于是连一个
     * 落脚点都找不到 —— 员工走到门口就显示「通勤」原地不动，重新指派也走不进去。
     * 现在改看**碰撞箱**：站得进去就算能站。树苗仍然排除（规则 33：树苗是障碍）。</p>
     */
    public static boolean isSafeStand(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        net.minecraft.world.level.block.state.BlockState here = level.getBlockState(pos);
        net.minecraft.world.level.block.state.BlockState above = level.getBlockState(pos.above());
        net.minecraft.world.level.block.state.BlockState below = level.getBlockState(pos.below());
        if (here.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) {
            return false;
        }
        return here.getCollisionShape(level, pos).isEmpty()
            && above.getCollisionShape(level, pos.above()).isEmpty()
            && below.blocksMotion()
            && !com.worldplayers.resident.ai.ResidentNodeEvaluator.isDangerous(below)
            && !com.worldplayers.resident.ai.ResidentNodeEvaluator.isDangerous(here);
    }

    /**
     * 战斗低血量逃跑（v0.2.11-alpha.5）：朝「远离所有威胁质心」的方向，找 8~12 格外的安全落点跑。
     *
     * <p>和躲 TNT 的逃跑是同一个思路：先**脱离包围圈**再谈吃饭，
     * 不是站在敌人面前边抖边啃面包。</p>
     */
    public void fleeFromCombat(java.util.List<? extends net.minecraft.world.entity.LivingEntity> threats) {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        double centerX = this.getX();
        double centerZ = this.getZ();
        if (threats != null && !threats.isEmpty()) {
            centerX = 0.0D;
            centerZ = 0.0D;
            for (net.minecraft.world.entity.LivingEntity threat : threats) {
                centerX += threat.getX();
                centerZ += threat.getZ();
            }
            centerX /= threats.size();
            centerZ /= threats.size();
        }
        double dx = this.getX() - centerX;
        double dz = this.getZ() - centerZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            dx = 0.0D;
            dz = 1.0D;
        } else {
            dx /= length;
            dz /= length;
        }
        for (double radius = 24.0D; radius >= 8.0D; radius -= 4.0D) {
            net.minecraft.core.BlockPos base = net.minecraft.core.BlockPos.containing(
                this.getX() + dx * radius, this.getY() + 1.0D, this.getZ() + dz * radius);
            for (int dy = -3; dy <= 2; dy++) {
                net.minecraft.core.BlockPos pos = base.offset(0, dy, 0);
                if (isSafeStand(level, pos)) {
                    this.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 1.15D);
                    return;
                }
            }
        }
        // 找不到安全点：直接朝反方向冲（和躲 TNT 一样，跑够远再说）
        this.getNavigation().moveTo(this.getX() + dx * 20.0D, this.getY(), this.getZ() + dz * 20.0D, 1.15D);
    }

    /** 居民像玩家一样发一条聊天：<名字> 受击台词（从对白池随机挑；本地化由客户端语言决定）。 */
    private void broadcastHitTest(ServerLevel level, DamageSource source) {
        if (!(source.getEntity() instanceof Player)) {
            return;
        }
        // 受击文本 1 秒最多一句（主人 2026-11-21）：连打不会刷屏
        if (this.tickCount - this.lastHitChatTick < 20) {
            return;
        }
        this.lastHitChatTick = this.tickCount;
        // 统一走 ResidentChat：自动加名字 + 聊天头像标记，文本从对白池里随机挑
        ResidentChat.say(level, this, ResidentChatPool.pick(
            ResidentChatPool.Kind.HIT, this.getRandom()));
    }

    /**
     * 被玩家打中：心情 -5%（v0.2.11 主人定），头上冒村民那种生气粒子。
     *
     * <p>两个效果共用一个 1 秒冷却（主人要求"减少心情值跟粒子动画都是冷却一秒一次"），
     * 所以连打也不会瞬间把心情打空，但持续挨打会明显掉心情。</p>
     */
    private void hurtMood(ServerLevel level) {
        if (this.tickCount - this.lastMoodHurtTick < 20) {
            return;
        }
        this.lastMoodHurtTick = this.tickCount;
        this.setMood(this.mood() - 5.0F);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER,
            this.getX(), this.getY() + this.getBbHeight() + 0.35D, this.getZ(),
            1, 0.25D, 0.1D, 0.25D, 0.0D);
    }

    /** 死亡时像玩家一样在聊天栏报一条死亡信息：名字 + 死因全由原版死亡消息提供。 */
    @Override
    public void die(DamageSource source) {
        ServerLevel serverLevel = this.level() instanceof ServerLevel level ? level : null;
        if (serverLevel != null) {
            // getLocalizedDeathMessage 会自动套原版 death.attack.* 文本（含凶手名字），不带聊天头像
            ResidentChat.announce(serverLevel, source.getLocalizedDeathMessage(this));
        }
        net.minecraft.core.BlockPos bed = this.bedPos;
        super.die(source);
        if (serverLevel != null) {
            if (bed != null) {
                // 有床：掉落/保留处理完之后留一份完整快照，5 秒后在床边以同一个 UUID 复活
                CompoundTag snapshot = this.saveWithoutId(new CompoundTag());
                ResidentRevival.schedule(serverLevel, this.getUUID(), snapshot, bed,
                    ResidentRevival.REVIVE_DELAY_TICKS);
            }
            // 有床的居民会复活、继续上班；流浪居民永久消失 → 员工栏也要把他拿掉（2026-11-22）
            BedBinder.onResidentDied(serverLevel, this.getUUID(), bed == null);
        }
    }

    /**
     * 死亡不掉落开着、且这个居民有床 → 物品不掉、跟随复活留在背包里（主人 2026-11-20）。
     * 流浪居民不受这条影响：必定掉落全部物品。
     */
    private boolean keepsInventoryOnDeath() {
        return this.bedPos != null && this.level() instanceof ServerLevel level
            && level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY);
    }

    @Override
    protected void dropEquipment() {
        if (this.keepsInventoryOnDeath()) {
            return;
        }
        super.dropEquipment();
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
            ResidentSkins.pickRandom(this.random).ifPresent(this::applySkin);
        }
        if (this.random.nextFloat() < LEFT_HANDED_CHANCE) {
            this.setLeftHanded(true);
        }
        // 新居民（刷怪蛋 / 招募 / 自然生成）立刻刷新成员列表：
        // 以前只在玩家登录时同步，刷怪蛋生成的要重进世界才看得到（2026-11-22 修）
        if (!level.getLevel().isClientSide()) {
            BedBinder.requestSync();
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
        if (this.distanceToSqr(item) > radius * radius) {
            return false;
        }
        // 有工作区的员工：只捡工作区内的东西，别被远处的掉落物引出去（主人 2026-11-20）
        if (this.level() instanceof ServerLevel serverLevel) {
            WorkZone zone = WorkZoneData.get(serverLevel).findByEmployee(this.getUUID().toString());
            if (zone != null && !zone.containsBody(item)) {
                return false;
            }
        }
        return true;
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

    /**
     * 统一入口：设置皮肤名，并按文件名末尾 {@code _slim} 切成人 slim / wide
     * （2026-11-22 主人要求）。{@code finalizeSpawn} / {@code pinSkin} 等所有随机入口都走这里，
     * 不要各写一遍；YSM 模型保持不动。
     */
    public void applySkin(String name) {
        this.setSkinName(name);
        if (name != null && this.modelType() != MODEL_YSM) {
            this.setModelType(name.endsWith("_slim") ? MODEL_SLIM : MODEL_WIDE);
        }
    }

    /** 0 = 自动（按皮肤图判断 slim/wide），1 = 强制 wide，2 = 强制 slim。 */
    public int modelType() {
        return this.entityData.get(DATA_MODEL);
    }

    public void setModelType(int model) {
        // 5 = YSM 模型，超出 kid 范围也要允许
        int clamped = model == MODEL_YSM
            ? MODEL_YSM : Math.clamp(model, MODEL_WIDE, MODEL_KID_SLIM);
        this.entityData.set(DATA_MODEL, clamped);
        this.refreshDimensions();
    }

    /** YSM 模型名（模型类型 = {@link #MODEL_YSM} 时使用）。 */
    public String ysmModel() {
        return this.entityData.get(DATA_YSM_MODEL);
    }

    public void setYsmModel(String name) {
        this.entityData.set(DATA_YSM_MODEL, name == null ? "" : name);
    }

    /** kid 模型：碰撞箱只有一格高。 */
    public boolean isKid() {
        return this.modelType() >= MODEL_KID_WIDE && this.modelType() != MODEL_YSM;
    }

    /**
     * 尺寸 + **骑乘定位点**。
     *
     * <p>v0.2.3：和玩家对齐坐骑高度。原版乘客的位置是
     * {@code 载具的乘客附着点 - 乘客自己的 VEHICLE 附着点}；玩家在 {@code STANDING_DIMENSIONS} 里
     * 把自己的 VEHICLE 附着点设成 {@code (0, 0.6, 0)}（= 1.8 身高 / 3），而普通生物默认落在脚底
     * {@code (0,0,0)} —— 所以乘客位置会**比玩家高 0.6 格**，坐在船/矿车/马背上就是浮着的。
     * 这里照玩家写：{@code (0, 身高 / 3, 0)}，成人与小孩都自动对齐。</p>
     */
    @Override
    protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        net.minecraft.world.entity.EntityDimensions base = this.isKid()
            ? net.minecraft.world.entity.EntityDimensions.fixed(0.55F, 1.0F)
            : super.getDefaultDimensions(pose);
        return base.withAttachments(EntityAttachments.builder()
            .attach(EntityAttachment.VEHICLE, new Vec3(0.0D, base.height() / 3.0D, 0.0D)));
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

    /** 加减饥饿值（负数 = 消耗）。 */
    public void addHunger(float delta) {
        // 2026-11-23 主人定：**吃饭不再回复心情**（原来每 2 点饥饿 +1% 心情）。
        // 分享食物那条 +5% 是社交奖励，不在这里。
        this.setHunger(this.hunger() + delta);
    }

    /** 饥饿恢复累计到「心情 +1%」的余数（2026-11-23 起不再使用，字段先留着免得旧存档/旧代码引用）。 */
    private float moodFoodAccum;

    /** 饥饿系统（调试命令 / 以后存档迁移用）。 */
    public ResidentHunger hungerSystem() {
        return this.hungerSystem;
    }

    /** 社交系统（T4 食物分享的需求 / 冷却 / 配对）。 */
    public ResidentSocial socialSystem() {
        return this.socialSystem;
    }

    /**
     * 正在吃食物（原版「使用物品」流程，手上有食物）。
     *
     * <p>吃饭走的是原版 {@code startUsingItem} + {@code LivingEntity#tick} 的推进，
     * 所以粒子/音效/时长和玩家完全一致；这里只用来挂三件事：
     * 移速 ×0.2、进食时不举盾、不整理装备。</p>
     */
    public boolean isEatingFood() {
        return this.isUsingItem()
            && !this.getUseItem().isEmpty()
            && this.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD);
    }

    /** 生命值比例（0..1）。要跟最大生命值挂钩的地方一律用这个，别写死数字。 */
    public float healthRatio() {
        float max = this.getMaxHealth();
        return max <= 0.0F ? 0.0F : this.getHealth() / max;
    }

    /**
     * 是不是处在战斗中：有目标（自己找的怪 / 来援助的目标 / 被仇恨的目标），或者最近被谁打过。
     *
     * <p>主人在意这个是为了「战斗时禁止进食」——所以这里宁可宽一点，
     * 被打了 30 秒内都算还在战斗，免得刚挨完打就站着啃面包。</p>
     */
    public boolean isInCombat() {
        net.minecraft.world.entity.LivingEntity target = this.getTarget();
        if (target != null && target.isAlive()) {
            return true;
        }
        net.minecraft.world.entity.LivingEntity attacker = this.getLastHurtByMob();
        return attacker != null && attacker.isAlive()
            && this.tickCount - this.getLastHurtByMobTimestamp() < 600;
    }

    /** 战斗血量低到该逃了（主人定的 75%，按比例算，方便以后改最大生命值）。 */
    public boolean shouldFleeFromCombat() {
        return this.healthRatio() <= LOW_HEALTH_FLEE_RATIO;
    }

    /**
     * 需要「逃跑 + 进食」来续命：血量 ≤75% **且身上没有生命恢复效果**。
     *
     * <p>主人 2026-11-19 定的节奏：掉到阈值 → 逃跑拉开距离 → 吃一块 → **立刻回战斗**；
     * 等恢复效果过去、血还是 ≤75% → 再进入这一轮。所以恢复效果本身就是闸门。</p>
     */
    public boolean needsCombatRecovery() {
        return this.shouldFleeFromCombat()
            && !this.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION);
    }

    /**
     * 吃完的收尾。
     *
     * <p>原版 {@code ItemStack#finishUsingItem} 只给**玩家**加饥饿（{@code FoodData}），
     * 生物只是扣物品 + 应用药水效果 —— 所以居民的饥饿值要在这里自己补：营养 × 5。</p>
     * <p>同时补上主人在意的两件收尾表现：完食音效 + 极少量碎屑（吃完的瞬间，不是持续进食的）。</p>
     */
    @Override
    protected void completeUsingItem() {
        ItemStack used = this.getUseItem().copy();
        super.completeUsingItem();
        if (!used.isEmpty() && used.has(net.minecraft.core.component.DataComponents.FOOD)) {
            this.hungerSystem.onAte(used);
            this.applyFoodSaturationEffect(used);
            // 完食音效 + 极少量碎屑（持续进食中的音效/碎屑是原版 LivingEntity 每 4 tick 播的）
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                net.minecraft.sounds.SoundEvents.PLAYER_BURP,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.5F,
                0.9F + this.random.nextFloat() * 0.2F);
            if (this.level() instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(
                    new net.minecraft.core.particles.ItemParticleOption(
                        net.minecraft.core.particles.ParticleTypes.ITEM, used),
                    this.getX(), this.getY() + this.getBbHeight() * 0.6D, this.getZ(),
                    3, 0.1D, 0.1D, 0.1D, 0.0D);
            }
        }
    }

    /**
     * 饱腹度 → 恢复药水（主人 2026-11-19 最终版）。
     *
     * <p>**1 点饱腹度 = 1 秒生命恢复 II**（隐藏粒子），不管食物大小都是 II 级、不再分级/减半。
     * 例：面包 6 点 → 6 秒 II；牛扒 12.8 → 13 点 → 13 秒 II。</p>
     * <p>读 {@code FoodProperties#saturation()}，所以任何模组食物自动兼容。
     * 注意是**真的药水效果**（`addEffect`），不是代码里自己回血 —— Jade 里要能看见它就说明生效了。</p>
     */
    private void applyFoodSaturationEffect(ItemStack food) {
        net.minecraft.world.food.FoodProperties properties =
            food.get(net.minecraft.core.component.DataComponents.FOOD);
        if (properties == null) {
            return;
        }
        int seconds = Math.round(properties.saturation());
        if (seconds <= 0) {
            return;
        }
        this.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.REGENERATION,
            seconds * 20, 1, false, false));
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

    // ---- 自然语言指令（v0.3.0，只存不执行） ----

    /** 居民身上的人话指令（空 = 没有）。目前没有任何 AI 读它，纯为未来的「任务规划层」铺存档格式。 */
    public String instructions() {
        return this.entityData.get(DATA_INSTRUCTIONS);
    }

    /** 设置指令（截到 512 字，避免同步包过大）。传入 null = 清空。 */
    public void setInstructions(@javax.annotation.Nullable String text) {
        String value = text == null ? "" : text.strip();
        if (value.length() > 512) {
            value = value.substring(0, 512);
        }
        this.entityData.set(DATA_INSTRUCTIONS, value);
    }

    /** 当前工作（idle 闲逛 / follow 跟随 / stand 站立）。 */
    public String jobKey() {
        return this.entityData.get(DATA_JOB);
    }

    /** 临时工作状态（crafting 等），空字符串表示没有。 */
    public String activityKey() {
        return this.entityData.get(DATA_ACTIVITY);
    }

    /** 设置/清空临时工作状态；{@code null} = 清空。 */
    public void setActivity(@javax.annotation.Nullable String activity) {
        this.entityData.set(DATA_ACTIVITY, activity == null ? "" : activity);
    }

    /** 浮窗 / Jade 显示用：临时工作优先，否则显示手册模式。 */
    public String displayJobKey() {
        String activity = this.activityKey();
        return activity.isEmpty() ? this.jobKey() : activity;
    }

    /** 诊断用：手工 goal 现在是不是正在跑。 */
    public boolean isCraftingGoalRunning() {
        for (net.minecraft.world.entity.ai.goal.WrappedGoal wrapped : this.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof com.worldplayers.resident.ai.ResidentCraftingGoal && wrapped.isRunning()) {
                return true;
            }
        }
        return false;
    }

    public void setJobKey(String job) {
        // 从「跟随 / 站立」切回「闲逛」= 解除工作场所绑定（跟随/站立本身不解绑）
        if (JOB_IDLE.equals(job)) {
            this.workSite = null;
        }
        // 站立 = 站岗：进模式时记住"站哪儿"，去援助/被推走之后靠这个点回去（主人 2026-11-20）
        if (JOB_STAND.equals(job)) {
            if (this.standHome == null) {
                this.standHome = this.blockPosition();
            }
        } else {
            this.standHome = null;
        }
        this.entityData.set(DATA_JOB, job);
    }

    /** 站岗点（站立模式的位置，援助回来用；非站立模式为 null）。 */
    @javax.annotation.Nullable
    public net.minecraft.core.BlockPos standHome() {
        return this.standHome;
    }

    public void setStandHome(@javax.annotation.Nullable net.minecraft.core.BlockPos pos) {
        this.standHome = pos;
    }

    /** 自己的闲逛锚点（只在世界里没有殖民地要素时才用；见 {@link ColonyAnchors#personalAnchor}）。 */
    @javax.annotation.Nullable
    public net.minecraft.core.BlockPos strollAnchor() {
        return this.strollAnchor;
    }

    public void setStrollAnchor(@javax.annotation.Nullable net.minecraft.core.BlockPos pos) {
        this.strollAnchor = pos;
    }

    // ---- 娱乐社交（2026-11-22，14 号文档 2.8） ----
    // 冷却记在居民身上（不进 NBT）：+2% 心情 / 40 秒，比分享食物的 10 秒长。

    private int entertainmentSocialUntil;

    /** 娱乐社交的冷却好了没。 */
    public boolean entertainmentSocialReady() {
        return this.tickCount >= this.entertainmentSocialUntil;
    }

    /** 开始 40 秒娱乐社交冷却（双方各调一次）。 */
    public void startEntertainmentSocialCooldown() {
        this.entertainmentSocialUntil = this.tickCount + 20 * 40;
    }

    /**
     * 战斗是不是已经结束（没有目标、挨打也过去 5 秒以上）。
     *
     * <p>比 {@link #isInCombat()} 宽进严出里的那个 30 秒宽限期短得多：那个宽限期是给
     * 「刚挨完打先别啃面包」用的，战后归位不需要等那么久。</p>
     */
    public boolean combatSettled() {
        net.minecraft.world.entity.LivingEntity target = this.getTarget();
        if (target != null && target.isAlive()) {
            return false;
        }
        net.minecraft.world.entity.LivingEntity attacker = this.getLastHurtByMob();
        return attacker == null || !attacker.isAlive()
            || this.tickCount - this.getLastHurtByMobTimestamp() >= 100;
    }

    /** 固定工作场所（现在只是预留，还没有工作系统去绑定它）。 */
    @javax.annotation.Nullable
    public net.minecraft.core.BlockPos workSite() {
        return this.workSite;
    }

    public void setWorkSite(@javax.annotation.Nullable net.minecraft.core.BlockPos pos) {
        this.workSite = pos;
    }

    // ---- 床（招募绑定 / 死亡复活） ----

    /** 绑定的床（头或脚那一格）；null = 流浪居民。 */
    @javax.annotation.Nullable
    public net.minecraft.core.BlockPos bedPos() {
        return this.entityData.get(DATA_BED).orElse(null);
    }

    public void setBedPos(@javax.annotation.Nullable net.minecraft.core.BlockPos pos) {
        this.bedPos = pos == null ? null : pos.immutable();
        this.entityData.set(DATA_BED, java.util.Optional.ofNullable(this.bedPos));
    }

    /** 有床的居民死亡会复活（主人 2026-11-20）。 */
    public boolean hasBed() {
        return this.entityData.get(DATA_BED).isPresent();
    }

    // ---- 通勤（调试棒 / 以后的工作指派） ----
    // 注意：目标不存 NBT —— 重启后由指令方重新下发；只是临时行为。

    /** 通勤目的地（方块坐标），null = 没有通勤任务。 */
    @javax.annotation.Nullable
    private net.minecraft.core.BlockPos commuteTarget;
    /** 目的地显示名（"调试棒"、以后是工作区域名）。 */
    private String destination = "";

    /** 下达通勤指令：目的地 + 浮窗显示的名字，同时把临时工作状态切成「通勤」。 */
    public void setCommuteTarget(net.minecraft.core.BlockPos pos, String destination) {
        this.commuteTarget = pos;
        this.destination = destination == null ? "" : destination;
        this.setActivity(ResidentJobs.COMMUTE);
        this.entityData.set(DATA_DESTINATION, this.destination);
    }

    @javax.annotation.Nullable
    public net.minecraft.core.BlockPos commuteTarget() {
        return this.commuteTarget;
    }

    /** 清掉通勤目标与显示（到达/超时放弃时调用）。 */
    public void clearCommute() {
        this.commuteTarget = null;
        this.destination = "";
        this.entityData.set(DATA_DESTINATION, "");
        if (ResidentJobs.COMMUTE.equals(this.activityKey())) {
            this.setActivity(null);
        }
    }

    /** 浮窗里"通勤"下面那一行：目的地名字（空字符串 = 不画）。 */
    public String destinationKey() {
        return this.entityData.get(DATA_DESTINATION);
    }

    /**
     * 目的地**显示用的文本**（浮窗 / 名字牌 / Jade 共用）。
     *
     * <p>「床」是内部标记 {@link #BED_DESTINATION}，按玩家语言翻译；其它目的地（工作区名）
     * 是玩家自己起的名字，原样显示。</p>
     */
    public net.minecraft.network.chat.Component destinationText() {
        String key = this.destinationKey();
        if (BED_DESTINATION.equals(key) || BED_DESTINATION_LEGACY.equals(key)) {
            return net.minecraft.network.chat.Component.translatable(
                "gui.worldplayers.destination.bed");
        }
        return net.minecraft.network.chat.Component.literal(key);
    }

    /**
     * 进入工作区的工作状态（主人 2026-11-20）：浮窗/Jade 显示「农作 / 道路维护」，
     * 底下那行显示工作地点名字，而不是「闲逛」。
     */
    public void setWorkActivity(String jobId, String placeName) {
        this.setActivity(jobId);
        this.entityData.set(DATA_DESTINATION, placeName == null ? "" : placeName);
    }

    /**
     * 所有「工作区临时状态」（含暂停变体）。
     *
     * <p>离开工作区 / goal 结束时统一清掉。**2026-11-22 修**：以前手写白名单漏了挖掘 / 填充的
     * 状态，导致工程完工删区后员工一直挂在「填充工：缺少方块」上退不出来。</p>
     */
    private static final java.util.Set<String> WORK_ACTIVITIES = java.util.Set.of(
        ResidentJobs.FARMING, ResidentJobs.FARMING_PAUSED, ResidentJobs.ROADWORK,
        ResidentJobs.LUMBER, ResidentJobs.LUMBER_PAUSED,
        ResidentJobs.RANCHING, ResidentJobs.FISHING,
        ResidentJobs.MINER, ResidentJobs.MINER_PAUSED, ResidentJobs.MINER_BAG_FULL,
        ResidentJobs.FILLER, ResidentJobs.FILLER_PAUSED,
        ResidentJobs.HANDLING_BAG,
        // 娱乐（2026-11-22）：前往途中的「闲逛」与抵达后的「娱乐」都算临时状态
        ResidentJobs.STROLLING, ResidentJobs.ENTERTAINING);

    /** 离开工作区：把临时工作状态与地点清掉，回到 {@link #jobKey()}（通常是闲逛）。 */
    public void clearWorkActivity() {
        String activity = this.activityKey();
        if (WORK_ACTIVITIES.contains(activity)) {
            this.setActivity(null);
            this.entityData.set(DATA_DESTINATION, "");
        }
    }

    /**
     * 期望的移动速度（通勤开始/结束时切换；平时保持游荡基础速度）。
     *
     * <p>进食期间实际速度会再乘 {@link #EATING_SPEED_FACTOR}（主人要求 0.2 倍），
     * 但「期望值」不变 —— 吃完自动恢复，不用各处再调一遍。</p>
     *
     * <p>最终速度 = 传入值、**体力档速度**、饥饿封顶（≤30% 按红脸档）三者取**最慢**：
     * 心情/体力/饥饿任何一项差都会拖慢，任何一项好都不能抵消另一项差（主人 2026-11-21）。</p>
     */
    public void applyMovementSpeed(double value) {
        this.wantedMovementSpeed = value;
        // 体力档位：阈值与速度照搬心情（绿 5 / 黄 4 / 红 75%）
        double wanted = Math.min(value, staminaMovementSpeed(this.stamina()));
        // 饥饿 ≤30%：行走速度等同于红脸心情档（主人 2026-11-20）
        if (this.hunger() <= LOW_HUNGER_SPEED_THRESHOLD) {
            wanted = Math.min(wanted, lowMoodMovementSpeed());
        }
        double actual = this.isEatingFood() ? wanted * EATING_SPEED_FACTOR : wanted;
        net.minecraft.world.entity.ai.attributes.AttributeInstance speed =
            this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && Math.abs(speed.getBaseValue() - actual) > 1.0E-4D) {
            speed.setBaseValue(actual);
        }
    }

    /** 按「期望速度 + 当前进食状态」重算一次属性（进食开始/结束时调用）。 */
    public void refreshMovementSpeed() {
        this.applyMovementSpeed(this.wantedMovementSpeed);
    }

    private void pinSkin(ServerLevel level) {
        if (!this.skinName().isEmpty()) {
            return;
        }
        ResidentSkins.pickRandom(this.random).ifPresent(this::applySkin);
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
        // 手持「可喂食」物品（金苹果一类，tag 可扩展）→ 右键喂食，不打开背包（主人 2026-11-20）
        ItemStack held = player.getItemInHand(hand);
        if (held.is(com.worldplayers.registry.WPItems.FEEDABLE_RESIDENT)) {
            return this.feedResident(player, held);
        }
        if (this.level().isClientSide()) {
            return InteractionResult.sidedSuccess(true);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(
                new SimpleMenuProvider(
                    (containerId, playerInventory, openingPlayer) ->
                        new com.worldplayers.menu.ResidentMenu(containerId, playerInventory, this),
                    this.getDisplayName()),
                buffer -> {
                    buffer.writeVarInt(this.getId());
                    buffer.writeBoolean(false);
                });
        }
        return InteractionResult.sidedSuccess(false);
    }

    /**
     * 右键喂食（服务端）：补 20% 饥饿 + 播一次完食音效 + 给食物自带的全部增益效果，
     * 然后消耗 1 个物品（创造模式不消耗）。
     */
    private InteractionResult feedResident(Player player, ItemStack stack) {
        if (this.level().isClientSide()) {
            return InteractionResult.sidedSuccess(true);
        }
        if (this.isDeadOrDying() || !this.isAlive()) {
            return InteractionResult.PASS;
        }
        // 饥饿没满才补（主人：「补充 20% 饥饿值（若未满）」）
        if (this.hunger() < 100.0F) {
            this.addHunger(20.0F);
            this.inventoryChanged();
        }
        // 金苹果 / 附魔金苹果自带的药水效果，照原版概率给
        net.minecraft.world.food.FoodProperties food =
            stack.get(net.minecraft.core.component.DataComponents.FOOD);
        if (food != null) {
            for (net.minecraft.world.food.FoodProperties.PossibleEffect possible : food.effects()) {
                if (this.random.nextFloat() < possible.probability()) {
                    this.addEffect(new net.minecraft.world.effect.MobEffectInstance(possible.effect()));
                }
            }
        }
        if (this.level() instanceof ServerLevel level) {
            level.playSound(null, this.getX(), this.getY(), this.getZ(),
                net.minecraft.sounds.SoundEvents.PLAYER_BURP, this.getSoundSource(), 0.8F, 1.0F);
            level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(
                    net.minecraft.core.particles.ParticleTypes.ITEM, stack.copy()),
                this.getX(), this.getEyeY() - 0.2D, this.getZ(), 5, 0.08D, 0.08D, 0.08D, 0.0D);
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
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

    /**
     * 对外显示名统一带「（流浪）」后缀（主人 2026-11-20）：Jade、死亡消息、菜单标题都走这里。
     *
     * <p>名字牌和改名栏位用的是 {@link #getCustomName()}，所以那里保持裸名字。</p>
     */
    @Override
    public Component getDisplayName() {
        return ResidentDisplayNames.display(this);
    }

    /** 有些模组/原版 UI 走 {@link #getName()} 而不是 getDisplayName，这里也统一带「（流浪）」。 */
    @Override
    public Component getName() {
        return ResidentDisplayNames.display(this);
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
        tag.putString("YsmModel", this.ysmModel());
        tag.putInt(TAG_EXPERIENCE, this.experiencePoints);
        tag.putFloat(TAG_HUNGER, this.hunger());
        tag.putFloat(TAG_STAMINA, this.stamina());
        tag.putFloat(TAG_MOOD, this.mood());
        tag.putString(TAG_JOB, this.jobKey());
        tag.putString("Instructions", this.instructions());
        if (this.workSite != null) {
            tag.putLong(TAG_WORK_SITE, this.workSite.asLong());
        }
        if (this.standHome != null) {
            tag.putLong("StandHome", this.standHome.asLong());
        }
        if (this.strollAnchor != null) {
            tag.putLong("StrollAnchor", this.strollAnchor.asLong());
        }
        if (this.bedPos != null) {
            tag.putLong(TAG_BED, this.bedPos.asLong());
        }
        tag.putBoolean("LeftHanded", this.isLeftHanded());
        tag.putInt("Duty", this.pickupDuty().ordinal());
        this.schedule.save(tag);
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
        this.setYsmModel(tag.contains("YsmModel") ? tag.getString("YsmModel") : "");
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
        this.setInstructions(tag.getString("Instructions"));
        this.workSite = tag.contains(TAG_WORK_SITE)
            ? net.minecraft.core.BlockPos.of(tag.getLong(TAG_WORK_SITE))
            : null;
        // 站岗点：NBT 里的优先（setJobKey 已经读过一次，这里覆盖）
        this.standHome = tag.contains("StandHome", net.minecraft.nbt.Tag.TAG_LONG)
            ? net.minecraft.core.BlockPos.of(tag.getLong("StandHome"))
            : (JOB_STAND.equals(this.jobKey()) ? this.blockPosition() : null);
        this.strollAnchor = tag.contains("StrollAnchor", net.minecraft.nbt.Tag.TAG_LONG)
            ? net.minecraft.core.BlockPos.of(tag.getLong("StrollAnchor"))
            : null;
        this.setBedPos(tag.contains(TAG_BED, net.minecraft.nbt.Tag.TAG_LONG)
            ? net.minecraft.core.BlockPos.of(tag.getLong(TAG_BED))
            : null);
        this.setLeftHanded(tag.getBoolean("LeftHanded"));
        int duty = tag.getInt("Duty");
        PickupDuty[] duties = PickupDuty.values();
        this.setPickupDuty(duties[Math.max(0, Math.min(duties.length - 1, duty))]);
        this.schedule.load(tag);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        if (this.keepsInventoryOnDeath()) {
            return;
        }
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
