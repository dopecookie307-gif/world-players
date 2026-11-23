package com.worldplayers.client;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

/**
 * YSM 模型的**基础动作**（第 ② 步，不碰 Molang）。
 *
 * <p>主人要的三件事：走路摆腿摆臂、头跟着玩家（看哪转哪）、待机轻微呼吸。
 * 做法和原版 {@code HumanoidModel} 完全一样，只是骨骼换成 bedrock 名字：</p>
 * <ul>
 *   <li>腿 / 臂：<code>cos(limbSwing × 0.6662) × 幅度 × limbSwingAmount</code>，左右反相；</li>
 *   <li>头：<code>netHeadYaw</code>（头相对身体）绕 Y、<code>headPitch</code>（俯仰）绕 X；</li>
 *   <li>呼吸：<code>UpBody / UpperBody</code> 的轻微前后起伏 + 手臂的 <code>zRot</code> 摇摆。</li>
 * </ul>
 *
 * <p>角度单位都是**度**，加在骨骼自己的静态旋转上面（{@link YsmGeometry} 已经统一到 MC 模型空间，
 * 所以旋转顺序 Z→Y→X 和符号约定跟 {@code ModelPart} 一致，不需要再翻）。</p>
 */
public final class YsmAnim {
    private static final float RAD2DEG = (float) (180.0D / Math.PI);
    /** 原版 HumanoidModel 的走路相位系数。 */
    private static final float WALK_PHASE = 0.6662F;
    /** 原版：头最多左右转 75°。 */
    private static final float HEAD_YAW_LIMIT = 75.0F;
    /** 俯仰限制（普通生物的 HeadLookControl 也不会超过这个量级）。 */
    private static final float HEAD_PITCH_LIMIT = 60.0F;

    /** 走路周期相位（{@code walkAnimation.position}）。 */
    public float walkSwing;
    /** 走路强度 0~1（{@code walkAnimation.speed} 截断）。 */
    public float walkAmount;
    /** 头相对身体左右（度）。 */
    public float headYaw;
    /** 俯仰（度，正值 = 低头）。 */
    public float headPitch;
    /** 年龄 tick（呼吸摇摆用）。 */
    public float age;
    /** 程序化动作总开关（配置里可关，用来二分定位「碎」的来源）。 */
    public boolean enabled = com.worldplayers.config.WPClientConfig.useYsmProgrammaticAnim();

    /** 停下时摆幅要「慢慢收回」，不能一帧归零（主人 2026-11-21：走→站是瞬移）。线性，约 0.55 秒。 */
    private static final double WALK_SETTLE_SECONDS = 0.55D;
    /**
     * 程序化动作从 0 淡入 / 淡出的时间。
     *
     * <p><b>为什么要这个（alpha.39）</b>：渲染器一直是「动画写了非零 rotation 就完全用动画、否则叠程序化」。
     * walk 停下的 0.55 秒里手臂的动画增量被线性收回 0，收到 0 的那一刻程序化（外张 5° + 摇摆 5.7°）
     * 会**一整帧跳进来** —— 主人看到的「过渡动画的窄手臂 → 瞬移 → 待机的宽手臂」就是这 10° 的阶跃。
     * 现在给每根骨骼一个 0~1 的权重：动画撒手后用同样 0.55 秒把程序化角度涨上去；
     * 动画重新接管时用 0.15 秒收掉（要快，否则摆臂会被程序化拖住）。</p>
     */
    private static final double PROC_RISE_SECONDS = 0.55D;
    private static final double PROC_FALL_SECONDS = 0.15D;
    /** 每个实体上一次的走路强度 + 时间戳（只渲染线程用）。 */
    private static final java.util.Map<LivingEntity, WalkBlend> WALK_BLEND = new java.util.WeakHashMap<>();
    /** 每个实体每根骨骼的「程序化权重」+ 时间戳。 */
    private static final java.util.Map<LivingEntity, java.util.Map<String, ProcWeight>> PROC_WEIGHT =
        new java.util.WeakHashMap<>();

    private static final class WalkBlend {
        private float amount = -1.0F;
        private double time = Double.NaN;
    }

    private static final class ProcWeight {
        private float value = 1.0F;
        private double time = Double.NaN;
    }

    /** 产生这份参数的生物（程序化权重 / 走路强度都要按实体记状态）。 */
    private LivingEntity entity;

    /** 从一个生物身上取动作参数（客户端渲染每帧调一次）。 */
    public static YsmAnim of(LivingEntity entity, float partialTick) {
        YsmAnim anim = new YsmAnim();
        anim.entity = entity;
        anim.walkSwing = entity.walkAnimation.position(partialTick);
        float target = Mth.clamp(entity.walkAnimation.speed(partialTick), 0.0F, 1.0F);
        anim.age = entity.tickCount + partialTick;
        anim.walkAmount = settleWalk(entity, target, anim.age);
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        float headYaw = Mth.rotLerp(partialTick, entity.yHeadRotO, entity.yHeadRot) - bodyYaw;
        anim.headYaw = Mth.clamp(Mth.wrapDegrees(headYaw), -HEAD_YAW_LIMIT, HEAD_YAW_LIMIT);
        anim.headPitch = Mth.clamp(Mth.lerp(partialTick, entity.xRotO, entity.getXRot()),
            -HEAD_PITCH_LIMIT, HEAD_PITCH_LIMIT);
        return anim;
    }

    /**
     * 走路强度平滑：起步立刻满幅，停下用约 0.45 秒收回来。
     *
     * <p>原版 {@code walkAnimation.speed} 在不走路的那一 tick 直接变 0，于是腿/手「啪」一下弹回站姿；
     * 参考动画（YSM 自己的 walk→idle）用 blend_transition 把最后一次摆手动腿慢慢摆回去。
     * 这里把振幅做成随时间指数衰减、相位照常按 cos 走，肢体就会沿原方向缓慢收回 —— 就是那个「半秒过渡」。</p>
     */
    private static float settleWalk(LivingEntity entity, float target, double now) {
        WalkBlend state = WALK_BLEND.computeIfAbsent(entity, key -> new WalkBlend());
        double dt = Double.isFinite(state.time) ? now - state.time : 0.0D;
        if (dt < 0.0D || dt > 1.0D) {
            dt = dt < 0.0D ? 0.0D : 1.0D;
        }
        state.time = now;
        if (state.amount < 0.0F) {
            state.amount = target;
            return target;
        }
        // 同一帧被多次调用（伤害染色层之类）时不要重复衰减
        if (dt <= 1.0E-4D || target >= state.amount) {
            state.amount = Math.max(state.amount, target);
            return state.amount;
        }
        double alpha = Math.min(1.0D, dt / WALK_SETTLE_SECONDS);
        float settled = (float) (state.amount + (target - state.amount) * alpha);
        if (Math.abs(settled - target) < 0.01F) {
            settled = target;
        }
        state.amount = settled;
        return settled;
    }

    /**
     * 这根骨骼「程序化动作」的权重（0~1）。
     *
     * <p>{@code animated=true} 表示这一帧是模型动画在管它（渲染器会把程序化让出去）：
     * 权重用 0.15 秒收掉；动画撒手后（{@code false}）用 0.55 秒涨回 1。
     * 于是「动画最后一帧的角度」和「程序化的角度」之间是连续的，不会阶跃。
     * 新实体初始权重就是 1，出生第一帧不会看到手臂从 0 展开。</p>
     */
    public float weight(String bone, boolean animated) {
        if (!this.enabled || this.entity == null) {
            return 0.0F;
        }
        java.util.Map<String, ProcWeight> weights =
            PROC_WEIGHT.computeIfAbsent(this.entity, key -> new java.util.HashMap<>());
        ProcWeight state = weights.computeIfAbsent(bone, key -> new ProcWeight());
        float target = animated ? 0.0F : 1.0F;
        if (!Double.isFinite(state.time)) {
            // 第一次见到这根骨：直接按当前状态起步（由动画管就是 0，不会在首帧多叠 10°）
            state.value = target;
            state.time = this.age;
            return state.value;
        }
        double dt = Math.min(1.0D, this.age - state.time);
        if (dt < 0.0D) {
            dt = 0.0D;
        }
        state.time = this.age;
        if (Math.abs(state.value - target) < 1.0E-4F) {
            state.value = target;
            return state.value;
        }
        double seconds = animated ? PROC_FALL_SECONDS : PROC_RISE_SECONDS;
        float alpha = (float) Math.min(1.0D, dt / seconds);
        if (alpha <= 0.0F) {
            return state.value;
        }
        state.value = state.value + (target - state.value) * alpha;
        if (Math.abs(state.value - target) < 0.01F) {
            state.value = target;
        }
        return state.value;
    }

    /** 这个骨骼额外绕 X 转多少度（俯仰 / 前后摆动），按权重缩放。 */
    public float extraX(String bone, float weight) {
        if (!this.enabled || weight <= 0.0F) {
            return 0.0F;
        }
        // YsmGeometry 的空间是原版模型空间绕 Z 转 180° 得到的，所以绕 X / Y 的旋转都要取反
        float angle = -switch (bone) {
            case "Head" -> this.headPitch;
            case "RightArm" -> this.armSwing(true) + this.armBreath(true);
            case "LeftArm" -> this.armSwing(false) + this.armBreath(false);
            case "RightLeg" -> this.legSwing(true);
            case "LeftLeg" -> this.legSwing(false);
            case "UpBody", "UpperBody" -> this.breath();
            default -> this.softSwing(bone);
        };
        return weight * angle;
    }

    /** 头发 / 尾巴的轻微摆动（作者的原动画里没有它们的通道，只能程序化补）。 */
    private float softSwing(String bone) {
        String lower = bone.toLowerCase(java.util.Locale.ROOT);
        if (isTailRoot(lower)) {
            // 周期约 1.2 秒：之前 0.09 的相位太慢（3.5 秒一轮），看起来像「不摇」
            return Mth.sin(this.age * 0.22F) * 6.0F * (1.0F + 1.2F * this.walkAmount);
        }
        // ★ 头发**一个字都不要转**（主人 2026-11-21 第二次反馈「头发跟脑袋分离摇晃」）。
        // 原因：这些模型里叫 `Hair` 的那根骨 pivot 在 y≈14.7（胸口），真正的发根是它下面的
        // `BaseHair`（y≈36）。围着胸口转 2° 时，整片头发是「绕胸画弧」——看起来就是从脑袋上飘出去了。
        // 之前给它同相呼吸也没用，因为问题不在相位而在**转了不该转的骨**。
        // 正确做法：头发纯跟随（靠父子链跟头一起动），程序化不碰它。
        return 0.0F;
    }

    /**
     * 尾巴根骨骼。放宽到「名字里带 tail、不以数字结尾」——
     * 自定义模型里尾巴常叫 `cute_tail` / `fox_tail` / `Tail_01`（后者带数字算子段）。
     * 排除 `Ponytail`（那是头发）和 `Tail2`..（子段，不能每段都转，会抽起来）。
     */
    private static boolean isTailRoot(String lower) {
        if (!lower.contains("tail") || lower.contains("pony")) {
            return false;
        }
        char last = lower.charAt(lower.length() - 1);
        return !Character.isDigit(last);
    }

    /** 额外绕 Y 转多少度（左右转，只有头用）。 */
    public float extraY(String bone, float weight) {
        if (!this.enabled || weight <= 0.0F) {
            return 0.0F;
        }
        return weight * ("Head".equals(bone) ? -this.headYaw : 0.0F);
    }

    /** 额外绕 Z 转多少度（手臂外张；模型的袖子/身体很宽，摆臂时不张开就会插进身体）。 */
    public float extraZ(String bone, float weight) {
        if (!this.enabled || weight <= 0.0F) {
            return 0.0F;
        }
        return weight * switch (bone) {
            case "RightArm" -> this.armSway() + this.armSpread();
            case "LeftArm" -> -(this.armSway() + this.armSpread());
            default -> 0.0F;
        };
    }

    /**
     * 走路时手臂的额外外张（度）。
     *
     * <p>YSM 作者的 walk 动画都会让手先离开身体再前后摆；我们做程序化摆臂时如果只绕 X 转，
     * 宽袖模型的手会直接插进躯干（主人反馈的「手臂在身体内摇摆」）。</p>
     */
    private float armSpread() {
        return 5.0F + 9.0F * this.walkAmount;
    }

    /** 原版：腿 1.4 rad、臂 1.0 rad，左右相差半个周期。YSM 的骨骼 pivot 比原版高，幅度收窄到 0.7 / 0.5。 */
    private float legSwing(boolean right) {
        float phase = this.walkSwing * WALK_PHASE + (right ? 0.0F : Mth.PI);
        return Mth.cos(phase) * 0.7F * this.walkAmount * RAD2DEG;
    }

    private float armSwing(boolean right) {
        float phase = this.walkSwing * WALK_PHASE + (right ? Mth.PI : 0.0F);
        return Mth.cos(phase) * 0.5F * this.walkAmount * RAD2DEG;
    }

    /** 原版手臂的“呼吸”俯仰：sin(age × 0.067) × 0.05 rad，左右反向。 */
    private float armBreath(boolean right) {
        float value = Mth.sin(this.age * 0.067F) * 0.05F * RAD2DEG;
        return right ? value : -value;
    }

    /** 原版手臂的外摆：cos(age × 0.09) × 0.05 + 0.05（弧度）。走路时减弱，免得摆臂和转身打架。 */
    private float armSway() {
        float breathing = Mth.cos(this.age * 0.09F) * 0.05F + 0.05F;
        return breathing * RAD2DEG * (1.0F - 0.6F * this.walkAmount);
    }

    /** 待机呼吸：上身轻微前后起伏（放大了一点，之前 0.8° 几乎看不出「在呼吸」）。 */
    private float breath() {
        return Mth.sin(this.age * 0.11F) * 1.6F * (1.0F - 0.5F * this.walkAmount);
    }

    private YsmAnim() {
    }
}
