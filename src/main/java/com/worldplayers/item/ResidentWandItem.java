package com.worldplayers.item;

import com.worldplayers.resident.ResidentEntity;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 居民调试棒（v0.2.9 起，v0.2.10 改成准心射线，v0.3.0 T3 改成多选）。
 *
 * <p>用法（服务端执行，**最远 64 格**）：</p>
 * <ul>
 *   <li><b>左键</b>：对准居民 → 选中/取消（可多选，客户端发 {@code wand_click} 包，服务器做 64 格射线）；</li>
 *   <li><b>右键</b>：对准居民 → 同样是选中/取消；对准方块 → 让**所有已选居民**前往该方块；</li>
 *   <li>对准空气/超出 64 格 → 红字提示，什么都不做；</li>
 *   <li>手持时已选居民会发光（{@link WandSelection#tick}）。</li>
 * </ul>
 *
 * <p>射线为什么不用 {@code Player#pick}：它走 {@code Entity#pick}，那条路**只打方块**，
 * 永远返回不了 {@link EntityHitResult}（旧代码里那半个分支其实是死代码）。
 * 这里方块照旧用 {@code pick}，实体另用 {@link ProjectileUtil#getEntityHitResult} 搜一遍，
 * 再按距离取近的那个。</p>
 */
public class ResidentWandItem extends Item {
    /** 调试棒下达的目的地名：主人要求就写「调试棒」（工作区域名接进来后再走语言键）。 */
    public static final String DEBUG_DESTINATION = "调试棒";
    /** 准心射线最远距离（主人定的上限）。 */
    public static final double RANGE = 64.0D;

    public ResidentWandItem(Properties properties) {
        super(properties);
    }

    /**
     * 准心射线：先取最近方块，再搜 64 格内的居民；居民比方块近（或没有方块）就返回居民。
     * 客户端（判断要不要发包）和服务端（真正执行）共用这一份，保证判定一致。
     */
    public static HitResult ray(Player player) {
        Vec3 from = player.getEyePosition(1.0F);
        Vec3 to = from.add(player.getViewVector(1.0F).scale(RANGE));
        HitResult blockHit = player.pick(RANGE, 1.0F, false);
        AABB search = player.getBoundingBox().expandTowards(to.subtract(from)).inflate(1.0D);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            player.level(), player, from, to, search,
            entity -> entity instanceof ResidentEntity resident && resident.isAlive());
        if (entityHit != null) {
            if (blockHit.getType() == HitResult.Type.MISS
                    || entityHit.getLocation().distanceToSqr(from)
                        <= blockHit.getLocation().distanceToSqr(from)) {
                return entityHit;
            }
        }
        return blockHit;
    }

    @Nullable
    public static ResidentEntity residentAt(Player player) {
        return ray(player) instanceof EntityHitResult hit
            && hit.getEntity() instanceof ResidentEntity resident
            && resident.isAlive()
            ? resident
            : null;
    }

    // ------------------------------------------------------------------
    // 右键：远处也走同一条准心射线
    // ------------------------------------------------------------------

    /** 右键空气/远处：走 64 格射线。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && hand == InteractionHand.MAIN_HAND) {
            useAtCrosshair(player);
        }
        return InteractionResultHolder.success(player.getItemInHand(hand));
    }

    /** 右键近处方块：也走同一条准心射线（点击面/远处都统一处理）。 */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!context.getLevel().isClientSide && context.getPlayer() != null) {
            useAtCrosshair(context.getPlayer());
        }
        return InteractionResult.SUCCESS;
    }

    private static void useAtCrosshair(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        HitResult hit = ray(player);
        // 对准居民：选中/取消（不管远近）
        if (hit instanceof EntityHitResult entityHit
                && entityHit.getEntity() instanceof ResidentEntity resident) {
            WandSelection.toggle(serverPlayer, resident);
            return;
        }
        // 对准方块：让所有已选中的居民通勤过去
        if (hit instanceof BlockHitResult blockHit) {
            List<ResidentEntity> targets = WandSelection.resolve(serverPlayer);
            if (targets.isEmpty()) {
                redMessage(player, "chat.worldplayers.wand.no_target");
                return;
            }
            BlockPos pos = blockHit.getBlockPos().relative(blockHit.getDirection());
            for (ResidentEntity resident : targets) {
                resident.setCommuteTarget(pos, DEBUG_DESTINATION);
                player.displayClientMessage(Component.translatable("chat.worldplayers.wand.commute",
                    resident.getName(), pos.getX(), pos.getY(), pos.getZ()), true);
            }
            return;
        }
        // 对准空气 / 超过 64 格 / 命中的不是居民
        redMessage(player, "chat.worldplayers.wand.out_of_range");
    }

    private static void redMessage(Player player, String key) {
        player.displayClientMessage(Component.translatable(key).withStyle(ChatFormatting.RED), true);
    }

    /** 诊断用：这个实体是不是能当调试棒的目标。 */
    public static boolean isResident(Entity entity) {
        return entity instanceof ResidentEntity;
    }
}
