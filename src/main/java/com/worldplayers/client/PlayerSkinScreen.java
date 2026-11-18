package com.worldplayers.client;

import com.worldplayers.network.PlayerSkinRequestPayload;
import com.worldplayers.resident.ResidentEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 「正版账号皮肤」界面：左边是居民的全身渲染，右边输入正版 ID + 确认。
 *
 * <p>确认时只发一次请求：服务端去 Mojang 抓一次皮肤 PNG、存进 customs 皮肤池，
 * 之后居民就把这张图当普通皮肤用（渲染 / 3DSL / 聊天头像都自动一致）。</p>
 */
public class PlayerSkinScreen extends Screen {
    private static final int WIDTH = 260;
    private static final int HEIGHT = 150;

    private final ResidentEntity resident;
    private final Screen parent;
    private EditBox input;
    private int left;
    private int top;

    public PlayerSkinScreen(ResidentEntity resident, Screen parent) {
        super(Component.translatable("gui.worldplayers.player_skin.title"));
        this.resident = resident;
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.left = (this.width - WIDTH) / 2;
        this.top = (this.height - HEIGHT) / 2;
        this.input = new EditBox(this.font, this.left + 132, this.top + 44, 110, 18,
            Component.translatable("gui.worldplayers.player_skin.hint"));
        this.input.setMaxLength(16);
        this.input.setFocused(true);
        this.addRenderableWidget(this.input);
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.worldplayers.player_skin.confirm"),
                button -> this.confirm())
            .bounds(this.left + 132, this.top + 70, 52, 18)
            .build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("gui.worldplayers.player_skin.cancel"),
                button -> this.back())
            .bounds(this.left + 190, this.top + 70, 52, 18)
            .build());
        this.setFocused(this.input);
    }

    private void confirm() {
        String id = this.input.getValue().trim();
        if (id.isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new PlayerSkinRequestPayload(this.resident.getId(), id));
        this.back();
    }

    private void back() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 不碰 renderBackground（会开世界模糊）。整屏直接铺不透明底色，世界完全不透出来，
        // 面板、文字、居民渲染一律清晰。
        guiGraphics.fill(0, 0, this.width, this.height, 0xFF101010);
        guiGraphics.fill(this.left, this.top, this.left + WIDTH, this.top + HEIGHT, 0xFF1E1E1E);
        guiGraphics.fill(this.left, this.top, this.left + WIDTH, this.top + 1, 0xFF8A8A8A);
        guiGraphics.fill(this.left, this.top + HEIGHT - 1, this.left + WIDTH, this.top + HEIGHT, 0xFF8A8A8A);
        guiGraphics.fill(this.left, this.top, this.left + 1, this.top + HEIGHT, 0xFF8A8A8A);
        guiGraphics.fill(this.left + WIDTH - 1, this.top, this.left + WIDTH, this.top + HEIGHT, 0xFF8A8A8A);

        guiGraphics.drawCenteredString(this.font, this.title, this.left + WIDTH / 2, this.top + 8, 0xFFFFFF);
        InventoryScreen.renderEntityInInventoryFollowsMouse(guiGraphics,
            this.left + 10, this.top + 24, this.left + 116, this.top + 140,
            62, 0.0625F, mouseX, mouseY, this.resident);
        guiGraphics.drawString(this.font, Component.translatable("gui.worldplayers.player_skin.hint"),
            this.left + 132, this.top + 30, 0xA0A0A0, false);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    /** 彻底关掉父类的背景绘制：它会给世界加模糊/暗化，本界面自己铺不透明底。 */
    @Override
    public void renderBackground(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 故意留空
    }

    @Override
    public void onClose() {
        this.back();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
