package com.worldplayers.resident;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

/**
 * 居民的「工作台 2×2 手搓」能力（v0.2.2）。
 *
 * <h3>不用硬编码任何物品</h3>
 * <p>所有配方都现场从 {@link RecipeType#CRAFTING} 里查，并且只认**能在 2×2 里摆下**的
 * {@link ShapedRecipe} / {@link ShapelessRecipe} —— 也就是说：原版的木板、木棍、火把、
 * 工作台、箱子……以及以后任何模组加的 2×2 有序/无序配方，居民天生就会，不需要改代码。</p>
 *
 * <p>「当前只做火把」只是**触发条件**层面的限制（见 {@code ResidentCraftingGoal}）：
 * 计划器的目标物是参数，以后想接区域划分/工作日程，直接把目标换成别的东西即可。</p>
 *
 * <h3>计划 vs 执行</h3>
 * <ul>
 *   <li>{@link #planTorch} / {@link #plan} 只做**纸面推演**：盘一下背包里的物品够不够，
 *       不够就往回找子配方（原木 → 木板 → 木棍 → 火把这种链式合成会自动串起来），
 *       返回按顺序执行的步骤表；背包一点都不会动。</li>
 *   <li>{@link #execute} 才真正扣材料、把成品放进**主手**（主人要求：合成完的成品先拿在手上，
 *       之后就当普通物品，谁有优先级谁把它挤下去）。</li>
 * </ul>
 */
public final class ResidentCrafting {
    /** 链式合成的最大回溯深度：原木 → 木板 → 木棍 → 火把 = 3 层，留一层余量。 */
    public static final int DEFAULT_DEPTH = 4;

    /** 一步合成：配方 + 这一步要消耗的材料 + 产物。 */
    public record Step(RecipeHolder<CraftingRecipe> recipe, List<Ingredient> ingredients, ItemStack result) {
        @Override
        public String toString() {
            return recipe.id() + " -> " + result;
        }
    }

    /**
     * 给「火把」做一个执行计划。
     *
     * <p>顺序永远是**先决步骤在前**：例如背包里只有原木时，计划是
     * 「原木→木板」「木板→木棍」「木棍+煤→火把」三步，居民一秒做一步。</p>
     *
     * @return 空列表 = 现有材料做不出来（不包含"背包里已经有火把"的情况，那个走 {@code hasTorch}）
     */
    public static List<Step> planTorch(Level level, ResidentEntity resident) {
        return plan(level, resident, stack -> stack.is(net.minecraft.world.item.Items.TORCH), 1, DEFAULT_DEPTH);
    }

    /**
     * 通用 2×2 计划器：在不动背包的前提下，算出「凑出 {@code count} 个符合 {@code target} 的物品」的步骤表。
     *
     * @param target 目标物品的判定（例如 {@code stack -> stack.is(Items.TORCH)}）
     * @param count  需要几个
     * @param depth  回溯深度（{@link #DEFAULT_DEPTH}）
     */
    public static List<Step> plan(Level level, ResidentEntity resident, Predicate<ItemStack> target,
                                  int count, int depth) {
        List<ItemStack> snapshot = snapshot(resident.inventory());
        List<Step> steps = new ArrayList<>();
        return ensure(level, snapshot, target, count, depth, steps) ? steps : List.of();
    }

    /**
     * 递归：确保 {@code snapshot} 里至少有 {@code count} 个满足 {@code target} 的物品。
     *
     * <p>先看现有；不够就找一条「产物符合目标」的 2×2 配方，把它的每个材料也递归凑齐
     * （材料同样可以靠别的配方现做），然后把这一步追加到步骤表末尾 —— 所以执行顺序天然是先决在前。</p>
     */
    private static boolean ensure(Level level, List<ItemStack> snapshot, Predicate<ItemStack> target,
                                  int count, int depth, List<Step> steps) {
        if (countMatching(snapshot, target) >= count) {
            return true;
        }
        if (depth <= 0) {
            return false;
        }
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            // 只认玩家 2×2 能摆下的有序/无序合成；染料、地图复制、烟花这类特殊配方一律跳过
            if (!(recipe instanceof ShapedRecipe) && !(recipe instanceof ShapelessRecipe)) {
                continue;
            }
            if (!recipe.canCraftInDimensions(2, 2)) {
                continue;
            }
            ItemStack result = recipe.getResultItem(level.registryAccess()).copy();
            if (result.isEmpty() || !target.test(result)) {
                continue;
            }
            List<ItemStack> trial = copy(snapshot);
            List<Step> local = new ArrayList<>();
            List<Ingredient> used = new ArrayList<>();
            boolean ok = true;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) {
                    continue;
                }
                if (!ensure(level, trial, ingredient, 1, depth - 1, local)) {
                    ok = false;
                    break;
                }
                if (!consumeOne(trial, ingredient)) {
                    ok = false;
                    break;
                }
                used.add(ingredient);
            }
            if (!ok) {
                continue;
            }
            addToSnapshot(trial, result);
            // 一次合成就把数量凑齐了才算成功（火把链每一步都满足；多批量的复杂配方先不做）
            if (countMatching(trial, target) < count) {
                continue;
            }
            // v0.2.7 修复：递归出来的子步骤会消耗材料、产出中间产物，这些变化必须**写回调用者的快照**，
            // 否则上一层拿到的还是原样的材料表 —— 会出现"推演里已经做出木板，外层却扣不到木板"，
            // 导致任何需要两步以上的链式合成（原木→木板→木棍→火把、煤块→煤→火把）永远算出 0 步。
            snapshot.clear();
            for (ItemStack stack : trial) {
                snapshot.add(stack.copy());
            }
            steps.addAll(local);
            steps.add(new Step(holder, List.copyOf(used), result.copy()));
            return true;
        }
        return false;
    }

    /** 按计划执行一步：扣材料 → 成品放进主手（原来的主手物品塞回背包或掉在地上）。 */
    public static boolean execute(ResidentEntity resident, Step step) {
        ResidentInventory inventory = resident.inventory();
        for (Ingredient ingredient : step.ingredients()) {
            if (!consumeOne(inventory, ingredient)) {
                return false;
            }
        }
        ItemStack previous = inventory.getItem(ResidentInventory.MAIN_HAND).copy();
        inventory.setItem(ResidentInventory.MAIN_HAND, step.result().copy());
        if (!previous.isEmpty()) {
            ItemStack rest = inventory.addItem(previous);
            if (!rest.isEmpty()) {
                resident.spawnAtLocation(rest);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 背包快照 / 材料计数
    // ------------------------------------------------------------------

    private static List<ItemStack> snapshot(ResidentInventory inventory) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            if (!ResidentInventory.isStorageSlot(slot)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        return stacks;
    }

    private static List<ItemStack> copy(List<ItemStack> snapshot) {
        List<ItemStack> copy = new ArrayList<>(snapshot.size());
        for (ItemStack stack : snapshot) {
            copy.add(stack.copy());
        }
        return copy;
    }

    private static int countMatching(List<ItemStack> snapshot, Predicate<ItemStack> target) {
        int total = 0;
        for (ItemStack stack : snapshot) {
            if (target.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 从快照里消耗一个满足材料的物品（每种材料按 1 个算，重复材料会分别消耗）。 */
    private static boolean consumeOne(List<ItemStack> snapshot, Ingredient ingredient) {
        for (ItemStack stack : snapshot) {
            if (stack.isEmpty() || !ingredient.test(stack)) {
                continue;
            }
            stack.shrink(1);
            return true;
        }
        return false;
    }

    /**
     * 从**真实**背包里消耗一个满足材料的物品。
     *
     * <p>直接改手上/槽位里的那份 stack 再 {@code setChanged()}，避免每扣一个都走一遍
     * {@code setItemSlot} 的装备音效/装备事件。</p>
     */
    private static boolean consumeOne(ResidentInventory inventory, Ingredient ingredient) {
        for (int slot = 0; slot < ResidentInventory.SIZE; slot++) {
            if (!ResidentInventory.isStorageSlot(slot)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !ingredient.test(stack)) {
                continue;
            }
            stack.shrink(1);
            if (stack.isEmpty()) {
                inventory.setItem(slot, ItemStack.EMPTY);
            } else {
                inventory.setChanged();
            }
            return true;
        }
        return false;
    }

    private static void addToSnapshot(List<ItemStack> snapshot, ItemStack result) {
        for (ItemStack stack : snapshot) {
            if (ItemStack.isSameItemSameComponents(stack, result) && stack.getCount() < stack.getMaxStackSize()) {
                int moved = Math.min(result.getCount(), stack.getMaxStackSize() - stack.getCount());
                stack.grow(moved);
                result.shrink(moved);
                if (result.isEmpty()) {
                    return;
                }
            }
        }
        if (!result.isEmpty()) {
            snapshot.add(result.copy());
        }
    }

    private ResidentCrafting() {
    }
}
