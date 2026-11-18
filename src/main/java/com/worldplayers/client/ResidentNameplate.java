package com.worldplayers.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.worldplayers.WorldPlayers;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentJobs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import org.joml.Matrix4f;

/**
 * 居民头顶显示（完全自绘）。
 *
 * <p>不走原版名字牌流程：原版那一层由 {@code ResidentRenderer#shouldShowName} 恒 false 彻底关掉
 * （注意：只在实体里写 {@code shouldShowName=false} 不够，NeoForge 在准星指着有自定义名的实体时
 * 仍会画原版名字牌），所以这里画的永远是唯一一层。</p>
 *
 * <p>文字用 {@code SEE_THROUGH} 模式，头顶有方块也不会被挡住。</p>
 *
 * <p>v0.1.9：所有实心块（面板、血条、心情球）画完**立刻 flush**，再画文字。
 * 以前面板和文字是两种 RenderType，flush 顺序一变，半透明黑面板就会盖在文字上，
 * 字看起来"变暗"——不同启动之间偶发就是这个原因。</p>
 */
@EventBusSubscriber(modid = WorldPlayers.MOD_ID, value = Dist.CLIENT)
public final class ResidentNameplate {
    private static final float SCALE = 0.025F;
    private static final float PAD_X = 3.0F;
    private static final float PAD_Y = 2.0F;
    private static final float LINE = 10.0F;
    private static final float BAR_W = 42.0F;
    private static final float BAR_H = 3.0F;
    private static final float BAR_GAP = 5.0F;
    private static final int PANEL = 0xB4000000;
    private static final Font.DisplayMode MODE = Font.DisplayMode.SEE_THROUGH;

    /** 心情球：直径 9（和一行文字等高），跟名字同一行。 */
    private static final float FACE_SIZE = 9.0F;
    private static final float FACE_GAP = 2.0F;
    private static final int FACE_GREEN = 0xFF3FBF4F;
    private static final int FACE_YELLOW = 0xFFE8C230;
    private static final int FACE_RED = 0xFFD84848;
    private static final int FACE_INK = 0xFF101010;

    /** kid 模型脑袋又大又高，锚点要抬得更多（下面的数值在 kid 的 0.62 缩放空间里）。 */
    private static float anchorY(ResidentEntity entity) {
        if (entity.isKid()) {
            // 3.2 × 0.62 ≈ 世界 1.98 格：卡片底边约 1.73 格，稳稳高于放大后的头顶
            return 3.2F;
        }
        return entity.getBbHeight() + 0.6F;
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Post<ResidentEntity, PlayerModel<ResidentEntity>> event) {
        if (!(event.getEntity() instanceof ResidentEntity resident)) {
            return;
        }
        Component custom = resident.getCustomName();
        boolean hasName = custom != null && !custom.getString().isBlank();
        boolean card = ClientDisplay.showNameplate(resident);
        if (!card && !hasName) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(0.0D, anchorY(resident), 0.0D);
        poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(SCALE, -SCALE, SCALE);

        // 状态浮窗永远全亮；普通名字牌跟随实体光照（主人要求：不发光）
        int light = card ? LightTexture.FULL_BRIGHT : event.getPackedLight();

        // 防御：别的渲染可能把 shader 颜色压暗过（受伤闪红之类），画之前恢复纯白
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        if (card) {
            renderCard(resident, hasName ? custom : null, poseStack, event.getMultiBufferSource(), light);
        } else {
            renderNameOnly(custom, poseStack, event.getMultiBufferSource(), light);
        }
        poseStack.popPose();
    }

    private static void renderNameOnly(Component name, PoseStack poseStack, MultiBufferSource buffer, int light) {
        Font font = Minecraft.getInstance().font;
        int width = font.width(name);
        font.drawInBatch(name, -width / 2.0F, 0.0F, 0xFFFFFFFF, false, poseStack.last().pose(),
            buffer, MODE, 0x40000000, light);
    }

    private static void renderCard(ResidentEntity entity, Component customName, PoseStack poseStack,
                                   MultiBufferSource buffer, int light) {
        Font font = Minecraft.getInstance().font;
        Matrix4f matrix = poseStack.last().pose();

        Component jobLine = jobText(entity.jobKey());
        float moodPercent = entity.mood();

        float nameWidth = customName == null ? 0.0F : font.width(customName);
        float titleWidth = customName == null
            ? FACE_SIZE
            : nameWidth + FACE_GAP + FACE_SIZE;
        float content = Math.max(titleWidth, font.width(jobLine) + PAD_X + BAR_W);
        float cardW = content + PAD_X * 2.0F;
        float cardH = LINE + PAD_Y * 2.0F + BAR_GAP * 2.0F + BAR_H * 3.0F - 1.0F;

        float left = -cardW / 2.0F;
        float top = -cardH / 2.0F;

        // ---- 实心块：面板 + 三条状态条 + 心情球 ----
        quad(buffer, matrix, left, top, left + cardW, top + cardH, 0.0F, PANEL);

        float rowY = top + PAD_Y + LINE;
        float barX = left + cardW - PAD_X - BAR_W;
        float barTop = rowY + 0.5F;
        drawBar(buffer, matrix, barX, barTop, entity.getMaxHealth() <= 0 ? 0
            : entity.getHealth() / entity.getMaxHealth(), 0xFFFF4040);
        drawBar(buffer, matrix, barX, barTop + BAR_GAP, entity.hunger() / 100.0F, 0xFFFFD24A);
        drawBar(buffer, matrix, barX, barTop + BAR_GAP * 2.0F, entity.stamina() / 100.0F, 0xFF58D858);

        float titleX = left + (cardW - titleWidth) / 2.0F;
        float faceX = customName == null ? titleX : titleX + nameWidth + FACE_GAP;
        drawFace(buffer, matrix, faceX, top + PAD_Y, moodPercent);

        // 关键：先把这一批 quad 画掉，文字之后画，半透明面板就绝不会盖住字
        flushQuads(buffer);

        // ---- 文字 ----
        // 抬高一点 z：面板 quad 在 z=0、文字默认也在 z=0，深度相等时偶发 z-fighting
        // （表现就是某个字突然变灰/被面板吃掉一格）。抬高 0.05 让文字永远压在面板上面。
        poseStack.pushPose();
        poseStack.translate(0.0F, 0.0F, 0.05F);
        Matrix4f textMatrix = poseStack.last().pose();
        if (customName != null) {
            font.drawInBatch(customName, titleX, top + PAD_Y, 0xFFFFFFFF, false,
                textMatrix, buffer, MODE, 0, light);
        }
        font.drawInBatch(jobLine, left + PAD_X, rowY, 0xFFFFFFFF, false, textMatrix, buffer, MODE, 0, light);
        poseStack.popPose();
    }

    /**
     * 心情球：绿色 >=75（微笑）、黄色 >=50（平脸）、红色 <50（哭脸）。
     * 用横条拼出圆形，眼睛和嘴巴是纯黑小方块——和 Minecraft 的画风一致。
     */
    private static void drawFace(MultiBufferSource buffer, Matrix4f matrix, float x, float y, float percent) {
        int color = percent >= 75.0F ? FACE_GREEN : (percent >= 50.0F ? FACE_YELLOW : FACE_RED);
        float u = FACE_SIZE / 9.0F;
        // 每一行的左右边界（以 9 格为单位），拼出圆
        int[][] rows = {
            {3, 6}, {2, 7}, {1, 8}, {0, 9}, {0, 9}, {0, 9}, {1, 8}, {2, 7}, {3, 6}
        };
        for (int row = 0; row < rows.length; row++) {
            float y0 = y + row * u;
            quad(buffer, matrix, x + rows[row][0] * u, y0, x + rows[row][1] * u, y0 + u, 0.02F, color);
        }
        // 眼睛
        quad(buffer, matrix, x + 2.2F * u, y + 2.6F * u, x + 3.6F * u, y + 4.4F * u, 0.03F, FACE_INK);
        quad(buffer, matrix, x + 5.4F * u, y + 2.6F * u, x + 6.8F * u, y + 4.4F * u, 0.03F, FACE_INK);

        if (percent >= 75.0F) {
            // 微笑：嘴角高、中间低
            quad(buffer, matrix, x + 2.2F * u, y + 5.6F * u, x + 3.6F * u, y + 6.6F * u, 0.03F, FACE_INK);
            quad(buffer, matrix, x + 3.6F * u, y + 6.4F * u, x + 5.4F * u, y + 7.2F * u, 0.03F, FACE_INK);
            quad(buffer, matrix, x + 5.4F * u, y + 5.6F * u, x + 6.8F * u, y + 6.6F * u, 0.03F, FACE_INK);
        } else if (percent >= 50.0F) {
            // 平脸：一条直线
            quad(buffer, matrix, x + 2.4F * u, y + 6.2F * u, x + 6.6F * u, y + 7.2F * u, 0.03F, FACE_INK);
        } else {
            // 哭脸：嘴角低、中间高
            quad(buffer, matrix, x + 2.2F * u, y + 6.6F * u, x + 3.6F * u, y + 7.6F * u, 0.03F, FACE_INK);
            quad(buffer, matrix, x + 3.6F * u, y + 5.6F * u, x + 5.4F * u, y + 6.4F * u, 0.03F, FACE_INK);
            quad(buffer, matrix, x + 5.4F * u, y + 6.6F * u, x + 6.8F * u, y + 7.6F * u, 0.03F, FACE_INK);
        }
    }

    private static void flushQuads(MultiBufferSource buffer) {
        if (buffer instanceof MultiBufferSource.BufferSource source) {
            source.endBatch(RenderType.debugQuads());
        }
    }

    private static void drawBar(MultiBufferSource buffer, Matrix4f matrix, float x, float y,
                                float fill, int color) {
        float filled = BAR_W * Mth.clamp(fill, 0.0F, 1.0F);
        if (filled > 0.0F) {
            quad(buffer, matrix, x, y, x + filled, y + BAR_H, 0.02F, color);
        }
        if (filled < BAR_W) {
            quad(buffer, matrix, x + filled, y, x + BAR_W, y + BAR_H, 0.02F, 0xFF303030);
        }
    }

    private static void quad(MultiBufferSource buffer, Matrix4f matrix,
                             float x0, float y0, float x1, float y1, float z, int argb) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.debugQuads());
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        consumer.addVertex(matrix, x0, y0, z).setColor(r, g, b, a);
        consumer.addVertex(matrix, x0, y1, z).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y1, z).setColor(r, g, b, a);
        consumer.addVertex(matrix, x1, y0, z).setColor(r, g, b, a);
    }

    /** 工作状态文字：统一向 {@link ResidentJobs} 登记表要，UI 不写死任何状态文案。 */
    private static Component jobText(String jobKey) {
        return ResidentJobs.text(jobKey);
    }

    private ResidentNameplate() {
    }
}
