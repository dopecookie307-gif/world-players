package com.worldplayers.resident.ranch;

import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.WorkZone;
import com.worldplayers.resident.WorkZoneData;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;

/**
 * 牧场的**区域级工作循环**（主人 2026-11-23 定）：剪毛 → 繁殖 → 屠宰，状态记在区域上，
 * 所有员工共用同一个循环，不记在居民身上。
 *
 * <ul>
 *   <li>剪毛：进入阶段先等 1 分钟（每轮剪毛间隔），到达后侦测有毛的绵羊 → 剪光；</li>
 *   <li>繁殖：进入后等 6 分钟 → 数量到顶就跳过喂养，否则有饲料就喂养；完成 / 跳过一律进屠宰；</li>
 *   <li>屠宰：只杀成年动物，优先已进入繁殖冷却的；杀到数量低于最大值就回到剪毛。</li>
 * </ul>
 *
 * <p>「工作区域内没有任何员工时重置循环」靠 {@link #presenceTick} 采样实现：
 * 居民自己的 tick（每 20 tick）上报自己在不在牧场区域里，空白超过 {@link #PRESENCE_GRACE}
 * 就重置。这样战斗 / 社交把工作 goal 打断时（人还在区域内）不会误判成「没人」。</p>
 *
 * <p>这个类只做**状态与时间**，具体动作（走路 / 挥手 / 剪毛 / 喂食 / 击杀）在
 * {@code ResidentWorkGoal.RanchWork} 里，多员工同时干活时阶段转换是幂等的。</p>
 */
public final class RanchCycle {
    /** 剪毛阶段每轮等待：1 分钟。 */
    public static final int SHEAR_WAIT = 20 * 60;
    /** 区域内没人的宽限：3 秒（被打断但人还在区域内不算没人）。 */
    public static final int PRESENCE_GRACE = 60;
    /** 缺剪刀 / 缺饲料的喊话冷却：60 秒（多员工共用，防刷屏）。 */
    public static final int CHAT_COOLDOWN = 20 * 60;
    /**
     * 屠宰动作锁：多员工共用，**同一时刻只允许一个人动手**（1 秒）。
     *
     * <p>2026-11-23 主人报「上限 4 只却杀到只剩 3 只」：两个员工可以同时冲进屠宰阶段、
     * 各挑一只动物，两刀下去数量直接掉 2。现在谁先抢到锁谁杀，其余人这个 tick 不动手，
     * 下一 tick 它们会先看到「数量已经低于上限」而回剪毛。</p>
     */
    public static final int BUTCHER_LOCK_TICKS = 20;

    /** 工作阶段（顺序固定）。 */
    public enum Phase {
        SHEAR,
        BREED,
        BUTCHER
    }

    private static final Map<String, RanchCycle> CACHE = new HashMap<>();

    private Phase phase = Phase.SHEAR;
    /** 当前阶段下一次「到点」的游戏时间。 */
    private long deadline;
    /** 上一次确认「区域里有员工」的游戏时间。 */
    private long lastPresenceTick = Long.MIN_VALUE;
    /** 上一次缺剪刀 / 缺饲料喊话的游戏时间。 */
    private long lastChatTick = Long.MIN_VALUE;
    /** 屠宰动作锁（多员工共用）：下一次允许动手的游戏时间。 */
    private long butcherLockUntil = Long.MIN_VALUE;
    /** 上一次同步到的设定（动物类型 / 数量 / 剪毛 / 范围）：任何一项变化都重置循环。 */
    private String animalType = "";
    private int animalMax = -1;
    private boolean animalShear;
    private long boxMin = Long.MIN_VALUE;
    private long boxMax = Long.MIN_VALUE;

    private RanchCycle() {
    }

    /** 取这个区域的循环状态（按区域唯一键）。 */
    public static RanchCycle get(WorkZone zone) {
        return CACHE.computeIfAbsent(zone.key(), key -> new RanchCycle());
    }

    /** 区域被删 / 改类型：缓存作废。 */
    public static void invalidate(String zoneKey) {
        CACHE.remove(zoneKey);
    }

    /** 换存档：全部清掉（静态缓存跨存档会串数据）。 */
    public static void clearAll() {
        CACHE.clear();
    }

    public Phase phase() {
        return this.phase;
    }

    /** 剪毛阶段是不是已经到点（可以开始这一轮侦测 / 剪毛）。 */
    public boolean shearDue(long now) {
        return this.phase == Phase.SHEAR && now >= this.deadline;
    }

    /** 繁殖阶段是不是已经到点（6 分钟到了，该判断数量 / 饲料）。 */
    public boolean breedDue(long now) {
        return this.phase == Phase.BREED && now >= this.deadline;
    }

    /**
     * 每 tick 由员工的工作代码调用：同步设定并返回当前阶段。
     *
     * <p>设定变了（玩家改了动物类型 / 数量 / 剪毛，或者区域被重新划线）→ 循环从头开始。
     * 用几个字段直接比较，不拼字符串：5 个员工 × 20 tick/秒也不会产生垃圾。</p>
     */
    public Phase poll(WorkZone zone, long now) {
        if (!zone.animalType().equals(this.animalType) || zone.animalMax() != this.animalMax
                || zone.animalShear() != this.animalShear
                || zone.min().asLong() != this.boxMin || zone.max().asLong() != this.boxMax) {
            this.animalType = zone.animalType();
            this.animalMax = zone.animalMax();
            this.animalShear = zone.animalShear();
            this.boxMin = zone.min().asLong();
            this.boxMax = zone.max().asLong();
            reset(now);
        }
        return this.phase;
    }

    /** 重置到「剪毛 + 立即到点」（下一次有员工进来马上开始）。 */
    public void reset(long now) {
        this.phase = Phase.SHEAR;
        this.deadline = now;
        this.butcherLockUntil = Long.MIN_VALUE;
    }

    /** 记录「现在区域里有员工」。空白期之后重新有人 = 从剪毛重新开始。 */
    public void markPresent(long now) {
        if (this.lastPresenceTick != Long.MIN_VALUE
                && now - this.lastPresenceTick > PRESENCE_GRACE) {
            reset(now);
        }
        this.lastPresenceTick = now;
    }

    /**
     * 区域里有人的采样（居民 tick 每 20 tick 调一次）。
     *
     * <p>员工在区域内 → 续上存在；员工在区域外且空白超过宽限 → 重置。
     * 同一区域有别的员工在场时，空白永远不会累积，所以不会被误重置。</p>
     */
    public static void presenceTick(ServerLevel level, ResidentEntity resident, long now) {
        String id = resident.getUUID().toString();
        WorkZone zone = WorkZoneData.get(level).findByEmployee(id);
        if (zone == null || !zone.isRanch()) {
            return;
        }
        RanchCycle cycle = get(zone);
        if (zone.containsBody(resident)) {
            cycle.markPresent(now);
        } else if (cycle.lastPresenceTick == Long.MIN_VALUE
                || now - cycle.lastPresenceTick > PRESENCE_GRACE) {
            cycle.reset(now);
        }
    }

    /**
     * 进入繁殖阶段（只在剪毛阶段生效，多员工并发时幂等）。
     *
     * <p>2026-11-23 主人实测：「剪完毛了没有立刻去喂养」。以前这里要再等 6 分钟
     * （`deadline = now + BREED_WAIT`），员工剪完毛就在区域里踱步干等。现在**立刻到点**，
     * 剪完 → 马上判断数量 / 饲料 → 喂养。两次喂养之间的间隔由「动物自身的繁殖冷却」决定
     * （{@code Animal#isInLove} 期间会被跳过，天然不会刷喂）。</p>
     */
    public void enterBreed(long now) {
        if (this.phase == Phase.SHEAR) {
            this.phase = Phase.BREED;
            this.deadline = now;
        }
    }

    /** 进入屠宰阶段（只在繁殖阶段生效）。 */
    public void enterButcher(long now) {
        if (this.phase == Phase.BREED) {
            this.phase = Phase.BUTCHER;
            this.deadline = now;
        }
    }

    /** 回到剪毛阶段（只在屠宰阶段生效）：等 1 分钟再开始下一轮剪毛。 */
    public void enterShear(long now) {
        if (this.phase == Phase.BUTCHER) {
            this.phase = Phase.SHEAR;
            this.deadline = now + SHEAR_WAIT;
        }
    }

    /** 缺剪刀 / 缺饲料的喊话节流（多员工共用一个 60 秒冷却）。 */
    public boolean chatAllowed(long now) {
        if (this.lastChatTick != Long.MIN_VALUE && now - this.lastChatTick < CHAT_COOLDOWN) {
            return false;
        }
        this.lastChatTick = now;
        return true;
    }

    /**
     * 屠宰动作锁（多员工共用一个，1 秒）：抢到的人才能动手。
     *
     * @return true = 这次可以杀；false = 别人刚动过手，等下一轮
     */
    public boolean tryButcherLock(long now) {
        if (now < this.butcherLockUntil) {
            return false;
        }
        this.butcherLockUntil = now + BUTCHER_LOCK_TICKS;
        return true;
    }

    /** 调试 / Jade 用：现在是不是有员工在场（不了解上下文时给 false）。 */
    public boolean staffPresent(long now) {
        return this.lastPresenceTick != Long.MIN_VALUE
            && now - this.lastPresenceTick <= PRESENCE_GRACE;
    }
}
