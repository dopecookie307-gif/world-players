package com.worldplayers.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentEntity;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.neoforged.fml.ModList;

/**
 * 居民渲染器：四种模型 Wide / Slim / Kid-Wide / Kid-Slim。
 *
 * <p>受伤闪红直接用**原版**那一套：{@code getRenderType} 走 {@code entityCutoutNoCull}，
 * overlay 里的红色遮罩就会生效（见下方 {@code getRenderType} 注释的踩坑）。
 * 这里只保留 jeb_ 彩虹需要的顶点色包装。</p>
 */
public class ResidentRenderer extends LivingEntityRenderer<ResidentEntity, PlayerModel<ResidentEntity>> {
    private static final float KID_SCALE = 0.62F;
    private static final float KID_HEAD = 1.8F;

    private final PlayerModel<ResidentEntity> wideModel;
    private final PlayerModel<ResidentEntity> slimModel;
    private final PlayerModel<ResidentEntity> kidWideModel;
    private final PlayerModel<ResidentEntity> kidSlimModel;

    public ResidentRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
        this.wideModel = this.model;
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.kidWideModel = kid(new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false));
        this.kidSlimModel = kid(new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true));

        this.addLayer(new HumanoidArmorLayer<>(this,
            new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            context.getModelManager()));
        this.addLayer(new ItemInHandLayer<>(this, context.getItemInHandRenderer()));
        this.add3dSkinLayers();
    }

    /** 大头：放大 head/hat 的 scale。 */
    private static PlayerModel<ResidentEntity> kid(PlayerModel<ResidentEntity> model) {
        for (ModelPart part : new ModelPart[]{model.head, model.hat}) {
            part.xScale = KID_HEAD;
            part.yScale = KID_HEAD;
            part.zScale = KID_HEAD;
        }
        return model;
    }

    /** 3D Skin Layers 是可选软依赖：先确认 mod 存在，再反射加载，缺了就完全跳过。 */
    @SuppressWarnings("unchecked")
    private void add3dSkinLayers() {
        if (!ModList.get().isLoaded("skinlayers3d")) {
            return;
        }
        try {
            Object layer = Class.forName("com.worldplayers.client.SkinLayers3DLayer")
                .getConstructor(RenderLayerParent.class).newInstance(this);
            this.addLayer((RenderLayer<ResidentEntity, PlayerModel<ResidentEntity>>) layer);
            WorldPlayers.LOGGER.info("World Players: 3D Skin Layers 兼容层已启用");
        } catch (Throwable missing) {
            WorldPlayers.LOGGER.warn("World Players: 3D Skin Layers 存在但兼容层加载失败，使用平面皮肤", missing);
        }
    }

    @Override
    public void render(ResidentEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        int type = entity.modelType();
        boolean kid = type >= ResidentEntity.MODEL_KID_WIDE;
        boolean slim = type == ResidentEntity.MODEL_SLIM || type == ResidentEntity.MODEL_KID_SLIM;
        this.model = kid
            ? (slim ? this.kidSlimModel : this.kidWideModel)
            : (slim ? this.slimModel : this.wideModel);
        this.shadowRadius = kid ? 0.25F : 0.5F;

        boolean jeb = isJeb(entity);
        MultiBufferSource actualBuffer = buffer;
        if (jeb) {
            // 彩蛋：jeb_ 居民照原版彩虹羊的方式给整只模型染色（顶点色，世界和 GUI 都生效）
            float[] rgb = rainbowColor(entity, partialTick);
            actualBuffer = new TintedBuffer(buffer, rgb[0], rgb[1], rgb[2]);
        }
        if (kid) {
            poseStack.pushPose();
            poseStack.scale(KID_SCALE, KID_SCALE, KID_SCALE);
            applyHeldItemPose(entity);
            super.render(entity, entityYaw, partialTick, poseStack, actualBuffer, packedLight);
            poseStack.popPose();
        } else {
            applyHeldItemPose(entity);
            super.render(entity, entityYaw, partialTick, poseStack, actualBuffer, packedLight);
        }
    }

    /**
     * 弓 / 弩的手臂姿势。
     *
     * <p>原版玩家是 {@code PlayerRenderer} 自己算 {@code ArmPose}，生物默认永远是 EMPTY，
     * 所以居民拉弓、上弦看起来就像干站着。这里在 {@code setupAnim} 之前把姿势填好，
     * {@code HumanoidModel#poseRightArm/poseLeftArm} 会自动套用（双手武器会同时摆两只手）。</p>
     */
    private void applyHeldItemPose(ResidentEntity entity) {
        HumanoidModel.ArmPose pose = HumanoidModel.ArmPose.EMPTY;
        ItemStack main = entity.getMainHandItem();
        if (!main.isEmpty()) {
            boolean usingMain = entity.isUsingItem()
                && entity.getUsedItemHand() == net.minecraft.world.InteractionHand.MAIN_HAND;
            if (main.getItem() instanceof CrossbowItem) {
                // 已装填 = 端平瞄准；正在拉弦 = 上弦姿势
                pose = CrossbowItem.isCharged(main) || !usingMain
                    ? HumanoidModel.ArmPose.CROSSBOW_HOLD
                    : HumanoidModel.ArmPose.CROSSBOW_CHARGE;
            } else if (main.getItem() instanceof BowItem && usingMain) {
                pose = HumanoidModel.ArmPose.BOW_AND_ARROW;
            }
        }
        this.model.rightArmPose = pose;
        this.model.leftArmPose = pose;
        // 举盾：副手盾牌正在使用时摆出格挡姿势（右撇子盾在左手，左撇子在右手）
        if (entity.isUsingItem() && entity.getUseItem().getItem() instanceof ShieldItem) {
            if (entity.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT) {
                this.model.leftArmPose = HumanoidModel.ArmPose.BLOCK;
            } else {
                this.model.rightArmPose = HumanoidModel.ArmPose.BLOCK;
            }
        }
    }

    /** 彩蛋开关：名字正好是 {@code jeb_}（和原版彩虹羊同一个判定）。 */
    private static boolean isJeb(ResidentEntity entity) {
        return entity.hasCustomName() && "jeb_".equals(entity.getCustomName().getString());
    }

    /** 完全照原版 {@code SheepRenderer}：每 25 tick 换一个染料色，并在两色之间插值。 */
    private static float[] rainbowColor(ResidentEntity entity, float partialTick) {
        int index = entity.tickCount / 25 + entity.getId();
        int count = net.minecraft.world.item.DyeColor.values().length;
        int from = index % count;
        int to = (index + 1) % count;
        float t = (entity.tickCount % 25 + partialTick) / 25.0F;
        float[] a = rgb(net.minecraft.world.item.DyeColor.byId(from).getFireworkColor());
        float[] b = rgb(net.minecraft.world.item.DyeColor.byId(to).getFireworkColor());
        return new float[]{
            net.minecraft.util.Mth.lerp(t, a[0], b[0]),
            net.minecraft.util.Mth.lerp(t, a[1], b[1]),
            net.minecraft.util.Mth.lerp(t, a[2], b[2])};
    }

    private static float[] rgb(int color) {
        return new float[]{
            ((color >> 16) & 0xFF) / 255.0F,
            ((color >> 8) & 0xFF) / 255.0F,
            (color & 0xFF) / 255.0F};
    }

    @Override
    public ResourceLocation getTextureLocation(ResidentEntity entity) {
        return ClientSkins.skin(entity.skinName()).texture();
    }

    /**
     * 彻底关掉原版名字牌。
     *
     * <p>坑：NeoForge 的 {@code EntityRenderer#shouldShowName} 实现是
     * <pre>entity.shouldShowName() || (entity.hasCustomName() &amp;&amp; entity == crosshairPickEntity)</pre>
     * —— 也就是说**准星指着有自定义名的实体时，原版照样会画名字牌**，
     * 光在 {@code ResidentEntity#shouldShowName()} 里返回 false 根本挡不住。
     * 于是在这里直接返回 false：头顶显示全部交给 {@link ResidentNameplate} 自绘。</p>
     */
    @Override
    protected boolean shouldShowName(ResidentEntity entity) {
        return false;
    }

    @Override
    protected void scale(ResidentEntity entity, PoseStack poseStack, float partialTick) {
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
    }

    /**
     * 渲染类型：**用原版的**（{@code model.renderType()} → {@code entityCutoutNoCull}）。
     *
     * <p>之前这里返回 {@code entityTranslucentCull}，而
     * {@code rendertype_entity_translucent_cull} 的 shader **没有 overlay 采样器**
     * （只有 Sampler0/Sampler2，而 {@code rendertype_entity_cutout} 有 Sampler1 就是 overlay），
     * 所以原版靠 {@code OverlayTexture} 红色遮罩做的受击红根本不会出现 ——
     * 3D 皮肤层用的是自己的 cutout 类 RenderType，于是只有它闪红。
     * 换回原版类型后，居民直接进入原版生物受击闪红。</p>
     */
    @Override
    protected RenderType getRenderType(ResidentEntity entity, boolean visible, boolean translucent, boolean glowing) {
        return super.getRenderType(entity, visible, translucent, glowing);
    }
}
