package com.worldplayers.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentHunger;
import com.worldplayers.resident.ai.ResidentCraftingGoal;
import java.util.Comparator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 调试命令。
 *
 * <ul>
 *   <li>{@code /worldplayers mood <0-100>}：改最近居民的心情值（现场看黄/红心情球）；</li>
 *   <li>{@code /worldplayers stamina <0-100>}：改最近居民的体力值（主人 2026-11-21 要的调试口子）；</li>
 *   <li>{@code /worldplayers hunger <0-100>}：改最近居民的饥饿值（百分比，100% = 20 点）；</li>
 *   <li>{@code /worldplayers craft}：把最近居民的「手工/插火把」触发条件逐条打出来，
 *       卡在哪一步一眼就能看见（同时写进服务端日志）；</li>
 *   <li>{@code /worldplayers instructions <text>}：给最近居民写一段自然语言指令（只存不执行，铺存档格式）。</li>
 * </ul>
 */
public final class WPCommands {
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("worldplayers")
            .then(Commands.literal("mood")
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                    .executes(ctx -> setMood(ctx, DoubleArgumentType.getDouble(ctx, "value")))))
            .then(Commands.literal("hunger")
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                    .executes(ctx -> setHunger(ctx, DoubleArgumentType.getDouble(ctx, "value")))))
            .then(Commands.literal("stamina")
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                    .executes(ctx -> setStamina(ctx, DoubleArgumentType.getDouble(ctx, "value")))))
            .then(Commands.literal("craft")
                .executes(WPCommands::diagnoseCraft))
            .then(Commands.literal("diag")
                .executes(WPCommands::diagnoseResidents))
            .then(Commands.literal("instructions")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                    .executes(ctx -> setInstructions(ctx, StringArgumentType.getString(ctx, "text"))))));
    }

    private static int setHunger(CommandContext<CommandSourceStack> ctx, double value) {
        ResidentEntity target = nearestResident(ctx);
        if (target == null) {
            return 0;
        }
        target.hungerSystem().setPercent((float) value);
        // 顺手把走路距离重新起算，避免"刚设完又被传送/位移"误扣
        target.hungerSystem().resetPosition();
        int points = Math.round((float) value / ResidentHunger.PERCENT_PER_POINT);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.worldplayers.hunger.set",
            target.getDisplayName(), (int) value, points), true);
        return 1;
    }

    private static int setMood(CommandContext<CommandSourceStack> ctx, double value) {
        ResidentEntity target = nearestResident(ctx);
        if (target == null) {
            return 0;
        }
        target.setMood((float) value);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.worldplayers.mood.set",
            target.getDisplayName(), (int) value), true);
        return 1;
    }

    /** 调试：直接改体力值（0..100）。体力影响劳动冷却（和工作心情取更慢档），所以改完立刻能看到节奏变化。 */
    private static int setStamina(CommandContext<CommandSourceStack> ctx, double value) {
        ResidentEntity target = nearestResident(ctx);
        if (target == null) {
            return 0;
        }
        target.setStamina((float) value);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.worldplayers.stamina.set",
            target.getDisplayName(), (int) Math.round(target.stamina())), true);
        return 1;
    }

    /**
     * 设置居民的自然语言指令（v0.3.0，主人 2026-11-21）。
     *
     * <p>目前**只存不执行**：写进 NBT + 同步给客户端，为未来的「任务规划层」铺存档格式。
     * 空字符串 = 清空。</p>
     */
    private static int setInstructions(CommandContext<CommandSourceStack> ctx, String text) {
        ResidentEntity target = nearestResident(ctx);
        if (target == null) {
            return 0;
        }
        target.setInstructions(text);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.worldplayers.instructions.set",
            target.getDisplayName(), target.instructions()), true);
        return 1;
    }

    private static int diagnoseCraft(CommandContext<CommandSourceStack> ctx) {
        ResidentEntity target = nearestResident(ctx);
        if (target == null) {
            return 0;
        }
        for (String line : ResidentCraftingGoal.diagnose(target)) {
            WorldPlayers.LOGGER.info("[craft] {}", line);
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    /**
     * 现场诊断（2026-11-23 加）：32 格内每个居民一行——位置 / 工作 / 活动 / 日程阶段 / 寻路状态 /
     * 当前真正在跑的 AI goal。用来抓「员工在工作区里抽搐 / 被弹出去」到底是谁在抢 MOVE。
     */
    private static int diagnoseResidents(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof Player player)) {
            ctx.getSource().sendFailure(Component.translatable("command.worldplayers.mood.no_player"));
            return 0;
        }
        java.util.List<ResidentEntity> residents = player.level().getEntitiesOfClass(
            ResidentEntity.class, player.getBoundingBox().inflate(32.0D), ResidentEntity::isAlive);
        if (residents.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("command.worldplayers.mood.not_found"));
            return 0;
        }
        for (ResidentEntity resident : residents) {
            StringBuilder line = new StringBuilder();
            line.append(resident.getName().getString())
                .append(" pos=").append(resident.blockPosition().toShortString())
                .append(" job=").append(resident.jobKey())
                .append(" act=").append(resident.activityKey())
                .append(" dest=").append(resident.destinationKey())
                .append(" phase=").append(resident.schedule().phase())
                .append(" stamina=").append(Math.round(resident.stamina()))
                .append(" navDone=").append(resident.getNavigation().isDone());
            var path = resident.getNavigation().getPath();
            if (path != null && path.getEndNode() != null) {
                line.append(" pathEnd=").append(path.getEndNode().asBlockPos().toShortString());
            }
            line.append(" goals=").append(runningGoals(resident));
            String text = line.toString();
            WorldPlayers.LOGGER.info("[diag] {}", text);
            ctx.getSource().sendSuccess(() -> Component.literal(text), false);
        }
        return residents.size();
    }

    /** 反射读 GoalSelector：列出所有正在运行的 goal（类名@优先级）。 */
    private static String runningGoals(ResidentEntity resident) {
        try {
            java.lang.reflect.Field field = net.minecraft.world.entity.ai.goal.GoalSelector.class
                .getDeclaredField("availableGoals");
            field.setAccessible(true);
            java.util.Collection<?> goals = (java.util.Collection<?>) field.get(resident.goalSelector);
            StringBuilder builder = new StringBuilder();
            for (Object entry : goals) {
                net.minecraft.world.entity.ai.goal.WrappedGoal wrapped =
                    (net.minecraft.world.entity.ai.goal.WrappedGoal) entry;
                if (!wrapped.isRunning()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(',');
                }
                builder.append(wrapped.getGoal().getClass().getSimpleName())
                    .append('@').append(wrapped.getPriority());
            }
            return builder.toString();
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** 最近的居民（32 格内）；找不到时已经把失败消息发给命令来源。 */
    private static ResidentEntity nearestResident(CommandContext<CommandSourceStack> ctx) {
        Entity source = ctx.getSource().getEntity();
        if (!(source instanceof Player player)) {
            ctx.getSource().sendFailure(Component.translatable("command.worldplayers.mood.no_player"));
            return null;
        }
        ResidentEntity target = player.level()
            .getEntitiesOfClass(ResidentEntity.class, player.getBoundingBox().inflate(32.0D), ResidentEntity::isAlive)
            .stream()
            .min(Comparator.comparingDouble(resident -> resident.distanceToSqr(player)))
            .orElse(null);
        if (target == null) {
            ctx.getSource().sendFailure(Component.translatable("command.worldplayers.mood.not_found"));
        }
        return target;
    }

    private WPCommands() {
    }
}
