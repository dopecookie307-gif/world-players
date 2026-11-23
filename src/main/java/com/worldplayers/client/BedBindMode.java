package com.worldplayers.client;

import com.worldplayers.WorldPlayers;
import com.worldplayers.network.BedBindPayload;
import com.worldplayers.network.ResidentListPayload;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端：绑定床铺模式（招募新成员 / 给流浪居民分配床铺）。
 *
 * <p>表现（主人 2026-11-20 定）：</p>
 * <ul>
 *   <li>物品栏上方常驻「正处于绑定床铺模式」，退出后「已退出绑定床铺模式」两秒渐隐；</li>
 *   <li>附近 16 格内的床全部高亮、**可穿墙**：已绑定 = 红色，未绑定 = 白色；</li>
 *   <li>按左键（空气 / 方块 / 实体）或右键无效方块都会退出模式。</li>
 * </ul>
 */
@EventBusSubscriber(modid = WorldPlayers.MOD_ID, value = Dist.CLIENT)
public final class BedBindMode {
    /** 高亮半径（主人 2026-11-23：手持手册时渲染范围改成 32 格）。 */
    private static final int BED_RADIUS = 32;
    /** 退出提示的渐隐时长：2 秒。 */
    private static final int EXIT_MESSAGE_TICKS = 40;
    /** 床位置扫描节流：每 2 秒开始一轮（分片扫，别一 tick 扫 27 万格）。 */
    private static final int RESCAN_TICKS = 40;
    /** 每 tick 扫几层 y：65 层大约 17 tick 扫完一轮。 */
    private static final int SCAN_LAYERS_PER_TICK = 4;

    private static boolean active;
    /** 分配给哪个已有居民；null = 招募新居民。 */
    @Nullable
    private static UUID assignTarget;
    private static int exitMessageTicks;
    private static int rescanTicks;
    /** 分片扫描游标：-1 = 空闲，>=0 = 正在扫第几层。 */
    private static int scanCursor = -1;
    private static BlockPos scanCenter = BlockPos.ZERO;
    private static final Set<BlockPos> BEDS = new HashSet<>();
    /** 屏幕坐标里的床浮窗（世界投影结果，每帧重算；16 格外不画）。 */
    private static final java.util.List<BedTag> TAGS = new java.util.ArrayList<>();

    public static boolean active() {
        return active;
    }

    // ------------------------------------------------------------------
    // 进入 / 退出
    // ------------------------------------------------------------------

    public static void enterRecruit() {
        active = true;
        assignTarget = null;
        exitMessageTicks = 0;
        BEDS.clear();
        rescanTicks = 0;
        PacketDistributor.sendToServer(new BedBindPayload(BedBindPayload.ENTER_RECRUIT, new UUID(0L, 0L)));
    }

    public static void enterAssign(UUID residentId) {
        if (residentId == null) {
            return;
        }
        active = true;
        assignTarget = residentId;
        exitMessageTicks = 0;
        BEDS.clear();
        rescanTicks = 0;
        PacketDistributor.sendToServer(new BedBindPayload(BedBindPayload.ENTER_ASSIGN, residentId));
    }

    /** 本地退出（左键 / 右键无效方块 / 关界面）。会告诉服务端。 */
    public static void exit() {
        if (!active) {
            return;
        }
        active = false;
        assignTarget = null;
        BEDS.clear();
        exitMessageTicks = EXIT_MESSAGE_TICKS;
        PacketDistributor.sendToServer(new BedBindPayload(BedBindPayload.EXIT, new UUID(0L, 0L)));
    }

    /** 服务端要求退出（招募成功等）。 */
    public static void exitFromServer() {
        if (!active) {
            return;
        }
        active = false;
        assignTarget = null;
        BEDS.clear();
        exitMessageTicks = EXIT_MESSAGE_TICKS;
    }

    /** 换维度 / 断线时清理。 */
    public static void reset() {
        active = false;
        assignTarget = null;
        BEDS.clear();
        scanCursor = -1;
        exitMessageTicks = 0;
    }

    // ------------------------------------------------------------------
    // tick / 输入
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (exitMessageTicks > 0) {
            exitMessageTicks--;
        }
        if (!shouldShowBeds()) {
            if (!BEDS.isEmpty()) {
                BEDS.clear();
            }
            scanCursor = -1;
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }
        if (--rescanTicks <= 0 && scanCursor < 0) {
            rescanTicks = RESCAN_TICKS;
            scanCenter = minecraft.player.blockPosition();
            scanCursor = 0;
            BEDS.clear();
        }
        if (scanCursor >= 0) {
            scanSlices(minecraft);
        }
    }

    /** 分片扫描（每 tick 扫 {@link #SCAN_LAYERS_PER_TICK} 层 y），扫完 scanCursor = -1。 */
    private static void scanSlices(Minecraft minecraft) {
        if (minecraft.level == null) {
            scanCursor = -1;
            return;
        }
        int totalLayers = BED_RADIUS * 2 + 1;
        int end = Math.min(totalLayers, scanCursor + SCAN_LAYERS_PER_TICK);
        int cx = scanCenter.getX();
        int cz = scanCenter.getZ();
        for (int i = scanCursor; i < end; i++) {
            int y = scanCenter.getY() - BED_RADIUS + i;
            for (int x = cx - BED_RADIUS; x <= cx + BED_RADIUS; x++) {
                for (int z = cz - BED_RADIUS; z <= cz + BED_RADIUS; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (minecraft.level.getBlockState(pos).getBlock() instanceof BedBlock) {
                        BEDS.add(pos);
                    }
                }
            }
        }
        scanCursor = end >= totalLayers ? -1 : end;
    }

    /**
     * 什么时候要画床：绑定模式中，**或者**手持居民手册且「床铺绑定状态」勾选打开（主人 2026-11-20）。
     */
    public static boolean shouldShowBeds() {
        if (active) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !com.worldplayers.config.WPClientConfig.showBedBinding()) {
            return false;
        }
        return minecraft.player.getMainHandItem().is(
                com.worldplayers.registry.WPItems.RESIDENT_MANUAL.get())
            || minecraft.player.getOffhandItem().is(
                com.worldplayers.registry.WPItems.RESIDENT_MANUAL.get());
    }

    /** 左键（空气 / 方块 / 实体）退出模式（主人 2026-11-20 定）。 */
    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (!active || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT
                || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        exit();
    }

    // ------------------------------------------------------------------
    // 渲染：床高亮（穿墙）
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!shouldShowBeds()) {
            // 收起手册 / 关掉勾选：小窗也要一起消失（主人 2026-11-20）
            if (!TAGS.isEmpty()) {
                TAGS.clear();
            }
            return;
        }
        if (BEDS.isEmpty()) {
            TAGS.clear();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        var pose = event.getPoseStack();
        var camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        var buffers = minecraft.renderBuffers().bufferSource();
        var consumer = buffers.getBuffer(RegionPlanner.HIGHLIGHT_LINES);
        for (BlockPos pos : BEDS) {
            boolean owned = ownerAt(pos) != null;
            AABB box = new AABB(pos);
            if (owned) {
                net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, consumer, box,
                    1.0F, 0.25F, 0.25F, 1.0F);
                // 红色再加一层膨胀，视觉上更「警告」
                net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, consumer,
                    box.inflate(0.02D), 1.0F, 0.25F, 0.25F, 0.5F);
            } else {
                net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, consumer, box,
                    1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
        buffers.endBatch(RegionPlanner.HIGHLIGHT_LINES);
        pose.popPose();

        // 已绑定床的小窗（居民头像 + 名字）：世界坐标投到屏幕，GUI 阶段再画；16 格外不画
        TAGS.clear();
        Vec3 cameraPos = event.getCamera().getPosition();
        for (BlockPos pos : BEDS) {
            ResidentListPayload.Member member = ownerAt(pos);
            if (member == null) {
                continue;
            }
            // 只在**枕头那一格**（床的 HEAD）画小窗，一张床只出一个（主人 2026-11-20）
            var state = minecraft.level.getBlockState(pos);
            if (!(state.getBlock() instanceof BedBlock)
                    || state.getValue(BedBlock.PART) != net.minecraft.world.level.block.state.properties.BedPart.HEAD) {
                continue;
            }
            Vec3 center = Vec3.atCenterOf(pos).add(0.0D, 0.4D, 0.0D);
            if (minecraft.player.position().distanceToSqr(center) > BED_RADIUS * BED_RADIUS) {
                continue;
            }
            Vec3 rel = center.subtract(cameraPos);
            org.joml.Vector4f clip = new org.joml.Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1.0F);
            clip.mul(event.getModelViewMatrix());
            clip.mul(event.getProjectionMatrix());
            if (clip.w() <= 0.05F) {
                continue;
            }
            float ndcX = clip.x() / clip.w();
            float ndcY = clip.y() / clip.w();
            if (Math.abs(ndcX) > 1.2F || Math.abs(ndcY) > 1.2F) {
                continue;
            }
            int screenX = (int) ((ndcX * 0.5F + 0.5F) * minecraft.getWindow().getGuiScaledWidth());
            int screenY = (int) ((0.5F - ndcY * 0.5F) * minecraft.getWindow().getGuiScaledHeight());
            TAGS.add(new BedTag(screenX, screenY, member));
        }
    }

    /** 屏幕坐标里的床浮窗（世界投影结果，每帧重算）。 */
    private record BedTag(int x, int y, ResidentListPayload.Member member) {
    }

    @Nullable
    private static ResidentListPayload.Member ownerAt(BlockPos pos) {
        ResidentListPayload.Member direct = ResidentStore.bedOwner(pos);
        if (direct != null) {
            return direct;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        var state = minecraft.level.getBlockState(pos);
        if (state.getBlock() instanceof BedBlock) {
            return ResidentStore.bedOwner(pos.relative(BedBlock.getConnectedDirection(state)));
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 渲染：物品栏上方的状态文本
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        GuiGraphics gui = event.getGuiGraphics();

        // 已绑定床的小窗：头像 + 名字（和床渲染同一个开关，不另设配置）
        for (BedTag tag : TAGS) {
            renderBedTag(gui, minecraft, tag);
        }

        if (!active && exitMessageTicks <= 0) {
            return;
        }
        Component text = Component.translatable(active
            ? "chat.worldplayers.bed.mode_on"
            : "chat.worldplayers.bed.mode_off");
        int alpha = active ? 255 : (int) (255.0F * Math.min(1.0F, exitMessageTicks / 20.0F));
        int color = (Math.max(0, Math.min(255, alpha)) << 24) | 0xFFFFFF;
        int x = (gui.guiWidth() - minecraft.font.width(text)) / 2;
        int y = gui.guiHeight() - 58;
        gui.drawString(minecraft.font, text, x, y, color, true);
    }

    /** 一张床上的居民：12×12 头像 + 名字，做成一个半透明小黑窗。 */
    private static void renderBedTag(GuiGraphics gui, Minecraft minecraft, BedTag tag) {
        Component name = tag.member().name() == null || tag.member().name().isBlank()
            ? Component.translatable("entity.worldplayers.resident")
            : Component.literal(tag.member().name());
        int nameW = minecraft.font.width(name);
        int boxW = 12 + 3 + nameW + 6;
        int boxH = 16;
        int x = tag.x() - boxW / 2;
        int y = tag.y() - boxH / 2;
        gui.fill(x, y, x + boxW, y + boxH, 0x66000000);
        gui.fill(x, y, x + boxW, y + 1, 0x33000000);
        net.minecraft.resources.ResourceLocation texture = ClientSkins.skin(tag.member().skin()).texture();
        gui.blit(texture, x + 3, y + 2, 12, 12, 8.0F, 8.0F, 8, 8, 64, 64);
        gui.blit(texture, x + 3, y + 2, 12, 12, 40.0F, 8.0F, 8, 8, 64, 64);
        gui.drawString(minecraft.font, name, x + 18, y + 4, 0xFFFFFF, true);
    }

    private BedBindMode() {
    }
}
