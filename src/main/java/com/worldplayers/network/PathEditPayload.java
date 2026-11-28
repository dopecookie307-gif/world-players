package com.worldplayers.network;

import com.worldplayers.WorldPlayers;
import com.worldplayers.item.PathBrushItem;
import com.worldplayers.resident.path.PathAction;
import com.worldplayers.resident.path.PathNetworkData;
import com.worldplayers.resident.path.PathNode;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：路径笔的一次编辑。
 *
 * <p>服务端会再核一遍「手里是不是拿着路径笔」「距离够不够」，客户端只是传意图。</p>
 */
public record PathEditPayload(int op, BlockPos pos, ResourceLocation dimension,
                              int linkFrom, int actionOrdinal)
        implements CustomPacketPayload {

    /** 左键三态：放路径点 → 升级锚点 → 删除。 */
    public static final int OP_CYCLE = 0;
    /** Shift+右键确认后删除。 */
    public static final int OP_DELETE = 1;
    /** 右键点已有的点：动作循环切换（2026-11-24 起右键改连线，这个 op 只留给兼容）。 */
    public static final int OP_ACTION = 2;
    /** 改宽度（第一轮还没有界面，先留协议）。 */
    public static final int OP_WIDTH = 3;
    /** 右键连线模式：把 {@code linkFrom} 这个点连到 {@code pos} 这个点（2026-11-24 主人要求）。 */
    public static final int OP_LINK = 4;
    /** 把某一个点的动作设成 {@code actionOrdinal}（Alt+滚轮改已选中的点）。 */
    public static final int OP_SET_ACTION = 5;

    /** 允许的最远操作距离（和准心射线一致）。 */
    public static final double RANGE = 64.0D;

    public static final CustomPacketPayload.Type<PathEditPayload> TYPE =
        new CustomPacketPayload.Type<>(WorldPlayers.id("path_edit"));

    public static final StreamCodec<FriendlyByteBuf, PathEditPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PathEditPayload::op,
            BlockPos.STREAM_CODEC, PathEditPayload::pos,
            ResourceLocation.STREAM_CODEC, PathEditPayload::dimension,
            ByteBufCodecs.VAR_INT, PathEditPayload::linkFrom,
            ByteBufCodecs.VAR_INT, PathEditPayload::actionOrdinal,
            PathEditPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PathEditPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // 服务端复核：主手必须是路径笔，且目标在 64 格内
            if (!(player.getMainHandItem().getItem() instanceof PathBrushItem)) {
                return;
            }
            if (player.distanceToSqr(Vec3.atCenterOf(payload.pos())) > (RANGE + 4.0D) * (RANGE + 4.0D)) {
                return;
            }
            PathNetworkData data = PathNetworkData.get(player.serverLevel());
            PathAction action = PathAction.byOrdinal(payload.actionOrdinal());
            // 连线模式（2026-11-24）：把选中的点连到这个点上，连完客户端自己退出连接模式
            if (payload.op() == OP_LINK) {
                PathNode from = data.byId(payload.linkFrom());
                PathNode to = data.find(payload.pos(), payload.dimension());
                if (from == null || to == null) {
                    player.displayClientMessage(
                        Component.translatable("chat.worldplayers.path.no_node"), false);
                    return;
                }
                if (!data.link(from.id(), to.id())) {
                    player.displayClientMessage(
                        Component.translatable("chat.worldplayers.path.link_failed"), false);
                    return;
                }
                player.displayClientMessage(Component.translatable("chat.worldplayers.path.linked",
                    from.pos().getX(), from.pos().getY(), from.pos().getZ(),
                    to.pos().getX(), to.pos().getY(), to.pos().getZ())
                    .withStyle(ChatFormatting.GREEN), false);
                PathNetworkData.sync(player.server);
                return;
            }
            PathNetworkData.Edit edit = switch (payload.op()) {
                case OP_DELETE -> data.delete(payload.pos(), payload.dimension());
                case OP_ACTION -> data.cycleAction(payload.pos(), payload.dimension());
                case OP_SET_ACTION -> data.setAction(payload.pos(), payload.dimension(), action);
                // 第一轮还没有宽度界面：linkFrom 直接当宽度传（1~8，越界会被夹住）
                case OP_WIDTH -> data.setWidth(payload.pos(), payload.dimension(), payload.linkFrom());
                default -> data.cycle(payload.pos(), payload.dimension(), action, payload.linkFrom());
            };
            switch (edit.kind()) {
                case CREATED -> hint(player, ChatFormatting.GREEN,
                    "chat.worldplayers.path.created", edit.node().pos());
                case UPGRADED -> hint(player, ChatFormatting.LIGHT_PURPLE,
                    "chat.worldplayers.path.upgraded", edit.node().pos());
                case REMOVED -> hint(player, ChatFormatting.YELLOW,
                    "chat.worldplayers.path.removed", edit.node().pos());
                case ACTION_CHANGED -> {
                    if (edit.node() != null) {
                        player.displayClientMessage(Component.translatable(
                            "chat.worldplayers.path.action",
                            edit.node().pos().getX(), edit.node().pos().getY(), edit.node().pos().getZ(),
                            Component.translatable("gui.worldplayers.path.action." + edit.node().action().id()))
                            .withStyle(ChatFormatting.AQUA), false);
                    }
                }
                case NONE -> player.displayClientMessage(
                    Component.translatable("chat.worldplayers.path.no_node"), false);
            }
            if (edit.kind() != PathNetworkData.EditKind.NONE) {
                PathNetworkData.sync(player.server);
            }
        });
    }

    private static void hint(ServerPlayer player, ChatFormatting color, String key, BlockPos pos) {
        player.displayClientMessage(Component.translatable(key,
            pos.getX(), pos.getY(), pos.getZ()).withStyle(color), false);
    }
}
