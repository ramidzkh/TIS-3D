package li.cil.tis3d.client.render.tile;

import li.cil.tis3d.api.API;
import li.cil.tis3d.api.machine.Face;
import li.cil.tis3d.api.module.Module;
import li.cil.tis3d.api.util.RenderUtil;
import li.cil.tis3d.client.render.TextureLoader;
import li.cil.tis3d.common.Constants;
import li.cil.tis3d.common.TIS3D;
import li.cil.tis3d.common.tile.TileEntityCasing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.registry.GameRegistry;

import java.util.HashSet;
import java.util.Set;

/**
 * Tile entity renderer for casings, used to dynamically render stuff for
 * different modules (in particular to allow dynamic displayed content, but
 * also so as not to spam the model registry with potentially a gazillion
 * block states for static individual texturing).
 */
public final class TileEntitySpecialRendererCasing extends TileEntitySpecialRenderer<TileEntityCasing> {
    private final static Set<Class<?>> BLACKLIST = new HashSet<>();
    private static Item key;

    @Override
    public void renderTileEntityAt(final TileEntityCasing casing, final double x, final double y, final double z, final float partialTicks, final int destroyStage) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x + 0.5, y + 0.5, z + 0.5);

        RenderHelper.disableStandardItemLighting();

        // Render all modules, adjust GL state to allow easily rendering an
        // overlay in (0, 0, 0) to (1, 1, 0).
        for (final Face face : Face.VALUES) {
            GlStateManager.pushMatrix();
            GlStateManager.pushAttrib();

            setupMatrix(face);

            ensureSanity(casing, face);

            if (isPlayerHoldingKey()) {
                drawLockOverlay(casing);
            } else {
                drawModuleOverlay(casing, face, partialTicks);
            }

            GlStateManager.popAttrib();
            GlStateManager.popMatrix();
        }

        RenderHelper.enableStandardItemLighting();

        GlStateManager.popMatrix();
    }

    private void setupMatrix(final Face face) {
        switch (face) {
            case Y_NEG:
                GlStateManager.rotate(-90, 1, 0, 0);
                break;
            case Y_POS:
                GlStateManager.rotate(90, 1, 0, 0);
                break;
            case Z_NEG:
                GlStateManager.rotate(0, 0, 1, 0);
                break;
            case Z_POS:
                GlStateManager.rotate(180, 0, 1, 0);
                break;
            case X_NEG:
                GlStateManager.rotate(90, 0, 1, 0);
                break;
            case X_POS:
                GlStateManager.rotate(-90, 0, 1, 0);
                break;
        }

        GlStateManager.translate(0.5, 0.5, -0.505);
        GlStateManager.scale(-1, -1, 1);
    }

    private void ensureSanity(final TileEntityCasing casing, final Face face) {
        GlStateManager.enableTexture2D();

        final int brightness = getWorld().getCombinedLight(
                casing.getPosition().offset(Face.toEnumFacing(face)), 0);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, brightness % 65536, brightness / 65536);

        GlStateManager.color(1, 1, 1, 1);
    }

    private void drawLockOverlay(final TileEntityCasing casing) {
        // Only bother rendering the overlay if the player is nearby.
        if (!isPlayerKindaClose(casing)) {
            return;
        }

        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 0);

        RenderUtil.bindTexture(TextureMap.locationBlocksTexture);

        final TextureAtlasSprite icon;
        if (casing.isLocked()) {
            icon = Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite(TextureLoader.LOCATION_CASING_LOCKED_OVERLAY.toString());
        } else {
            icon = Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite(TextureLoader.LOCATION_CASING_UNLOCKED_OVERLAY.toString());
        }

        RenderUtil.drawQuad(icon.getMinU(), icon.getMinV(), icon.getMaxU(), icon.getMaxV());
    }

    private void drawModuleOverlay(final TileEntityCasing casing, final Face face, final float partialTicks) {
        final Module module = casing.getModule(face);
        if (module == null) {
            return;
        }
        if (BLACKLIST.contains(module.getClass())) {
            return;
        }

        try {
            module.render(casing.isEnabled(), partialTicks);
        } catch (final Exception e) {
            BLACKLIST.add(module.getClass());
            TIS3D.getLog().error("A module threw an exception while rendering, won't render again!", e);
        }
    }

    private boolean isPlayerKindaClose(final TileEntityCasing casing) {
        final EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        return player.getDistanceSqToCenter(casing.getPos()) < 16 * 16;
    }

    private boolean isPlayerHoldingKey() {
        // Cache the key item reference to avoid having to query the game
        // registry every rendered frame.
        if (key == null) {
            key = GameRegistry.findItem(API.MOD_ID, Constants.NAME_ITEM_KEY);
        }

        final EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        final ItemStack stack = player.getHeldItem();
        return stack != null && stack.getItem() == key;
    }
}
