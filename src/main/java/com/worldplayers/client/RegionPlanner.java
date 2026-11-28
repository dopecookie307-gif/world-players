package com.worldplayers.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.worldplayers.config.WPClientConfig;
import com.worldplayers.registry.WPItems;
import com.worldplayers.resident.WorkZone;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;

/**
 * 工作区域规划模式（v0.2.12）。
 *
 * <p>状态分两层：</p>
 * <ul>
 *   <li>{@code planning}：规划模式本身（按按钮进入，Shift+左键退出，存完也退出）；</li>
 *   <li>{@code holdingManual()}：手里拿着居民手册。拿着才拦输入/显示教学；
 *       换成别的物品就**暂时**恢复普通交互，草稿保留，回来再继续。</li>
 * </ul>
 *
 * <p>操作：左键 64 格射线选点（第 1/2 下选角点、第 3 下清草稿）、Ctrl+滚轮推正面、Alt+滚轮推反面、
 * Shift+左键退出、右键（草稿完整）进命名页面。</p>
 */
@EventBusSubscriber(modid = com.worldplayers.WorldPlayers.MOD_ID, value = Dist.CLIENT)
public final class RegionPlanner {
    private static final double REACH = 64.0D;
    /** 退出的聊天提示保留 tick（只是聊天，不是 HUD）。 */
    private static final int HUD_MESSAGE_TICKS = 60;

    private static boolean planning;
    private static BlockPos origin;
    private static BlockPos min;
    private static BlockPos max;
    /** 第一选择点：第二次左键后 origin 会清空，但聊天提示/清单坐标要用它，所以单独留一份。 */
    private static BlockPos firstCorner;
    /** 准心当前指着的草稿墙面（射线与草稿盒求交的结果，每 tick 更新）。 */
    private static Direction hoverFace;
    private static int exitMessageTicks;
    /** 上一 tick 的快捷栏槽位：按住 Ctrl/Alt 滚轮时用来兜底，防止切到别的格子。 */
    private static int lastSelectedSlot = -1;
    /** 客户端 tick 计数（高亮计时用）。 */
    private static int clientTicks;
    /** 被点击坐标的工作区高亮：名称 → 到期 tick（主人 2026-11-20 要求 20 秒、可叠加）。 */
    private static final Map<String, Integer> HIGHLIGHTS = new HashMap<>();
    /** 高亮时长：20 秒。 */
    private static final int HIGHLIGHT_TICKS = 20 * 20;
    /** 屏幕坐标里的区域浮窗（世界投影结果，每帧重算）。 */
    private static final List<ScreenTag> TAGS = new ArrayList<>();

    /**
     * 高亮框用的线渲染：**关闭深度测试**（可穿透方块）+ 白色。
     * 线宽加粗用「画两层、外面那层稍微膨胀」实现（GL 的 lineWidth &gt;1 在 core profile 下不可靠）。
     */
    public static final RenderType HIGHLIGHT_LINES = RenderType.create(
        "wp_zone_highlight",
        DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.LINES,
        1536,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
            .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.empty()))
            .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .createCompositeState(false));

    private record ScreenTag(int x, int y, WorkZone zone) {
    }

    public static boolean isPlanning() {
        return planning;
    }

    public static boolean hasBox() {
        return min != null && max != null;
    }

    public static BlockPos min() {
        return min;
    }

    public static BlockPos max() {
        return max;
    }

    public static void begin() {
        planning = true;
        origin = null;
        min = null;
        max = null;
        firstCorner = null;
        hoverFace = null;
        exitMessageTicks = 0;
        lastSelectedSlot = -1;
    }

    /** 退出规划模式（草稿保留，主人要求「暂时离开」不丢草稿）。 */
    public static void exitMode() {
        if (planning) {
            planning = false;
            exitMessageTicks = HUD_MESSAGE_TICKS;
            say(Component.translatable("chat.worldplayers.plan.exited").withStyle(ChatFormatting.YELLOW));
        }
    }

    /** 存完/退出世界：彻底清空。 */
    public static void clear() {
        planning = false;
        origin = null;
        min = null;
        max = null;
        firstCorner = null;
        hoverFace = null;
        exitMessageTicks = 0;
        lastSelectedSlot = -1;
        HIGHLIGHTS.clear();
    }

    @SubscribeEvent
    public static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        ZoneStore.set(List.of());
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /** 现在能不能按「操作手册」来拦输入：规划中 + 手持手册 + 没有开界面。 */
    private static boolean inputActive() {
        Minecraft minecraft = Minecraft.getInstance();
        return planning && minecraft.screen == null && minecraft.player != null && holdingManual(minecraft);
    }

    static boolean holdingManual(Minecraft minecraft) {
        if (minecraft.player == null) {
            return false;
        }
        return minecraft.player.getMainHandItem().is(WPItems.RESIDENT_MANUAL.get())
            || minecraft.player.getOffhandItem().is(WPItems.RESIDENT_MANUAL.get());
    }

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != InputConstants.PRESS || !inputActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getButton() == 0) {
            if (minecraft.options.keyShift.isDown()) {
                exitMode();
            } else {
                pickCorner(minecraft);
            }
            event.setCanceled(true);
        } else if (event.getButton() == 1) {
            // 右键：草稿完整就进命名/储存界面；否则不拦（让手册正常打开）
            if (hasBox()) {
                minecraft.setScreen(new WorkZoneScreen(min, max,
                    firstCorner == null ? min : firstCorner));
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (!inputActive()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean modifier = controlDown(minecraft);
        boolean alternate = altDown(minecraft);
        if (!modifier && !alternate) {
            return;
        }
        // 按住 Ctrl/Alt 时滚轮只用来微调草稿，**绝不能**再滚到快捷栏（主人 2026-11-20）
        event.setCanceled(true);
        if (!hasBox()) {
            return;
        }
        Direction face = resolveHoverFace(minecraft);
        if (face == null) {
            // 准心没对着草稿盒的任何一面：只吞掉滚轮，不动机框
            return;
        }
        if (alternate) {
            face = face.getOpposite();
        }
        // 滚轮方向（主人 2026-11-20 定稿）：**向下滚 = 这一面朝玩家方向扩展（变大）**，向上滚 = 收紧
        int step = event.getScrollDeltaY() > 0.0D ? -1 : 1;
        Direction.Axis axis = face.getAxis();
        if (face.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            max = clampAbove(max.relative(axis, step), min, axis);
        } else {
            min = clampBelow(min.relative(axis, -step), max, axis);
        }
        // 主人 2026-11-20：滚轮调框不再刷聊天提示
    }

    /**
     * 准心对准的**草稿墙面**：从眼睛沿视线做一条射线，和草稿包围盒的六个面求交，
     * 取最近的那个交点所在的面 —— 起点在盒子外面（进入面）或**站在盒子里面（出射面）**都能用
     * （主人 2026-11-20 明确要求：盒内也要能操作墙面，而且不要用玩家位置来猜方向）。
     * 射线没穿过草稿盒时返回 null（这时候滚轮只吞掉、不调框）。
     */
    private static Direction resolveHoverFace(Minecraft minecraft) {
        if (!hasBox()) {
            return null;
        }
        Vec3 eye = minecraft.player.getEyePosition(1.0F);
        Vec3 look = minecraft.player.getViewVector(1.0F);
        return rayFace(box(), eye, look, REACH);
    }

    /** 射线与盒子六个面的最近交点 → 那个面；起点在盒内时自然得到出射面。 */
    private static Direction rayFace(AABB box, Vec3 eye, Vec3 look, double reach) {
        double bestT = Double.MAX_VALUE;
        Direction best = null;
        for (Direction dir : Direction.values()) {
            // 射线在该轴上的分量（**不是** look·法线：负方向的面会被这个符号搞反）
            double denom = switch (dir.getAxis()) {
                case X -> look.x;
                case Y -> look.y;
                case Z -> look.z;
            };
            if (Math.abs(denom) < 1.0E-6D) {
                continue;
            }
            double plane = switch (dir.getAxis()) {
                case X -> dir.getStepX() > 0 ? box.maxX : box.minX;
                case Y -> dir.getStepY() > 0 ? box.maxY : box.minY;
                case Z -> dir.getStepZ() > 0 ? box.maxZ : box.minZ;
            };
            double eyeCoord = switch (dir.getAxis()) {
                case X -> eye.x;
                case Y -> eye.y;
                case Z -> eye.z;
            };
            double t = (plane - eyeCoord) / denom;
            if (t <= 1.0E-4D || t > reach || t >= bestT) {
                continue;
            }
            if (!onFace(box, eye.add(look.scale(t)), dir)) {
                continue;
            }
            bestT = t;
            best = dir;
        }
        return best;
    }

    /** 交点是否真的落在这个面的范围内（另外两个轴在盒内）。 */
    private static boolean onFace(AABB box, Vec3 hit, Direction dir) {
        double eps = 1.0E-4D;
        return switch (dir.getAxis()) {
            case X -> hit.y >= box.minY - eps && hit.y <= box.maxY + eps
                && hit.z >= box.minZ - eps && hit.z <= box.maxZ + eps;
            case Y -> hit.x >= box.minX - eps && hit.x <= box.maxX + eps
                && hit.z >= box.minZ - eps && hit.z <= box.maxZ + eps;
            case Z -> hit.x >= box.minX - eps && hit.x <= box.maxX + eps
                && hit.y >= box.minY - eps && hit.y <= box.maxY + eps;
        };
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        clientTicks++;
        if (!HIGHLIGHTS.isEmpty()) {
            HIGHLIGHTS.entrySet().removeIf(entry -> entry.getValue() <= clientTicks);
        }
        if (exitMessageTicks > 0) {
            exitMessageTicks--;
        }
        if (!planning) {
            lastSelectedSlot = -1;
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            clear();
            return;
        }
        // 准心所指的草稿面：每 tick 重算，滚轮和「单面白色高亮」都用它
        hoverFace = resolveHoverFace(minecraft);
        // 兜底：万一滚轮事件没被拦住，也把快捷栏槽位撤回（按住 Ctrl/Alt 时滚轮只调草稿）
        if (holdingManual(minecraft)) {
            int slot = minecraft.player.getInventory().selected;
            if (lastSelectedSlot >= 0 && slot != lastSelectedSlot
                    && (controlDown(minecraft) || altDown(minecraft))) {
                minecraft.player.getInventory().selected = lastSelectedSlot;
                slot = lastSelectedSlot;
            }
            lastSelectedSlot = slot;
        }
    }

    /** 左键：第 1/2 下选角点，第 3 下清草稿（主人定的三段）。 */
    private static void pickCorner(Minecraft minecraft) {
        BlockPos clicked = aimedCell(minecraft);
        if (clicked == null) {
            say(Component.translatable("chat.worldplayers.plan.no_target").withStyle(ChatFormatting.RED));
            return;
        }
        if (hasBox()) {
            origin = null;
            min = null;
            max = null;
            firstCorner = null;
            say(Component.translatable("chat.worldplayers.plan.cleared").withStyle(ChatFormatting.YELLOW));
            return;
        }
        if (origin == null) {
            origin = clicked;
            firstCorner = clicked;
            say(Component.translatable("chat.worldplayers.plan.first",
                clicked.getX(), clicked.getY(), clicked.getZ()));
            return;
        }
        BlockPos first = origin;
        origin = null;
        min = BlockPos.min(first, clicked);
        max = BlockPos.max(first, clicked);
        say(Component.translatable("chat.worldplayers.plan.second",
            clicked.getX(), clicked.getY(), clicked.getZ()));
    }

    /** 64 格射线（不受 4.5 格触及范围限制）。 */
    private static BlockPos aimedCell(Minecraft minecraft) {
        HitResult hit = minecraft.player.pick(REACH, 1.0F, false);
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    private static boolean controlDown(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_CONTROL)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    private static boolean altDown(Minecraft minecraft) {
        long window = minecraft.getWindow().getWindow();
        return InputConstants.isKeyDown(window, GLFW.GLFW_KEY_LEFT_ALT)
            || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    static void say(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(message, false);
        }
    }

    private static AABB box() {
        return new AABB(min.getX(), min.getY(), min.getZ(),
            max.getX() + 1.0D, max.getY() + 1.0D, max.getZ() + 1.0D);
    }

    private static BlockPos clampAbove(BlockPos moved, BlockPos floor, Direction.Axis axis) {
        return new BlockPos(
            axis == Direction.Axis.X ? Math.max(moved.getX(), floor.getX()) : moved.getX(),
            axis == Direction.Axis.Y ? Math.max(moved.getY(), floor.getY()) : moved.getY(),
            axis == Direction.Axis.Z ? Math.max(moved.getZ(), floor.getZ()) : moved.getZ());
    }

    private static BlockPos clampBelow(BlockPos moved, BlockPos ceiling, Direction.Axis axis) {
        return new BlockPos(
            axis == Direction.Axis.X ? Math.min(moved.getX(), ceiling.getX()) : moved.getX(),
            axis == Direction.Axis.Y ? Math.min(moved.getY(), ceiling.getY()) : moved.getY(),
            axis == Direction.Axis.Z ? Math.min(moved.getZ(), ceiling.getZ()) : moved.getZ());
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        boolean manual = holdingManual(minecraft);
        // 已储存区域必须**手持手册**才显示；道路跟「城市道路」勾选，其它（农田等）跟「工作区域」勾选
        // （主人 2026-11-20：两种类型各走各的开关）
        boolean showRoads = manual && WPClientConfig.showCityRoads();
        boolean showWorkArea = manual && WPClientConfig.showWorkArea();
        // 娱乐区域独立勾选（2026-11-22）：只开这一项也要能看到娱乐区
        boolean showEntertainment = manual && WPClientConfig.showEntertainment();
        // 「建筑蓝图」（2026-11-23 由「建造中的工程」改名）：挖掘 / 填充工程区的渲染开关，
        // 同样只在**手持手册**时显示
        boolean showBlueprint = manual && WPClientConfig.showBuildingProject();
        boolean showSaved = showRoads || showWorkArea || showEntertainment || showBlueprint;
        boolean showDraft = planning && hasBox();
        boolean showOrigin = planning && origin != null;
        TAGS.clear();
        // 点过坐标的高亮框即使收起手册也要留着（持续 20 秒）
        if (!planning && !manual && HIGHLIGHTS.isEmpty()) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        VertexConsumer highlight = buffers.getBuffer(HIGHLIGHT_LINES);
        if (showSaved || !HIGHLIGHTS.isEmpty()) {
            for (WorkZone zone : ZoneStore.zones()) {
                boolean highlighted = isHighlighted(zone);
                if (!highlighted && !zoneVisible(zone, showRoads, showWorkArea)) {
                    continue;
                }
                if (highlighted) {
                    // 白色 + 加粗（画两层，外层稍微膨胀）+ 无深度测试（穿透方块）
                    net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, highlight, zone.box(),
                        1.0F, 1.0F, 1.0F, 1.0F);
                    net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, highlight,
                        zone.box().inflate(0.02D), 1.0F, 1.0F, 1.0F, 0.55F);
                } else {
                    int color = zoneColor(zone.type());
                    net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, lines, zone.box(),
                        ((color >> 16) & 0xFF) / 255.0F, ((color >> 8) & 0xFF) / 255.0F,
                        (color & 0xFF) / 255.0F, 1.0F);
                }
            }
        }
        if (showDraft) {
            net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, lines, box(),
                0.25F, 1.0F, 0.35F, 1.0F);
        }
        if (showOrigin) {
            AABB point = new AABB(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + 1.0D, origin.getY() + 1.0D, origin.getZ() + 1.0D);
            net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, lines, point,
                1.0F, 0.95F, 0.2F, 1.0F);
        }
        buffers.endBatch(RenderType.lines());
        buffers.endBatch(HIGHLIGHT_LINES);
        // 准心指着的草稿面：蒙一层淡白（主人 2026-11-20）
        if (showDraft && hoverFace != null) {
            drawHoverFace(buffers, pose, box(), hoverFace);
            buffers.endBatch(RenderType.debugQuads());
        }
        pose.popPose();

        // 区域浮窗：世界坐标投到屏幕，GUI 阶段再画（同样按类型走各自的渲染开关）
        if (showSaved) {
            for (WorkZone zone : ZoneStore.zones()) {
                if (!zoneVisible(zone, showRoads, showWorkArea)) {
                    continue;
                }
                Vec3 center = zone.box().getCenter();
                // 离开区域中心 32 格就不画浮窗（主人 2026-11-20）
                if (minecraft.player.position().distanceToSqr(center) > 32.0D * 32.0D) {
                    continue;
                }
                Vec3 rel = center.subtract(camera);
                Vector4f clip = new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1.0F);
                clip.mul(event.getModelViewMatrix());
                clip.mul(event.getProjectionMatrix());
                if (clip.w() <= 0.05F) {
                    continue;
                }
                float ndcX = clip.x() / clip.w();
                float ndcY = clip.y() / clip.w();
                if (Math.abs(ndcX) > 1.3F || Math.abs(ndcY) > 1.3F) {
                    continue;
                }
                int screenX = (int) ((ndcX * 0.5F + 0.5F) * minecraft.getWindow().getGuiScaledWidth());
                int screenY = (int) ((0.5F - ndcY * 0.5F) * minecraft.getWindow().getGuiScaledHeight());
                TAGS.add(new ScreenTag(screenX, screenY, zone));
            }
        }
    }

    /** 各类型区域的颜色（以后新增类型在这里加一行）。 */
    public static int zoneColor(String type) {
        if (WorkZone.TYPE_FARM.equals(type)
                || WorkZone.TYPE_TREE_FARM.equals(type)
                || WorkZone.TYPE_RANCH.equals(type)
                || WorkZone.TYPE_FISHERY.equals(type)) {
            return 0xFFA44A; // 工作区域（农田 / 树场 / 牧场 / 渔场）：橘色
        }
        if (WorkZone.TYPE_FORBIDDEN.equals(type)) {
            return 0xFF4444; // 禁行区域：红色
        }
        if (WorkZone.TYPE_ENTERTAINMENT.equals(type)) {
            return 0xAA00AA; // 娱乐区域（14 号文档 1.3）：#AA00AA 紫
        }
        if (WorkZone.TYPE_CUSTOM.equals(type)) {
            return 0xFFF6E0; // 自订区域（2026-11-25）：白纸色
        }
        if (WorkZone.TYPE_MINE.equals(type) || WorkZone.TYPE_FILL.equals(type)) {
            return 0x55FF55; // 工程区域（挖掘 / 填充）：绿色（规格 3.1）
        }
        return 0x55FF55; // 道路：绿色
    }

    /**
     * 这个区域归哪个渲染勾选管：道路 →「城市道路」，禁行 →「禁行区域」，
     * 挖掘 / 填充 →「建筑蓝图」，其它（农田等）→「工作区域」（主人 2026-11-20 / 2026-11-23）。
     */
    private static boolean zoneVisible(WorkZone zone, boolean showRoads, boolean showWorkArea) {
        if (WorkZone.TYPE_FORBIDDEN.equals(zone.type())) {
            return WPClientConfig.showForbidden();
        }
        // 娱乐区域有独立勾选（默认开），不跟「工作区域」走（14 号文档 1.4）
        if (WorkZone.TYPE_ENTERTAINMENT.equals(zone.type())) {
            return WPClientConfig.showEntertainment();
        }
        // 工程区（挖掘 / 填充）＝「建筑蓝图」（2026-11-23）
        if (WorkZone.TYPE_MINE.equals(zone.type()) || WorkZone.TYPE_FILL.equals(zone.type())) {
            return WPClientConfig.showBuildingProject();
        }
        return WorkZone.TYPE_ROAD.equals(zone.type()) ? showRoads : showWorkArea;
    }

    /**
     * 在手册里点了某个工作区的坐标：白色加粗穿透框高亮 20 秒（主人 2026-11-20）。
     * 重复点同一个会重置时间；点别的只是再加一个，互不影响。同名区域按「名字 + 第一选择点」区分。
     */
    public static void highlight(WorkZone zone) {
        HIGHLIGHTS.put(zone.key(), clientTicks + HIGHLIGHT_TICKS);
    }

    private static boolean isHighlighted(WorkZone zone) {
        return HIGHLIGHTS.containsKey(zone.key());
    }

    /** 浮窗用：区域所有员工（按员工栏顺序，空槽跳过）。 */
    private static java.util.List<com.worldplayers.network.ResidentListPayload.Member> employeesOf(WorkZone zone) {
        java.util.List<com.worldplayers.network.ResidentListPayload.Member> out = new java.util.ArrayList<>();
        for (String id : zone.employees()) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            for (var member : ResidentStore.members()) {
                if (member.id().toString().equals(id)) {
                    out.add(member);
                    break;
                }
            }
        }
        return out;
    }

    /** 浮窗用：区域第一个员工的成员信息（找不到就 null）。 */
    @javax.annotation.Nullable
    private static com.worldplayers.network.ResidentListPayload.Member firstEmployee(WorkZone zone) {
        String id = zone.firstEmployee();
        if (id.isEmpty()) {
            return null;
        }
        for (var member : ResidentStore.members()) {
            if (member.id().toString().equals(id)) {
                return member;
            }
        }
        return null;
    }

    /** 浮窗用：区域绑定的员工皮肤（找不到就给默认玩家皮肤）。 */
    private static net.minecraft.resources.ResourceLocation residentTexture(WorkZone zone) {
        var member = firstEmployee(zone);
        if (member != null) {
            return ClientSkins.skin(member.skin()).texture();
        }
        return net.minecraft.client.resources.DefaultPlayerSkin.getDefaultTexture();
    }

    /** 准心指着的草稿面蒙一层淡白（稍微往外偏 0.003 格，避免和方块表面 z-fighting）。 */
    private static void drawHoverFace(MultiBufferSource.BufferSource buffers, PoseStack pose,
                                      AABB box, Direction face) {
        double e = 0.003D;
        Vec3[] corners = switch (face) {
            case UP -> new Vec3[]{
                new Vec3(box.minX, box.maxY + e, box.minZ), new Vec3(box.maxX, box.maxY + e, box.minZ),
                new Vec3(box.maxX, box.maxY + e, box.maxZ), new Vec3(box.minX, box.maxY + e, box.maxZ)};
            case DOWN -> new Vec3[]{
                new Vec3(box.minX, box.minY - e, box.minZ), new Vec3(box.maxX, box.minY - e, box.minZ),
                new Vec3(box.maxX, box.minY - e, box.maxZ), new Vec3(box.minX, box.minY - e, box.maxZ)};
            case EAST -> new Vec3[]{
                new Vec3(box.maxX + e, box.minY, box.minZ), new Vec3(box.maxX + e, box.maxY, box.minZ),
                new Vec3(box.maxX + e, box.maxY, box.maxZ), new Vec3(box.maxX + e, box.minY, box.maxZ)};
            case WEST -> new Vec3[]{
                new Vec3(box.minX - e, box.minY, box.minZ), new Vec3(box.minX - e, box.maxY, box.minZ),
                new Vec3(box.minX - e, box.maxY, box.maxZ), new Vec3(box.minX - e, box.minY, box.maxZ)};
            case SOUTH -> new Vec3[]{
                new Vec3(box.minX, box.minY, box.maxZ + e), new Vec3(box.maxX, box.minY, box.maxZ + e),
                new Vec3(box.maxX, box.maxY, box.maxZ + e), new Vec3(box.minX, box.maxY, box.maxZ + e)};
            case NORTH -> new Vec3[]{
                new Vec3(box.minX, box.minY, box.minZ - e), new Vec3(box.maxX, box.minY, box.minZ - e),
                new Vec3(box.maxX, box.maxY, box.minZ - e), new Vec3(box.minX, box.maxY, box.minZ - e)};
        };
        VertexConsumer consumer = buffers.getBuffer(RenderType.debugQuads());
        Matrix4f matrix = pose.last().pose();
        for (Vec3 corner : corners) {
            consumer.addVertex(matrix, (float) corner.x, (float) corner.y, (float) corner.z)
                .setColor(1.0F, 1.0F, 1.0F, 0.20F);
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        GuiGraphics gui = event.getGuiGraphics();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();

        // 区域浮窗（图标 + 名称；有绑定居民时再加头像 + 居民名），绑定类型开关
        if (!TAGS.isEmpty()) {
            for (ScreenTag tag : TAGS) {
                WorkZone zone = tag.zone();
                int color = zoneColor(zone.type());
                Component name = Component.literal(zone.name());
                // 员工：只显示头像，不显示名字；有几个员工就画几个（主人 2026-11-20）
                java.util.List<com.worldplayers.network.ResidentListPayload.Member> staff =
                    employeesOf(zone);
                int textW = minecraft.font.width(name);
                int boxW = textW + 24 + staff.size() * 10;
                int x = tag.x() - boxW / 2;
                int y = tag.y() - 10;
                gui.fill(x, y, x + boxW, y + 18, 0x88000000);
                gui.fill(x, y, x + 2, y + 18, 0xFF000000 | color);
                gui.renderItem(ManualScreen.zoneIcon(zone.type()), x + 4, y + 1);
                int textX = x + 22;
                for (var member : staff) {
                    net.minecraft.resources.ResourceLocation texture =
                        ClientSkins.skin(member.skin()).texture();
                    gui.blit(texture, textX - 1, y + 5, 8, 8, 8.0F, 8.0F, 8, 8, 64, 64);
                    gui.blit(texture, textX - 1, y + 5, 8, 8, 40.0F, 8.0F, 8, 8, 64, 64);
                    textX += 10;
                }
                gui.drawString(minecraft.font, name, textX, y + 5, 0xFFFFFF, true);
            }
        }

        // 规划模式：物品栏上方提示 + 右侧教学栏
        boolean active = planning && holdingManual(minecraft);
        if (active) {
            int centerX = width / 2;
            int y = height - 62;
            gui.drawCenteredString(minecraft.font,
                Component.translatable("gui.worldplayers.plan.active"), centerX, y, 0xFFFFFF);
            if (hasBox()) {
                gui.drawCenteredString(minecraft.font,
                    Component.translatable("gui.worldplayers.plan.marked",
                        (max.getX() - min.getX() + 1), (max.getY() - min.getY() + 1),
                        (max.getZ() - min.getZ() + 1)),
                    centerX, y + 11, 0x55FF55);
            }
            drawTutorial(gui, minecraft, width, height);
        }
        if (exitMessageTicks > 0) {
            int alpha = Math.min(255, exitMessageTicks * 5);
            gui.drawCenteredString(minecraft.font,
                Component.translatable("gui.worldplayers.plan.exited"),
                width / 2, height - 62, (alpha << 24) | 0xFFFFFF);
        }
    }

    /** 左侧教学栏（纯文字，走语言键，方便以后翻译）。主人要求放屏幕左边。 */
    private static void drawTutorial(GuiGraphics gui, Minecraft minecraft, int width, int height) {
        Component[] lines = {
            Component.translatable("gui.worldplayers.plan.tutorial.title"),
            Component.translatable("gui.worldplayers.plan.tutorial.pick"),
            Component.translatable("gui.worldplayers.plan.tutorial.clear"),
            Component.translatable("gui.worldplayers.plan.tutorial.roll_front"),
            Component.translatable("gui.worldplayers.plan.tutorial.roll_back"),
            Component.translatable("gui.worldplayers.plan.tutorial.finish"),
            Component.translatable("gui.worldplayers.plan.tutorial.exit")
        };
        int lineH = 11;
        int padding = 6;
        int boxW = 0;
        for (Component line : lines) {
            boxW = Math.max(boxW, minecraft.font.width(line));
        }
        boxW += padding * 2;
        int boxH = lines.length * lineH + padding * 2;
        int x = 10;
        int y = height / 2 - boxH / 2;
        gui.fill(x, y, x + boxW, y + boxH, 0x99000000);
        gui.fill(x, y, x + boxW, y + 1, 0xFF808080);
        for (int i = 0; i < lines.length; i++) {
            int color = i == 0 ? 0xFFFFD24A : 0xFFFFFF;
            gui.drawString(minecraft.font, lines[i], x + padding, y + padding + i * lineH, color, true);
        }
    }
}
