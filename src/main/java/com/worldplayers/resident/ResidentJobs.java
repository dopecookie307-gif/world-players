package com.worldplayers.resident;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.network.chat.Component;

/**
 * 工作状态的中央登记表。
 *
 * <p>UI（浮窗 / Jade / 以后的其他地方）**都不再写死文案**，只问这里要：
 * 新增一种工作状态 = {@code ResidentJobs.register("mining", "job.worldplayers.mining")} + 语言文件加一条，
 * 不用碰 {@code ResidentNameplate} 和 {@code ResidentInfoProvider}。</p>
 *
 * <p>以后如果工作状态要跟着殖民地/日程系统走，只要那边的注册入口调 {@code register} 就行。</p>
 */
public final class ResidentJobs {
    /**
     * 游荡（v0.3.0 T1 对外改名，内部 id 仍是 {@code "idle"}）。
     *
     * <p>**id 不改**：它会被写进存档 NBT（`Job`），改 id 等于让所有老存档的居民读档后变成未知状态。
     * 所以这轮只改显示词条：{@code job.worldplayers.wandering}。</p>
     */
    public static final String IDLE = "idle";
    public static final String FOLLOW = "follow";
    public static final String STAND = "stand";
    /**
     * 闲逛（v0.3.0 T2，**空壳**）：挑一个不太远的建筑/工作区域走过去逗留一阵。
     * 这轮只登记词条与状态占位，不写 AI——触发机制等日程（T8）。
     */
    public static final String STROLLING = "strolling";
    /** 临时工作状态：正在合成（由 {@code ResidentCraftingGoal} 设置，不属于手册模式）。 */
    public static final String CRAFTING = "crafting";
    /** 临时工作状态：正在通勤（调试棒/以后的工作指派设置，目的地名字见 {@code ResidentEntity#destinationKey}）。 */
    public static final String COMMUTE = "commute";
    /** 工作区员工：农田里干活（工作地点名字见 {@code ResidentEntity#destinationKey}）。 */
    public static final String FARMING = "farming";
    /**
     * 农夫 · **背包满暂停**（2026-11-22，12 号规格 §5.8 阶段 B）：采收前算掉落，
     * 塞不下就原地站着等，每 5 秒探一次背包。
     */
    public static final String FARMING_PAUSED = "farming_paused";
    /**
     * 「处理背包」：**占位状态，暂不设置**（2026-11-22，12 号规格 §5.8 阶段 B 第 7 条）。
     *
     * <p>以后接仓库类行动时，满背包的员工会去仓库 / 整理 / 丢东西，那时才把状态切成它。
     * 现在只登记词条，谁都不显示。</p>
     */
    public static final String HANDLING_BAG = "handling_bag";
    /** 工作区员工：道路维护。 */
    public static final String ROADWORK = "roadwork";
    /** 工作区员工：树场伐木（2026-11-21 先登记状态，砍树逻辑以后写）。 */
    public static final String LUMBER = "lumber";
    /**
     * 伐木工 · **背包满暂停**（规则 32）：状态仍显示伐木工，只是多一个暂停标记；
     * 每 5 秒探一次背包，有空位自动复工。
     */
    public static final String LUMBER_PAUSED = "lumber_paused";
    /** 工作区员工：牧场（状态先登记）。 */
    public static final String RANCHING = "ranching";
    /** 工作区员工：渔场（状态先登记）。 */
    public static final String FISHING = "fishing";
    /** 工作区员工：挖掘工程（2026-11-22）。 */
    public static final String MINER = "miner";
    /** 挖掘工 · 缺少工具（镐 / 斧）暂停。 */
    public static final String MINER_PAUSED = "miner_paused";
    /** 挖掘工 · 背包满暂停（任务 A：树场同款局部逻辑）。 */
    public static final String MINER_BAG_FULL = "miner_bag_full";
    /** 工作区员工：填充工程。 */
    public static final String FILLER = "filler";
    /** 填充工 · 缺少方块暂停。 */
    public static final String FILLER_PAUSED = "filler_paused";
    /**
     * 社交（v0.3.0 T4）：分享食物、对视、轮流挥手。
     *
     * <p>**以后所有社交行为都共用这一个状态**（主人 2026-11-21），
     * 不再为「聊天 / 串门 / 围观」各开一个并列 JOB——D17 的「自由活动内部状态」规矩照旧。</p>
     */
    public static final String SOCIAL = "social";
    /**
     * 睡觉（v0.3.0 T8 的第一块）：体力见底回床后睡下，**睡觉期间体力恢复**。
     *
     * <p>同样只是「自由活动」内部的临时状态，不是并列的手册模式（D17）。</p>
     */
    public static final String SLEEPING = "sleeping";
    /**
     * 娱乐（2026-11-22，14 号文档第二节）：自由时间里待在娱乐区域，每 10 秒回 0.5% 心情。
     * 前往途中显示「闲逛」+ 娱乐区名，抵达后才切成「娱乐」。
     */
    public static final String ENTERTAINING = "entertaining";
    /**
     * 补给 / 存放（14 号文档 3.6）：**0 秒瞬时过渡**，只登记词条，不产生停留。
     * 第 2 包先留名字，接仓库类行动时再用。
     */
    public static final String SUPPLY = "supply";
    public static final String STOCK = "stock";

    /** 按工作区类型给一个工作状态 id（以后加新类型在这里加一行）。 */
    public static String workJobFor(String zoneType) {
        if (WorkZone.TYPE_FARM.equals(zoneType)) {
            return FARMING;
        }
        if (WorkZone.TYPE_ROAD.equals(zoneType)) {
            return ROADWORK;
        }
        if (WorkZone.TYPE_TREE_FARM.equals(zoneType)) {
            return LUMBER;
        }
        if (WorkZone.TYPE_RANCH.equals(zoneType)) {
            return RANCHING;
        }
        if (WorkZone.TYPE_FISHERY.equals(zoneType)) {
            return FISHING;
        }
        if (WorkZone.TYPE_MINE.equals(zoneType)) {
            return MINER;
        }
        if (WorkZone.TYPE_FILL.equals(zoneType)) {
            return FILLER;
        }
        return FARMING;
    }

    /** key 是状态 id（进 NBT），value 是语言键。 */
    private static final Map<String, String> REGISTRY = new LinkedHashMap<>();

    static {
        register(IDLE, "job.worldplayers.wandering");
        register(STROLLING, "job.worldplayers.strolling");
        register(FOLLOW, "job.worldplayers.follow");
        register(STAND, "job.worldplayers.stand");
        register(CRAFTING, "job.worldplayers.crafting");
        register(COMMUTE, "job.worldplayers.commute");
        register(FARMING, "job.worldplayers.farming");
        register(FARMING_PAUSED, "job.worldplayers.farming_paused");
        register(HANDLING_BAG, "job.worldplayers.handling_bag");
        register(ROADWORK, "job.worldplayers.roadwork");
        register(LUMBER, "job.worldplayers.lumber");
        register(LUMBER_PAUSED, "job.worldplayers.lumber_paused");
        register(RANCHING, "job.worldplayers.ranching");
        register(FISHING, "job.worldplayers.fishing");
        register(MINER, "job.worldplayers.miner");
        register(MINER_PAUSED, "job.worldplayers.miner_paused");
        register(MINER_BAG_FULL, "job.worldplayers.miner_bag_full");
        register(FILLER, "job.worldplayers.filler");
        register(FILLER_PAUSED, "job.worldplayers.filler_paused");
        register(SOCIAL, "job.worldplayers.social");
        register(SLEEPING, "job.worldplayers.sleeping");
        register(ENTERTAINING, "job.worldplayers.entertaining");
        register(SUPPLY, "job.worldplayers.supply");
        register(STOCK, "job.worldplayers.stock");
    }

    /** 登记一种工作状态（重复登记会覆盖语言键，方便别的系统把同名状态接到自己的文案上）。 */
    public static void register(String id, String translationKey) {
        REGISTRY.put(id, translationKey);
    }

    /** 状态 id → 可直接显示的文字。没登记的 id 回退成 {@code job.worldplayers.<id>}。 */
    public static Component text(String id) {
        String key = id == null ? null : REGISTRY.get(id);
        if (key == null) {
            key = "job.worldplayers." + (id == null || id.isBlank() ? IDLE : id);
        }
        return Component.translatable(key);
    }

    private ResidentJobs() {
    }
}
