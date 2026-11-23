package com.worldplayers.client;

import com.worldplayers.compat.ysm.Molang;
import com.worldplayers.compat.ysm.YsmAnimations;
import com.worldplayers.resident.ResidentEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * YSM 逐帧姿态（第 ④ 步）：把模型自带的动画按实体的 query 求值成「骨骼 → 变换」。
 *
 * <p>层的顺序（后写的覆盖前面的同名通道）：</p>
 * <ol>
 *   <li>主状态动画（idle / walk / run / sneak）；</li>
 *   <li>{@code pre_parallel0..7}（头发 / 尾巴物理那种并行动画）；</li>
 *   <li>{@code parallel0..7}（表情、备用形态、特效件的显隐）。</li>
 * </ol>
 *
 * <p>求不出来的通道不写（渲染时退回几何里的静态值），所以就算模型写得很怪也不会散架。</p>
 */
public final class YsmPose {
    /** 一个骨骼在这一帧的变换（模型空间；旋转度、位移模型单位、缩放倍数）。 */
    public static final class BonePose {
        public float rx;
        public float ry;
        public float rz;
        public float px;
        public float py;
        public float pz;
        public float sx = 1.0F;
        public float sy = 1.0F;
        public float sz = 1.0F;
        public boolean visible = true;
        public boolean touched;
        /** 这几条通道真的被动画写过（否则渲染时补程序化动作）。 */
        public boolean hasRotation;
        public boolean hasPosition;
        public boolean hasScale;
    }

    private static final int PARALLEL_COUNT = 8;
    /** 走路 / 跑步的速度阈值（格/秒）。 */
    private static final double RUN_SPEED = 4.2D;
    /**
     * 动画切换的过渡时间（秒）—— YSM 控制器里是 blend_transition ≈ 0.2；
     * 主人 2026-11-21 明确要「约莫半秒、肉眼可观测地把最后一次摆手动腿摆回去」。
     *
     * <p><b>为什么从指数改成线性</b>：指数（tau=0.15）在 0.15 秒就走完 63%，肉眼看就是「啪」一下——
     * 主人两次都说「还是瞬移」。线性混合在 0.55 秒里匀速摆回去，才是他要的那个「缓慢过渡」。</p>
     */
    private static final float BLEND_SECONDS = 0.55F;
    /** 每个实体的上一帧姿态，用来做过渡（渲染线程单线程访问）。 */
    private static final java.util.Map<ResidentEntity, Smoothed> SMOOTH =
        new java.util.WeakHashMap<>();
    /** 主动画切换诊断用的「上一次主动画」+ 日志次数上限。 */
    private static final java.util.Map<ResidentEntity, String> LAST_MAIN = new java.util.WeakHashMap<>();
    private static int switchLogs;
    /** 「切换时哪根骨骼在跳」的诊断日志次数（只打前 8 次切换，避免刷屏）。 */
    private static int deltaLogs;

    /** 一个实体上一帧的姿态 + 时间戳。 */
    private static final class Smoothed {
        private final Map<String, BonePose> pose = new LinkedHashMap<>();
        private double time = Double.NaN;
        private String main;
    }

    public static Map<String, BonePose> compute(ResidentEntity entity, float partialTick, YsmAnimations animations) {
        Map<String, BonePose> target = raw(entity, partialTick, animations);
        String main = mainAnimationName(entity, partialTick, animations);
        logSwitch(entity, partialTick, animations, target);
        return smooth(entity, target, entity.tickCount + (double) partialTick, main);
    }

    /**
     * 切换那一帧「上一帧输出」与「新目标」的差，按大小排前 5 根骨骼打一行日志。
     * 主人再说「某根骨在跳」时，这行能直接告诉我们是哪根、差多少度，不用再猜。
     */
    private static void logTransition(String from, String to, Map<String, BonePose> oldPose,
                                      Map<String, BonePose> target) {
        record Delta(String bone, float value, boolean isNew) {
        }
        java.util.List<Delta> deltas = new java.util.ArrayList<>();
        for (Map.Entry<String, BonePose> entry : target.entrySet()) {
            BonePose now = entry.getValue();
            BonePose before = oldPose.get(entry.getKey());
            if (before == null) {
                deltas.add(new Delta(entry.getKey(), Math.max(Math.max(Math.abs(now.rx), Math.abs(now.ry)),
                    Math.abs(now.rz)), true));
                continue;
            }
            // 位移通道是「像素」，×16 换算成和角度同一量级再排序
            float d = 0.0F;
            d = Math.max(d, Math.abs(now.rx - before.rx));
            d = Math.max(d, Math.abs(now.ry - before.ry));
            d = Math.max(d, Math.abs(now.rz - before.rz));
            d = Math.max(d, Math.abs(now.px - before.px) * 16.0F);
            d = Math.max(d, Math.abs(now.py - before.py) * 16.0F);
            d = Math.max(d, Math.abs(now.pz - before.pz) * 16.0F);
            if (d > 1.0F) {
                deltas.add(new Delta(entry.getKey(), d, false));
            }
        }
        deltas.sort((a, b) -> Float.compare(b.value(), a.value()));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(5, deltas.size()); i++) {
            Delta delta = deltas.get(i);
            if (i > 0) {
                text.append("，");
            }
            text.append(delta.bone()).append(delta.isNew() ? "(新骨骼)" : "Δ" + Math.round(delta.value()));
        }
        com.worldplayers.WorldPlayers.LOGGER.info("World Players: YSM 过渡 {} → {} 跳变最大：{}",
            from, to, text.length() == 0 ? "无明显跳变" : text.toString());
    }

    /**
     * 主动画切换的诊断日志：只打前 12 次，够定位「走→站到底有没有切换 / 切到了什么」。
     * 主人复测「过渡还是瞬移」时，这一行能省掉一轮猜测。
     */
    private static void logSwitch(ResidentEntity entity, float partialTick, YsmAnimations animations,
                                  Map<String, BonePose> target) {
        String main = mainAnimationName(entity, partialTick, animations);
        String key = main == null ? "(静态)" : main;
        String previous = LAST_MAIN.put(entity, key);
        if (previous == null || previous.equals(key) || switchLogs > 12) {
            return;
        }
        switchLogs++;
        com.worldplayers.WorldPlayers.LOGGER.info(
            "World Players: YSM 主动画切换 {} → {}（模型 {}，骨骼 {}）", previous, key, entity.ysmModel(), target.size());
    }

    /** 立刻算出这一帧的目标姿态（不做过渡）。 */
    private static Map<String, BonePose> raw(ResidentEntity entity, float partialTick, YsmAnimations animations) {
        Map<String, BonePose> pose = new LinkedHashMap<>();
        if (!com.worldplayers.config.WPClientConfig.useYsmAnimations()) {
            // 不走逐帧动画：YSM 的并行动画需要控制器状态机，硬播会把头发/尾巴拧碎。
            return pose;
        }
        if (animations == null || animations.isEmpty()) {
            return pose;
        }
        Molang.Env env = environment(entity, partialTick);
        String main = mainAnimationName(entity, partialTick, animations);
        if (main != null) {
            YsmAnimations.Animation animation = animations.get(main);
            apply(animation, timeFor(main, animation, entity, partialTick), env, pose);
        }
        // 控制器层（alpha.41）：酒狐的 idle1~4 彩蛋待机由 player.post_main 决定；没有控制器文件的模型跳过
        com.worldplayers.compat.ysm.YsmControllers.Controller controller =
            com.worldplayers.compat.ysm.YsmControllers.of(entity.ysmModel()).orElse(null);
        if (controller != null) {
            double clock = (entity.tickCount + partialTick) / 20.0D;
            for (com.worldplayers.compat.ysm.YsmControllers.Playing playing
                : com.worldplayers.compat.ysm.YsmControllers.update(entity, controller, env, animations,
                    clock, "idle".equals(main), false)) {
                YsmAnimations.Animation controlled = animations.get(playing.animation());
                if (controlled != null) {
                    apply(controlled, playing.time(), env, pose);
                }
            }
        }
        for (int i = 0; i < PARALLEL_COUNT; i++) {
            YsmAnimations.Animation animation = animations.get("pre_parallel" + i);
            if (animation != null) {
                apply(animation, animationTime(animation, entity, partialTick), env, pose);
            }
        }
        for (int i = 0; i < PARALLEL_COUNT; i++) {
            YsmAnimations.Animation animation = animations.get("parallel" + i);
            if (animation != null) {
                apply(animation, animationTime(animation, entity, partialTick), env, pose);
            }
        }
        return pose;
    }

    /**
     * 和上一帧的姿态做时间过渡（YSM 的 {@code blend_transition}）。
     *
     * <p>没有这一步，从 idle 切到 walk 会「啪」地一下跳过去（主人反馈的「没有转场，十分生硬」）。</p>
     *
     * <p><b>三条规则</b>（alpha.36 → alpha.39 逐步修出来的，别再退回去）：</p>
     * <ol>
     *   <li>两边都有的骨骼 → 线性混合，0.55 秒；</li>
     *   <li>目标里消失的骨骼（walk 有、idle 没有）→ 朝 bind 淡出，别一帧删（alpha.36）；</li>
     *   <li>这一帧才出现的骨骼（idle 有、walk 没有）→ 同样要淡入（alpha.39）。
     *       以前直接取目标值，模型只要在 idle 里多写一根 walk 没写的腰/头，停下就瞬移。</li>
     * </ol>
     */
    private static Map<String, BonePose> smooth(ResidentEntity entity, Map<String, BonePose> target, double now,
                                                @javax.annotation.Nullable String main) {
        Smoothed smoothed = SMOOTH.get(entity);
        if (smoothed == null) {
            smoothed = new Smoothed();
            SMOOTH.put(entity, smoothed);
        }
        String previousMain = smoothed.main;
        smoothed.main = main == null ? "(静态)" : main;
        float alpha = 1.0F;
        if (Double.isFinite(smoothed.time)) {
            double dt = now - smoothed.time;
            if (dt < 0.0D) {
                dt = 0.0D;
            } else if (dt > 1.0D) {
                dt = 0.05D;
            }
            // 线性收敛：0.55 秒匀速摆回去（指数太快，看起来就是瞬移）
            alpha = (float) Math.min(1.0D, dt / BLEND_SECONDS);
        }
        smoothed.time = now;
        if (previousMain != null && !previousMain.equals(smoothed.main)
            && deltaLogs < 8 && !smoothed.pose.isEmpty()) {
            deltaLogs++;
            logTransition(previousMain, smoothed.main, smoothed.pose, target);
        }
        if (smoothed.pose.isEmpty()) {
            copyInto(target, smoothed.pose);
            return target;
        }
        if (target.isEmpty()) {
            // 动画关掉/换模型：整体淡出
            boolean faded = blendOut(smoothed.pose, alpha);
            return faded ? emptyPose() : copyOf(smoothed.pose);
        }
        java.util.Set<String> keys = new java.util.LinkedHashSet<>(smoothed.pose.keySet());
        keys.addAll(target.keySet());
        Map<String, BonePose> out = new LinkedHashMap<>();
        java.util.List<String> remove = new java.util.ArrayList<>();
        for (String key : keys) {
            BonePose from = smoothed.pose.get(key);
            BonePose to = target.get(key);
            if (to == null) {
                // 这一帧的动画没写这块骨骼 → 朝 bind 淡出，别一帧删掉
                BonePose fading = fadeTowardsBind(from, alpha);
                if (fading == null) {
                    remove.add(key);
                } else {
                    out.put(key, fading);
                }
                continue;
            }
            if (from == null) {
                // ★ alpha.39：这一帧才出现的骨骼，从 bind（增量 0、缩放 1）**淡入**，不再直接取目标值。
                // 以前 copyOf(to)：idle 里有、walk 里没有的骨骼（酒狐的 UpperBody、很多自定义模型的 Head/腰）
                // 在切到 idle 的那一帧整块跳过去 —— 主人看到的「停下时脑袋瞬移回待机轨」就是它。
                // pose 存的是相对 bind 的增量，从 0 起步只是「慢慢加上去」，不会把 bind 旋转抹掉。
                BonePose bind = new BonePose();
                bind.sx = 1.0F;
                bind.sy = 1.0F;
                bind.sz = 1.0F;
                bind.visible = to.visible;
                out.put(key, blendOf(bind, to, alpha));
                continue;
            }
            out.put(key, blendOf(from, to, alpha));
        }
        for (String key : remove) {
            smoothed.pose.remove(key);
        }
        copyInto(out, smoothed.pose);
        return out;
    }

    /** 两个骨骼姿态按 alpha 线性混合（rotation / position / scale 都混）。 */
    private static BonePose blendOf(BonePose from, BonePose to, float alpha) {
        BonePose blend = new BonePose();
        blend.rx = lerp(from.rx, to.rx, alpha);
        blend.ry = lerp(from.ry, to.ry, alpha);
        blend.rz = lerp(from.rz, to.rz, alpha);
        blend.px = lerp(from.px, to.px, alpha);
        blend.py = lerp(from.py, to.py, alpha);
        blend.pz = lerp(from.pz, to.pz, alpha);
        blend.sx = lerp(from.sx, to.sx, alpha);
        blend.sy = lerp(from.sy, to.sy, alpha);
        blend.sz = lerp(from.sz, to.sz, alpha);
        blend.visible = to.visible;
        blend.touched = true;
        blend.hasRotation = to.hasRotation || from.hasRotation;
        blend.hasPosition = to.hasPosition || from.hasPosition;
        blend.hasScale = to.hasScale || from.hasScale;
        return blend;
    }

    /** 把一帧姿态朝 bind（旋转/位移 0、缩放 1）推进；已经贴到 bind 就返回 null（可以删了）。 */
    private static BonePose fadeTowardsBind(BonePose from, float alpha) {
        float keep = 1.0F - alpha;
        BonePose fade = copyOf(from);
        fade.rx *= keep;
        fade.ry *= keep;
        fade.rz *= keep;
        fade.px *= keep;
        fade.py *= keep;
        fade.pz *= keep;
        fade.sx = 1.0F + (fade.sx - 1.0F) * keep;
        fade.sy = 1.0F + (fade.sy - 1.0F) * keep;
        fade.sz = 1.0F + (fade.sz - 1.0F) * keep;
        fade.visible = true;
        fade.touched = true;
        boolean settled = Math.abs(fade.rx) < 0.05F && Math.abs(fade.ry) < 0.05F
            && Math.abs(fade.rz) < 0.05F && Math.abs(fade.px) < 0.01F && Math.abs(fade.py) < 0.01F
            && Math.abs(fade.pz) < 0.01F
            && Math.abs(fade.sx - 1.0F) < 0.01F && Math.abs(fade.sy - 1.0F) < 0.01F
            && Math.abs(fade.sz - 1.0F) < 0.01F;
        return settled ? null : fade;
    }

    /** 把整张表向零值淡出；返回是否已经完全淡没。 */
    private static boolean blendOut(Map<String, BonePose> pose, float alpha) {
        boolean all = true;
        for (BonePose p : pose.values()) {
            p.rx *= 1.0F - alpha;
            p.ry *= 1.0F - alpha;
            p.rz *= 1.0F - alpha;
            p.px *= 1.0F - alpha;
            p.py *= 1.0F - alpha;
            p.pz *= 1.0F - alpha;
            p.sx = 1.0F + (p.sx - 1.0F) * (1.0F - alpha);
            p.sy = 1.0F + (p.sy - 1.0F) * (1.0F - alpha);
            p.sz = 1.0F + (p.sz - 1.0F) * (1.0F - alpha);
            if (Math.abs(p.rx) > 0.01F || Math.abs(p.px) > 0.01F || Math.abs(p.sx - 1.0F) > 0.01F) {
                all = false;
            }
        }
        return all;
    }

    private static Map<String, BonePose> emptyPose() {
        return new LinkedHashMap<>();
    }

    private static Map<String, BonePose> copyOf(Map<String, BonePose> source) {
        Map<String, BonePose> out = new LinkedHashMap<>();
        copyInto(source, out);
        return out;
    }

    private static void copyInto(Map<String, BonePose> source, Map<String, BonePose> target) {
        target.clear();
        for (Map.Entry<String, BonePose> entry : source.entrySet()) {
            target.put(entry.getKey(), copyOf(entry.getValue()));
        }
    }

    private static BonePose copyOf(BonePose p) {
        BonePose out = new BonePose();
        out.rx = p.rx;
        out.ry = p.ry;
        out.rz = p.rz;
        out.px = p.px;
        out.py = p.py;
        out.pz = p.pz;
        out.sx = p.sx;
        out.sy = p.sy;
        out.sz = p.sz;
        out.visible = p.visible;
        out.touched = p.touched;
        out.hasRotation = p.hasRotation;
        out.hasPosition = p.hasPosition;
        out.hasScale = p.hasScale;
        return out;
    }

    private static float lerp(float from, float to, float alpha) {
        return from + (to - from) * alpha;
    }

    /** 攻击动画由原版挥手进度驱动（0→1），不然每次挥手的起始相位都是随机的。 */    private static double timeFor(String name, YsmAnimations.Animation animation, ResidentEntity entity,
                                  float partialTick) {
        if ("swing_hand".equals(name) || "attack".equals(name) || "attack_left".equals(name)
            || "swing".equals(name)) {
            double length = animation != null && animation.length > 0.0D ? animation.length : 0.25D;
            return entity.getAttackAnim(partialTick) * length;
        }
        return animationTime(animation, entity, partialTick);
    }

    /** 按实体状态挑主状态动画（没有对应动画返回 null，交给几何静态姿态）。 */
    @javax.annotation.Nullable
    private static String mainAnimationName(ResidentEntity entity, float partialTick, YsmAnimations animations) {
        // ① 挥手臂 / 攻击：原版 swingTime 一帧帧走，模型用 swing_hand 这套
        if (entity.getAttackAnim(partialTick) > 0.02F) {
            String attack = first(animations, "swing_hand", "attack", "attack_left", "swing");
            if (attack != null) {
                return attack;
            }
        }
        // ② 工作（农作 / 道路 / 合成）：手上有活的时候播挥手动画，看起来才像在干活
        String activity = entity.activityKey();
        if (com.worldplayers.resident.ResidentJobs.FARMING.equals(activity)
            || com.worldplayers.resident.ResidentJobs.ROADWORK.equals(activity)
            || com.worldplayers.resident.ResidentJobs.CRAFTING.equals(activity)) {
            String work = first(animations, "use_mainhand", "swing_hand", "work", "farming");
            if (work != null) {
                return work;
            }
        }
        double speed = horizontalSpeed(entity);
        if (entity.isCrouching() && animations.get("sneak") != null) {
            return "sneak";
        }
        if (speed > RUN_SPEED && animations.get("run") != null) {
            return "run";
        }
        if (speed > 0.25D && animations.get("walk") != null) {
            return "walk";
        }
        if (animations.get("idle") != null) {
            return "idle";
        }
        return animations.get("walk") != null && speed > 0.25D ? "walk" : null;
    }

    /** 按顺序找第一个存在的动画名。 */
    @javax.annotation.Nullable
    private static String first(YsmAnimations animations, String... names) {
        for (String name : names) {
            if (animations.get(name) != null) {
                return name;
            }
        }
        return null;
    }

    private static void apply(@javax.annotation.Nullable YsmAnimations.Animation animation, double time,
                              Molang.Env env, Map<String, BonePose> pose) {
        if (animation == null || animation.bones.isEmpty()) {
            return;
        }
        // loop 动画的周期：末帧 → 首帧要自动闭合，否则周期一到就跳一下（「呼吸结尾震颤」）
        double cycle = animation.loop && animation.length > 0.0D ? animation.length : 0.0D;
        for (Map.Entry<String, Map<String, YsmAnimations.Channel>> boneEntry : animation.bones.entrySet()) {
            BonePose bone = pose.computeIfAbsent(boneEntry.getKey(), key -> new BonePose());
            bone.touched = true;
            Map<String, YsmAnimations.Channel> channels = boneEntry.getValue();
            YsmAnimations.Channel rotation = channels.get(YsmAnimations.ROTATION);
            if (rotation != null) {
                float[] out = new float[3];
                if (rotation.sample(time, env, out, cycle)) {
                    bone.rx = out[0];
                    bone.ry = out[1];
                    bone.rz = out[2];
                    bone.hasRotation = true;
                }
            }
            YsmAnimations.Channel position = channels.get(YsmAnimations.POSITION);
            if (position != null) {
                float[] out = new float[3];
                if (position.sample(time, env, out, cycle)) {
                    bone.px = out[0];
                    bone.py = out[1];
                    bone.pz = out[2];
                    bone.hasPosition = true;
                }
            }
            YsmAnimations.Channel scale = channels.get(YsmAnimations.SCALE);
            if (scale != null) {
                float[] out = new float[3];
                if (scale.sample(time, env, out, cycle)) {
                    bone.sx = out[0];
                    bone.sy = out[1];
                    bone.sz = out[2];
                    bone.hasScale = true;
                    if (Math.abs(out[0]) < 0.01F && Math.abs(out[1]) < 0.01F && Math.abs(out[2]) < 0.01F) {
                        bone.visible = false;
                    }
                }
            }
            YsmAnimations.Channel visible = channels.get(YsmAnimations.VISIBLE);
            if (visible != null) {
                float[] out = new float[3];
                if (visible.sample(time, env, out, cycle)) {
                    bone.visible = out[0] != 0.0F;
                }
            }
        }
    }

    /** 动画时间：有长度的按长度取模（循环动画），没写长度的直接用世界时间。 */
    private static double animationTime(YsmAnimations.Animation animation, ResidentEntity entity, float partialTick) {
        double clock = (entity.tickCount + partialTick) / 20.0D;
        return animation.length > 0.0D ? clock % animation.length : clock;
    }

    /** 每帧的 query 环境。 */
    public static Molang.Env environment(LivingEntity entity, float partialTick) {
        Molang.Env env = new Molang.Env();
        double horizontal = horizontalSpeed(entity);
        double vertical = entity.getDeltaMovement().y * 20.0D;
        double clock = (entity.tickCount + partialTick) / 20.0D;
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float headYaw = Mth.wrapDegrees(Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot) - bodyYaw);
        float headPitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
        put(env, "ground_speed", horizontal);
        put(env, "vertical_speed", vertical);
        put(env, "is_moving", horizontal > 0.25D ? 1.0D : 0.0D);
        put(env, "is_on_ground", entity.onGround() ? 1.0D : 0.0D);
        put(env, "is_sneaking", entity.isShiftKeyDown() ? 1.0D : 0.0D);
        put(env, "is_swimming", entity.isSwimming() ? 1.0D : 0.0D);
        put(env, "life_time", clock);
        put(env, "anim_time", clock);
        put(env, "delta_time", 1.0D / 20.0D);
        put(env, "time_of_day", entity.level().getDayTime() % 24000L / 24000.0D);
        put(env, "head_yaw", headYaw);
        put(env, "head_pitch", headPitch);
        put(env, "body_yaw", Mth.wrapDegrees(bodyYaw));
        put(env, "yaw_speed", 0.0D);
        put(env, "modified_distance_moved", horizontal * clock);
        put(env, "health", entity.getHealth());
        put(env, "max_health", entity.getMaxHealth());
        // YSM 的 ysm.food_level 是 MC 的饱食度（0~20）。居民的饥饿是 0~100，除以 5 换算。
        // 这条不填的话，模型里 `ysm.food_level<=6?25:0` 会被当成 0 → 一直播「饿到捂肚子垂头」的姿势。
        put(env, "food_level", entity instanceof ResidentEntity resident
            ? Math.max(0.0D, resident.hunger()) / 5.0D : 20.0D);
        put(env, "is_hurt", entity.hurtTime > 0 ? 1.0D : 0.0D);
        put(env, "attack_time", entity.getAttackAnim(partialTick));
        // 手持物品：这两条直接影响模型的手部形态（大酒狐 parallel4 用它们切「雨伞手臂」）
        put(env, "has_mainhand", entity.getMainHandItem().isEmpty() ? 0.0D : 1.0D);
        put(env, "has_offhand", entity.getOffhandItem().isEmpty() ? 0.0D : 1.0D);
        // 其它 YSM 查询一律 0（居民不做第一人称/睡觉/骑乘这些）
        for (String key : new String[]{"has_helmet", "has_chestplate", "has_leggings", "has_boots",
            "is_first_person", "is_sleep", "is_riding", "is_crawling"}) {
            put(env, key, 0.0D);
        }
        return env;
    }

    /** 同时写 {@code q.xxx} 与 {@code ysm.xxx}（YSM 自己的函数名空间）。 */
    private static void put(Molang.Env env, String name, double value) {
        env.queries.put("q." + name, value);
        env.queries.put("query." + name, value);
        env.queries.put("ysm." + name, value);
    }

    private static double horizontalSpeed(LivingEntity entity) {
        return entity.getDeltaMovement().horizontalDistance() * 20.0D;
    }

    private YsmPose() {
    }
}
