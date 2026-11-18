package com.worldplayers.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.worldplayers.resident.ResidentEntity;
import java.util.Comparator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 调试命令：{@code /worldplayers mood <0-100>}。
 *
 * <p>改**最近一个居民**（32 格内）的心情值，用来现场看黄色 / 红色心情球。
 * 这是临时测试入口，等真实的心情增减逻辑做出来就可以删。</p>
 */
public final class WPCommands {
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("worldplayers")
            .then(Commands.literal("mood")
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0D, 100.0D))
                    .executes(ctx -> setMood(ctx, DoubleArgumentType.getDouble(ctx, "value"))))));
    }

    private static int setMood(CommandContext<CommandSourceStack> ctx, double value) {
        Entity source = ctx.getSource().getEntity();
        if (!(source instanceof Player player)) {
            ctx.getSource().sendFailure(Component.translatable("command.worldplayers.mood.no_player"));
            return 0;
        }
        ResidentEntity target = player.level()
            .getEntitiesOfClass(ResidentEntity.class, player.getBoundingBox().inflate(32.0D), ResidentEntity::isAlive)
            .stream()
            .min(Comparator.comparingDouble(resident -> resident.distanceToSqr(player)))
            .orElse(null);
        if (target == null) {
            ctx.getSource().sendFailure(Component.translatable("command.worldplayers.mood.not_found"));
            return 0;
        }
        target.setMood((float) value);
        ctx.getSource().sendSuccess(() -> Component.translatable("command.worldplayers.mood.set",
            target.getDisplayName(), (int) value), true);
        return 1;
    }

    private WPCommands() {
    }
}
