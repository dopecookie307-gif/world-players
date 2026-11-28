package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import com.worldplayers.resident.path.PathAction;
import com.worldplayers.resident.path.PathNetworkData;
import com.worldplayers.resident.path.PathNode;
import com.worldplayers.resident.path.PathRoute;
import com.worldplayers.resident.path.PathTraffic;
import java.util.EnumSet;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * 居民执行路网（第二轮，`0.3.1-alpha.4`；`0.3.1-alpha.5/6` 修实机问题）。
 *
 * <p>主人要的验收：**在广东楼画一条楼梯路，居民能从四层走下来**。原版寻路走不通跨楼层，所以：</p>
 * <ol>
 *   <li>谁要去远处（调试棒通勤 / 上班 / 去娱乐区），而且直线距离超过 {@link PathRoute#MIN_TRIP_DISTANCE}；</li>
 *   <li>{@link PathRoute#plan} 在路网上找一条「入口点 → …（逐段）… → 出口点」的路线；</li>
 *   <li>这里逐点走过去（每点按它自己的动作：走 / 跳 / 爬梯 / 传送），**到了出口点就算赶路结束**；</li>
 *   <li>最后几格交回原版寻路（原来的工作 / 娱乐 / 通勤 goal 自己接着走）。</li>
 * </ol>
 *
 * <p>优先级 2，和通勤 goal 同级 → 两边共用 {@link #handlesTrip()} 判定，通勤 goal 主动让路（alpha.5）。</p>
 *
 * <p>状态显示沿用已有的**通勤 + 目的地**，速度用居民平时的走路速度。</p>
 */
public class ResidentPathNetGoal extends Goal {
    /** 目的地直线距离小于 12 格就不走路网（原版寻路够用）。 */
    private static final double MIN_TRIP_DISTANCE = PathRoute.MIN_TRIP_DISTANCE;
    /** 到达一个点的判定：水平 1.5 格内、垂直 1.5 格内。 */
    private static final double NODE_ARRIVE_HORIZONTAL_SQ = 1.5D * 1.5D;
    /** 点本身落在方块里（画的时候被堵）→ 放宽到 2.5 格，路过就算到过。 */
    private static final double BLOCKED_NODE_ARRIVE_SQ = 2.5D * 2.5D;
    private static final double NODE_ARRIVE_VERTICAL = 1.5D;
    /** 重新寻路的间隔。 */
    private static final int REPATH_INTERVAL = 10;
    /**
     * 沿路网走的时候的速度倍率：1.0 —— **属性走心情 / 体力档**（{@link ResidentEntity#applyTravelSpeed()}），
     * 和通勤 goal 用的是同一套（2026-11-24 主人定稿，方案 A）。
     */
    private static final double TRIP_SPEED = 1.0D;
    /** 走完路网后「最后几格」的追赶时间（10 秒）：追不到就交回原来的 goal。 */
    private static final int HANDOFF_TICKS = 20 * 10;
    /** 最后一个节点到目的地的距离（格）之内就算到了。 */
    private static final double HANDOFF_ARRIVE = 3.0D;
    /** 整趟赶路的超时（2 分钟）。 */
    private static final int TRIP_TIMEOUT_TICKS = 20 * 120;
    /** 算不出路线后的重试间隔（别每 tick 跑一次 Dijkstra）。 */
    private static final int PLAN_RETRY_TICKS = 40;
    /** 规划结果缓存多久（通勤 goal 每 tick 都会问 {@link #handlesTrip()}）。 */
    private static final int PLAN_CACHE_TICKS = 20;
    /** 这一趟走不通（点走不到 / 超时）后，同一目的地多少 tick 内交还原版寻路。 */
    private static final int GIVE_UP_TICKS = 20 * 30;

    /** 判定「原地不动」的位移阈值（格，平方）。 */
    private static final double STALL_MOVE_SQ = 0.0025D;
    /** 原地不动多少 tick 就试着跳一下 + 重新寻路（2 秒）。 */
    private static final int STALL_JUMP_TICKS = 40;
    /** 原地不动多少 tick 就放弃这个点、直接跳下一个点（6 秒）。 */
    private static final int STALL_SKIP_TICKS = 120;
    /** 「已经越过这个点」的判定：到下一个点的水平距离比到当前点近 2 格以上。 */
    private static final double PASSED_NODE_MARGIN_SQ = 4.0D;
    /** 排队时多少 tick 重试一次进段。 */
    private static final int WAIT_RECHECK_TICKS = 10;
    /** 排队超过这么久（100 tick = 5 秒）就认为撞上死锁，直接送过这一段。 */
    private static final int DEADLOCK_WAIT_TICKS = 100;

    /** 这次要去的地方。 */
    private record Destination(BlockPos pos, String name, String key) {
    }

    /** 一次规划结果：目的地 + 要依次走过的点。 */
    private record Trip(Destination destination, List<PathNode> route) {
    }

    private final ResidentEntity resident;
    private String destinationKey = "";
    /** 这一趟的最终目的地（走完路网后「最后几格」追它用）。 */
    @Nullable
    private BlockPos destinationPos;
    @Nullable
    private List<PathNode> route;
    private int index;
    private int navTicks;
    private int tripTicks;
    /** 路网走完了、正在追最后几格。 */
    private boolean handingOff;
    private int handoffTicks;
    /** 原地不动的累计 tick（楼梯卡住的检测，2026-11-24 主人报）。 */
    private int stallTicks;
    private double lastX;
    private double lastY;
    private double lastZ;

    // ---- 路网第三轮：排队 / 迎面避让（`0.3.1-alpha.11`，2026-11-24）----

    /** 这一趟正在占着的段（两个点 id）；-1 = 没占（不在段上 / 已经到点释放了）。 */
    private int segmentFrom = -1;
    private int segmentTo = -1;
    /** 正在段口的节点上排队等通行。 */
    private boolean waiting;
    private int waitTicks;

    /** 规划缓存（通勤 goal 每 tick 都会问，别每次都跑 Dijkstra）。 */
    @Nullable
    private Trip cachedTrip;
    private String cachedKey = "";
    private int cachedAtTick = Integer.MIN_VALUE;
    /** 算不出路线的目的地 key 与解禁时间。 */
    private String failedKey = "";
    private int retryAfterTick;
    /** 走不通之后「交还原版」的截止时间（这段时间通勤 goal 照常跑）。 */
    private String giveUpKey = "";
    private int giveUpUntil;

    public ResidentPathNetGoal(ResidentEntity resident) {
        this.resident = resident;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /**
     * 「这一趟该不该由路网来走」——**通勤 goal 与本 goal 共用同一个判定**，保证两边不会互相卡死。
     *
     * @return true = 有可用路线，通勤 goal 应该让位
     */
    public boolean handlesTrip() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        return basicAllowed() && planTrip(level) != null;
    }

    /** 共用的前置条件（两个 goal 都要求这些）。 */
    private boolean basicAllowed() {
        if (!this.resident.isAlive() || this.resident.isDeadOrDying() || this.resident.isSleeping()) {
            return false;
        }
        // 跟随 / 站立永远最高优先级：一切自动赶路都让位（主人 2026-11-24）
        if (!ResidentJobs.IDLE.equals(this.resident.jobKey())) {
            return false;
        }
        if (this.resident.isInCombat() || !this.resident.canLookAround()) {
            return false;
        }
        // 注意：**不能**用「体力见底就不准走路网」来挡 —— 累瘫回床正是最需要路网的一趟。
        return true;
    }

    @Override
    public boolean canUse() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        if (!basicAllowed()) {
            return false;
        }
        Trip trip = planTrip(level);
        if (trip == null) {
            return false;
        }
        this.route = trip.route();
        this.destinationKey = trip.destination().key();
        this.destinationPos = trip.destination().pos();
        return true;
    }

    /**
     * 规划这一趟（带缓存）。目的地来源（不新开指令）：
     * 调试棒 / 回床的 {@code commuteTarget} → 上班路上的工作区 → 日程挑好的娱乐区。
     */
    @Nullable
    private Trip planTrip(ServerLevel level) {
        Destination target = pickDestination(level);
        if (target == null) {
            return null;
        }
        int now = this.resident.tickCount;
        if (target.key().equals(this.giveUpKey) && now < this.giveUpUntil) {
            return null;
        }
        if (target.key().equals(this.failedKey) && now < this.retryAfterTick) {
            return null;
        }
        if (this.cachedTrip != null && target.key().equals(this.cachedKey)
                && now - this.cachedAtTick < PLAN_CACHE_TICKS) {
            return this.cachedTrip;
        }
        this.cachedKey = target.key();
        this.cachedAtTick = now;
        if (this.resident.blockPosition().distSqr(target.pos())
                < MIN_TRIP_DISTANCE * MIN_TRIP_DISTANCE) {
            this.cachedTrip = null;
            return null;
        }
        // 入口点要验「原版寻路走得到」：最近的入口被墙挡住时换成同层下一个入口
        // （2026-11-24 主人报「只能把居民拖到楼梯上才肯走」）
        List<PathNode> planned = PathRoute.plan(level, this.resident.blockPosition(), target.pos(),
            this::entryReachable);
        if (planned == null || planned.size() < 2) {
            this.failedKey = target.key();
            this.retryAfterTick = now + PLAN_RETRY_TICKS;
            this.cachedTrip = null;
            return null;
        }
        this.cachedTrip = new Trip(target, planned);
        return this.cachedTrip;
    }

    /** 居民能不能用原版寻路走到这个入口点。 */
    private boolean entryReachable(PathNode node) {
        net.minecraft.world.level.pathfinder.Path path =
            this.resident.getNavigation().createPath(node.pos(), 1);
        return path != null && path.canReach();
    }

    @Nullable
    private Destination pickDestination(ServerLevel level) {
        // 1）调试棒 / 回床：已经有明确的通勤目标
        BlockPos commute = this.resident.commuteTarget();
        if (commute != null) {
            return new Destination(commute, this.resident.destinationText().getString(),
                "commute:" + commute.asLong());
        }
        // 累瘫 + 有床：这时候**只能去睡**，不许把居民带去上班 / 娱乐再折返
        // （2026-11-24 主人报「回到房间又立刻去树场、到楼梯又回房间」的死循环：
        //  工作 goal 自己会因体力拒绝上班，但路网 goal 以前不看体力，照样把它带到工作区，
        //  到了以后工作 goal 不启动、回床目标又回来 → 来回拉锯）
        if (exhaustedWithBed()) {
            return null;
        }
        // 2）上班路上（工作阶段、还没走进工作区）
        if (this.resident.schedule().isWorkPhase()) {
            WorkZone zone = WorkZoneData.get(level)
                .findByEmployee(this.resident.getUUID().toString());
            if (zone != null && !zone.inWorkRangeBody(this.resident)) {
                BlockPos center = BlockPos.containing(zone.box().getCenter());
                return new Destination(center, zone.name(), "work:" + zone.key());
            }
        }
        // 3）去娱乐区的路上（日程已经挑好了区）
        if (this.resident.schedule().isFree()) {
            WorkZone planned = this.resident.schedule().entertainmentTarget();
            if (planned != null) {
                BlockPos center = BlockPos.containing(planned.box().getCenter());
                return new Destination(center, planned.name(), "fun:" + planned.key());
            }
        }
        return null;
    }

    /**
     * 体力见底（≤10%）又有床 —— 这时候路网只负责「回床」，**不接上班 / 娱乐**。
     *
     * <p>不跟着 `bedReturnGivenUp` 松口：那会让「放弃这轮睡觉」的 60 秒里居民又被网络带去工作区，
     * 然后回床目标一来又往回走，来回拉锯（主人 2026-11-24 报的树场 / 房间死循环）。
     * 真放弃了睡觉的居民，上班走路交回原版寻路即可。</p>
     */
    private boolean exhaustedWithBed() {
        return this.resident.stamina() <= ResidentEntity.EXHAUSTED_STAMINA_THRESHOLD
            && this.resident.bedPos() != null;
    }

    @Override
    public void start() {
        this.index = 0;
        this.navTicks = 0;
        this.stallTicks = 0;
        this.tripTicks = 0;
        this.waiting = false;
        this.waitTicks = 0;
        this.segmentFrom = -1;
        this.segmentTo = -1;
        this.lastX = this.resident.getX();
        this.lastY = this.resident.getY();
        this.lastZ = this.resident.getZ();
        this.resident.setPathTripActive(true);
        // 状态显示：沿用已有的「通勤 + 目的地」（主人 2026-11-24：不要单独的「赶路」状态）
        this.applyTripStatus(ResidentJobs.COMMUTE);
        // 速度：走 WP 已有的移速系统（心情档 + 体力/饥饿封顶），和通勤 goal 完全一致
        this.applyTripSpeed();
        this.walkToCurrentNode(true);
    }

    /**
     * 写这一趟的状态显示（「通勤 + 目的地」／排队时「等待通行 + 目的地」）。
     *
     * <p>2026-11-24 第三轮新增「等待通行」：底下那行仍然是目的地，玩家一眼看出「他在等路」。</p>
     */
    private void applyTripStatus(String jobId) {
        String name = this.destinationName();
        if (name.isEmpty() && this.resident.commuteTarget() != null) {
            name = this.resident.destinationKey();
        }
        this.resident.setWorkActivity(jobId, name);
    }

    /** 每 20 tick 重新套一次速度（心情 / 体力变化立刻生效，口径 = 心情·体力档）。 */
    private void applyTripSpeed() {
        this.resident.applyTravelSpeed();
    }

    private String destinationName() {
        Trip trip = this.cachedTrip;
        return trip == null ? "" : trip.destination().name();
    }

    @Override
    public boolean canContinueToUse() {
        if (!basicAllowed()) {
            return false;
        }
        if (this.tripTicks >= TRIP_TIMEOUT_TICKS) {
            return false;
        }
        // 目的地没了（区域被删 / 指令被清 / 日程改主意）→ 收工
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        Destination current = pickDestination(level);
        if (current == null || !current.key().equals(this.destinationKey)) {
            return false;
        }
        // 「追最后几格」阶段：路线已经清空，但这一趟还没结束
        if (this.handingOff) {
            return this.handoffTicks <= HANDOFF_TICKS;
        }
        return this.route != null && this.index < this.route.size();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        this.tripTicks++;
        this.trackProgress();
        if (this.resident.tickCount % 20 == 0) {
            this.applyTripSpeed();
        }
        // 段占用每 10 tick 刷新一次（实体被卸载 / goal 异常结束由 PathTraffic 的老化兜底）
        if (this.resident.tickCount % 10 == 0) {
            this.touchSegment();
        }
        // 在段口排队等通行：别的什么都不做（stallTicks 由 tickWaiting 清零，别误判成「卡住」）
        if (this.waiting) {
            this.tickWaiting();
            return;
        }
        // 路网走完了：追最后几格（以前直接交还原版，结果站在出口锚点上不动）
        if (this.handingOff) {
            this.tickHandoff();
            return;
        }
        if (this.route == null || this.index >= this.route.size()) {
            return;
        }
        PathNode node = this.route.get(this.index);

        // 1）已经越过这个点（楼梯上点太密 / 点落在方块里）：到下一个点更近就直接算过点
        if (this.index + 1 < this.route.size()
                && passedNext(this.route.get(this.index + 1), node)) {
            this.advance(false);
            return;
        }
        // 2）正常到点
        if (this.atNode(node)) {
            this.advance(true);
            return;
        }
        // 3）还没到：按节流重新寻路
        if (--this.navTicks <= 0 || this.resident.getNavigation().isDone()) {
            this.navTicks = REPATH_INTERVAL;
            this.walkToCurrentNode(false);
        }
        // 4）原地不动（楼梯卡住）：先跳一下、重算路；还不动就放弃这一趟
        if (this.stallTicks == STALL_JUMP_TICKS || this.stallTicks == STALL_JUMP_TICKS * 2) {
            this.resident.getJumpControl().jump();
            this.walkToCurrentNode(true);
        } else if (this.stallTicks > STALL_SKIP_TICKS) {
            // 这个点走不到（点悬空 / 点被方块埋 / 楼梯太窄）：结束这一趟，交给原版寻路兜底
            this.giveUp();
        }
    }

    /** 走完路网后的「最后几格」：能走就走到目的地附近，走不动 / 超时就把控制权还给原来的 goal。 */
    private void tickHandoff() {
        if (++this.handoffTicks > HANDOFF_TICKS || this.destinationPos == null) {
            this.finish();
            return;
        }
        if (this.resident.blockPosition().distSqr(this.destinationPos)
                <= HANDOFF_ARRIVE * HANDOFF_ARRIVE) {
            this.finish();
            return;
        }
        if (this.resident.getNavigation().isDone() || this.stallTicks > STALL_SKIP_TICKS) {
            // 最后几格走完了 / 走不到：交回原来的 goal（通勤 / 工作 / 睡眠），别在这里死等
            this.finish();
        }
    }

    /** 开始「追最后几格」：目的地能用原版走过去才追，否则直接交回给原来的 goal。 */
    private void beginHandoff() {
        this.route = null;
        this.index = 0;
        if (this.destinationPos == null
                || this.resident.blockPosition().distSqr(this.destinationPos)
                    <= HANDOFF_ARRIVE * HANDOFF_ARRIVE
                || !this.canReach(this.destinationPos)) {
            this.finish();
            return;
        }
        this.handingOff = true;
        this.handoffTicks = 0;
        this.stallTicks = 0;
        this.resident.getNavigation().moveTo(
            this.destinationPos.getX() + 0.5D, this.destinationPos.getY(),
            this.destinationPos.getZ() + 0.5D, TRIP_SPEED);
    }

    /** 原版寻路能不能走到这个方块（用来跳过画歪 / 被埋的点）。 */
    private boolean canReach(BlockPos pos) {
        net.minecraft.world.level.pathfinder.Path path =
            this.resident.getNavigation().createPath(pos, 1);
        return path != null && path.canReach();
    }

    /** 记录「有没有真的在走」（`navDone` 只是说原版路径走完了，不代表人在动）。 */
    private void trackProgress() {
        double movedSq = this.resident.position()
            .distanceToSqr(this.lastX, this.lastY, this.lastZ);
        if (movedSq > STALL_MOVE_SQ) {
            this.stallTicks = 0;
            this.lastX = this.resident.getX();
            this.lastY = this.resident.getY();
            this.lastZ = this.resident.getZ();
        } else {
            this.stallTicks++;
        }
    }

    /** 到下一个点的水平距离比到当前点近 {@link #PASSED_NODE_MARGIN_SQ} 格² 以上 = 已经越过当前点。 */
    private boolean passedNext(PathNode next, PathNode current) {
        double toNext = horizontalDistSq(next);
        double toCurrent = horizontalDistSq(current);
        return toNext + PASSED_NODE_MARGIN_SQ < toCurrent;
    }

    /** 到下一个点（或结束这一趟）。 */
    private void advance(boolean performAction) {
        PathNode node = this.route == null ? null : this.route.get(this.index);
        if (node != null) {
            this.performAction(node);
        }
        // 到点了：把刚走完的那一段的通行权还回去（别人可以接着走）
        this.releaseSegment();
        this.index++;
        this.stallTicks = 0;
        this.navTicks = 0;
        this.lastX = this.resident.getX();
        this.lastY = this.resident.getY();
        this.lastZ = this.resident.getZ();
        if (this.route == null || this.index >= this.route.size()) {
            // 到出口点了：接「最后几格」（能走就走过去，走不到再交还原版）
            this.beginHandoff();
            return;
        }
        // 下一段：先占段，占不到就在这个节点上排队（「等待通行」）
        if (!this.beginNextSegment()) {
            return;
        }
        this.walkToCurrentNode(true);
    }

    // ------------------------------------------------------------------
    // 第三轮：段的预约 / 排队 / 死锁兜底（`0.3.1-alpha.11`，2026-11-24）
    // ------------------------------------------------------------------

    /** 走向 {@code route[index]} 之前，先预约「上一个点 → 这个点」这一段。 */
    private boolean beginNextSegment() {
        if (this.route == null || this.index <= 0 || this.index >= this.route.size()) {
            return true;
        }
        PathNode from = this.route.get(this.index - 1);
        PathNode to = this.route.get(this.index);
        if (this.tryEnterSegment(from, to)) {
            return true;
        }
        this.startWaiting();
        return false;
    }

    /** 段容量 = 两端点宽度取小；没有被路网记录成段的位置直接放行。 */
    private boolean tryEnterSegment(PathNode from, PathNode to) {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return true;
        }
        if (from.id() == to.id()) {
            return true;
        }
        PathNetworkData data = PathNetworkData.get(level);
        if (!data.hasSegment(from.id(), to.id())) {
            this.segmentFrom = from.id();
            this.segmentTo = to.id();
            return true;
        }
        int capacity = Math.max(1, Math.min(from.width(), to.width()));
        PathTraffic.Enter enter = PathTraffic.of(level).tryEnter(from.id(), to.id(),
            this.resident.getUUID(), capacity, level.getGameTime());
        if (enter == PathTraffic.Enter.FULL) {
            return false;
        }
        this.segmentFrom = from.id();
        this.segmentTo = to.id();
        return true;
    }

    private void releaseSegment() {
        if (this.segmentFrom < 0) {
            return;
        }
        if (this.resident.level() instanceof ServerLevel level) {
            PathTraffic.of(level).release(this.segmentFrom, this.segmentTo,
                this.resident.getUUID());
        }
        this.segmentFrom = -1;
        this.segmentTo = -1;
    }

    private void touchSegment() {
        if (this.segmentFrom < 0 || this.segmentTo < 0) {
            return;
        }
        if (this.resident.level() instanceof ServerLevel level) {
            PathTraffic.of(level).touch(this.segmentFrom, this.segmentTo,
                this.resident.getUUID(), level.getGameTime());
        }
    }

    /** 段口排队：停下脚步、状态改成「等待通行」，并尽量侧身让开节点。 */
    private void startWaiting() {
        if (this.waiting) {
            return;
        }
        this.waiting = true;
        this.waitTicks = 0;
        this.stallTicks = 0;
        this.resident.getNavigation().stop();
        this.applyTripStatus(ResidentJobs.WAITING);
        this.stepAside();
    }

    /** 排队等待中：每 {@link #WAIT_RECHECK_TICKS} tick 试一次进段；等太久就死锁兜底。 */
    private void tickWaiting() {
        this.waitTicks++;
        this.stallTicks = 0;
        // 等久了：对面可能卡在段上。先把这一趟的居民直接送到下一节点（主人：保证每个人都能走过去）
        if (this.waitTicks > DEADLOCK_WAIT_TICKS && this.teleportPastSegment()) {
            return;
        }
        if (this.waitTicks % WAIT_RECHECK_TICKS != 0) {
            return;
        }
        if (this.route == null || this.index <= 0 || this.index >= this.route.size()) {
            this.waiting = false;
            return;
        }
        PathNode from = this.route.get(this.index - 1);
        PathNode to = this.route.get(this.index);
        if (!this.tryEnterSegment(from, to)) {
            return;
        }
        this.waiting = false;
        this.waitTicks = 0;
        this.applyTripStatus(ResidentJobs.COMMUTE);
        this.walkToCurrentNode(true);
    }

    /**
     * 死锁兜底：排队太久，直接把居民送到下一个节点（算走到、继续走），别人也能过去。
     *
     * @return true = 已经处理过（这一趟继续往前），false = 没法传送（交给调用方继续等）
     */
    private boolean teleportPastSegment() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        if (this.route == null || this.index <= 0 || this.index >= this.route.size()) {
            return false;
        }
        PathNode to = this.route.get(this.index);
        if (!ResidentEntity.isSafeStand(level, to.pos())) {
            // 点被方块埋住 / 悬空：传送会卡进去。直接强行进段（双方都过，物理上推挤一下就好）
            this.waiting = false;
            this.waitTicks = 0;
            this.applyTripStatus(ResidentJobs.COMMUTE);
            this.walkToCurrentNode(true);
            return true;
        }
        this.releaseSegment();
        this.resident.teleportTo(to.pos().getX() + 0.5D, to.pos().getY(), to.pos().getZ() + 0.5D);
        this.resident.getNavigation().stop();
        this.waiting = false;
        this.waitTicks = 0;
        this.applyTripStatus(ResidentJobs.COMMUTE);
        this.index++; // 已经到 to 了
        this.stallTicks = 0;
        this.navTicks = 0;
        this.lastX = this.resident.getX();
        this.lastY = this.resident.getY();
        this.lastZ = this.resident.getZ();
        if (this.index >= this.route.size()) {
            this.beginHandoff();
            return true;
        }
        if (this.beginNextSegment()) {
            this.walkToCurrentNode(true);
        }
        return true;
    }

    /**
     * 排队时侧身让开节点：在所在节点周围找一个能站、离自己最近的邻格站过去，
     * 别挡在路口（路网段本来就窄，站着不动就是堵门）。
     */
    private void stepAside() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return;
        }
        if (this.route == null || this.index <= 0 || this.index >= this.route.size()) {
            return;
        }
        BlockPos base = this.route.get(this.index - 1).pos();
        BlockPos current = this.resident.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                BlockPos candidate = base.offset(dx, 0, dz);
                if (!ResidentEntity.isSafeStand(level, candidate)) {
                    continue;
                }
                if (candidate.equals(current)) {
                    continue;
                }
                double distance = candidate.distSqr(current);
                if (distance < bestDist) {
                    bestDist = distance;
                    best = candidate;
                }
            }
        }
        if (best == null || bestDist > 4.0D) {
            return;
        }
        this.resident.getNavigation().moveTo(best.getX() + 0.5D, best.getY(),
            best.getZ() + 0.5D, TRIP_SPEED);
    }

    private double horizontalDistSq(PathNode node) {
        double dx = this.resident.getX() - (node.pos().getX() + 0.5D);
        double dz = this.resident.getZ() - (node.pos().getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    private void walkToCurrentNode(boolean force) {
        if (this.route == null || this.index >= this.route.size()) {
            return;
        }
        PathNode node = this.route.get(this.index);
        if (!force && this.resident.getNavigation().getPath() != null
                && !this.resident.getNavigation().isDone()) {
            return;
        }
        if (this.navigateTo(node)) {
            return;
        }
        // 这个点原版走不到（画歪 / 被方块埋 / 隔着一层楼）：往后看 1~2 个点，能走到就直接跳过
        for (int ahead = 1; ahead <= 2 && this.index + ahead < this.route.size(); ahead++) {
            PathNode candidate = this.route.get(this.index + ahead);
            if (!this.canReach(candidate.pos())) {
                continue;
            }
            this.index += ahead;
            this.stallTicks = 0;
            this.navTicks = 0;
            // 跳过一个走不到的点 = 抄近路绕过它：原来那一段的占用先留着，
            // 等真的到下一个点（advance）再一起还回去，别人不会从后面挤上来。
            this.navigateTo(candidate);
            return;
        }
    }

    private boolean navigateTo(PathNode node) {
        return this.resident.getNavigation().moveTo(
            node.pos().getX() + 0.5D, node.pos().getY(), node.pos().getZ() + 0.5D, TRIP_SPEED);
    }

    /** 到点后的动作（走 / 跳 / 爬梯 / 传送）。 */
    private void performAction(PathNode node) {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return;
        }
        PathAction action = node.action();
        if (action == PathAction.JUMP) {
            this.resident.getJumpControl().jump();
        } else if (action == PathAction.TELEPORT) {
            // 传送点：直接落到这个点上（必须能站，不然会卡进方块）
            if (ResidentEntity.isSafeStand(level, node.pos())) {
                this.resident.teleportTo(node.pos().getX() + 0.5D, node.pos().getY(),
                    node.pos().getZ() + 0.5D);
            }
        }
        // WALK / CLIMB：原版寻路上台阶 / 贴梯子自己会处理
    }

    /** 到达某个点：水平按「点是否被方块埋住」放宽，垂直 1.5 格内。 */
    private boolean atNode(PathNode node) {
        double dy = this.resident.getY() - node.pos().getY();
        if (Math.abs(dy) > NODE_ARRIVE_VERTICAL) {
            return false;
        }
        double limit = NODE_ARRIVE_HORIZONTAL_SQ;
        if (this.resident.level().getBlockState(node.pos()).blocksMotion()) {
            limit = BLOCKED_NODE_ARRIVE_SQ;
        }
        return this.horizontalDistSq(node) <= limit;
    }

    /** 正常走完整条路线：**不设冷却**（到了出口点后起点=出口点，下一次规划自然就返回 null）。 */
    private void finish() {
        this.releaseSegment();
        this.waiting = false;
        this.waitTicks = 0;
        this.route = null;
        this.index = 0;
        this.handingOff = false;
        this.handoffTicks = 0;
        this.cachedTrip = null;
        this.cachedAtTick = Integer.MIN_VALUE;
    }

    /** 这一趟走不通：30 秒内同一目的地交还原版寻路（之后可以再试一次）。 */
    private void giveUp() {
        this.releaseSegment();
        this.waiting = false;
        this.waitTicks = 0;
        this.giveUpKey = this.destinationKey;
        this.giveUpUntil = this.resident.tickCount + GIVE_UP_TICKS;
        this.failedKey = this.destinationKey;
        this.retryAfterTick = this.resident.tickCount + GIVE_UP_TICKS;
        this.route = null;
        this.index = 0;
        this.handingOff = false;
        this.handoffTicks = 0;
        this.cachedTrip = null;
    }

    @Override
    public void stop() {
        this.resident.getNavigation().stop();
        this.resident.applyMovementSpeed(ResidentEntity.BASE_MOVEMENT_SPEED);
        this.resident.setPathTripActive(false);
        // 第三轮：结束这一趟就把段让出来（同一段别的居民还在等）
        this.releaseSegment();
        if (this.resident.level() instanceof ServerLevel level) {
            PathTraffic.of(level).releaseAll(this.resident.getUUID());
        }
        this.waiting = false;
        this.waitTicks = 0;
        this.route = null;
        this.index = 0;
        this.stallTicks = 0;
        this.handingOff = false;
        this.handoffTicks = 0;
        this.destinationPos = null;
        this.cachedTrip = null;
        // 清掉「通勤」状态；调试棒 / 回床的目标由原来的通勤 goal 接管（它的目的地数据没被覆盖）
        if (this.resident.commuteTarget() == null) {
            this.resident.setWorkActivity(null, "");
        } else {
            this.resident.setActivity(ResidentJobs.COMMUTE);
        }
    }

    /** 诊断用：现在正在赶路吗（/worldplayers diag）。 */
    public boolean running() {
        return this.route != null && this.index < this.route.size();
    }

    /** 诊断用：当前要去哪。 */
    @Nullable
    public BlockPos destination() {
        Trip trip = this.cachedTrip;
        return trip == null ? null : trip.destination().pos();
    }
}
