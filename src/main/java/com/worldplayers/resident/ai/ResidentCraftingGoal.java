package com.worldplayers.resident.ai;

import com.worldplayers.resident.ResidentCrafting;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentInventory;
import com.worldplayers.resident.ResidentJobs;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 「闲逛时在暗处做点手工 + 插火把」（v0.2.2）。
 *
 * <h3>触发条件（暂时只有这一条）</h3>
 * <ul>
 *   <li>居民处于**闲逛**（{@link ResidentJobs#IDLE}）—— 跟随、站立、战斗都不做；</li>
 *   <li>附近 8 格内有**亮度 0**、且伸手够得到（4.5 格内）的可放置位置；</li>
 *   <li>背包里**已经有火把**，或者能用 2×2 配方链现做出火把（原木 → 木板 → 木棍 → 火把）。</li>
 * </ul>
 *
 * <h3>动作节奏（主人定的手感）</h3>
 * <ul>
 *   <li>每次「甩手」= 一次合成/一次插火把，然后**呆站 1 秒**再做下一次；</li>
 *   <li>呆站时不走路（{@code navigation.stop}）、不锁任何实体（视线固定看向正前方）；</li>
 *   <li>甩手只是动画（{@code swing}），**没有伤害、没有攻击音效、没有击退**；</li>
 *   <li>合成产物放进**主手**，此后就是普通物品 —— 战斗/上弦/捡装备等更高优先级的东西
 *       需要主手时，随时可以把它挤回背包（主人说的"抢优先级"）。</li>
 * </ul>
 *
 * <p>战斗会立刻打断：这个 goal 的优先级低于战斗，且 {@link #canContinueToUse()} 一见仇恨/切模式就退出。
 * 玩家正在翻背包时也不做（怕东西在界面上"自己变"）。</p>
 */
public class ResidentCraftingGoal extends Goal {
    /** 触发探测半径（水平 ±8、垂直 ±4，"中等范围"）。 */
    private static final int SCAN_HORIZONTAL = 8;
    private static final int SCAN_VERTICAL = 4;
    /** 伸手距离平方：4.5 格（和玩家摆放方块的触及距离同一档）。 */
    private static final double REACH_SQR = 4.5D * 4.5D;
    /** 每个动作之间的呆站时间：1 秒。 */
    private static final int ACTION_INTERVAL = 20;
    /** 一次闲逛最多做多少个动作，防止站在暗洞里永远做下去。 */
    private static final int MAX_ACTIONS = 12;
    /** 一轮结束后的冷却（5 秒），免得刚做完又立刻开工。 */
    private static final int COOLDOWN = 100;
    /** 没有材料/没有暗块时的重新探测间隔。 */
    private static final int RESCAN_INTERVAL = 40;

    private final ResidentEntity resident;
    private final List<ResidentCrafting.Step> plan = new ArrayList<>();
    private final List<ResidentCrafting.Step> pending = new ArrayList<>();

    private int actionTicks;
    private int actions;
    private int cooldown;
    private int nextScanTick;
    private int lastWarnTick = -1000;
    private boolean finished;

    public ResidentCraftingGoal(ResidentEntity resident) {
        this.resident = resident;
        // MOVE：呆站时不许闲逛/乱走；LOOK：不锁实体，也不让随机看东西抢镜头
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // 有工作区的员工不插火把（不然它会为了点灯跑出工作区，主人 2026-11-20）
        if (this.resident.level() instanceof net.minecraft.server.level.ServerLevel serverLevel
                && com.worldplayers.resident.WorkZoneData.get(serverLevel)
                    .findByEmployee(this.resident.getUUID().toString()) != null) {
            return false;
        }
        if (!this.canWork()) {
            return false;
        }
        if (this.cooldown > 0) {
            this.cooldown--;
            return false;
        }
        if (this.resident.tickCount < this.nextScanTick) {
            return false;
        }
        this.nextScanTick = this.resident.tickCount + RESCAN_INTERVAL;
        boolean following = ResidentJobs.FOLLOW.equals(this.resident.jobKey());
        // 跟随中只能插火把、**不合成**（主人 2026-11-23）：手里没现成火把就直接不启动
        if (following && !this.hasTorch()) {
            return false;
        }

        // 先要暗到值得点灯，再要"有火把 / 做得出来"
        if (this.findDarkSpot() == null) {
            this.warn("no dark spot within reach");
            return false;
        }
        this.pending.clear();
        if (!this.hasTorch()) {
            this.pending.addAll(ResidentCrafting.planTorch(this.resident.level(), this.resident));
            if (this.pending.isEmpty()) {
                this.warn("no torch and no craftable plan");
                return false;
            }
        }
        return true;
    }

    /** 调试用节流日志：为什么没开始手工（10 秒最多一条）。 */
    private void warn(String reason) {
        if (this.resident.tickCount - this.lastWarnTick < 200) {
            return;
        }
        this.lastWarnTick = this.resident.tickCount;
        // 2026-11-23：改成 DEBUG（这条以前是 WARN，居民多的时候日志也会跟着涨）
        com.worldplayers.WorldPlayers.LOGGER.debug("World Players: {} crafting not triggered: {} (job={}, target={}, canLook={})",
            this.resident.getName().getString(), reason, this.resident.jobKey(),
            this.resident.getTarget() == null ? "null" : this.resident.getTarget().getName().getString(),
            this.resident.canLookAround());
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.canWork() || this.finished) {
            return false;
        }
        // 跟随中不许合成：还没做完的合成计划直接作废
        if (ResidentJobs.FOLLOW.equals(this.resident.jobKey()) && !this.plan.isEmpty()) {
            return false;
        }
        return this.actions < MAX_ACTIONS;
    }

    @Override
    public void start() {
        this.resident.getNavigation().stop();
        this.actions = 0;
        this.actionTicks = 1; // 开工第一下立刻甩，之后按 1 秒节奏
        this.finished = false;
        this.plan.clear();
        this.plan.addAll(this.pending);
        this.pending.clear();
        this.stareAhead();
        com.worldplayers.WorldPlayers.LOGGER.info("World Players: {} starts crafting ({} steps)",
            this.resident.getName().getString(), this.plan.size());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        // 呆站：不许乱走
        this.resident.getNavigation().stop();
        if (this.finished) {
            return;
        }
        if (--this.actionTicks > 0) {
            return;
        }
        this.actionTicks = ACTION_INTERVAL;

        // 上一批东西做完了：如果手里没火把，就再盘一次（材料可能刚被捡回来）
        if (this.plan.isEmpty() && !this.hasTorch()) {
            if (ResidentJobs.FOLLOW.equals(this.resident.jobKey())) {
                // 跟着主人时不合成（主人 2026-11-23）
                this.finished = true;
                return;
            }
            this.plan.addAll(ResidentCrafting.planTorch(this.resident.level(), this.resident));
            if (this.plan.isEmpty()) {
                this.finished = true;
                return;
            }
        }

        if (!this.plan.isEmpty()) {
            ResidentCrafting.Step step = this.plan.remove(0);
            // 合成期间对外显示「合成」工作状态（插火把不算，主人 2026-11-19 明确要求）
            this.resident.setActivity(ResidentJobs.CRAFTING);
            if (!ResidentCrafting.execute(this.resident, step)) {
                this.finished = true;
                return;
            }
            this.actions++;
            this.resident.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            return;
        }

        // 手里有火把（或背包里有）→ 插一支；放置火把不写工作状态
        this.resident.setActivity(null);
        if (!this.placeTorch()) {
            this.finished = true;
            return;
        }
        this.actions++;
        this.resident.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    @Override
    public void stop() {
        this.plan.clear();
        this.pending.clear();
        this.finished = true;
        this.cooldown = COOLDOWN;
        this.resident.setActivity(null);
        this.resident.getNavigation().stop();
    }

    // ------------------------------------------------------------------
    // 条件
    // ------------------------------------------------------------------

    /** 只有闲逛 / 跟随、没仇恨、活着、没被玩家翻背包的时候才工作（跟随中只插火把、不合成）。 */
    private boolean canWork() {
        boolean idle = ResidentJobs.IDLE.equals(this.resident.jobKey());
        boolean following = ResidentJobs.FOLLOW.equals(this.resident.jobKey());
        return this.resident.isAlive()
            && !this.resident.isDeadOrDying()
            && (idle || following)
            && this.resident.getTarget() == null
            && this.resident.canLookAround();
    }

    /** 视线固定看向正前方 —— 目光不锁任何实体。 */
    private void stareAhead() {
        float yaw = this.resident.getYRot() * Mth.DEG_TO_RAD;
        double dx = -Mth.sin(yaw);
        double dz = Mth.cos(yaw);
        this.resident.getLookControl().setLookAt(
            this.resident.getX() + dx * 3.0D,
            this.resident.getEyeY(),
            this.resident.getZ() + dz * 3.0D);
    }

    // ------------------------------------------------------------------
    // 找暗点 / 插火把
    // ------------------------------------------------------------------

    /**
     * 附近「亮度 0 + 够得到 + 放得下火把」的位置里，离居民最近的那个。
     *
     * <p>只在闲逛且需要的时候调用（{@link #canUse()} 有 40 tick 节流），
     * 半径 8×4×8 的方块读取对这个频率来说完全可以忽略。</p>
     */
    @Nullable
    private BlockPos findDarkSpot() {
        return findDarkSpot(this.resident);
    }

    /** 诊断用：给定居民找最近的可插火把暗点（不依赖 goal 实例）。 */
    @Nullable
    static BlockPos findDarkSpot(ResidentEntity resident) {
        if (!(resident.level() instanceof ServerLevel level)) {
            return null;
        }
        BlockPos origin = resident.blockPosition();
        BlockState torch = Blocks.TORCH.defaultBlockState();
        AABB selfBox = resident.getBoundingBox();
        BlockPos best = null;
        double bestDistance = REACH_SQR;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-SCAN_HORIZONTAL, -SCAN_VERTICAL, -SCAN_HORIZONTAL),
                origin.offset(SCAN_HORIZONTAL, SCAN_VERTICAL, SCAN_HORIZONTAL))) {
            if (level.getBlockState(pos).isAir()) {
                // 亮度判定（v0.2.4）：
                // - 方块光必须是 0：不往已经点着的地方再插一支；
                // - 综合亮度 <= 4：夜晚户外的天空光会被压到 4（15 - skyDarken 11），
                //   用 == 0 的话"晚上在外面永远不插"，白天户外 = 15、洞里 = 0、暗房 = 0，都符合直觉。
                if (level.getBrightness(LightLayer.BLOCK, pos) != 0) {
                    continue;
                }
                if (level.getMaxLocalRawBrightness(pos) > 4) {
                    continue;
                }
                if (!torch.canSurvive(level, pos)) {
                    continue;
                }
                if (selfBox.intersects(new AABB(pos))) {
                    continue;
                }
                double distance = resident.distanceToSqr(
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
                if (distance > bestDistance) {
                    continue;
                }
                // 必须有直线视野才插（主人 2026-11-23）：隔着墙 / 地板的位置不算
                if (!hasLineOfSight(level, resident, pos)) {
                    continue;
                }
                bestDistance = distance;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** 眼睛到火把位置的直线视野（中间被实体方块挡住就不插）。 */
    private static boolean hasLineOfSight(ServerLevel level, ResidentEntity resident,
                                          BlockPos pos) {
        net.minecraft.world.phys.Vec3 eye = resident.getEyePosition();
        net.minecraft.world.phys.Vec3 target = net.minecraft.world.phys.Vec3.atCenterOf(pos);
        net.minecraft.world.phys.BlockHitResult hit = level.clip(
            new net.minecraft.world.level.ClipContext(eye, target,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, resident));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS
            || hit.getBlockPos().equals(pos);
    }

    /** 背包/主手里有没有火把（与 {@link ResidentInventory#holdInMainHand} 的搜索范围一致）。 */
    private boolean hasTorch() {
        return hasTorch(this.resident);
    }

    static boolean hasTorch(ResidentEntity resident) {
        ResidentInventory inventory = resident.inventory();
        for (int slot = 0; slot < ResidentInventory.BACKPACK_START + 27; slot++) {
            if (inventory.getItem(slot).is(Items.TORCH)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 插一支火把：找位置 → **先换到主手** → 从主手扣一支 → 放方块 → 播放置音效。
     *
     * <p>v0.2.7：和玩家一样，火把在手上用掉，剩下的还留在主手（不再从背包角落凭空扣走）。
     * 主手原来拿的东西放回火把原来的槽位。</p>
     */
    private boolean placeTorch() {
        if (!(this.resident.level() instanceof ServerLevel level)) {
            return false;
        }
        BlockPos pos = this.findDarkSpot();
        if (pos == null) {
            return false;
        }
        BlockState torch = Blocks.TORCH.defaultBlockState();
        if (!torch.canSurvive(level, pos)) {
            return false;
        }
        if (!this.consumeTorch()) {
            return false;
        }
        level.setBlockAndUpdate(pos, torch);
        SoundType sound = torch.getSoundType();
        level.playSound(null, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
            sound.getPlaceSound(), SoundSource.BLOCKS,
            (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        return true;
    }

    private boolean consumeTorch() {
        ResidentInventory inventory = this.resident.inventory();
        if (!inventory.holdInMainHand(stack -> stack.is(Items.TORCH))) {
            return false;
        }
        ItemStack main = inventory.getItem(ResidentInventory.MAIN_HAND);
        main.shrink(1);
        if (main.isEmpty()) {
            inventory.setItem(ResidentInventory.MAIN_HAND, ItemStack.EMPTY);
        } else {
            inventory.setChanged();
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 诊断（/worldplayers craft）
    // ------------------------------------------------------------------

    /** 给玩家看的一行行诊断：卡在哪个条件上一目了然（英文/ASCII，日志里不会变问号）。 */
    public static List<String> diagnose(ResidentEntity resident) {
        List<String> out = new ArrayList<>();
        BlockPos origin = resident.blockPosition();
        out.add("resident=" + resident.getName().getString()
            + " job=" + resident.jobKey()
            + " activity=" + resident.activityKey()
            + " target=" + (resident.getTarget() == null ? "null" : resident.getTarget().getName().getString()));
        out.add("alive=" + resident.isAlive()
            + " noAi=" + resident.isNoAi()
            + " canLook=" + resident.canLookAround()
            + " goalRunning=" + resident.isCraftingGoalRunning());
        out.add("pos=" + origin.getX() + "," + origin.getY() + "," + origin.getZ()
            + " blockLight=" + resident.level().getBrightness(LightLayer.BLOCK, origin)
            + " skyLight=" + resident.level().getBrightness(LightLayer.SKY, origin)
            + " raw=" + resident.level().getMaxLocalRawBrightness(origin)
            + " night=" + resident.level().isNight());
        BlockPos dark = findDarkSpot(resident);
        out.add("darkSpot=" + (dark == null ? "none"
            : dark.getX() + "," + dark.getY() + "," + dark.getZ()));
        out.add("hasTorch=" + hasTorch(resident));
        List<ResidentCrafting.Step> plan = ResidentCrafting.planTorch(resident.level(), resident);
        out.add("plan=" + plan.size() + " steps");
        for (ResidentCrafting.Step step : plan) {
            out.add("  " + step);
        }
        ResidentInventory inventory = resident.inventory();
        int shown = 0;
        for (int slot = 0; slot < ResidentInventory.SIZE && shown < 24; slot++) {
            if (!ResidentInventory.isStorageSlot(slot)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            out.add("  inv[" + slot + "]=" + stack.getItem() + " x" + stack.getCount());
            shown++;
        }
        if (shown == 0) {
            out.add("  inv=EMPTY");
        }
        return out;
    }
}
