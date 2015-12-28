package li.cil.tis3d.common.tile;

import cpw.mods.fml.common.Optional;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import li.cil.tis3d.api.infrared.InfraredPacket;
import li.cil.tis3d.api.infrared.InfraredReceiver;
import li.cil.tis3d.api.machine.Casing;
import li.cil.tis3d.api.machine.Face;
import li.cil.tis3d.api.machine.Port;
import li.cil.tis3d.api.module.Module;
import li.cil.tis3d.api.module.traits.BlockChangeAware;
import li.cil.tis3d.api.module.traits.BundledRedstone;
import li.cil.tis3d.api.module.traits.Redstone;
import li.cil.tis3d.common.Settings;
import li.cil.tis3d.common.integration.redlogic.ProxyRedLogic;
import li.cil.tis3d.common.integration.redlogic.RedLogicBundledRedstone;
import li.cil.tis3d.common.integration.redlogic.RedLogicConnectable;
import li.cil.tis3d.common.integration.redlogic.RedLogicRedstone;
import li.cil.tis3d.common.integration.redstone.RedstoneIntegration;
import li.cil.tis3d.common.inventory.InventoryCasing;
import li.cil.tis3d.common.inventory.SidedInventoryProxy;
import li.cil.tis3d.common.machine.CasingImpl;
import li.cil.tis3d.common.machine.CasingProxy;
import li.cil.tis3d.common.network.Network;
import li.cil.tis3d.common.network.message.MessageCasingState;
import li.cil.tis3d.util.InventoryUtils;
import net.minecraft.block.Block;
import li.cil.tis3d.util.OneEightCompat;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

/**
 * Tile entity for casings.
 * <p>
 * Manages modules installed in it and takes care of maintaining network state
 * with other casings (i.e. injects virtual forwarding module in slots between
 * two casing blocks to relay data between the casings).
 * <p>
 * Also takes care of notifying a connected controller if some state changed,
 * so that the controller can re-scan for a multi-block.
 * <p>
 * Casings do not tick. The modules installed in them are driven by a
 * controller (transitively) connected to their casing.
 */
@Optional.InterfaceList({
        @Optional.Interface(iface = "li.cil.tis3d.common.integration.redlogic.RedLogicBundledRedstone", modid = ProxyRedLogic.MOD_ID),
        @Optional.Interface(iface = "li.cil.tis3d.common.integration.redlogic.RedLogicConnectable", modid = ProxyRedLogic.MOD_ID),
        @Optional.Interface(iface = "li.cil.tis3d.common.integration.redlogic.RedLogicRedstone", modid = ProxyRedLogic.MOD_ID)
})
public final class TileEntityCasing extends TileEntityComputer implements
        RedLogicConnectable, RedLogicRedstone, RedLogicBundledRedstone,
        SidedInventoryProxy, CasingProxy, InfraredReceiver {
    // --------------------------------------------------------------------- //
    // Persisted data

    private final InventoryCasing inventory = new InventoryCasing(this);
    private final CasingImpl casing = new CasingImpl(this);

    // --------------------------------------------------------------------- //
    // Computed data

    // NBT tag names.
    private static final String TAG_INVENTORY = "inventory";
    private static final String TAG_CASING = "casing";
    private static final String TAG_ENABLED = "enabled";

    private TileEntityController controller;
    private boolean isEnabled;
    private boolean redstoneDirty = true;

    // --------------------------------------------------------------------- //
    // Networking

    public TileEntityController getController() {
        return controller;
    }

    public void setController(final TileEntityController controller) {
        this.controller = controller;
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    @SideOnly(Side.CLIENT)
    public void setEnabled(final boolean value) {
        isEnabled = value;
    }

    public void scheduleScan() {
        if (getWorldObj().isRemote) {
            return;
        }
        if (getController() != null) {
            getController().scheduleScan();
        } else {
            // If we don't have a controller there either isn't one, or
            // the controller is in an error state. In the latter case we
            // have ot actively look for a controller and notify it.
            final TileEntityController controller = findController();
            if (controller != null) {
                controller.scheduleScan();
            }
        }
    }

    public void onEnabled() {
        if (isEnabled) {
            return;
        }
        isEnabled = true;
        sendState();
        casing.onEnabled();
    }

    public void onDisabled() {
        if (!isEnabled) {
            return;
        }
        isEnabled = false;
        sendState();
        casing.onDisabled();
    }

    public void stepRedstone() {
        if (!redstoneDirty) {
            return;
        }
        redstoneDirty = false;

        for (final Face face : Face.VALUES) {
            final Module module = getCasing().getModule(face);
            if (module instanceof Redstone) {
                final Redstone redstone = (Redstone) module;
                final short signal = (short) RedstoneIntegration.INSTANCE.getRedstoneInput(redstone);
                redstone.setRedstoneInput(signal);
            }

            if (module instanceof BundledRedstone) {
                final BundledRedstone bundledRedstone = (BundledRedstone) module;
                for (int channel = 0; channel < 16; channel++) {
                    final short signal = (short) RedstoneIntegration.INSTANCE.getBundledRedstoneInput(bundledRedstone, channel);
                    bundledRedstone.setBundledRedstoneInput(channel, signal);
                }
            }
        }
    }

    public void stepModules() {
        casing.stepModules();
    }

    public void setModule(final Face face, final Module module) {
        casing.setModule(face, module);
    }

    public void lock(final ItemStack stack) {
        casing.lock(stack);
    }

    public void unlock(final ItemStack stack) {
        casing.unlock(stack);
    }

    public void notifyModulesOfBlockChange(final Block neighborBlock) {
        for (final Face face : Face.VALUES) {
            final Module module = getModule(face);
            if (module instanceof BlockChangeAware) {
                ((BlockChangeAware) module).onNeighborBlockChange(neighborBlock);
            }
        }
    }

    // --------------------------------------------------------------------- //
    // PipeHost

    @Override
    protected void setNeighbor(final Face face, final TileEntityComputer neighbor) {
        super.setNeighbor(face, neighbor);

        // Ensure there are no modules installed between two casings.
        if (neighbors[face.ordinal()] != null) {
            InventoryUtils.drop(getWorldObj(), xCoord, yCoord, zCoord, this, face.ordinal(), getInventoryStackLimit(), Face.toEnumFacing(face));
        }

        if (neighbor instanceof TileEntityController) {
            // If we have a controller and it's not our controller, tell our
            // controller to do a re-scan (because now we have more than one
            // controller, which is invalid).
            if (getController() != neighbor && getController() != null) {
                getController().scheduleScan();
            }
        }
    }

    @Override
    public void onWriteComplete(final Face sendingFace, final Port sendingPort) {
        super.onWriteComplete(sendingFace, sendingPort);

        final Module module = getModule(sendingFace);
        if (module != null) {
            module.onWriteComplete(sendingPort);
        }
    }

    // --------------------------------------------------------------------- //
    // RedLogicConnectable, RedLogicRedstone, RedLogicBundledRedstone

    @Override
    public TileEntityCasing getTileEntity() {
        return this;
    }

    // --------------------------------------------------------------------- //
    // IInventory

    @Override
    public boolean isUseableByPlayer(final EntityPlayer player) {
        if (worldObj.getTileEntity(getPositionX(), getPositionY(), getPositionZ()) != this) return false;
        final double maxDistance = 64;
        return OneEightCompat.getDistanceSqToCenter(player, getPositionX(), getPositionY(), getPositionZ()) <= maxDistance;
    }

    // --------------------------------------------------------------------- //
    // SidedInventoryProxy

    @Override
    public ISidedInventory getInventory() {
        return inventory;
    }

    // --------------------------------------------------------------------- //
    // CasingProxy

    @Override
    public Casing getCasing() {
        return casing;
    }

    // --------------------------------------------------------------------- //
    // InfraredReceiver

    @Override
    public void onInfraredPacket(final InfraredPacket packet, final MovingObjectPosition hit) {
        final Module module = getModule(Face.fromEnumFacing(EnumFacing.getFront(hit.sideHit)));
        if (module instanceof InfraredReceiver) {
            ((InfraredReceiver) module).onInfraredPacket(packet, hit);
        }
    }

    // --------------------------------------------------------------------- //
    // TileEntity

    @Override
    public boolean canUpdate() {
        return false;
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (!getCasingWorld().isRemote) {
            onDisabled();
        }
        dispose();
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        dispose();
    }

    @Override
    public void readFromNBT(final NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        load(nbt);
    }

    @Override
    public void writeToNBT(final NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        save(nbt);
    }

    @Override
    public void onDataPacket(final NetworkManager manager, final S35PacketUpdateTileEntity packet) {
        final NBTTagCompound nbt = packet.func_148857_g();
        load(nbt);
        getCasingWorld().markBlockForUpdate(getPositionX(), getPositionY(), getPositionZ());
    }

    @Override
    public Packet getDescriptionPacket() {
        final NBTTagCompound nbt = new NBTTagCompound();
        save(nbt);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, -1, nbt);
    }

    // --------------------------------------------------------------------- //

    private TileEntityController findController() {
        // List of processed tile entities to avoid loops.
        final Set<TileEntity> processed = new HashSet<>();
        // List of pending tile entities that still need to be scanned.
        final Queue<TileEntity> queue = new ArrayDeque<>();

        // Number of casings we encountered for optional early exit.
        int casings = 0;

        // Start at our location, keep going until there's nothing left to do.
        processed.add(this);
        queue.add(this);
        while (!queue.isEmpty()) {
            final TileEntity tileEntity = queue.remove();
            if (tileEntity.isInvalid()) {
                continue;
            }

            // Check what we have. We only add controllers and casings to this list,
            // so we can skip the type check in the else branch.
            if (tileEntity instanceof TileEntityController) {
                return (TileEntityController) tileEntity;
            } else /* if (tileEntity instanceof TileEntityCasing) */ {
                // We only allow a certain number of casings per multi-block, so
                // we can early exit if there are too many (because even if we
                // notified the controller, it'd enter an error state again anyway).
                if (++casings > Settings.maxCasingsPerController) {
                    onDisabled();
                    return null;
                }

                // Keep looking...
                if (!TileEntityController.addNeighbors(getWorldObj(), tileEntity, processed, queue)) {
                    // Hit end of loaded area, so scheduling would just result in
                    // error again anyway. Do *not* disable casings, keep last
                    // known valid state when all parts were loaded.
                    return null;
                }
            }
        }

        // Could not find a controller, disable modules.
        onDisabled();
        return null;
    }

    public void markRedstoneDirty() {
        redstoneDirty = true;
    }

    private void sendState() {
        final MessageCasingState message = new MessageCasingState(this, isEnabled);
        Network.INSTANCE.getWrapper().sendToDimension(message, getWorldObj().provider.dimensionId);
    }

    private void dispose() {
        if (getController() != null) {
            getController().scheduleScan();
        }
        casing.onDisposed();
    }

    private void load(final NBTTagCompound nbt) {
        final NBTTagCompound inventoryNbt = nbt.getCompoundTag(TAG_INVENTORY);
        inventory.readFromNBT(inventoryNbt);

        final NBTTagCompound casingNbt = nbt.getCompoundTag(TAG_CASING);
        casing.readFromNBT(casingNbt);

        isEnabled = nbt.getBoolean(TAG_ENABLED);
    }

    private void save(final NBTTagCompound nbt) {
        final NBTTagCompound inventoryNbt = new NBTTagCompound();
        inventory.writeToNBT(inventoryNbt);
        nbt.setTag(TAG_INVENTORY, inventoryNbt);

        final NBTTagCompound casingNbt = new NBTTagCompound();
        casing.writeToNBT(casingNbt);
        nbt.setTag(TAG_CASING, casingNbt);

        nbt.setBoolean(TAG_ENABLED, isEnabled());
    }
}
