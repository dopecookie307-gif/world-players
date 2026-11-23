package com.worldplayers.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.worldplayers.resident.ResidentEntity;
import dev.tr7zw.skinlayers.api.Mesh;
import dev.tr7zw.skinlayers.api.MeshHelper;
import dev.tr7zw.skinlayers.api.OffsetProvider;
import dev.tr7zw.skinlayers.api.SkinLayersAPI;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

/**
 * 3D Skin Layers 兼容层（v0.1.1 的行为：建网格参数照抄 3DSL 的 SkinUtil，逐部位渲染）。
 *
 * <p>只在装了 3DSL 时由 {@code ResidentRenderer} 反射加载，没有 3DSL 就完全不会加载这个类。</p>
 */
public class SkinLayers3DLayer extends RenderLayer<ResidentEntity, PlayerModel<ResidentEntity>> {
    private final Map<String, Meshes> cache = new HashMap<>();
    /** 渲染阶段一旦出错（对方 API 版本变了）就停用本层，只警告一次，绝不每帧刷屏/崩客户端。 */
    private boolean failed;

    private record Meshes(Mesh head, Mesh torso, Mesh leftArm, Mesh rightArm, Mesh leftLeg, Mesh rightLeg) {
    }

    public SkinLayers3DLayer(RenderLayerParent<ResidentEntity, PlayerModel<ResidentEntity>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int light, ResidentEntity entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (this.failed) {
            return;
        }
        try {
            renderLayers(poseStack, buffer, light, entity);
        } catch (Throwable incompatible) {
            this.failed = true;
            this.cache.clear();
            com.worldplayers.WorldPlayers.LOGGER.warn(
                "World Players: 3D Skin Layers 接口与当前版本不兼容，居民改用平面皮肤", incompatible);
        }
    }

    private void renderLayers(PoseStack poseStack, MultiBufferSource buffer, int light, ResidentEntity entity) {
        if (entity.isInvisible()) {
            return;
        }
        ClientSkins.Skin skin = ClientSkins.skin(entity.skinName());
        NativeImage pixels = skin.pixels();
        if (pixels == null || pixels.getWidth() != 64 || pixels.getHeight() != 64) {
            return;
        }
        boolean slim = entity.modelType() == ResidentEntity.MODEL_SLIM
            || entity.modelType() == ResidentEntity.MODEL_KID_SLIM;
        String key = skin.texture() + "|" + slim;
        Meshes meshes = this.cache.get(key);
        if (meshes == null) {
            meshes = this.build(pixels, slim);
            if (meshes == null) {
                return;
            }
            this.cache.put(key, meshes);
        }

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(skin.texture(), true));
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0F);
        PlayerModel<ResidentEntity> model = this.getParentModel();

        renderPart(poseStack, consumer, light, overlay, model.head, meshes.head(), OffsetProvider.HEAD);
        renderPart(poseStack, consumer, light, overlay, model.body, meshes.torso(), OffsetProvider.BODY);
        renderPart(poseStack, consumer, light, overlay, model.leftArm, meshes.leftArm(),
            slim ? OffsetProvider.LEFT_ARM_SLIM : OffsetProvider.LEFT_ARM);
        renderPart(poseStack, consumer, light, overlay, model.rightArm, meshes.rightArm(),
            slim ? OffsetProvider.RIGHT_ARM_SLIM : OffsetProvider.RIGHT_ARM);
        renderPart(poseStack, consumer, light, overlay, model.leftLeg, meshes.leftLeg(), OffsetProvider.LEFT_LEG);
        renderPart(poseStack, consumer, light, overlay, model.rightLeg, meshes.rightLeg(), OffsetProvider.RIGHT_LEG);
    }

    private void renderPart(PoseStack poseStack, VertexConsumer consumer, int light, int overlay,
                            ModelPart part, Mesh mesh, OffsetProvider offset) {
        if (mesh == null || mesh == Mesh.EMPTY || !part.visible) {
            return;
        }
        poseStack.pushPose();
        part.translateAndRotate(poseStack);
        offset.applyOffset(poseStack, mesh);
        mesh.render(part, poseStack, consumer, light, overlay, -1);
        poseStack.popPose();
    }

    @Nullable
    private Meshes build(NativeImage image, boolean slim) {
        MeshHelper helper = SkinLayersAPI.getMeshHelper();
        if (helper == null) {
            return null;
        }
        return new Meshes(
            helper.create3DMesh(image, 8, 8, 8, 32, 0, false, 0.6F),
            helper.create3DMesh(image, 8, 12, 4, 16, 32, true, 0.0F),
            helper.create3DMesh(image, slim ? 3 : 4, 12, 4, 48, 48, true, -2.0F),
            helper.create3DMesh(image, slim ? 3 : 4, 12, 4, 40, 32, true, -2.0F),
            helper.create3DMesh(image, 4, 12, 4, 0, 48, true, 0.0F),
            helper.create3DMesh(image, 4, 12, 4, 0, 32, true, 0.0F));
    }
}
