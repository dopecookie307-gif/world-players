package com.worldplayers.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.worldplayers.compat.ysm.YsmGeometry;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 把 {@link YsmGeometry} 画出来。
 *
 * <p>坐标语义见 {@link YsmGeometry}：pivot 是**绝对**模型坐标（格），四边形顶点也是绝对的，
 * 每个骨骼按 {@code T(pivot) · Rz·Ry·Rx · S · T(-pivot)} 变换后沿父子链递归 —— 和 folkways 的
 * {@code GeoMeshData.Builder#walk} 完全一致。立方体自转已经烘焙进顶点，这里不再处理。</p>
 *
 * <p>变换来源（后面的覆盖前面的同名分量）：</p>
 * <ol>
 *   <li>几何里的静态 position/rotation/scale；</li>
 *   <li>动画姿态 {@link YsmPose}（有 rotation 通道就覆盖静态）；</li>
 *   <li>{@link YsmAnim} 的程序化补充（只在动画没写 rotation 时加）。</li>
 * </ol>
 */
public final class YsmModelRenderer {
    /** 贴脸件外推多少格（沿脸朝方向 −z；1/100 格，肉眼不可见，只为避开与头面的深度冲突）。 */
    private static final float FACE_PUSH = 0.01F;
    /** 每个模型只打一次姿态诊断（排查用）。 */
    private static final java.util.Set<Integer> DIAGNOSED = new java.util.HashSet<>();
    /** 保底模式只警告一次，免得有人开着它去测动画。 */
    private static boolean rawWarned;
    /** 手持物品挂点只对每个模型打一次日志（定位「物品在哪只手上 / 挂到哪根骨」）。 */
    private static final java.util.Set<Integer> HAND_LOGGED = new java.util.HashSet<>();

    public static void render(YsmGeometry geometry, Map<String, YsmPose.BonePose> pose, YsmAnim anim,
                              PoseStack stack, VertexConsumer buffer, MultiBufferSource buffers,
                              int light, int overlay, @javax.annotation.Nullable LivingEntity entity) {
        if (DIAGNOSED.size() < 64 && DIAGNOSED.add(System.identityHashCode(geometry))) {
            diagnose(geometry, pose);
        }
        boolean raw = com.worldplayers.config.WPClientConfig.useYsmRawGeometry();
        if (raw && !rawWarned) {
            rawWarned = true;
            com.worldplayers.WorldPlayers.LOGGER.warn(
                "World Players: YSM 保底模式（ysmRawGeometry=true）开着 —— 跳过所有骨骼旋转、也不播任何动画；"
                    + "调试完记得改回 false，否则看起来就是「碎片立正漂浮」");
        }
        Context context = new Context(geometry, pose, anim, entity, buffer, buffers, light, overlay,
            hasHandLocator(geometry));
        for (YsmGeometry.Bone root : geometry.roots) {
            if (raw) {
                drawRaw(root, stack, buffer, light, overlay, new float[3]);
            } else {
                drawBone(context, root, stack);
            }
        }
    }

    /** 一次渲染里到处都要用的东西（省得每个方法背十个参数）。 */
    private record Context(YsmGeometry geometry, Map<String, YsmPose.BonePose> pose, YsmAnim anim,
                           @javax.annotation.Nullable LivingEntity entity, VertexConsumer buffer,
                           @javax.annotation.Nullable MultiBufferSource buffers, int light, int overlay,
                           boolean hasHandLocator) {
    }

    /** 模型里有没有 `*HandLocator`（有就只认它，否则 `RightHand` 和它同位置会重复画两个物品）。 */
    private static boolean hasHandLocator(YsmGeometry geometry) {
        for (YsmGeometry.Bone root : geometry.roots) {
            if (containsHandLocator(root)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHandLocator(YsmGeometry.Bone bone) {
        if (bone.name.toLowerCase(java.util.Locale.ROOT).contains("handlocator")) {
            return true;
        }
        for (YsmGeometry.Bone child : bone.children) {
            if (containsHandLocator(child)) {
                return true;
            }
        }
        return false;
    }

    /** 原始几何模式：骨骼只按 pivot 差平移，不旋转、不缩放（排查用）。 */
    private static void drawRaw(YsmGeometry.Bone bone, PoseStack stack, VertexConsumer buffer,
                                int light, int overlay, float[] parentPivot) {
        if (bone.hidden) {
            return;
        }
        stack.pushPose();
        stack.translate(bone.pivot[0] - parentPivot[0], bone.pivot[1] - parentPivot[1],
            bone.pivot[2] - parentPivot[2]);
        PoseStack.Pose last = stack.last();
        for (YsmGeometry.Quad quad : bone.quads) {
            emit(quad, last, buffer, light, overlay);
        }
        for (YsmGeometry.Bone child : bone.children) {
            drawRaw(child, stack, buffer, light, overlay, bone.pivot);
        }
        stack.popPose();
    }

    /** 正常模式：骨骼 pivot 旋转 + 缩放，逐骨骼递归。 */
    private static void drawBone(Context context, YsmGeometry.Bone bone, PoseStack stack) {
        Map<String, YsmPose.BonePose> pose = context.pose();
        YsmAnim anim = context.anim();
        if (bone.hidden) {
            return;
        }
        YsmPose.BonePose animated = pose == null ? null : pose.get(bone.name);
        if (animated != null && !animated.visible) {
            return;
        }
        float rx = bone.rotation[0];
        float ry = bone.rotation[1];
        float rz = bone.rotation[2];
        float sx = bone.scale[0];
        float sy = bone.scale[1];
        float sz = bone.scale[2];
        float ox = 0.0F;
        float oy = 0.0F;
        float oz = 0.0F;
        boolean animatedRotation = false;
        if (animated != null) {
            if (animated.hasRotation) {
                // 动画 rotation 是**相对 bind 的增量**，不是覆盖！
                // 例：LeftArm 的 bind 是 Z=-18°（袖子往外张），walk 动画首帧写 Z=-0.76°，
                // 覆盖的话手臂会被掰回身体里、裙摆四片全部立正（主人看到的「裙甲漂浮」）。
                rx = bone.rotation[0] - animated.rx;
                ry = bone.rotation[1] - animated.ry;
                rz = bone.rotation[2] + animated.rz;
                // ★ 但「写了通道、值恒为 0」不能算动画在管：`pre_parallel2` 给 Tail/长发写了 rotation，
                // 数值全来自控制器的 v.L*_P*（我们没有控制器 → 恒 0）。以前这种 0 会挡住程序化摆动，
                // 结果是「尾巴一动不摇」。值≈0 时仍然允许程序化补。
                // 例外是**人形肢体**（手臂/腿/头/躯干）：摆臂的 cos 会周期性过零，按值判断的话手臂
                // 每次过零都会被程序化「抢一下」，看起来像抖。肢体只要有通道就归动画管。
                animatedRotation = Math.abs(animated.rx) > 0.01F || Math.abs(animated.ry) > 0.01F
                    || Math.abs(animated.rz) > 0.01F || isLimb(bone.name);
            }
            sx = bone.scale[0] * clampScale(animated.sx);
            sy = bone.scale[1] * clampScale(animated.sy);
            sz = bone.scale[2] * clampScale(animated.sz);
            // position 通道单位是**模型像素**：和 inflate 一样要乘这个模型的 ws/hs
            // （骨架绝对坐标已经是「格」并且按 ws/hs 缩放过；只除 16 会让 hs=0.7 的模型上的
            //  位移放大 1.43 倍 —— 嘴被推出过头、眼睛/表情块深度全乱）。
            // 坐标空间里 x 是镜像的，y/z 不翻。
            float ws = context.geometry().widthScale;
            float hs = context.geometry().heightScale;
            float px = clampOffset(animated.px);
            float py = clampOffset(animated.py);
            float pz = clampOffset(animated.pz);
            if (bone.mouthPart) {
                // 嘴是贴脸件：作者的 idle 里偶尔挂着 −5.4 像素的 y 位移（嘴会掉到下巴，
                // 甚至跑到脖子前面），那不是「表情」而是没排完的片段。大的 x/y 位移直接忽略，
                // 只保留「往前推」的 z —— 嘴永远待在脸上，前后位置仍按作者。
                if (Math.abs(px) > 2.0F) {
                    px = 0.0F;
                }
                if (Math.abs(py) > 2.0F) {
                    py = 0.0F;
                }
            }
            ox = -px * ws * 0.0625F;
            oy = py * hs * 0.0625F;
            oz = pz * ws * 0.0625F;
        }
        // 程序化动作加在「模型空间轴」上；动画自己写了 rotation 就让位。
        // ★ alpha.39：让位/接管都按权重渐变 —— 以前是硬开关，动画增量收到 ≈0 的那一帧
        // 程序化的外张（5° + 摇摆 5.7°）会一整帧跳进来，主人看到的「过渡动画窄手臂 → 瞬移 → 待机宽手臂」
        // 就是这 10° 的阶跃。现在动画撒手后 0.55 秒把程序化涨上来，重新走路时 0.15 秒收掉。
        float weight = anim.weight(bone.name, animatedRotation);
        if (weight > 0.001F) {
            rx += anim.extraX(bone.name, weight);
            ry += anim.extraY(bone.name, weight);
            rz += anim.extraZ(bone.name, weight);
        }
        float[] pivot = bone.pivot;
        stack.pushPose();
        stack.translate(pivot[0] + ox, pivot[1] + oy, pivot[2] + oz);
        applyRotationAndScale(stack, rx, ry, rz, sx, sy, sz);
        stack.translate(-pivot[0], -pivot[1], -pivot[2]);
        // ★ alpha.41b：贴脸件的「外推」必须**整根骨一起沿脸朝方向挪**（模型空间 −z），
        // 不能按每个面的法线各自推 —— 那样一个像素块的六个面会被撑开，睫毛/眼睑就像「星球爆炸」。
        if (isFacePart(bone.name)) {
            stack.translate(0.0F, 0.0F, -FACE_PUSH);
        }
        PoseStack.Pose last = stack.last();
        for (YsmGeometry.Quad quad : bone.quads) {
            emit(quad, last, context.buffer(), context.light(), context.overlay());
        }
        // 手持物品：挂在手部定位骨骼上（YSM 的 *HandLocator 就是干这个的）
        renderHandItem(context, bone, stack);
        for (YsmGeometry.Bone child : bone.children) {
            drawBone(context, child, stack);
        }
        stack.popPose();
    }

    /**
     * 手臂/腿这类「摆幅大、cos 周期性过零」的骨骼：动画有通道就一定归动画管，
     * 免得每次过零都被程序化抢一下（看起来像抖）。
     *
     * <p>**头不要放进来**：Head 的通道常常是恒 0 占位，值判定时程序化正好能补「头跟着人转」——
     * 把 Head 也算肢体的话，居民的头就不会跟着玩家看了。</p>
     */
    private static boolean isLimb(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("tail") || lower.contains("hair") || lower.contains("clothe")) {
            return false;
        }
        return lower.contains("arm") || lower.contains("leg") || lower.contains("foot");
    }

    /**
     * 在 {@code RightHandLocator} / {@code LeftHandLocator}（没有就退回 {@code RightHand} / {@code LeftHand}）
     * 上画手持物品。
     *
     * <p>为什么是「再算一遍变换」而不是直接复用原版 {@code ItemInHandLayer}：YSM 分支完全跳过了
     * {@code super.render}，原版那层挂在 {@code PlayerModel} 的部件上，根本没有跑的机会。</p>
     *
     * <p>变换推导（和 {@link YsmGeometry} 的空间一致）：我们的模型空间 = 原版模型空间绕 Z 转 180°，
     * 也就是 {@code M = diag(-1,-1,1)}。原版手里的物品链是
     * {@code A · Rx(-90) · Ry(180) · T(v) · D}（A = 手臂矩阵，D = 物品模型自己的 display 变换），
     * 共轭到我们的空间就是 {@code A' · M·Rx(-90)·Ry(180) · T(M·v) · D}，而
     * {@code M·Rx(-90)·Ry(180) = Rx(90)·Ry(180)·Rz(180)}，所以下面按这个顺序 mulPose。
     * D 不用动（这也是为什么物品本身不会被镜像）。</p>
     */
    private static void renderHandItem(Context context, YsmGeometry.Bone bone, PoseStack stack) {
        LivingEntity entity = context.entity();
        MultiBufferSource buffers = context.buffers();
        if (entity == null || buffers == null) {
            return;
        }
        String lower = bone.name.toLowerCase(java.util.Locale.ROOT);
        // "*2" 是同一模型里的另一套躯干（挂在很远的骨骼上），不能重复挂物品
        if (lower.endsWith("2")) {
            return;
        }
        boolean right;
        boolean locator = lower.contains("handlocator");
        if (locator) {
            right = lower.contains("right");
        } else if (context.hasHandLocator()) {
            // 模型有 *HandLocator → 只认它，普通 RightHand/LeftHand 不再重复挂
            return;
        } else if ("righthand".equals(lower) || "lefthand".equals(lower)) {
            right = "righthand".equals(lower);
        } else {
            return;
        }
        HumanoidArm arm = right ? HumanoidArm.RIGHT : HumanoidArm.LEFT;
        ItemStack item = arm == entity.getMainArm() ? entity.getMainHandItem() : entity.getOffhandItem();
        if (item.isEmpty()) {
            return;
        }
        // ★ alpha.39b：不再按 1/heightScale 反除。骨骼矩阵只有「平移 + 旋转」，顶点里的 height_scale
        // 不会传到挂在手上的物品；反除等于把物品放大 32%，剑尖直接插进地面（主人在 alpha.36 看到的
        // 「太大 + 在地上」是同一条）。默认原版大小，嫌小就调 config 的 ysmItemScale。
        float scale = com.worldplayers.config.WPClientConfig.ysmItemScale();
        if (HAND_LOGGED.size() < 32 && HAND_LOGGED.add(System.identityHashCode(context.geometry()))) {
            com.worldplayers.WorldPlayers.LOGGER.info(
                "World Players: YSM 手持物品挂在 {}（pivot {},{},{}，物品缩放 {}）", bone.name,
                round(bone.pivot[0]), round(bone.pivot[1]), round(bone.pivot[2]), round(scale));
        }
        stack.pushPose();
        // ★ 物品的原点是「骨骼 pivot」= 手的位置。drawBone 里的骨骼矩阵是 T(p)…T(-p)，
        // 当前坐标系的原点已经被搬回模型原点，所以这里必须自己平移到 pivot ——
        // 之前少了这一句，物品就画在脚底（主人：「尺寸对了，但位置还是在地上」）。
        stack.translate(bone.pivot[0], bone.pivot[1], bone.pivot[2]);
        stack.scale(scale, scale, scale);
        stack.mulPose(Axis.XP.rotationDegrees(90.0F));
        stack.mulPose(Axis.YP.rotationDegrees(180.0F));
        stack.mulPose(Axis.ZP.rotationDegrees(180.0F));
        ItemDisplayContext display = right
            ? ItemDisplayContext.THIRD_PERSON_RIGHT_HAND : ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        Minecraft.getInstance().getItemRenderer().renderStatic(item, display, context.light(),
            context.overlay(), stack, buffers, entity.level(), entity.getId());
        stack.popPose();
    }

    /** 贴在脸上的部件（眼睛/眼睑/眉毛/嘴/鼻子/表情贴片）—— 渲染时沿法线外推一点点，防 z-fighting。 */
    private static boolean isFacePart(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("eye") || lower.contains("eyelid") || lower.contains("brow")
            || lower.contains("mouth") || lower.contains("nose") || lower.contains("expression");
    }

    private static void emit(YsmGeometry.Quad quad, PoseStack.Pose last, VertexConsumer buffer,
                             int light, int overlay) {
        for (int i = 0; i < 4; i++) {
            buffer.addVertex(last, quad.pos[i * 3], quad.pos[i * 3 + 1], quad.pos[i * 3 + 2])
                .setColor(255, 255, 255, 255)
                .setUv(quad.uv[i * 2], quad.uv[i * 2 + 1])
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(last, quad.normal[0], quad.normal[1], quad.normal[2]);
        }
    }

    /** 第一次渲染某模型时，把离谱的姿态通道 + 应用姿态后的真实包围盒打到日志里。 */
    private static void diagnose(YsmGeometry geometry, Map<String, YsmPose.BonePose> pose) {
        StringBuilder odd = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, YsmPose.BonePose> entry : pose.entrySet()) {
            YsmPose.BonePose p = entry.getValue();
            boolean bad = Math.abs(p.sx - 1.0F) > 0.05F || Math.abs(p.sy - 1.0F) > 0.05F
                || Math.abs(p.sz - 1.0F) > 0.05F
                || Math.abs(p.px) > 3.0F || Math.abs(p.py) > 3.0F || Math.abs(p.pz) > 3.0F
                || Math.abs(p.rx) > 100.0F || Math.abs(p.ry) > 100.0F || Math.abs(p.rz) > 100.0F;
            if (!bad) {
                continue;
            }
            count++;
            if (count <= 10) {
                odd.append(entry.getKey())
                    .append("(s=").append(round(p.sx)).append(',').append(round(p.sy)).append(',').append(round(p.sz))
                    .append(" p=").append(round(p.px)).append(',').append(round(p.py)).append(',').append(round(p.pz))
                    .append(" r=").append(round(p.rx)).append(',').append(round(p.ry)).append(',').append(round(p.rz))
                    .append(") ");
            }
        }
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean[] any = {false};
        for (YsmGeometry.Bone root : geometry.roots) {
            measureWithPose(root, YsmGeometry.Affine.IDENTITY, pose, geometry.widthScale, geometry.heightScale,
                min, max, any);
        }
        com.worldplayers.WorldPlayers.LOGGER.info(
            "World Players: YSM 姿态诊断 动画骨骼{} 异常通道{} 姿态包围盒 {}×{}×{} 格 (y {}\uFF5E{}) 异常明细：{}",
            pose.size(), count,
            any[0] ? round(max[0] - min[0]) : -1.0F,
            any[0] ? round(max[1] - min[1]) : -1.0F,
            any[0] ? round(max[2] - min[2]) : -1.0F,
            any[0] ? round(min[1]) : 0.0F,
            any[0] ? round(max[1]) : 0.0F,
            count == 0 ? "无" : odd.toString());
    }

    /** 和 {@link #drawBone} 同一套变换，只算顶点包围盒（方块单位）。 */
    private static void measureWithPose(YsmGeometry.Bone bone, float[] parent, Map<String, YsmPose.BonePose> pose,
                                        float ws, float hs, float[] min, float[] max, boolean[] any) {
        if (bone.hidden) {
            return;
        }
        YsmPose.BonePose p = pose.get(bone.name);
        if (p != null && !p.visible) {
            return;
        }
        float rx = bone.rotation[0];
        float ry = bone.rotation[1];
        float rz = bone.rotation[2];
        float sx = bone.scale[0];
        float sy = bone.scale[1];
        float sz = bone.scale[2];
        float ox = 0.0F;
        float oy = 0.0F;
        float oz = 0.0F;
        if (p != null) {
            rx = bone.rotation[0] - p.rx;
            ry = bone.rotation[1] - p.ry;
            rz = bone.rotation[2] + p.rz;
            sx *= clampScale(p.sx);
            sy *= clampScale(p.sy);
            sz *= clampScale(p.sz);
            float px = clampOffset(p.px);
            float py = clampOffset(p.py);
            if (bone.mouthPart) {
                if (Math.abs(px) > 2.0F) {
                    px = 0.0F;
                }
                if (Math.abs(py) > 2.0F) {
                    py = 0.0F;
                }
            }
            ox = -px * ws * 0.0625F;
            oy = py * hs * 0.0625F;
            oz = clampOffset(p.pz) * ws * 0.0625F;
        }
        float[] pivot = bone.pivot;
        float[] m = YsmGeometry.Affine.mul(parent, YsmGeometry.Affine.translate(
            pivot[0] + ox, pivot[1] + oy, pivot[2] + oz));
        if (rz != 0) {
            m = YsmGeometry.Affine.mul(m, YsmGeometry.Affine.rotation(2, rz));
        }
        if (ry != 0) {
            m = YsmGeometry.Affine.mul(m, YsmGeometry.Affine.rotation(1, ry));
        }
        if (rx != 0) {
            m = YsmGeometry.Affine.mul(m, YsmGeometry.Affine.rotation(0, rx));
        }
        if (sx != 1 || sy != 1 || sz != 1) {
            m = YsmGeometry.Affine.mul(m, YsmGeometry.Affine.scale(sx, sy, sz));
        }
        m = YsmGeometry.Affine.mul(m, YsmGeometry.Affine.translate(-pivot[0], -pivot[1], -pivot[2]));
        for (YsmGeometry.Quad quad : bone.quads) {
            for (int i = 0; i < 4; i++) {
                float[] v = YsmGeometry.Affine.apply(m, quad.pos[i * 3], quad.pos[i * 3 + 1], quad.pos[i * 3 + 2]);
                any[0] = true;
                for (int axis = 0; axis < 3; axis++) {
                    min[axis] = Math.min(min[axis], v[axis]);
                    max[axis] = Math.max(max[axis], v[axis]);
                }
            }
        }
        for (YsmGeometry.Bone child : bone.children) {
            measureWithPose(child, m, pose, ws, hs, min, max, any);
        }
    }

    /** 保险：离谱的缩放（>4 倍 / NaN）当 1 处理。 */
    private static float clampScale(float value) {
        if (!Float.isFinite(value) || Math.abs(value) > 4.0F) {
            return 1.0F;
        }
        return value;
    }

    /** 保险：离谱的位移（>32 像素 / NaN）当 0 处理。 */
    private static float clampOffset(float value) {
        if (!Float.isFinite(value) || Math.abs(value) > 32.0F) {
            return 0.0F;
        }
        return value;
    }

    /** 绕当前原点旋转（先 Z、再 Y、最后 X，和原版 ModelPart.rotationZYX 一致）再缩放。 */
    private static void applyRotationAndScale(PoseStack stack, float rx, float ry, float rz,
                                              float sx, float sy, float sz) {
        if (rz != 0) {
            stack.mulPose(Axis.ZP.rotationDegrees(rz));
        }
        if (ry != 0) {
            stack.mulPose(Axis.YP.rotationDegrees(ry));
        }
        if (rx != 0) {
            stack.mulPose(Axis.XP.rotationDegrees(rx));
        }
        if (sx != 1.0F || sy != 1.0F || sz != 1.0F) {
            stack.scale(sx, sy, sz);
        }
    }

    private static float round(float value) {
        return Math.round(value * 100.0F) / 100.0F;
    }

    private YsmModelRenderer() {
    }
}
