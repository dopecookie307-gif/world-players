package com.worldplayers.resident;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * 社交系统（v0.3.0 T4）：食物分享的「需求」与「冷却」记录。
 *
 * <p>这里**不跑任何动作**——走动、对视、挥手、搬食物全在
 * {@link com.worldplayers.resident.ai.ResidentSocialGoal} 里。这个类只负责三件事：</p>
 * <ol>
 *   <li><b>触发 A</b>：每 10 秒看一次自己背包，食物为 0 就挂一个「需要被给饭」的需求；</li>
 *   <li><b>触发 B</b>：饿到掉血时由 {@link ResidentHunger} 立刻挂需求（优先级比 A 高，见 {@link #isUrgent()}）；</li>
 *   <li>给过 / 被给过之后的 10 秒冷却（无次数上限，主人 2026-11-21 定）。</li>
 * </ol>
 *
 * <p>所有状态都是**临时字段、不进 NBT**：重启后需求作废、重新按 A/B 判定即可，先省存档字段。</p>
 */
public final class ResidentSocial {
    /** 需求有效期：10 秒（和检查频率一致，A 只要还没饭吃就会自己续上）。 */
    public static final int NEED_TTL = 200;
    /** 触发 A 的检查频率：每 10 秒（主人 2026-11-21 定）。 */
    public static final int NEED_CHECK_INTERVAL = 200;
    /** 冷却：给者与被给者都是 **5 秒**（2026-11-23 主人定：给者无长 CD，极短 CD 防止来回乒乓）。 */
    public static final int COOLDOWN_TICKS = 100;
    /** 给者至少要有 2 份食物才发起分享（自己不留空 = 防止两个人来回乒乓）。 */
    public static final int GIVER_MIN_FOOD = 2;

    private final ResidentEntity resident;
    /** 需求截止 tick（居民 tickCount 基准）；0 = 没有需求。 */
    private int needUntil;
    /** B（被饿伤）挂上的需求：其他居民会优先去救。 */
    private boolean urgent;
    /** 冷却截止 tick。 */
    private int cooldownUntil;
    /** 本次社交配对的对方（只在本轮社交期间有效）。 */
    @Nullable
    private ResidentEntity partner;
    /** 触发 A 的检查节流。 */
    private int checkTimer;

    ResidentSocial(ResidentEntity resident) {
        this.resident = resident;
    }

    /** 每 tick 调一次（服务端）：只管触发 A，B 在受伤那边直接调用 {@link #requestHelp(boolean)}。 */
    public void tick() {
        if (!this.resident.isAlive() || this.resident.isDeadOrDying()) {
            return;
        }
        if (++this.checkTimer < NEED_CHECK_INTERVAL) {
            return;
        }
        this.checkTimer = 0;
        if (!this.hasNeed() && foodCount(this.resident) <= 0) {
            this.requestHelp(false);
        }
    }

    /** 挂一个需求：{@code urgent = true} 表示是饿伤（B），会被优先选中。 */
    public void requestHelp(boolean urgent) {
        boolean stillUrgent = this.hasNeed() && this.urgent;
        this.needUntil = this.resident.tickCount + NEED_TTL;
        this.urgent = urgent || stillUrgent;
    }

    public boolean hasNeed() {
        return this.needUntil > 0 && this.resident.tickCount < this.needUntil;
    }

    public boolean isUrgent() {
        return this.urgent && this.hasNeed();
    }

    public void clearNeed() {
        this.needUntil = 0;
        this.urgent = false;
    }

    public boolean isOnCooldown() {
        return this.resident.tickCount < this.cooldownUntil;
    }

    public void startCooldown() {
        this.cooldownUntil = this.resident.tickCount + COOLDOWN_TICKS;
    }

    @Nullable
    public ResidentEntity partner() {
        return this.partner;
    }

    public void setPartner(@Nullable ResidentEntity partner) {
        this.partner = partner;
    }

    // ------------------------------------------------------------------
    // 食物工具（分享与触发判定共用）
    // ------------------------------------------------------------------

    /** 能不能当"饭"：至少有 1 点营养、不在负面食物标签里、不是生肉（负面药水效果的和吃饭那边保持一致，不细查）。 */
    public static boolean isFood(ItemStack stack) {
        if (stack.isEmpty() || stack.is(ResidentHunger.NEGATIVE_FOOD_TAG)
                || ResidentHunger.isRawMeat(stack)) {
            return false;
        }
        FoodProperties properties = stack.get(DataComponents.FOOD);
        return properties != null && properties.nutrition() >= 1;
    }

    /** 背包里能吃的东西数量（主手 / 快捷栏 / 主背包 / **副手**；不含盔甲槽）。 */
    public static int foodCount(ResidentEntity resident) {
        ResidentInventory inventory = resident.inventory();
        int count = 0;
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            if (slot >= ResidentInventory.ARMOR_START && slot < ResidentInventory.OFFHAND) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (isFood(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 从背包拿走 {@code count} 份食物（**普通食物优先，农作物垫底**，和居民自己的吃饭分级一致）。
     *
     * @return 实际拿到的物品（可能少于请求数量：背包里没那么多）
     */
    public static List<ItemStack> takeFood(ResidentEntity resident, int count) {
        List<ItemStack> taken = new ArrayList<>();
        int remaining = count;
        remaining = takeTier(resident, ResidentFoodTier.NORMAL, remaining, taken);
        if (remaining > 0) {
            takeTier(resident, ResidentFoodTier.CROP, remaining, taken);
        }
        return taken;
    }

    private static int takeTier(ResidentEntity resident, int tier, int remaining, List<ItemStack> out) {
        ResidentInventory inventory = resident.inventory();
        for (int slot = 0; slot < ResidentInventory.SIZE && remaining > 0; slot++) {
            if (slot >= ResidentInventory.ARMOR_START && slot < ResidentInventory.OFFHAND) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (!isFood(stack) || ResidentFoodTier.tier(stack) != tier) {
                continue;
            }
            // 一次一件地拿：分享是按"个数"算的，不是整摞搬走
            ItemStack one = inventory.removeItem(slot, 1);
            if (one.isEmpty()) {
                continue;
            }
            out.add(one);
            remaining--;
        }
        return remaining;
    }
}
