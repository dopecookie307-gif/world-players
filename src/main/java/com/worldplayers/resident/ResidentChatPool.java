package com.worldplayers.resident;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;

/**
 * 居民对白池（主人 2026-11-21 定）：**所有「一次触发只说一句」的文本都从这里随机挑一条**。
 *
 * <p>规矩：</p>
 * <ul>
 *   <li>每个 {@link Kind} 对应一组语言键，每次触发**在整组里择其一**发送；</li>
 *   <li>列表以后随时可加减——只改这里的数组 + 语言文件，调用点不用动；</li>
 *   <li>文本统一走 {@link ResidentChat#say}，所以自动带居民名字与聊天头像（= 普通玩家的聊天格式）；</li>
 *   <li>语言键命名 `chat.worldplayers.&lt;类别&gt;.&lt;序号&gt;`，序号从 1 开始，保持连续。</li>
 * </ul>
 */
public final class ResidentChatPool {
    /** 触发场景。 */
    public enum Kind {
        /** 有床居民 5 秒后在床边复活。 */
        REVIVE,
        /** 绑定的床被玩家拆掉、解绑。 */
        BED_BROKEN,
        /** 被玩家打中（1 秒最多一句）。 */
        HIT,
        /** 受到饥饿伤害（坐标由调用方拼在后方）。 */
        STARVE,
        /** 想耕地但背包里没有锄头。 */
        TOOL_HOE,
        /** 想砍树但背包里没有斧头（树场工作实装后启用）。 */
        TOOL_AXE,
        /** 牧场：有毛的绵羊但背包里没有剪刀（v0.3.0）。 */
        NO_SHEARS,
        /** 牧场：背包里没有能繁殖这种动物的饲料（v0.3.0，带动物名参数）。 */
        NO_FEED,
        /** 渔场：背包（含副手）里没有钓鱼竿（v0.3.0，15 号规则 3.1）。 */
        TOOL_ROD,
        /** 渔场：区域附近找不到能钓鱼的水（干涸，v0.3.0，15 号规则 2.3）。 */
        TOOL_ROD_DRY
    }

    private static final String[] REVIVE_KEYS = {
        "chat.worldplayers.revive.1",
        "chat.worldplayers.revive.2",
        "chat.worldplayers.revive.3",
        "chat.worldplayers.revive.4",
        "chat.worldplayers.revive.5",
        "chat.worldplayers.revive.6",
        "chat.worldplayers.revive.7"
    };

    private static final String[] BED_BROKEN_KEYS = {
        "chat.worldplayers.bed.broken.1",
        "chat.worldplayers.bed.broken.2",
        "chat.worldplayers.bed.broken.3",
        "chat.worldplayers.bed.broken.4",
        "chat.worldplayers.bed.broken.5",
        "chat.worldplayers.bed.broken.6",
        "chat.worldplayers.bed.broken.7"
    };

    private static final String[] HIT_KEYS = {
        "chat.worldplayers.hit.1",
        "chat.worldplayers.hit.2",
        "chat.worldplayers.hit.3",
        "chat.worldplayers.hit.4",
        "chat.worldplayers.hit.5",
        "chat.worldplayers.hit.6",
        "chat.worldplayers.hit.7",
        "chat.worldplayers.hit.8",
        "chat.worldplayers.hit.9",
        "chat.worldplayers.hit.10"
    };

    private static final String[] STARVE_KEYS = {
        "chat.worldplayers.starve.1",
        "chat.worldplayers.starve.2",
        "chat.worldplayers.starve.3",
        "chat.worldplayers.starve.4"
    };

    private static final String[] TOOL_HOE_KEYS = {
        "chat.worldplayers.tool.hoe.1",
        "chat.worldplayers.tool.hoe.2",
        "chat.worldplayers.tool.hoe.3"
    };

    private static final String[] TOOL_AXE_KEYS = {
        "chat.worldplayers.tool.axe.1",
        "chat.worldplayers.tool.axe.2",
        "chat.worldplayers.tool.axe.3"
    };

    private static final String[] NO_SHEARS_KEYS = {
        "chat.worldplayers.shear.no_shears.1",
        "chat.worldplayers.shear.no_shears.2",
        "chat.worldplayers.shear.no_shears.3",
        "chat.worldplayers.shear.no_shears.4",
        "chat.worldplayers.shear.no_shears.5"
    };

    private static final String[] NO_FEED_KEYS = {
        "chat.worldplayers.ranch.no_feed.1",
        "chat.worldplayers.ranch.no_feed.2",
        "chat.worldplayers.ranch.no_feed.3",
        "chat.worldplayers.ranch.no_feed.4"
    };

    /** 渔场没竿（15 号规则第八节 8.1，5 条）。 */
    private static final String[] TOOL_ROD_KEYS = {
        "chat.worldplayers.tool.rod.1",
        "chat.worldplayers.tool.rod.2",
        "chat.worldplayers.tool.rod.3",
        "chat.worldplayers.tool.rod.4",
        "chat.worldplayers.tool.rod.5"
    };

    /** 渔场干涸（15 号规则第八节 8.2，2 条）。 */
    private static final String[] TOOL_ROD_DRY_KEYS = {
        "chat.worldplayers.tool.rod.dry.1",
        "chat.worldplayers.tool.rod.dry.2"
    };

    /** 从对应类别的列表里随机挑一条。 */
    public static Component pick(Kind kind, RandomSource random) {
        return pick(kind, random, new Object[0]);
    }

    /**
     * 从对应类别的列表里随机挑一条，并把 {@code args} 填进去（目前只有 {@link Kind#NO_FEED} 用，
     * 参数是动物名：牛 / 绵羊 / 猪 / 鸡）。
     */
    public static Component pick(Kind kind, RandomSource random, Object... args) {
        String[] keys = switch (kind) {
            case REVIVE -> REVIVE_KEYS;
            case BED_BROKEN -> BED_BROKEN_KEYS;
            case HIT -> HIT_KEYS;
            case STARVE -> STARVE_KEYS;
            case TOOL_HOE -> TOOL_HOE_KEYS;
            case TOOL_AXE -> TOOL_AXE_KEYS;
            case NO_SHEARS -> NO_SHEARS_KEYS;
            case NO_FEED -> NO_FEED_KEYS;
            case TOOL_ROD -> TOOL_ROD_KEYS;
            case TOOL_ROD_DRY -> TOOL_ROD_DRY_KEYS;
        };
        return Component.translatable(keys[random.nextInt(keys.length)], args);
    }

    // ------------------------------------------------------------------
    // 流浪（无床）聊天（2026-11-23，14 号文档 3.23 / 3.24）
    // ------------------------------------------------------------------

    /**
     * 流浪居民按体力算档位（1..4），**0 = 体力够，不喊话**。
     *
     * <p>边界来自 14 号文档 3.23：≤75 / ≤50 / ≤25 / ≤10 各一句。</p>
     */
    public static int homelessLevel(float stamina) {
        if (stamina <= 10.0F) {
            return 4;
        }
        if (stamina <= 25.0F) {
            return 3;
        }
        if (stamina <= 50.0F) {
            return 2;
        }
        if (stamina <= 75.0F) {
            return 1;
        }
        return 0;
    }

    /**
     * 被指派为员工时的那一版（14 号文档 3.24）：体力再高也要说一句，
     * 所以 75~100% 也返回 1（句子和 ≥75 档相同）。
     */
    public static int assignHomelessLevel(float stamina) {
        if (stamina <= 24.0F) {
            return 4;
        }
        if (stamina <= 49.0F) {
            return 3;
        }
        if (stamina <= 74.0F) {
            return 2;
        }
        return 1;
    }

    /** 说一句流浪居民的话（{@code level} 1..4，0 = 不说）。 */
    public static void sayHomeless(net.minecraft.server.level.ServerLevel level,
                                   ResidentEntity resident, int homelessLevel) {
        if (homelessLevel <= 0) {
            return;
        }
        String key = switch (homelessLevel) {
            case 4 -> "chat.worldplayers.homeless.10";
            case 3 -> "chat.worldplayers.homeless.25";
            case 2 -> "chat.worldplayers.homeless.50";
            default -> "chat.worldplayers.homeless.75";
        };
        ResidentChat.say(level, resident, Component.translatable(key));
    }

    private ResidentChatPool() {
    }
}
