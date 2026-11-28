package com.worldplayers.resident.ai;

import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentChat;
import com.worldplayers.resident.ResidentChatPool;
import com.worldplayers.resident.ResidentDoors;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentInventory;
import com.worldplayers.resident.ResidentJobs;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import com.worldplayers.resident.project.ProjectQueue;
import com.worldplayers.resident.project.ProjectRules;
import com.worldplayers.resident.project.ProjectStance;
import com.worldplayers.resident.tree.TreeFarmGrid;
import com.worldplayers.resident.tree.TreeFarmIndex;
import com.worldplayers.resident.tree.TreeSpecies;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 工作模式（主人 2026-11-20）：绑定了工作区、且处于「闲逛」状态的居民，闲逛会被这个 goal 取代。
 *
 * <p>状态：</p>
 * <ul>
 *   <li>不在工作区域里 → **通勤**，浮窗/Jade 底下显示工作区名字，一路走过去；</li>
 *   <li>在工作区域里 → **农作**（农田），底下不显示地点名字。</li>
 * </ul>
 *
 * <p>农田工作（每次劳作冷却 1 秒，操作范围 4.5 格 + 直线视野）：</p>
 * <ol>
 *   <li>区域内成熟作物 → 走过去 → 挥手 → 产物进背包（少掉 1 个种子）→ 破坏音效/粒子 → 作物回到 0% 生长；</li>
 *   <li>没有成熟作物就找最近的可耕地 → 走过去 → 主手持锄 → 锄成耕地 → 锄地音效 → 耐久 -1（附魔耐久照常生效）；</li>
 *   <li>两者都没有 → 在区域内随机走动（不离开区域、不传送）。</li>
 * </ol>
 */
public class ResidentWorkGoal extends Goal {
    /**
     * 劳作冷却：**心情和体力各算一档，取更慢的那一档**（主人 2026-11-21 定）。
     *
     * <p>心情（2026-11-20 定）：红脸 3 秒 / 黄脸 2 秒 / 绿脸 1 秒；</p>
     * <p>体力（2026-11-21 加）：同样 绿（&ge;75）20 tick / 黄（&ge;50）40 tick / 红（&lt;50）60 tick。</p>
     * <p>两个数值不一样时**取更高的冷却**（= 更慢），也就是「又累又丧」时按最差的那项算，
     * 不会因为心情好就无视体力见底。</p>
     */
    static int laborCooldown(ResidentEntity resident) {
        int base = Math.max(vitalityCooldown(resident.mood()), vitalityCooldown(resident.stamina()));
        // 14 号文档 4.8 / 数值定稿：体力 >75 **且** 心情 >75 → 工作速度 ×2（间隔减半）
        if (doubleSpeed(resident)) {
            return Math.max(1, base / 2);
        }
        return base;
    }

    /** 工作速度奖励的判定：**体力和心情都 ≥75**（14 号文档问题清单写的是 `>=75%`）。 */
    static boolean doubleSpeed(ResidentEntity resident) {
        return resident.mood() >= 75.0F && resident.stamina() >= 75.0F;
    }

    /** 一次劳动实际扣的体力：加倍速时**减半**（不是翻倍，14 号文档 4.8）。 */
    static float staminaCost(ResidentEntity resident, float base) {
        return doubleSpeed(resident) ? base * 0.5F : base;
    }

    /**
     * 扣一次工具耐久：工作 ×2 奖励时**损耗减半**（2026-11-23 主人定）。
     *
     * <p>用「50% 概率这次不扣」实现，长期平均正好减半；不引入额外的耐久计数状态
     * （那个东西还得进 NBT，代价不值）。</p>
     */
    static void hurtTool(ResidentEntity resident, ItemStack tool) {
        if (tool.isEmpty()) {
            return;
        }
        if (doubleSpeed(resident) && resident.getRandom().nextInt(2) == 0) {
            return;
        }
        tool.hurtAndBreak(1, resident, EquipmentSlot.MAINHAND);
    }

    /** 单个数值（心情 / 体力，0..100）→ 冷却 tick。 */
    private static int vitalityCooldown(float value) {
        if (value >= 75.0F) {
            return 20;
        }
        if (value >= 50.0F) {
            return 40;
        }
        return 60;
    }
    /** 操作范围：4.5 格。 */
    static final double RANGE = 4.5D;
    /** 搜索目标的最大距离（区域内）。 */
    private static final double SEARCH_RANGE = 32.0D;
    /** 每次劳作消耗的体力（主人 2026-11-21 定：采收 / 耕地 / 播种都算一次，各 −0.1）。 */
    static final float STAMINA_PER_LABOR = 0.1F;
    /** 扫描节流：没目标时每 20~40 tick 才扫一次区域（T10 第 4 条）。 */
    private static final int SCAN_COOLDOWN_MIN = 20;
    private static final int SCAN_COOLDOWN_MAX = 40;
    /** 工具搜寻冷却：30 秒（主人 2026-11-21 定，所有工具共用）。 */
    private static final int TOOL_SEARCH_CD = 20 * 30;
    /** 自订区域（2026-11-25）：每 6 秒扣 0.1 点体力（主人定）。 */
    private static final int CUSTOM_STAMINA_INTERVAL = 20 * 6;
    private static final float CUSTOM_STAMINA_COST = 0.1F;
    /** 自订区域「可站格只有一格」的扫描节流（5 秒）。 */
    private static final int CUSTOM_SCAN_INTERVAL = 100;
    /** 区域体积超过这个数就不做「只有一格」的全扫（按多格处理，直接游荡）。 */
    private static final long CUSTOM_SCAN_MAX_VOLUME = 32_768L;
    /** 数据包可加的「模组种子」逃生口（T11）。 */
    public static final TagKey<Item> PLANTABLE_SEEDS_TAG =
        TagKey.create(Registries.ITEM, WorldPlayers.id("plantable_seeds"));

    private final ResidentEntity resident;
    /** 下一次重新选点的倒计时。 */
    private int cooldown;
    private int doorTicks;
    private int laborCooldown;
    /** 当前要操作 / 走向的方块（作物或泥土）。 */
    @Nullable
    private BlockPos target;
    /** 不可达目标黑名单（T10 第 3 条）：寻路验证过到不了的格子，一段时间内不再试。 */
    private final Set<BlockPos> unreachable = new HashSet<>();
    /**
     * 黑名单的年龄（2026-11-23 第 9 轮）：以前 only clear on {@code start()}，
     * 树场被树叶 / 树苗塞满时「紧邻没有可站格」会把所有树一次一次全判成不可达，
     * 员工从此只在外圈 {@link #wander}，直到玩家切换一次跟随 / 站立（= goal 重启）。
     * 现在每 {@link #UNREACHABLE_TTL} tick 清空一次，地形变了会自己恢复。
     */
    private int unreachableAge;
    /** 树场找树的节流：一次扫不到就歇一会儿，别每 tick 对每棵树跑寻路。 */
    private int treeRetryCd;
    /** 扫描节流倒计时（T10 第 4 条）。 */
    private int scanCooldown;
    /** 工具搜寻冷却：每次真的翻背包找工具后，30 秒内不再翻（无论有没有）。 */
    private int toolSearchCd;
    // ---- 自订区域（2026-11-25） ----
    /** 「可站格只有一格」的扫描节流倒计时。 */
    private int customScanCd;
    /** 已经扫过这一轮（避免刚上班时每 tick 重扫）。 */
    private boolean customOnlyKnown;
    /** 区域里唯一的可站格；null = 不止一格 / 太大没扫 / 没有可站格。 */
    @Nullable
    private BlockPos customOnlySpot;
    /** 自订区域扣体力的倒计时（6 秒）。 */
    private int customStaminaCd;
    /** 上次翻背包找的工具种类与结果（冷却期间直接用）。 */
    @Nullable
    private ToolNeed toolCachedType;
    private boolean toolCachedResult;
    /** 上一次设置的状态（只在变化时写同步数据）。 */
    private String lastMode = "";
    @Nullable
    private WorkZone zone;
    /** 通勤进区时缓存的落脚点（找不到点时不每 tick 全扫区域）。 */
    @Nullable
    private BlockPos commuteSpot;
    /** 落脚点缓存刷新倒计时（40 tick 重挑一次，避免死盯一个走不到的点）。 */
    private int commuteSpotAge;
    /** 通勤寻路重试节流。 */
    private int commuteRetryCd;
    /** 「这一脚是工作 goal 自己走的」还剩多少 tick（社交 / 拾取等别的 goal 用同一套导航时别误伤）。 */
    private int ownMoveTicks;

    // ---- 树场（T7，2026-11-21 定稿规则） ----

    /** 正在砍的那棵树：树干底 + 共享领取（多人平摊同一份剩余方块）。 */
    @Nullable
    private TreeFarmIndex.Claim lumberClaim;
    /** 每格破坏的间隔倒计时（原木 12 tick / 树叶 5 tick，规则 23）。 */
    private int breakCooldown;
    /** 背包满的探测倒计时 / 暂停标志（规则 32）。 */
    private int bagCheckCd;
    private boolean bagPaused;
    /** 当前要清掉的违规树苗。 */
    @Nullable
    private BlockPos pendingSapling;
    /** 当前要砍的树干底（走过去之前）。 */
    @Nullable
    private BlockPos pendingTree;
    /** 树干 / 树苗的站位缓存（2026-11-23 第 9 轮：树场外扩找站位的中间结果）。 */
    @Nullable
    private BlockPos pendingStand;
    /** 站位缓存对应的目标方块（目标换了就重算）。 */
    @Nullable
    private BlockPos pendingStandFor;
    /** 当前要种的槽位（单株）或 2×2 组锚点。 */
    @Nullable
    private BlockPos pendingPlant;

    // ---- 挖掘 / 填充工程（v0.2.15，规格见 12 号文件） ----

    /** 工程站位点（区域外环的安全格，规格 5.2）。 */
    @Nullable
    private BlockPos projectStance;
    /** 这一轮是否已经「开工」：到达站位 / 中断返回后要重建队列。 */
    private boolean workStarted;
    /** 工程喊话冷却（缺工具 / 缺方块 / 没站位共用 30 秒）。 */
    private int projectChatCd;
    /** 上一次推进度包的游戏时间（节流 5 秒）。 */
    private long lastProgressTick;
    /** 队列暂时没格可领（被同区域别人领了）时的重试倒计时，别每 tick 全扫一遍。 */
    private int projectRetryCd;

    // ---- 牧场（v0.3.0）：动作在 RanchWork 里，循环在区域级 RanchCycle 里 ----
    private final RanchWork ranch;

    // ---- 渔场（v0.3.0，15 号规则）：动作 / 状态机在 FisheryWork 里，解析与缓存在 fishery 包里 ----
    private final FisheryWork fishery;

    public ResidentWorkGoal(ResidentEntity resident) {
        this.resident = resident;
        this.ranch = new RanchWork(this, resident);
        this.fishery = new FisheryWork(this, resident);
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        // 显式通勤指令（调试棒，以后是日程）优先：有目标就让位，别用"农作"把它压回去。
        // 通勤 goal 的优先级本来就更低（2 < 5），它抢不到 MOVE 是因为这个 goal 一直占着，
        // 所以这里必须主动退让；目标一清空（到达/超时）工作自动恢复。
        if (this.resident.commuteTarget() != null) {
            return false;
        }
        // 体力见底（≤10%）：累瘫了，先回床，不干活（主人 2026-11-21）；
        // 但「打断 30 秒都回不到床」已经放弃这轮睡觉时，照常回去工作。
        if (this.resident.stamina() <= ResidentEntity.EXHAUSTED_STAMINA_THRESHOLD
                && !this.resident.bedReturnGivenUp()) {
            return false;
        }
        if (!ResidentEntity.JOB_IDLE.equals(this.resident.jobKey()) || this.resident.isInCombat()) {
            return false;
        }
        // 日程门控（2026-11-23，14 号文档第三节）：只有工作阶段才上班；
        // 自由时间里居民该去游荡 / 娱乐，不能还在工作区干活。
        if (!this.resident.schedule().isWorkPhase()) {
            return false;
        }
        this.zone = WorkZoneData.get(level).findByEmployee(this.resident.getUUID().toString());
        // 道路 / 禁行区域不算"工作场所"（主人 2026-11-20：道路不需要员工）
        if (this.zone != null && (WorkZone.TYPE_ROAD.equals(this.zone.type())
                || WorkZone.TYPE_FORBIDDEN.equals(this.zone.type()))) {
            this.zone = null;
        }
        // 工程区没在跑 / 填充还没选方块 → 不干活，回游荡（规格 5.3）
        if (this.zone != null && this.zone.isProject()
                && (!this.zone.projectRunning()
                    || (this.zone.isFill() && this.zone.projectBlock().isEmpty()))) {
            this.zone = null;
        }
        return this.zone != null;
    }

    /**
     * 工程区被暂停 / 完工删掉时，本 goal 要主动结束（否则会一直占着 MOVE 站着）。
     *
     * <p>顺便把区域刷成存档里的最新版本：工程状态由服务端说了算。</p>
     */
    @Override
    public boolean canContinueToUse() {
        if (this.zone == null || !(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        // 日程到点（工作时间满 10 分钟 / 员工被撤 / 工作区被删）→ 立刻收工
        if (!this.resident.schedule().isWorkPhase()) {
            return false;
        }
        WorkZone current = WorkZoneData.get(level).findByKey(this.zone.key());
        if (current == null) {
            return false;
        }
        if (current.isProject()) {
            this.zone = current;
            return current.hasEmployee(this.resident.getUUID().toString())
                && current.projectRunning()
                && (!current.isFill() || !current.projectBlock().isEmpty());
        }
        // 其它类型照旧走原来的判断（员工被撤、体力见底、通勤指令等都不受影响）
        return super.canContinueToUse();
    }

    @Override
    public void start() {
        this.cooldown = 0;
        this.doorTicks = 0;
        this.laborCooldown = 0;
        this.target = null;
        this.lastMode = "";
        this.scanCooldown = 0;
        this.commuteSpot = null;
        this.commuteSpotAge = 0;
        this.commuteRetryCd = 0;
        this.ownMoveTicks = 0;
        // 每次进入工作重新验一次可达性（地形可能变了）
        this.unreachable.clear();
        this.unreachableAge = 0;
        this.treeRetryCd = 0;
        // 树场：每次上班重建一次槽位表（规则 14③），并清掉上一轮的临时状态
        this.lumberClaim = null;
        this.breakCooldown = 0;
        this.bagPaused = false;
        this.bagCheckCd = 0;
        this.pendingSapling = null;
        this.pendingTree = null;
        this.pendingStand = null;
        this.pendingStandFor = null;
        this.pendingPlant = null;
        this.pendingTicks = 0;
        this.projectStance = null;
        this.workStarted = false;
        this.projectChatCd = 0;
        this.lastProgressTick = 0L;
        this.ranch.reset();
        this.fishery.reset();
        // 自订区域（2026-11-25）：每次上班重新判断「可站格只有一格」
        this.customScanCd = 0;
        this.customOnlyKnown = false;
        this.customOnlySpot = null;
        this.customStaminaCd = CUSTOM_STAMINA_INTERVAL;
        if (this.zone != null && this.zone.isTreeFarm()) {
            TreeFarmIndex.get(this.zone).forceRescan();
        }
        // 渔场（规则 2.2 第 6 条）：员工上班时重建钓点缓存（水可能被动过）
        if (this.zone != null && this.zone.isFishery()) {
            com.worldplayers.resident.fishery.FisheryCache.invalidate(this.zone.key());
        }
    }

    @Override
    public void tick() {
        if (!(this.resident.level() instanceof ServerLevel level) || this.zone == null) {
            return;
        }
        if (--this.doorTicks <= 0) {
            this.doorTicks = 10;
            // 栅栏门统一在 ResidentEntity#tick 里处理（任何状态都会开），这里不用再管
        }
        if (this.laborCooldown > 0) {
            this.laborCooldown--;
        }
        if (this.ownMoveTicks > 0) {
            this.ownMoveTicks--;
        }
        // 黑名单老化：60 秒后重试一遍（树被砍掉 / 树叶消失 / 玩家整理过地形后能自己恢复工作）
        if (++this.unreachableAge >= UNREACHABLE_TTL) {
            this.unreachableAge = 0;
            this.unreachable.clear();
        }

        // 卡方块自救（2026-11-22 主人反馈：砍树时被树叶/原木蒙住脑袋就停工）：
        // 身体两格被占住 / IN_WALL → 先挪到安全落脚点，本 tick 的临时目标全部作废重来。
        // 正在砍的那棵树保留领取（通常人还在区域内），由下一 tick 的 inside 判定决定去留。
        if (this.resident.unstickFromBlocks(level)) {
            this.pendingTree = null;
            this.pendingPlant = null;
            this.pendingSapling = null;
            this.pendingTicks = 0;
            this.projectStance = null;
            this.fishery.reset();
            return;
        }

        // 挖掘 / 填充：员工站在区域**外面**干活（规格 5，不走「进入区域」那套）
        if (this.zone.isProject()) {
            this.tickProject(level);
            return;
        }

        boolean inside = this.zone.inWorkRangeBody(this.resident);
        if (!inside) {
            // 离开工作区域：树场正在砍的那棵树立刻失效（规则 22）
            if (this.lumberClaim != null) {
                TreeFarmIndex.get(this.zone).abandon(this.lumberClaim,
                    this.resident.getUUID());
                this.lumberClaim = null;
            }
            this.pendingSapling = null;
            this.pendingTree = null;
            this.pendingPlant = null;
            // 还没到工作地点 → 通勤状态（底下显示工作区名字）
            this.applyMode(ResidentJobs.COMMUTE, this.zone.name());
            if (this.resident.getNavigation().isDone()) {
                // 2026-11-23 修：以前直接走包围盒中心，区域只有一层时中心在地面方块里 → 走不进去、
                // 只能在边缘来回蹭（玩家看到的「抽搐 / 被弹出去」）。改成走区域内的安全落脚点。
                this.walkIntoZone(level);
            }
            return;
        }

        // 在工作区域里 → 「农作」等状态，底下不显示地点名字
        // 自订区域（2026-11-25）：工作状态显示详情页里写下的文字（空 → 「工作」）
        String job = ResidentJobs.workJobFor(this.zone.type());
        if (this.zone.isCustom()) {
            job = this.zone.statusText().isBlank()
                ? ResidentJobs.CUSTOM : ResidentJobs.literal(this.zone.statusText());
        }
        this.applyMode(job, "");
        // 进区之后恢复「日常基础移速」：通勤阶段的心情档加速只属于赶路，别带进区域内的踱步 / 干活
        if (this.resident.tickCount % 20 == 0) {
            this.resident.applyMovementSpeed(ResidentEntity.BASE_MOVEMENT_SPEED);
        }

        // 2026-11-23 三修（主人：工作期间员工必须待在工作区）：寻路终点跑到区域外就立刻取消。
        // 以前只靠「栅栏门归属」拦门，员工追区域外的动物 / 绕路时还是会开门走出去。
        if (this.guardInside(level)) {
            return;
        }

        if (WorkZone.TYPE_FARM.equals(this.zone.type())) {
            this.tickFarming(level);
        } else if (this.zone.isTreeFarm()) {
            this.tickLumber(level);
        } else if (this.zone.isRanch()) {
            this.ranch.tick(level, this.zone);
        } else if (this.zone.isFishery()) {
            this.fishery.tick(level, this.zone);
        } else if (this.zone.isCustom()) {
            this.tickCustom(level);
        } else {
            this.wander(level);
        }
    }

    @Override
    public void stop() {
        this.resident.getNavigation().stop();
        // 清掉"去上班"这个状态；但**有外部通勤目标时不许动**——那是调试棒/日程下的指令，
        // clearCommute() 会把目标一起清掉，等于把主人的命令吞了。
        if (this.resident.commuteTarget() == null
                && ResidentJobs.COMMUTE.equals(this.resident.activityKey())) {
            this.resident.clearCommute();
        }
        this.resident.clearWorkActivity();
        // 树场：结束工作立刻放掉正在砍的那棵树（规则 22）
        if (this.lumberClaim != null && this.zone != null) {
            TreeFarmIndex.get(this.zone).abandon(this.lumberClaim, this.resident.getUUID());
        }
        this.lumberClaim = null;
        this.pendingSapling = null;
        this.pendingTree = null;
        this.pendingStand = null;
        this.pendingStandFor = null;
        this.pendingPlant = null;
        this.bagPaused = false;
        this.lastMode = "";
        this.target = null;
        // 工程：放掉领取的格子，下一轮重新排队（规格 5.6）
        if (this.zone != null && this.zone.isProject()) {
            ProjectQueue.get(this.zone).releaseAll(this.resident.getUUID());
        }
        this.projectStance = null;
        this.workStarted = false;
        this.ranch.reset();
        this.fishery.reset();
        this.customScanCd = 0;
        this.customOnlyKnown = false;
        this.customOnlySpot = null;
        this.zone = null;
    }

    /** 只在状态真的变化时写同步数据（避免每 tick 刷 entityData）。 */
    private void applyMode(String job, String place) {
        String key = job + "|" + place;
        // 2026-11-23 三修：不能只看 lastMode —— 社交 / 娱乐等 goal 会在工作途中把 activity 清掉或改成别的，
        // 这时 lastMode 没变（还是「农作」），以前就再也不写回去了，玩家看到工作期间的员工显示「游荡」。
        if (key.equals(this.lastMode)
                && job.equals(this.resident.activityKey())
                && place.equals(this.resident.destinationKey())) {
            return;
        }
        this.lastMode = key;
        this.resident.setWorkActivity(job, place);
    }

    // ------------------------------------------------------------------
    // 通用背包满 / 缺资源暂停（12 号规格 §5.8 阶段 B，2026-11-22）
    // ------------------------------------------------------------------

    /**
     * 所有工作区统一的「满背包 / 缺资源」暂停：**原地站着等**，每 100 tick（5 秒）探一次，
     * 有空间 / 有资源就清暂停、视为一次开工复工（农田 / 树场 / 挖掘 / 填充共用）。
     *
     * <p><b>扩展点</b>：以后做仓库类行动（去仓库 / 整理 / 丢东西）时**只改这个方法**——
     * 把「站着等」换成「进入 {@link ResidentJobs#HANDLING_BAG} 行为」即可，
     * 农田 / 树场 / 挖掘 / 填充各自的 tick 一行都不用动。5 秒探测作为兜底保留。</p>
     */
    void handleBagFull(String pausedJob) {
        this.resident.getNavigation().stop();
        this.applyMode(pausedJob, "");
        // 预留：未来的仓库类行动在这里把状态切成 ResidentJobs.HANDLING_BAG（现在只登记、不设置）
    }

    /**
     * 暂停中的 5 秒探测。返回 true = 资源回来了，调用方清 `bagPaused` 并当作一次开工复工。
     *
     * @param hasRoom 各工作区自己的「还有空间 / 有资源」判断
     *                （掉落塞得下 / 背包有空位 / 方块够用）
     */
    boolean tickBagPause(java.util.function.BooleanSupplier hasRoom) {
        if (--this.bagCheckCd > 0) {
            return false;
        }
        this.bagCheckCd = BAG_CHECK_CD;
        return hasRoom.getAsBoolean();
    }

    // ------------------------------------------------------------------
    // 农田
    // ------------------------------------------------------------------

    private void tickFarming(ServerLevel level) {
        if (this.toolSearchCd > 0) {
            this.toolSearchCd--;
        }
        // 通用背包满暂停（阶段 B）：采收失败塞不下时原地站着，每 100 tick 探一次
        if (this.bagPaused) {
            this.handleBagFull(ResidentJobs.FARMING_PAUSED);
            if (this.tickBagPause(() -> this.resident.inventory().hasRoomForAnything())) {
                this.bagPaused = false;
                this.scanCooldown = 0;
            }
            return;
        }
        // 有目标：先走过去，到位再干活
        if (this.target != null) {
            BlockState state = level.getBlockState(this.target);
            boolean harvest = isMatureCrop(state);
            boolean till = isTillable(state);
            boolean plant = !harvest && !till && this.hasSeeds() && isPlantSpot(level, this.target, state);
            if (!harvest && !till && !plant) {
                this.target = null;
                return;
            }
            double distance = this.resident.distanceToSqr(
                this.target.getX() + 0.5D, this.target.getY() + 0.5D, this.target.getZ() + 0.5D);
            if (distance <= RANGE * RANGE && this.hasLineOfSight(level, this.target)) {
                if (this.laborCooldown <= 0) {
                    // 阶段 B：采收前先算掉落，塞不下就暂停（不再把塞不下的种子掉一地）
                    if (harvest && !this.canAcceptAll(Block.getDrops(state, level, this.target,
                            null, this.resident, this.resident.getMainHandItem()))) {
                        this.bagPaused = true;
                        this.bagCheckCd = BAG_CHECK_CD;
                        this.target = null;
                        this.handleBagFull(ResidentJobs.FARMING_PAUSED);
                        return;
                    }
                    boolean worked = harvest
                        ? this.harvest(level, this.target, state)
                        : till ? this.till(level, this.target) : this.plant(level, this.target);
                    if (worked) {
                        // 三种农活同属「一次劳动」：同一套劳动冷却（心情/体力取更慢档）、同样扣 0.1 体力
                        this.laborCooldown = laborCooldown(this.resident);
                        this.resident.setStamina(this.resident.stamina() - staminaCost(this.resident, STAMINA_PER_LABOR));
                        this.target = null;
                    } else if (till) {
                        // 锄头没了 / 不在背包：目标作废，回到扫描（工具 CD 在扫描阶段处理）
                        this.target = null;
                    } else if (plant) {
                        // 这块地没有能种的种子（例如模组稻米只认水田）：标记跳过，别反复跑
                        this.unreachable.add(this.target);
                        this.target = null;
                    }
                }
                return;
            }
            if (this.resident.getNavigation().isDone()) {
                this.moveTo(
                    this.target.getX() + 0.5D, this.target.getY(), this.target.getZ() + 0.5D, 0.9D);
            }
            return;
        }

        // 找目标：先采收成熟的 → 再耕地（有锄才耕）→ 最后播种空耕地
        // T10-4：没目标时 20~40 tick 才扫一次区域，别每 tick 全量扫大农田
        if (this.scanCooldown > 0) {
            this.scanCooldown--;
            this.wander(level);
            return;
        }
        this.scanCooldown = SCAN_COOLDOWN_MIN
            + this.resident.getRandom().nextInt(SCAN_COOLDOWN_MAX - SCAN_COOLDOWN_MIN + 1);
        BlockPos crop = this.findMatureCrop(level);
        if (crop != null && this.isReachable(crop)) {
            this.target = crop;
            return;
        }
        // 耕地需要锄头：找不到就吆喝一声，30 秒内不再翻背包；
        // CD 期间**照样**继续找作物 / 空耕地播种，不会完全停工（主人 2026-11-21 定）。
        BlockPos dirt = this.findTillable(level);
        if (dirt != null && this.ensureTool(ToolNeed.HOE, level) && this.isReachable(dirt)) {
            this.target = dirt;
            return;
        }
        BlockPos empty = this.findEmptyFarmland(level);
        if (empty != null && this.isReachable(empty)) {
            this.target = empty;
            return;
        }
        this.wander(level);
    }

    /**
     * 共享的「找工具」逻辑（主人 2026-11-21）：锄头、斧头、以后所有需要工具的工作都走这里。
     *
     * <ul>
     *   <li>**每次真正翻背包**（无论有没有工具）都会进入 {@link #TOOL_SEARCH_CD} 冷却；</li>
     *   <li>冷却期间直接返回上次结果，不再扫背包（后台不会「耕一次地就翻一次包」）；</li>
     *   <li>没有工具就发一句随机台词（对白池 {@code TOOL_HOE} / {@code TOOL_AXE}）。</li>
     * </ul>
     */
    private boolean ensureTool(ToolNeed need, ServerLevel level) {
        if (this.toolSearchCd > 0) {
            return this.toolCachedType == need && this.toolCachedResult;
        }
        this.toolSearchCd = TOOL_SEARCH_CD;
        this.toolCachedType = need;
        this.toolCachedResult = this.hasTool(need);
        if (!this.toolCachedResult) {
            ResidentChat.say(level, this.resident,
                ResidentChatPool.pick(need.chatKind, this.resident.getRandom()));
        }
        return this.toolCachedResult;
    }

    /** 背包（主手 + 快捷栏 + 主背包）里有没有这个工具。 */
    private boolean hasTool(ToolNeed need) {
        ResidentInventory inventory = this.resident.inventory();
        for (int slot = 0; slot < ResidentInventory.BACKPACK_START + 27; slot++) {
            if (need.matches(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /** 工作需要的工具种类（以后树场等直接加一行）。 */
    private enum ToolNeed {
        HOE(ResidentChatPool.Kind.TOOL_HOE, stack -> stack.getItem() instanceof HoeItem),
        AXE(ResidentChatPool.Kind.TOOL_AXE, stack -> stack.getItem() instanceof net.minecraft.world.item.AxeItem);

        private final ResidentChatPool.Kind chatKind;
        private final java.util.function.Predicate<ItemStack> matcher;

        ToolNeed(ResidentChatPool.Kind chatKind, java.util.function.Predicate<ItemStack> matcher) {
            this.chatKind = chatKind;
            this.matcher = matcher;
        }

        boolean matches(ItemStack stack) {
            return this.matcher.test(stack);
        }
    }

    /** T10-3：先用寻路验一次目标可达；不可达就进黑名单，本次工作不再试它。 */
    private boolean isReachable(BlockPos pos) {
        if (this.unreachable.contains(pos)) {
            return false;
        }
        Path path = this.resident.getNavigation().createPath(pos, 1);
        if (path == null || !path.canReach()) {
            this.unreachable.add(pos.immutable());
            return false;
        }
        return true;
    }

    /**
     * 「能走到这个方块**旁边**」：树干 / 树苗本身都不可站（规则 33 树苗 = 障碍），
     * 直接拿它们当寻路目标会永远判成不可达。所以改为找 {@link #treeStandSpot} 给的站位格。
     */
    private boolean isReachableNear(BlockPos pos) {
        if (this.unreachable.contains(pos)) {
            return false;
        }
        if (this.treeStandSpot(pos) != null) {
            return true;
        }
        this.unreachable.add(pos.immutable());
        return false;
    }

    /** 区域里最近的成熟作物（优先）。 */
    @Nullable
    private BlockPos findMatureCrop(ServerLevel level) {
        WorkZone work = this.zone;
        if (work == null) {
            return null;
        }
        BlockPos self = this.resident.blockPosition();
        BlockPos best = null;
        double bestDistance = SEARCH_RANGE * SEARCH_RANGE;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (BlockPos pos : BlockPos.betweenClosed(work.min(), work.max())) {
            probe.set(pos);
            BlockState state = level.getBlockState(probe);
            if (!isMatureCrop(state) || this.unreachable.contains(probe)) {
                continue;
            }
            double distance = self.distSqr(probe);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = probe.immutable();
            }
        }
        return best;
    }

    /** 区域里最近的可耕地（上方是空气）。 */
    @Nullable
    private BlockPos findTillable(ServerLevel level) {
        WorkZone work = this.zone;
        if (work == null) {
            return null;
        }
        BlockPos self = this.resident.blockPosition();
        BlockPos best = null;
        double bestDistance = SEARCH_RANGE * SEARCH_RANGE;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (BlockPos pos : BlockPos.betweenClosed(work.min(), work.max())) {
            probe.set(pos);
            if (!isTillable(level.getBlockState(probe)) || !level.getBlockState(probe.above()).isAir()
                    || this.unreachable.contains(probe)) {
                continue;
            }
            double distance = self.distSqr(probe);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = probe.immutable();
            }
        }
        return best;
    }

    /** 区域里最近的空耕地（耕地 + 上方空气），只在背包里真有种子时才找。 */
    @Nullable
    private BlockPos findEmptyFarmland(ServerLevel level) {
        WorkZone work = this.zone;
        if (work == null || !this.hasSeeds()) {
            return null;
        }
        BlockPos self = this.resident.blockPosition();
        BlockPos best = null;
        double bestDistance = SEARCH_RANGE * SEARCH_RANGE;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (BlockPos pos : BlockPos.betweenClosed(work.min(), work.max())) {
            probe.set(pos);
            if (!isPlantSpot(level, probe, level.getBlockState(probe))
                    || this.unreachable.contains(probe)) {
                continue;
            }
            double distance = self.distSqr(probe);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = probe.immutable();
            }
        }
        return best;
    }

    /** 空耕地 = 耕地方块 + 上方是空气。 */
    private static boolean isPlantSpot(ServerLevel level, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof FarmBlock && level.getBlockState(pos.above()).isAir();
    }

    /** 播种（T11）：主手换成「能种在这格耕地上」的种子 → 挥手 → 上方长出作物 → 消耗 1 颗。 */
    private boolean plant(ServerLevel level, BlockPos pos) {
        BlockPos above = pos.above();
        if (!this.resident.inventory().holdInMainHand(
                stack -> looksLikeSeed(stack) && canPlantHere(stack, level, above))) {
            return false;
        }
        ItemStack seed = this.resident.getMainHandItem();
        if (!(seed.getItem() instanceof BlockItem item)) {
            return false;
        }
        BlockState crop = item.getBlock().defaultBlockState();
        if (!crop.canSurvive(level, above)) {
            return false;
        }
        this.resident.swing(InteractionHand.MAIN_HAND, true);
        level.setBlock(above, crop, 3);
        level.playSound(null, pos, crop.getSoundType().getPlaceSound(), SoundSource.BLOCKS,
            1.0F, 1.0F);
        seed.shrink(1);
        this.resident.inventory().setChanged();
        return true;
    }

    /** 背包（主手 + 快捷栏 + 主背包）里有没有能种的种子。 */
    private boolean hasSeeds() {
        ResidentInventory inventory = this.resident.inventory();
        for (int slot = 0; slot < ResidentInventory.BACKPACK_START + 27; slot++) {
            if (looksLikeSeed(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 看起来像种子（T11：模组作物兼容）。
     *
     * <p>判定顺序：`#worldplayers:plantable_seeds` 逃生口 → **原版 `#minecraft:villager_plantable_seeds`**
     * （村民能种的都在这里，Farmer's Delight 的稻米/番茄也靠它）→ 原版 {@link CropBlock} / {@link StemBlock}。
     * **不再接受「任何 BlockItem」**——那会把南瓜/西瓜方块直接种进地里（2026-11-21 实测 bug）。</p>
     */
    private static boolean looksLikeSeed(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            return false;
        }
        if (stack.is(PLANTABLE_SEEDS_TAG) || stack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS)) {
            return true;
        }
        Block block = item.getBlock();
        return block instanceof CropBlock || block instanceof StemBlock;
    }

    /** 这颗种子真的能种在这一格上方的空气里（模组作物靠这个判定）。 */
    private static boolean canPlantHere(ItemStack stack, ServerLevel level, BlockPos above) {
        if (!(stack.getItem() instanceof BlockItem item)) {
            return false;
        }
        return item.getBlock().defaultBlockState().canSurvive(level, above);
    }

    /** 采收：产物进背包（少一个种子）、重置生长、破坏音效与粒子、挥手。 */
    private boolean harvest(ServerLevel level, BlockPos pos, BlockState state) {
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, this.resident,
            this.resident.getMainHandItem());
        // 「产物（种子数量 -1）」：去掉一份种子
        boolean removedSeed = false;
        for (ItemStack drop : drops) {
            if (!removedSeed && drop.getItem() instanceof net.minecraft.world.item.ItemNameBlockItem) {
                drop.shrink(1);
                removedSeed = true;
            }
        }
        this.resident.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.BLOCKS,
            1.0F, 1.0F);
        level.levelEvent(2001, pos, Block.getId(state));
        // 作物回到 0% 生长；瓜类果实直接摘掉
        if (state.getBlock() instanceof CropBlock crop) {
            level.setBlock(pos, crop.getStateForAge(0), 3);
        } else {
            level.removeBlock(pos, false);
        }
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            ItemStack rest = this.resident.inventory().addItem(drop);
            if (!rest.isEmpty()) {
                this.resident.spawnAtLocation(rest);
            }
        }
        return true;
    }

    /** 耕地：主手必须是锄；换成耕地、音效、耐久 -1（附魔耐久自动生效）。 */
    private boolean till(ServerLevel level, BlockPos pos) {
        this.resident.inventory().holdInMainHand(
            stack -> stack.getItem() instanceof HoeItem);
        ItemStack tool = this.resident.getMainHandItem();
        if (!(tool.getItem() instanceof HoeItem)) {
            return false;
        }
        this.resident.swing(InteractionHand.MAIN_HAND, true);
        level.setBlock(pos, Blocks.FARMLAND.defaultBlockState(), 11);
        level.playSound(null, pos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        hurtTool(this.resident, tool);
        return true;
    }

    /** 距离 + 直线视野（不看穿方块）。 */
    private boolean hasLineOfSight(ServerLevel level, BlockPos pos) {
        Vec3 eye = this.resident.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(
            eye, center, net.minecraft.world.level.ClipContext.Block.COLLIDER,
            net.minecraft.world.level.ClipContext.Fluid.NONE, this.resident));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    private static boolean isMatureCrop(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }
        return block == Blocks.MELON || block == Blocks.PUMPKIN;
    }

    private static boolean isTillable(BlockState state) {
        return state.is(BlockTags.DIRT) && !(state.getBlock() instanceof FarmBlock);
    }

    // ------------------------------------------------------------------
    // 树场（T7，2026-11-21 主人定稿；规则见 10 号文件「树场规则（定稿）」）
    // ------------------------------------------------------------------

    /** 原木每格的破坏间隔（规则 23）。 */
    private static final int LOG_BREAK_TICKS = 12;
    /** 树叶每格的破坏间隔（规则 23）。 */
    private static final int LEAF_BREAK_TICKS = 5;
    /** 砍一格原木扣的体力（规则 24，对得上「砍 16 组木头力竭」）。 */
    private static final float STAMINA_PER_LOG = 0.1F;
    /** 背包满探测间隔：5 秒（规则 32）。 */
    private static final int BAG_CHECK_CD = 100;
    /** 不可达黑名单的有效期：60 秒（地形 / 树叶变化后自己恢复，不用玩家切换状态）。 */
    private static final int UNREACHABLE_TTL = 20 * 60;
    /** 砍树时站位的搜索半径：树干周围最多外扩 4 格（树叶 / 树苗把紧邻格盖住时也能开工）。 */
    private static final int TREE_STAND_RADIUS = 4;
    /** 站位候选里最多验几格寻路（每格一次 A*，别把整个环全跑一遍）。 */
    private static final int TREE_STAND_TRIES = 6;
    /** 找不到树之后的重新扫描间隔（tick）。 */
    private static final int TREE_RETRY_CD = 20;
    /** 走到目标的时间上限（200 tick 还没到就放弃，换下一个）。 */
    private static final int WALK_TIMEOUT = 200;

    /** 走到目标已经花了多少 tick。 */
    private int pendingTicks;

    /**
     * 树场主循环（规则 18）：**有目标原木 → 砍；没有 → 种；都没有 → 踱步**。
     *
     * <p>每次只做一件事，做完重新判定（规则 18「一次只砍一棵，砍完重新判定」）。</p>
     */
    private void tickLumber(ServerLevel level) {
        TreeSpecies species = this.zone.species();
        if (species == TreeSpecies.NONE) {
            // 树种 = 「无」：员工只在区域内踱步（规则 1）
            this.wander(level);
            return;
        }
        TreeFarmIndex index = TreeFarmIndex.get(this.zone);
        long now = level.getGameTime();
        index.tick(level, this.zone, now);
        index.pruneClaims(now);

        // 背包满（规则 32）：停止工作、原地站立；状态仍是「伐木工」+ 暂停标记，每 5 秒探一次
        if (this.bagPaused) {
            this.handleBagFull(ResidentJobs.LUMBER_PAUSED);
            if (this.tickBagPause(() -> this.hasBagRoom(species))) {
                this.bagPaused = false;
                // 视为一次开工：重扫一轮槽位/树清单，复工后按最新地形继续
                index.forceRescan();
            }
            return;
        }
        if (!this.hasBagRoom(species)) {
            this.bagPaused = true;
            this.bagCheckCd = BAG_CHECK_CD;
            this.handleBagFull(ResidentJobs.LUMBER_PAUSED);
            this.abandonLumber(index);
            return;
        }

        // 正在砍一棵树 → 无限触及（规则 22），只按 12/5 tick 逐格破坏
        if (this.lumberClaim != null) {
            // 规则 25：必须持斧；动手前先确认（否则会把队列里的方块白白消耗掉）
            if (!this.resident.inventory().holdInMainHand(
                    stack -> stack.getItem() instanceof net.minecraft.world.item.AxeItem)) {
                this.abandonLumber(index);
                this.ensureTool(ToolNeed.AXE, level);
                return;
            }
            if (this.breakCooldown > 0) {
                this.breakCooldown--;
                return;
            }
            BlockPos next = index.nextBlock(this.lumberClaim, now);
            if (next == null) {
                this.finishLumber(level, index, species);
                return;
            }
            this.breakBlock(level, next, species);
            return;
        }

        if (this.toolSearchCd > 0) {
            this.toolSearchCd--;
        }
        // 违规树苗扫描节流（规则 16：区域级共享一份结果，这里只是「取」）
        if (this.pendingSapling == null) {
            if (this.scanCooldown > 0) {
                this.scanCooldown--;
            } else {
                this.scanCooldown = SCAN_COOLDOWN_MIN
                    + this.resident.getRandom().nextInt(SCAN_COOLDOWN_MAX - SCAN_COOLDOWN_MIN + 1);
                this.pendingSapling = this.findViolation(index);
            }
        }

        // 1) 违规树苗先清（规则 16、17）
        if (this.pendingSapling != null) {
            if (!this.isStillSaplingTarget(level, this.pendingSapling, species)) {
                this.pendingSapling = null;
                return;
            }
            if (this.nearEnough(level, this.pendingSapling)) {
                this.clearSapling(level, this.pendingSapling, species);
                this.pendingSapling = null;
            } else {
                this.walkOrGiveUp(level, this.pendingSapling, true, () -> this.pendingSapling = null);
            }
            return;
        }

        // 2) 有目标原木 → 砍（原木由低到高由 TreeFarmGrid 排序保证）
        //
        // 2026-11-23 第 10 轮主人定：树场**不再走到树干脚下**，站在工作区域（外扩 3 格）里就砍。
        // 砍树本来就是无限触及（规则 22），以前要求「走到树旁 + 4.5 格 + 视线」会：
        // ① 被树冠 / 树叶挡住时永远判定「没到」；② 为了贴近树干而钻进树叶里卡头。
        // 现在直接领取整棵树 → 从低到高逐格破坏，砍完（含树叶）再换下一棵。
        BlockPos treeBase = this.pendingTree != null ? this.pendingTree : this.findTreeBase(index, now);
        if (treeBase != null) {
            this.pendingTree = null;
            this.pendingTicks = 0;
            TreeFarmGrid.TreeBody body = TreeFarmGrid.findTree(level, species, treeBase);
            if (body == null) {
                this.unreachable.add(treeBase);
                return;
            }
            TreeFarmIndex.Claim claim = index.claim(treeBase, body, now, this.resident.getUUID());
            if (claim == null) {
                // 别人 3 秒前就领走了，这棵不插队（规则 30），本 tick 换下一棵
                return;
            }
            this.resident.getNavigation().stop();
            this.lumberClaim = claim;
            this.breakCooldown = 0;
            return;
        }

        // 3) 没有原木 → 种（附近空槽位）
        if (this.pendingPlant != null || this.hasSapling(species)) {
            BlockPos plant = this.pendingPlant != null
                ? this.pendingPlant : this.findPlantSlot(level, index, species);
            if (plant != null) {
                if (this.pendingPlant != null && this.pendingTicks > WALK_TIMEOUT) {
                    this.unreachable.add(plant);
                    this.pendingPlant = null;
                    this.pendingTicks = 0;
                    return;
                }
                if (!this.nearEnough(level, plant)) {
                    this.pendingPlant = plant;
                    // 槽位本身是地面方块（不可站）：导航目标换成槽位**上方**那一格，
                    // 否则 createPath 直接把槽位判成不可达（和树干/树苗同一个坑）
                    this.walkOrGiveUp(level, plant.above(), false, () -> {
                        this.pendingPlant = null;
                        this.pendingTicks = 0;
                    });
                    return;
                }
                this.pendingPlant = null;
                this.pendingTicks = 0;
                this.resident.getNavigation().stop();
                if (this.laborCooldown <= 0) {
                    this.plantTrees(level, plant, species);
                    this.laborCooldown = laborCooldown(this.resident);
                }
                return;
            }
        }

        // 4) 都没了 → 区域内踱步
        this.wander(level);
    }

    /**
     * 走不到就放弃（超时 → 回调清目标）。{@code besideOnly} = 目标是不可站的方块（树干 / 树苗）。
     *
     * <p>2026-11-23 第 9 轮：树干 / 树苗的站位从「紧邻 8 格」扩到 {@link #treeStandSpot}
     * —— 树场被树叶 / 树苗塞满时，紧邻格全部不可站，以前会直接把整片树场判成不可达。</p>
     */
    private void walkOrGiveUp(ServerLevel level, BlockPos pos, boolean besideOnly, Runnable onGiveUp) {
        if (besideOnly) {
            if (this.unreachable.contains(pos)) {
                onGiveUp.run();
                return;
            }
            BlockPos stand = this.pendingStand;
            // 站位缓存：目标方块没变、格里还能站、还在工作范围里就继续用，别每 tick 重扫候选格
            if (stand != null && pos.equals(this.pendingStandFor)
                    && (this.zone == null
                        || this.zone.inWorkRange(stand) || this.zone.inWorkRange(stand.below()))
                    && ResidentEntity.isSafeStand(level, stand)) {
                // 直接沿用
            } else {
                stand = this.treeStandSpot(pos);
                this.pendingStand = stand;
                this.pendingStandFor = stand == null ? null : pos.immutable();
            }
            if (stand == null) {
                this.unreachable.add(pos.immutable());
                onGiveUp.run();
                return;
            }
            this.pendingTicks++;
            if (this.resident.getNavigation().isDone()) {
                this.moveTo(
                    stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 0.9D);
            }
            return;
        }
        if (!this.isReachable(pos)) {
            onGiveUp.run();
            return;
        }
        this.pendingTicks++;
        if (this.resident.getNavigation().isDone()) {
            this.moveTo(
                pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, 0.9D);
        }
    }

    /**
     * 树干 / 树苗旁边**能站过去、也走得到**的格子：紧邻优先，最多外扩 {@link #TREE_STAND_RADIUS} 格
     * （只挑工作范围里的格子，别贴着区域边界往外跑）。
     *
     * <p>2026-11-23 第 9 轮（主人报「树场全是树时伐木工只围着外围走」）：以前只找紧邻 8 格，
     * 树叶 / 树干 / 树苗把这些格子占满时一个候选都没有 → 每棵树都被判不可达 → 员工只能踱步。
     * 砍树本身是无限触及（规则 22），站位只要在区域内、脚下能站、寻路能到就行。</p>
     *
     * @return 找不到返回 {@code null}（调用方把目标记进黑名单）
     */
    @Nullable
    private BlockPos treeStandSpot(BlockPos pos) {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return null;
        }
        List<BlockPos> candidates = new java.util.ArrayList<>();
        for (int radius = 1; radius <= TREE_STAND_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // 只看这一圈的外框（半径 1 就是原来的 8 邻格）
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    BlockPos beside = pos.offset(dx, 0, dz);
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos spot = beside.above(dy);
                        if (this.zone != null
                                && !(this.zone.inWorkRange(spot) || this.zone.inWorkRange(spot.below()))) {
                            continue;
                        }
                        if (!ResidentEntity.isSafeStand(level, spot)) {
                            continue;
                        }
                        candidates.add(spot.immutable());
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        BlockPos self = this.resident.blockPosition();
        candidates.sort(java.util.Comparator.comparingDouble(self::distSqr));
        int checked = 0;
        for (BlockPos spot : candidates) {
            if (++checked > TREE_STAND_TRIES) {
                return null;
            }
            Path path = this.resident.getNavigation().createPath(spot, 1);
            if (path != null && path.canReach()) {
                return spot;
            }
        }
        return null;
    }

    /** 距离 ≤ 4.5 格就算「到脚下了」——树场的砍 / 种 / 清苗都**不再要求直线视野**（树叶会挡视线）。 */
    private boolean nearEnough(ServerLevel level, BlockPos pos) {
        double distance = this.resident.distanceToSqr(
            pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        return distance <= RANGE * RANGE;
    }

    /** 背包还有没有地方放下原木 / 树叶 / 树苗（规则 32）。 */
    private boolean hasBagRoom(TreeSpecies species) {
        ResidentInventory inventory = this.resident.inventory();
        for (int slot = 0; slot < ResidentInventory.BACKPACK_START + 27; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return true;
            }
        }
        if (species.logBlock() != null && inventory.canAccept(new ItemStack(species.logBlock()))) {
            return true;
        }
        if (species.saplingItem() != null && inventory.canAccept(new ItemStack(species.saplingItem()))) {
            return true;
        }
        return species.leavesBlock() != null && inventory.canAccept(new ItemStack(species.leavesBlock()));
    }

    /** 背包里有没有这个树种的树苗。 */
    private boolean hasSapling(TreeSpecies species) {
        if (species.saplingItem() == null) {
            return false;
        }
        ResidentInventory inventory = this.resident.inventory();
        for (int slot = 0; slot < ResidentInventory.BACKPACK_START + 27; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(species.saplingItem())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 最近一棵可以砍的树（未被黑名单、没被别人占着、32 格内、能走到旁边）。
     *
     * <p>2026-11-23 第 9 轮：以前只验「最近的那一棵」，它不可达就整 tick 返回 null ——
     * 树场被树塞满时每 tick 只黑掉一棵，最后所有树都进黑名单，员工永远只踱步。
     * 现在按距离由近到远依次验前 {@link #TREE_CANDIDATES} 棵，并且扫不到就歇
     * {@link #TREE_RETRY_CD} tick，别每 tick 对整片树场跑寻路。</p>
     */
    @Nullable
    private BlockPos findTreeBase(TreeFarmIndex index, long now) {
        if (this.treeRetryCd > 0) {
            this.treeRetryCd--;
            return null;
        }
        BlockPos self = this.resident.blockPosition();
        BlockPos best = null;
        double bestDistance = SEARCH_RANGE * SEARCH_RANGE;
        for (BlockPos base : index.treeBases()) {
            if (this.unreachable.contains(base) || index.isBusyElsewhere(base, now, this.resident.getUUID())) {
                continue;
            }
            double distance = self.distSqr(base);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = base;
            }
        }
        if (best == null) {
            // 没树可砍（或全在别人手里）：20 tick 后再看，别每 tick 扫一遍清单
            this.treeRetryCd = TREE_RETRY_CD;
        }
        return best;
    }

    /** 最近的空槽位（上方是空气）+ 可达；2×2 时返回组锚点。 */
    @Nullable
    private BlockPos findPlantSlot(ServerLevel level, TreeFarmIndex index, TreeSpecies species) {
        BlockPos self = this.resident.blockPosition();
        BlockPos best = null;
        double bestDistance = SEARCH_RANGE * SEARCH_RANGE;
        for (BlockPos slot : index.slots()) {
            if (this.unreachable.contains(slot) || !this.isFreeSpot(level, slot, species)) {
                continue;
            }
            double distance = self.distSqr(slot);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = slot;
            }
        }
        return best != null && this.isReachableSpot(best) ? best : null;
    }

    /**
     * 种植槽位可走性：槽位本身是**地面方块**（泥土 / 草），不能当寻路终点——
     * 必须验它**上方那一格**能不能站、走不走得到（和树干 / 树苗「本身不可站」是同一个坑）。
     */
    private boolean isReachableSpot(BlockPos slot) {
        if (this.unreachable.contains(slot)) {
            return false;
        }
        BlockPos stand = slot.above();
        if (!ResidentEntity.isSafeStand(this.resident.level(), stand)) {
            this.unreachable.add(slot.immutable());
            return false;
        }
        Path path = this.resident.getNavigation().createPath(stand, 1);
        if (path == null || !path.canReach()) {
            this.unreachable.add(slot.immutable());
            return false;
        }
        return true;
    }

    /**
     * 这个槽位现在是不是空着可以种：上方是空气，底下还是泥土类。
     * 2×2 时要求这组 4 格里有至少一格能种（缺苗有几棵种几棵，规则 28）。
     *
     * <p>2026-11-23 主人改口：树场外围**只给站立 / 通勤用**，不许在区域外种苗 —— 所以这里
     * 用 {@code containsBlock}（严格区域内），不是 {@code inWorkRange}。</p>
     */
    private boolean isFreeSpot(ServerLevel level, BlockPos slot, TreeSpecies species) {
        if (this.zone.isTwoByTwo()) {
            for (BlockPos spot : TreeFarmIndex.twoByTwoSpots(slot)) {
                if (this.zone.containsBlock(spot)
                        && TreeFarmGrid.isSoil(level.getBlockState(spot))
                        && level.getBlockState(spot.above()).isAir()) {
                    return true;
                }
            }
            return false;
        }
        return TreeFarmGrid.isSoil(level.getBlockState(slot))
            && level.getBlockState(slot.above()).isAir();
    }

    /** 最近的违规树苗（规则 16）。 */
    @Nullable
    private BlockPos findViolation(TreeFarmIndex index) {
        BlockPos self = this.resident.blockPosition();
        BlockPos best = null;
        double bestDistance = SEARCH_RANGE * SEARCH_RANGE;
        for (BlockPos pos : index.violations()) {
            if (this.unreachable.contains(pos)) {
                continue;
            }
            double distance = self.distSqr(pos);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best != null && this.isReachableNear(best) ? best : null;
    }

    /** 这格还是不是「该清掉的树苗」（不是本树种，或者违规）。 */
    private boolean isStillSaplingTarget(ServerLevel level, BlockPos pos, TreeSpecies species) {
        BlockState state = level.getBlockState(pos);
        if (!TreeSpecies.isAnySapling(state)) {
            return false;
        }
        return !species.isOwnSapling(state)
            || TreeFarmGrid.isViolation(level, species, pos, this.zone.isTwoByTwo());
    }

    /** 破坏一格（原木 / 树叶），掉落直接进背包（规则 26）。 */
    private void breakBlock(ServerLevel level, BlockPos pos, TreeSpecies species) {
        BlockState state = level.getBlockState(pos);
        boolean log = species.isTargetLog(state);
        boolean leaf = species.isOwnLeaves(state);
        if (!log && !leaf) {
            // 已经被别人砍掉了：这格跳过，保持节奏
            this.breakCooldown = 1;
            return;
        }
        // 规则 25：必须持斧；树叶也照着手上的斧砍，但**树叶不减耐久**
        if (!this.resident.inventory().holdInMainHand(
                stack -> stack.getItem() instanceof net.minecraft.world.item.AxeItem)) {
            // 保底：claim 分支已经查过持斧，这里真走不到就停手等下一轮（不吞掉队列里的方块）
            this.breakCooldown = 20;
            return;
        }
        ItemStack tool = this.resident.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, this.resident, tool);
        this.resident.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.BLOCKS,
            1.0F, 1.0F);
        level.levelEvent(2001, pos, Block.getId(state));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        // 树叶：玩家手放的装饰叶（persistent=true）不掉产物（规则 8）；永远不生成掉落实体（规则 37）
        if (log || TreeFarmGrid.leavesDrop(state)) {
            for (ItemStack drop : drops) {
                if (drop.isEmpty()) {
                    continue;
                }
                ItemStack rest = this.resident.inventory().addItem(drop);
                if (!rest.isEmpty()) {
                    this.resident.spawnAtLocation(rest);
                }
            }
        }
        if (log) {
            this.resident.setStamina(this.resident.stamina() - staminaCost(this.resident, STAMINA_PER_LOG));
            hurtTool(this.resident, tool);
            this.breakCooldown = doubleSpeed(this.resident) ? LOG_BREAK_TICKS / 2 : LOG_BREAK_TICKS;
        } else {
            this.breakCooldown = LEAF_BREAK_TICKS;
        }
    }

    /** 砍完一棵：给树苗（2×2 大树 4 个、普通树 1 个，规则 27），释放领取。 */
    private void finishLumber(ServerLevel level, TreeFarmIndex index, TreeSpecies species) {
        TreeFarmIndex.Claim claim = this.lumberClaim;
        this.lumberClaim = null;
        this.breakCooldown = 0;
        if (claim == null) {
            return;
        }
        index.release(claim);
        if (species.saplingItem() == null) {
            return;
        }
        ItemStack reward = new ItemStack(species.saplingItem(), claim.saplingReward());
        ItemStack rest = this.resident.inventory().addItem(reward);
        if (!rest.isEmpty()) {
            this.resident.spawnAtLocation(rest);
        }
        this.laborCooldown = laborCooldown(this.resident);
    }

    /** 放掉正在砍的树（背包满 / 离开区域 / 没斧头）。 */
    private void abandonLumber(TreeFarmIndex index) {
        if (this.lumberClaim != null) {
            index.abandon(this.lumberClaim, this.resident.getUUID());
            this.lumberClaim = null;
        }
        this.breakCooldown = 0;
    }

    /**
     * 种树：单株种 1 棵；2×2 一次种 4 棵、**缺苗有几棵种几棵**（规则 28）。
     */
    private void plantTrees(ServerLevel level, BlockPos anchor, TreeSpecies species) {
        Block sapling = species.saplingBlock();
        if (sapling == null) {
            return;
        }
        List<BlockPos> spots = this.zone.isTwoByTwo()
            ? TreeFarmIndex.twoByTwoSpots(anchor) : List.of(anchor);
        for (BlockPos spot : spots) {
            // 2026-11-23：只种**区域内**的土（外围格子只用于站立 / 通勤）
            if (!this.zone.containsBlock(spot) || !TreeFarmGrid.isSoil(level.getBlockState(spot))) {
                continue;
            }
            BlockPos above = spot.above();
            if (!level.getBlockState(above).isAir()) {
                continue;
            }
            BlockState saplingState = sapling.defaultBlockState();
            if (!saplingState.canSurvive(level, above)) {
                continue;
            }
            if (!this.resident.inventory().holdInMainHand(stack -> stack.is(species.saplingItem()))) {
                // 苗用完了，剩下的位置留给下一次
                return;
            }
            this.resident.swing(InteractionHand.MAIN_HAND, true);
            level.setBlock(above, saplingState, 3);
            level.playSound(null, above, saplingState.getSoundType().getPlaceSound(),
                SoundSource.BLOCKS, 1.0F, 1.0F);
            this.resident.getMainHandItem().shrink(1);
            this.resident.inventory().setChanged();
        }
    }

    /** 清掉一棵苗（违规 / 不是新树种）：破坏 + 掉落直接进背包（规则 16、4）。 */
    private void clearSapling(ServerLevel level, BlockPos pos, TreeSpecies species) {
        BlockState state = level.getBlockState(pos);
        if (!TreeSpecies.isAnySapling(state)) {
            return;
        }
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, this.resident,
            this.resident.getMainHandItem());
        this.resident.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.BLOCKS,
            1.0F, 1.0F);
        level.levelEvent(2001, pos, Block.getId(state));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            ItemStack rest = this.resident.inventory().addItem(drop);
            if (!rest.isEmpty()) {
                this.resident.spawnAtLocation(rest);
            }
        }
        this.laborCooldown = laborCooldown(this.resident);
    }

    // ------------------------------------------------------------------
    // 挖掘 / 填充工程（v0.2.15，规格 12 号文件）
    // ------------------------------------------------------------------

    /**
     * 工程主流程：**站在区域外**的站位点上、够得着里面每一格（规格 5.2、5.3）。
     *
     * <p>到站位 = 一次开工（重建队列 + 重扫进度）；没在跑 / 没方块时 goal 会被结束。</p>
     */
    private void tickProject(ServerLevel level) {
        WorkZone zone = this.zone;
        if (zone == null) {
            return;
        }
        if (this.toolSearchCd > 0) {
            this.toolSearchCd--;
        }
        if (this.projectChatCd > 0) {
            this.projectChatCd--;
        }
        if (this.projectRetryCd > 0) {
            this.projectRetryCd--;
        }

        // 站位丢了（被打断 / 被占 / 地形变了）就重找
        if (this.projectStance == null || !ResidentEntity.isSafeStand(level, this.projectStance)) {
            this.projectStance = ProjectStance.find(level, zone, this.resident,
                this.takenStances(level, zone));
            if (this.projectStance == null) {
                this.resident.getNavigation().stop();
                this.applyMode(ResidentJobs.workJobFor(zone.type()), "");
                if (this.projectChatCd <= 0) {
                    this.projectChatCd = TOOL_SEARCH_CD;
                    ResidentChat.say(level, this.resident,
                        Component.translatable("chat.worldplayers.project.no_stand"));
                }
                return;
            }
            this.workStarted = false;
        }

        BlockPos stance = this.projectStance;
        // 已经找到安全落脚点 = 已经在上班：工程区本身是禁行区，员工**永远站在区域外**，
        // 以前走到站位之前一路显示「通勤 + 区域名」，看起来像没开工（2026-11-22 主人要求改）。
        this.applyMode(ResidentJobs.workJobFor(zone.type()), "");
        double distance = this.resident.distanceToSqr(
            stance.getX() + 0.5D, stance.getY(), stance.getZ() + 0.5D);
        if (distance > 2.25D) {
            // 还没到站位：导航目标 = 站位点（状态仍是「挖掘工 / 填充工」）
            if (this.resident.getNavigation().isDone()) {
                this.moveTo(
                    stance.getX() + 0.5D, stance.getY(), stance.getZ() + 0.5D, 0.9D);
            }
            return;
        }
        this.resident.getNavigation().stop();

        ProjectQueue queue = ProjectQueue.get(zone);
        if (!this.workStarted) {
            // 抵达站位 / 从任何中断返回 / 换到新工具 / 处理掉最后一格 → 视为一次开工
            queue.rebuild(level, zone);
            this.workStarted = true;
            this.bagPaused = false;
            this.bagCheckCd = 0;
            this.pushProgress(level, zone, queue, true);
        }
        if (zone.isMine()) {
            this.tickMine(level);
        } else {
            this.tickFill(level);
        }
    }

    /** 同区域其他员工的脚下方块（站位避让用）。 */
    private java.util.Set<BlockPos> takenStances(ServerLevel level, WorkZone zone) {
        java.util.Set<BlockPos> taken = new java.util.HashSet<>();
        for (String id : zone.employees()) {
            if (id == null || id.isEmpty() || id.equals(this.resident.getUUID().toString())) {
                continue;
            }
            try {
                if (level.getEntity(java.util.UUID.fromString(id)) instanceof ResidentEntity other
                        && !other.isRemoved()) {
                    taken.add(other.blockPosition());
                }
            } catch (IllegalArgumentException ignored) {
                // 坏的 UUID 直接跳过
            }
        }
        return taken;
    }

    /**
     * 挖掘主循环（规格 5.4）：按队列逐格挖，掉落直接进背包；
     * **背包满用树场同款局部暂停**（任务 A；阶段 B 再统一到所有工作区）。
     */
    private void tickMine(ServerLevel level) {
        WorkZone zone = this.zone;
        ProjectQueue queue = ProjectQueue.get(zone);

        if (this.bagPaused) {
            this.handleBagFull(ResidentJobs.MINER_BAG_FULL);
            if (--this.bagCheckCd <= 0) {
                this.bagCheckCd = BAG_CHECK_CD;
                BlockPos next = queue.nextFor(this.resident.getUUID());
                if (next == null) {
                    queue.releaseAll(this.resident.getUUID());
                    if (!ProjectQueue.anyLeft(level, zone)) {
                        this.completeProject(level, zone);
                        return;
                    }
                    queue.forceRebuild(level, zone);
                } else if (this.hasRoomFor(level, next)) {
                    queue.release(next);
                    this.bagPaused = false;
                    this.workStarted = false;
                } else {
                    queue.release(next);
                }
            }
            return;
        }

        if (this.laborCooldown > 0) {
            return;
        }
        BlockPos pos = queue.nextFor(this.resident.getUUID());
        if (pos == null) {
            this.finishOrWait(level, zone, queue);
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!ProjectRules.isDiggable(level, pos)) {
            // 已经被别人挖了 / 是空气或液体：这格算过
            queue.markDone(pos);
            return;
        }

        ProjectRules.ToolKind kind = ProjectRules.toolFor(state);
        boolean hasTool = kind != ProjectRules.ToolKind.NONE && this.hasProjectTool(kind);
        if (ProjectRules.required(kind) && !hasTool) {
            // 没工具：停下喊话（30 秒 CD），不跳过、不卡死
            queue.release(pos);
            this.applyMode(ResidentJobs.MINER_PAUSED, "");
            if (this.projectChatCd <= 0) {
                this.projectChatCd = TOOL_SEARCH_CD;
                ResidentChat.say(level, this.resident,
                    Component.translatable(ProjectRules.missingToolKey(kind), state.getBlock().getName()));
            }
            return;
        }
        if (hasTool) {
            this.resident.inventory().holdInMainHand(ProjectRules.matcher(kind));
        }
        ItemStack tool = this.resident.getMainHandItem();
        List<ItemStack> drops = Block.getDrops(state, level, pos, null, this.resident, tool);
        if (!this.canAcceptAll(drops)) {
            // 背包满：放掉这一格、原地站着，5 秒探一次
            queue.release(pos);
            this.bagPaused = true;
            this.bagCheckCd = BAG_CHECK_CD;
            this.handleBagFull(ResidentJobs.MINER_BAG_FULL);
            if (this.projectChatCd <= 0) {
                this.projectChatCd = TOOL_SEARCH_CD;
                ResidentChat.say(level, this.resident,
                    Component.translatable("chat.worldplayers.project.bag_full", state.getBlock().getName()));
            }
            return;
        }

        // 方块实体的内容物直接掉地上（规格 5.4），方块本体掉落进背包
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container container) {
            net.minecraft.world.Containers.dropContents(level, pos, container);
        }
        this.resident.swing(InteractionHand.MAIN_HAND, true);
        level.playSound(null, pos, state.getSoundType().getBreakSound(), SoundSource.BLOCKS,
            1.0F, 1.0F);
        level.levelEvent(2001, pos, Block.getId(state));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            ItemStack rest = this.resident.inventory().addItem(drop);
            if (!rest.isEmpty()) {
                this.resident.spawnAtLocation(rest);
            }
        }
        this.resident.setStamina(this.resident.stamina() - staminaCost(this.resident, STAMINA_PER_LABOR));
        if (hasTool) {
            hurtTool(this.resident, tool);
        }
        int cooldown = laborCooldown(this.resident);
        // 泥沙类有铲 → 2 倍速（冷却减半）
        if (kind == ProjectRules.ToolKind.SHOVEL && hasTool) {
            cooldown = Math.max(1, cooldown / 2);
        }
        this.laborCooldown = cooldown;
        queue.markDone(pos);
        this.pushProgress(level, zone, queue, false);
    }

    /** 填充主循环（规格 5.5）：从背包取方块，逐格填；缺方块停下喊话。 */
    private void tickFill(ServerLevel level) {
        WorkZone zone = this.zone;
        Block target = ProjectRules.blockById(zone.projectBlock());
        net.minecraft.world.item.BlockItem item = ProjectRules.blockItemOf(target);
        ProjectQueue queue = ProjectQueue.get(zone);
        if (target == null || item == null) {
            return;
        }
        if (this.laborCooldown > 0) {
            return;
        }
        // 通用「缺方块」暂停（阶段 B）：原地站着，每 100 tick 看一次背包里有没有方块
        if (this.bagPaused) {
            this.handleBagFull(ResidentJobs.FILLER_PAUSED);
            if (--this.bagCheckCd <= 0) {
                this.bagCheckCd = BAG_CHECK_CD;
                if (this.countItem(item) >= 1) {
                    this.bagPaused = false;
                    // 视为一次开工：重扫队列（地形可能被玩家改过）
                    this.workStarted = false;
                } else if (!ProjectQueue.anyLeft(level, zone)) {
                    this.completeProject(level, zone);
                }
            }
            return;
        }
        BlockPos pos = queue.nextFor(this.resident.getUUID());
        // 2026-11-22 修：**先看队列还有没有格**。以前先判「背包里没有目标方块」，
        // 最后一块填完、方块正好用完时就会一直挂在「填充工（缺少方块）」上，永远走不到完工删区。
        if (pos == null) {
            this.finishOrWait(level, zone, queue);
            return;
        }
        if (this.countItem(item) < 1) {
            queue.release(pos);
            this.bagPaused = true;
            this.bagCheckCd = BAG_CHECK_CD;
            this.handleBagFull(ResidentJobs.FILLER_PAUSED);
            if (this.projectChatCd <= 0) {
                this.projectChatCd = TOOL_SEARCH_CD;
                ResidentChat.say(level, this.resident,
                    Component.translatable("chat.worldplayers.project.out_of_block", target.getName()));
            }
            return;
        }
        if (!ProjectRules.isFillable(level, pos, target)) {
            queue.markDone(pos);
            return;
        }
        BlockState placement = target.defaultBlockState();
        if (!placement.canSurvive(level, pos)) {
            // 摆不上去（花需要泥土、门需要支撑…）：跳过这一格
            queue.markDone(pos);
            com.worldplayers.WorldPlayers.LOGGER.debug(
                "fill target {} cannot survive at {}", target, pos);
            return;
        }
        if (!this.resident.inventory().holdInMainHand(stack -> stack.is(item))) {
            queue.release(pos);
            this.handleBagFull(ResidentJobs.FILLER_PAUSED);
            return;
        }
        this.resident.swing(InteractionHand.MAIN_HAND, true);
        level.setBlock(pos, placement, 3);
        level.playSound(null, pos, placement.getSoundType().getPlaceSound(), SoundSource.BLOCKS,
            1.0F, 1.0F);
        this.resident.getMainHandItem().shrink(1);
        this.resident.inventory().setChanged();
        this.resident.setStamina(this.resident.stamina() - staminaCost(this.resident, STAMINA_PER_LABOR));
        this.laborCooldown = laborCooldown(this.resident);
        queue.markDone(pos);
        this.pushProgress(level, zone, queue, false);
    }

    /** 队列干了：有剩下的就重扫（水流 / 玩家改动 / 别人正领着），全清完就完工。 */
    private void finishOrWait(ServerLevel level, WorkZone zone, ProjectQueue queue) {
        if (ProjectQueue.anyLeft(level, zone)) {
            // 别人正领着剩下的格子时别每 tick 全扫一遍
            if (this.projectRetryCd > 0) {
                return;
            }
            this.projectRetryCd = 40;
            queue.forceRebuild(level, zone);
            this.workStarted = true;
            this.pushProgress(level, zone, queue, true);
            return;
        }
        this.completeProject(level, zone);
    }

    /** 背包里有没有这种工具（主手 + 快捷栏 + 主背包）。 */
    private boolean hasProjectTool(ProjectRules.ToolKind kind) {
        ResidentInventory inventory = this.resident.inventory();
        for (int slot = 0; slot < ResidentInventory.BACKPACK_START + 27; slot++) {
            if (ProjectRules.matcher(kind).test(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    /** 背包里这种物品的总数量。 */
    private int countItem(Item item) {
        ResidentInventory inventory = this.resident.inventory();
        int count = 0;
        for (int slot = 0; slot < ResidentInventory.BACKPACK_START + 27; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 这些掉落能不能全塞进背包（一件塞不下就返回 false → 暂停）。 */
    private boolean canAcceptAll(List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            if (!drop.isEmpty() && !this.resident.inventory().canAccept(drop)) {
                return false;
            }
        }
        return true;
    }

    /** 这一格挖出来的东西塞不塞得下（背包满探测用）。 */
    private boolean hasRoomFor(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!ProjectRules.isDiggable(level, pos)) {
            return true;
        }
        return this.canAcceptAll(Block.getDrops(state, level, pos, null, this.resident,
            this.resident.getMainHandItem()));
    }

    /**
     * 完工（规格 7）：删区域 + 广播 + 正中心顶部一枚烟花 + 第一个在场员工说一句。
     *
     * <p>删除是原子的：多人同时处理最后一格时，后到者发现区域没了直接退出。</p>
     */
    private void completeProject(ServerLevel level, WorkZone zone) {
        WorkZoneData data = WorkZoneData.get(level);
        WorkZone current = data.findByKey(zone.key());
        if (current == null || !current.isProject()) {
            return;
        }
        data.remove(current.name(), current.origin());
        ProjectQueue.invalidate(current.key());
        for (net.minecraft.server.level.ServerPlayer online
                : level.getServer().getPlayerList().getPlayers()) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(online,
                new com.worldplayers.network.ZoneListPayload(data.zones()));
        }
        this.spawnFirework(level, current);
        ResidentEntity speaker = this.firstOnlineEmployee(level, current);
        Component typeName = Component.translatable("gui.worldplayers.zone.type." + current.type());
        ResidentChat.say(level, speaker == null ? this.resident : speaker,
            Component.translatable("chat.worldplayers.project.finished",
                current.origin().getX(), current.origin().getY(), current.origin().getZ(), typeName));
        this.projectStance = null;
        this.workStarted = false;
        this.bagPaused = false;
    }

    /** 正中心顶部放一枚烟花（随机颜色，不带伤害要求）。 */
    private void spawnFirework(ServerLevel level, WorkZone zone) {
        double x = (zone.min().getX() + zone.max().getX()) / 2.0D + 0.5D;
        double z = (zone.min().getZ() + zone.max().getZ()) / 2.0D + 0.5D;
        double y = zone.max().getY() + 1.0D;
        ItemStack rocket = new ItemStack(net.minecraft.world.item.Items.FIREWORK_ROCKET);
        int color = this.resident.getRandom().nextInt(0x1000000);
        net.minecraft.world.item.component.FireworkExplosion.Shape[] shapes =
            net.minecraft.world.item.component.FireworkExplosion.Shape.values();
        net.minecraft.world.item.component.FireworkExplosion explosion =
            new net.minecraft.world.item.component.FireworkExplosion(
                shapes[this.resident.getRandom().nextInt(shapes.length)],
                it.unimi.dsi.fastutil.ints.IntList.of(color),
                it.unimi.dsi.fastutil.ints.IntList.of(),
                true, true);
        rocket.set(net.minecraft.core.component.DataComponents.FIREWORKS,
            new net.minecraft.world.item.component.Fireworks(1, List.of(explosion)));
        level.addFreshEntity(new net.minecraft.world.entity.projectile.FireworkRocketEntity(
            level, x, y, z, rocket));
    }

    /** 区域员工里第一个在线的（完工喊话的人）；都不在线 → null。 */
    @Nullable
    private ResidentEntity firstOnlineEmployee(ServerLevel level, WorkZone zone) {
        for (String id : zone.employees()) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            try {
                if (level.getEntity(java.util.UUID.fromString(id)) instanceof ResidentEntity resident) {
                    return resident;
                }
            } catch (IllegalArgumentException ignored) {
                // 坏的 UUID 直接跳过
            }
        }
        return null;
    }

    /** 进度包：5 秒节流（规格 6），顺手把 done 写回存档（重启后进度还在）。 */
    private void pushProgress(ServerLevel level, WorkZone zone, ProjectQueue queue, boolean force) {
        long now = level.getGameTime();
        if (!force && now - this.lastProgressTick < 5L) {
            return;
        }
        this.lastProgressTick = now;
        int done = (int) Math.min(Integer.MAX_VALUE, queue.done());
        WorkZoneData data = WorkZoneData.get(level);
        WorkZone current = data.findByKey(zone.key());
        if (current != null && current.projectDone() != done) {
            data.add(current.withProjectDone(done));
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(
            new com.worldplayers.network.ZoneProjectProgressPayload(
                zone.key(), queue.done(), queue.total()));
    }

    // ------------------------------------------------------------------
    // 非农田：区域内随机走动
    // ------------------------------------------------------------------

    /**
     * 工作 goal 自己的寻路入口：顺手打上「这是工作 goal 发起的移动」标记，
     * {@link #guardInside} 只检查这种路径，不会误伤社交 / 拾取 goal 自己的导航。
     */
    boolean moveTo(double x, double y, double z, double speed) {
        this.ownMoveTicks = 20;
        return this.resident.getNavigation().moveTo(x, y, z, speed);
    }

    // ------------------------------------------------------------------
    // 自订区域（2026-11-25，主人提出）
    // ------------------------------------------------------------------

    /**
     * 自订区域的工作内容：**没有活**，只在区域里游荡 + 每 6 秒扣 0.1 体力点。
     *
     * <p>主人 2026-11-25 要求：</p>
     * <ul>
     *   <li>员工到区域后按普通工作区一样在区域内游荡；</li>
     *   <li>区域里**可站方块只有一格**时，员工就站在那一格之内；</li>
     *   <li>工作状态显示详情页里写下的文字（在 {@code tick} 里写，这里只负责体力与走位）。</li>
     * </ul>
     */
    private void tickCustom(ServerLevel level) {
        if (--this.customStaminaCd <= 0) {
            this.customStaminaCd = CUSTOM_STAMINA_INTERVAL;
            this.resident.setStamina(this.resident.stamina() - CUSTOM_STAMINA_COST);
        }
        BlockPos only = this.customOnlyStandSpot(level);
        if (only == null) {
            this.wander(level);
            return;
        }
        // 只有一个能站的地方：走进去待着（已经在附近就别再挪）
        if (this.resident.blockPosition().distSqr(only) > 4.0D) {
            this.moveTo(only.getX() + 0.5D, only.getY(), only.getZ() + 0.5D, 0.9D);
        } else if (!this.resident.getNavigation().isDone()) {
            this.resident.getNavigation().stop();
        }
    }

    /** 区域里唯一的可站格（缓存 5 秒）；不止一格 / 太大 / 没有 → null。 */
    @Nullable
    private BlockPos customOnlyStandSpot(ServerLevel level) {
        if (this.zone == null) {
            return null;
        }
        if (!this.customOnlyKnown || this.customScanCd-- <= 0) {
            this.customOnlyKnown = true;
            this.customScanCd = CUSTOM_SCAN_INTERVAL;
            this.customOnlySpot = findOnlyStandSpot(level, this.zone);
        }
        return this.customOnlySpot;
    }

    /**
     * 扫整个区域找「可站的位置」：找到第二个就返回 null（= 不是只有一格）。
     *
     * <p>口径和 {@link #wander} 一致：区域方块本身能站就站，否则试它上面一格
     * （区域只框了地面一层时，真正能站的是上面那一格）。</p>
     */
    @Nullable
    private BlockPos findOnlyStandSpot(ServerLevel level, WorkZone work) {
        if (work.volume() > CUSTOM_SCAN_MAX_VOLUME) {
            return null;
        }
        BlockPos only = null;
        for (int x = work.min().getX(); x <= work.max().getX(); x++) {
            for (int z = work.min().getZ(); z <= work.max().getZ(); z++) {
                for (int y = work.max().getY() + 1; y >= work.min().getY(); y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockPos stand = ResidentEntity.isSafeStand(level, pos) ? pos
                        : ResidentEntity.isSafeStand(level, pos.above()) ? pos.above() : null;
                    if (stand == null
                            || !(work.inWorkRange(stand) || work.inWorkRange(stand.below()))) {
                        continue;
                    }
                    if (only != null) {
                        // 已经有一个了，又找到第二个 → 不是「只有一格」
                        return null;
                    }
                    only = stand.immutable();
                    break;
                }
            }
        }
        return only;
    }

    void wander(ServerLevel level) {
        if (--this.cooldown > 0) {
            return;
        }
        this.cooldown = 40 + this.resident.getRandom().nextInt(40);
        if (!this.resident.getNavigation().isDone()) {
            return;
        }
        WorkZone work = this.zone;
        if (work == null) {
            return;
        }
        // 树场允许在区域外几格里踱步（树叶 / 树苗占满时区域内没地方站）
        int margin = work.workMargin();
        for (int tries = 0; tries < 12; tries++) {
            int x = Mth.nextInt(this.resident.getRandom(),
                work.min().getX() - margin, work.max().getX() + margin);
            int z = Mth.nextInt(this.resident.getRandom(),
                work.min().getZ() - margin, work.max().getZ() + margin);
            for (int y = work.max().getY(); y >= work.min().getY(); y--) {
                BlockPos pos = new BlockPos(x, y, z);
                // 区域方块本身能站就站；否则试它上面一格（区域只有一层地面方块时就是这种情况，
                // 2026-11-23 修：以前永远找不到点，员工只能在原地蹭）
                BlockPos spot = ResidentEntity.isSafeStand(level, pos) ? pos
                    : ResidentEntity.isSafeStand(level, pos.above()) ? pos.above() : null;
                if (spot != null) {
                    this.moveTo(
                        spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 0.9D);
                    return;
                }
            }
        }
    }

    /**
     * 通勤：走进区域内的一个安全落脚点。
     *
     * <p>2026-11-23 三修：以前每 tick 都重算一次落脚点、`moveTo` 失败也照原样重试，
     * 找不到落脚点（种满作物的温室）或到不了时就会永远「通勤」在原地站着。现在：</p>
     * <ul>
     *   <li>落脚点缓存 40 tick，只在不安全时才重挑；</li>
     *   <li>挑点从区域中心向外扩到**整个区域**（不再只看中心 3 格）；</li>
     *   <li>寻路失败也有节流（每 5 tick 重试一次），不会每 tick 全扫区域。</li>
     * </ul>
     */
    private void walkIntoZone(ServerLevel level) {
        if (this.commuteRetryCd > 0) {
            this.commuteRetryCd--;
            return;
        }
        this.commuteRetryCd = 5;
        if (++this.commuteSpotAge > 40
                || this.commuteSpot == null
                || !this.isStandInZone(level, this.commuteSpot)) {
            this.commuteSpot = this.findStandSpot(level, this.zone);
            this.commuteSpotAge = 0;
        }
        if (this.commuteSpot != null) {
            // 通勤速度走心情 / 体力档（2026-11-24 主人定稿，方案 A；和路网赶路、调试棒通勤同一套）
            this.resident.applyTravelSpeed();
            this.moveTo(
                this.commuteSpot.getX() + 0.5D, this.commuteSpot.getY(),
                this.commuteSpot.getZ() + 0.5D, 1.0D);
        }
    }

    /** 落脚点还站在区域里、而且脚下的格子没被堵住（区域只有一层时允许站区域方块的上面一格）。 */
    private boolean isStandInZone(ServerLevel level, BlockPos spot) {
        return this.zone != null
            && (this.zone.inWorkRange(spot) || this.zone.inWorkRange(spot.below()))
            && ResidentEntity.isSafeStand(level, spot);
    }

    /** 区域中心附近找一个能站的格子（区域方块本身或它上方一格）。 */
    @Nullable
    private BlockPos findStandSpot(ServerLevel level, WorkZone work) {
        return this.findStandSpotNear(level, work,
            BlockPos.containing(work.box().getCenter()));
    }

    /**
     * 区域内**离 {@code near} 最近**的能站格子：`near` 被 clamp 回区域范围后向外扩，
     * 最多扩 8 圈 / 到区域边缘为止。找不到返回 null。
     *
     * <p>2026-11-23：树场的「工作范围」允许外扩 {@link WorkZone#workMargin()} 格
     * （树叶 / 树苗把区域内占满时，员工可以站到区域外几格继续干活），其它类型 margin = 0。</p>
     */
    @Nullable
    BlockPos findStandSpotNear(ServerLevel level, WorkZone work, BlockPos near) {
        int margin = work.workMargin();
        int minX = work.min().getX() - margin;
        int maxX = work.max().getX() + margin;
        int minZ = work.min().getZ() - margin;
        int maxZ = work.max().getZ() + margin;
        BlockPos center = new BlockPos(
            Mth.clamp(near.getX(), minX, maxX),
            Mth.clamp(near.getY(), work.min().getY(), work.max().getY()),
            Mth.clamp(near.getZ(), minZ, maxZ));
        int maxRadius = Math.min(8 + margin, Math.max(maxX - minX, maxZ - minZ) + 1);
        for (int radius = 0; radius <= maxRadius; radius++) {
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // 只看这一圈的外框（半径 0 就一个点）
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) {
                        continue;
                    }
                    int x = Mth.clamp(center.getX() + dx, minX, maxX);
                    int z = Mth.clamp(center.getZ() + dz, minZ, maxZ);
                    for (int y = work.max().getY() + 1; y >= work.min().getY(); y--) {
                        BlockPos pos = new BlockPos(x, y, z);
                        BlockPos stand = ResidentEntity.isSafeStand(level, pos) ? pos
                            : ResidentEntity.isSafeStand(level, pos.above()) ? pos.above() : null;
                        // 允许站「区域方块的上面一格」（区域只有一层地面方块时就是这种情况），
                        // 但不许跑出工作范围（树场外扩几格）
                        if (stand == null
                                || !(work.inWorkRange(stand) || work.inWorkRange(stand.below()))) {
                            continue;
                        }
                        double distance = stand.distSqr(center);
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = stand;
                        }
                    }
                }
            }
            if (best != null) {
                return best.immutable();
            }
        }
        return null;
    }

    /**
     * 员工在工作区里时：寻路终点跑到区域外 → 取消这一脚（工作期间不出工作区）。
     *
     * <p>会走到区域外的情况：追区域外的动物、树干/作物在边界外绕路、路径想从栅栏门绕出去。
     * 取消后把当前目标作废（农活目标进黑名单 / 牧场目标清空），下一 tick 重新选点。</p>
     *
     * @return true = 本 tick 已经取消移动，调用方应当直接 return
     */
    private boolean guardInside(ServerLevel level) {
        if (this.zone == null || this.ownMoveTicks <= 0) {
            return false;
        }
        Path path = this.resident.getNavigation().getPath();
        if (path == null || this.resident.getNavigation().isDone()
                || path.getEndNode() == null) {
            return false;
        }
        BlockPos end = path.getEndNode().asBlockPos();
        if (this.zone.inWorkRange(end) || this.zone.inWorkRange(end.below())) {
            return false;
        }
        this.resident.getNavigation().stop();
        if (this.target != null) {
            this.unreachable.add(this.target);
            this.target = null;
        }
        this.pendingTree = null;
        this.pendingPlant = null;
        this.pendingSapling = null;
        this.commuteSpot = null;
        this.ranch.reset();
        this.fishery.reset();
        return true;
    }
}
