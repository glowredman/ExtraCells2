package extracells.part;

import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartRenderHelper;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.util.AEColor;
import appeng.client.texture.CableBusTextures;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import extracells.container.ContainerFluidTerminal;
import extracells.container.ContainerGasTerminal;
import extracells.gridblock.ECBaseGridBlock;
import extracells.gui.GuiFluidTerminal;
import extracells.network.packet.part.PacketFluidTerminal;
import extracells.render.TextureManager;
import extracells.util.FluidUtil;
import extracells.util.PermissionUtil;
import extracells.util.inventory.ECPrivateInventory;
import extracells.util.inventory.IInventoryUpdateReceiver;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import org.apache.commons.lang3.tuple.MutablePair;

public class PartFluidTerminal extends PartECBase implements IGridTickable, IInventoryUpdateReceiver {

    protected Fluid currentFluid;
    private final List<Object> containers = new ArrayList<Object>();
    protected ECPrivateInventory inventory = new ECPrivateInventory("extracells.part.fluid.terminal", 2, 64, this) {

        @Override
        public boolean isItemValidForSlot(int i, ItemStack itemStack) {
            return isItemValidForInputSlot(i, itemStack);
        }
    };

    protected boolean isItemValidForInputSlot(int i, ItemStack itemStack) {
        return FluidUtil.isFluidContainer(itemStack);
    }

    protected MachineSource machineSource = new MachineSource(this);

    @Override
    public void getDrops(List<ItemStack> drops, boolean wrenched) {
        for (ItemStack stack : inventory.slots) {
            if (stack == null) continue;
            drops.add(stack);
        }
    }

    @Override
    public int getLightLevel() {
        return this.isPowered() ? 9 : 0;
    }

    public void addContainer(ContainerFluidTerminal containerTerminalFluid) {
        this.containers.add(containerTerminalFluid);
        sendCurrentFluid();
    }

    public void addContainer(ContainerGasTerminal containerTerminalGas) {
        this.containers.add(containerTerminalGas);
        sendCurrentFluid();
    }

    @Override
    public int cableConnectionRenderTo() {
        return 1;
    }

    public void decreaseFirstSlot() {
        ItemStack slot = this.inventory.getStackInSlot(0);
        slot.stackSize--;
        if (slot.stackSize <= 0) this.inventory.setInventorySlotContents(0, null);
    }

    public void doWork() {
        ItemStack secondSlot = this.inventory.getStackInSlot(1);
        if (secondSlot != null && secondSlot.stackSize >= secondSlot.getMaxStackSize()) return;
        ItemStack container = this.inventory.getStackInSlot(0);
        if (!FluidUtil.isFluidContainer(container)) return;
        container = container.copy();
        container.stackSize = 1;

        ECBaseGridBlock gridBlock = getGridBlock();
        if (gridBlock == null) return;
        IMEMonitor<IAEFluidStack> monitor = gridBlock.getFluidMonitor();
        if (monitor == null) return;

        if (FluidUtil.isEmpty(container)) {
            if (this.currentFluid == null) return;
            FluidStack request = new FluidStack(this.currentFluid, Integer.MAX_VALUE);
            MutablePair<ItemStack, FluidStack> simulation =
                    FluidUtil.fillItemFromAe(container, request, monitor, Actionable.SIMULATE, this.machineSource);
            if (simulation == null || simulation.getLeft() == null) {
                return;
            }
            if (!fillSecondSlot(simulation.getLeft(), false)) {
                return;
            }
            request.amount = simulation.getRight().amount;
            MutablePair<ItemStack, FluidStack> result =
                    FluidUtil.fillItemFromAe(container, request, monitor, Actionable.MODULATE, this.machineSource);
            if (result == null || result.getLeft() == null) {
                return;
            }
            if (!fillSecondSlot(result.getLeft(), true)) {
                // Rare case: couldn't withdraw all requested fluid with AE, so a partially filled container can't stack
                // with the other containers
                TileEntity host = getHostTile();
                if (host == null || host.getWorldObj() == null) {
                    return;
                }
                ForgeDirection side = getSide();
                EntityItem overflow = new EntityItem(
                        host.getWorldObj(),
                        host.xCoord + side.offsetX,
                        host.yCoord + side.offsetY,
                        host.zCoord + side.offsetZ,
                        result.getLeft());
                host.getWorldObj().spawnEntityInWorld(overflow);
            }
            decreaseFirstSlot();
        } else {
            FluidStack containerFluid = FluidUtil.getFluidFromContainer(container);

            ItemStack simulation =
                    FluidUtil.drainItemIntoAe(container, monitor, Actionable.SIMULATE, this.machineSource);
            if (simulation == null) {
                return;
            }
            if (!fillSecondSlot(simulation, false)) {
                return;
            }
            ItemStack result = FluidUtil.drainItemIntoAe(container, monitor, Actionable.MODULATE, this.machineSource);
            if (result == null) {
                return;
            }
            if (!fillSecondSlot(result, true)) {
                // Rare case: couldn't withdraw all requested fluid with AE, so a partially filled container can't stack
                // with the other containers
                TileEntity host = getHostTile();
                if (host == null || host.getWorldObj() == null) {
                    return;
                }
                ForgeDirection side = getSide();
                EntityItem overflow = new EntityItem(
                        host.getWorldObj(),
                        host.xCoord + side.offsetX,
                        host.yCoord + side.offsetY,
                        host.zCoord + side.offsetZ,
                        result);
                host.getWorldObj().spawnEntityInWorld(overflow);
            }
            decreaseFirstSlot();
        }
    }

    public boolean fillSecondSlot(ItemStack itemStack, boolean doFill) {
        if (itemStack == null) return false;
        ItemStack secondSlot = this.inventory.getStackInSlot(1);
        if (secondSlot == null) {
            if (doFill) {
                this.inventory.setInventorySlotContents(1, itemStack);
            }
            return true;
        } else {
            if (!secondSlot.isItemEqual(itemStack) || !ItemStack.areItemStackTagsEqual(itemStack, secondSlot))
                return false;
            if (doFill) {
                this.inventory.incrStackSize(1, itemStack.stackSize);
            }
            return true;
        }
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        bch.addBox(2, 2, 14, 14, 14, 16);
        bch.addBox(4, 4, 13, 12, 12, 14);
        bch.addBox(5, 5, 12, 11, 11, 13);
    }

    @Override
    public Object getClientGuiElement(EntityPlayer player) {
        return new GuiFluidTerminal(this, player);
    }

    public IInventory getInventory() {
        return this.inventory;
    }

    @Override
    public double getPowerUsage() {
        return 0.5D;
    }

    @Override
    public Object getServerGuiElement(EntityPlayer player) {
        return new ContainerFluidTerminal(this, player);
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(2, 20, false, false);
    }

    @Override
    public boolean onActivate(EntityPlayer player, Vec3 pos) {
        if (isActive()
                && (PermissionUtil.hasPermission(player, SecurityPermissions.INJECT, (IPart) this)
                        || PermissionUtil.hasPermission(player, SecurityPermissions.EXTRACT, (IPart) this)))
            return super.onActivate(player, pos);
        return false;
    }

    @Override
    public void onInventoryChanged() {
        saveData();
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        this.inventory.readFromNBT(data.getTagList("inventory", 10));
    }

    public void removeContainer(ContainerFluidTerminal containerTerminalFluid) {
        this.containers.remove(containerTerminalFluid);
    }

    public void removeContainer(ContainerGasTerminal containerTerminalGas) {
        this.containers.remove(containerTerminalGas);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderInventory(IPartRenderHelper rh, RenderBlocks renderer) {
        final IIcon sideTexture = CableBusTextures.PartMonitorSides.getIcon();
        final IIcon backTexture = CableBusTextures.PartMonitorBack.getIcon();

        // Back Panel
        rh.setTexture(sideTexture, sideTexture, backTexture, sideTexture, sideTexture, sideTexture);
        rh.setBounds(4, 4, 13, 12, 12, 14);
        rh.renderInventoryBox(renderer);

        // Front Panel
        rh.setTexture(
                sideTexture,
                sideTexture,
                backTexture,
                TextureManager.BUS_BORDER.getTexture(),
                sideTexture,
                sideTexture);
        rh.setBounds(2, 2, 14, 14, 14, 16);
        rh.renderInventoryBox(renderer);

        // Front Screen
        rh.setBounds(3, 3, 15, 13, 13, 16);
        rh.setInvColor(AEColor.Transparent.blackVariant);
        rh.renderInventoryFace(TextureManager.TERMINAL_FRONT.getTextures()[0], ForgeDirection.SOUTH, renderer);
        rh.setInvColor(AEColor.Transparent.mediumVariant);
        rh.renderInventoryFace(TextureManager.TERMINAL_FRONT.getTextures()[1], ForgeDirection.SOUTH, renderer);
        rh.setInvColor(AEColor.Transparent.whiteVariant);
        rh.renderInventoryFace(TextureManager.TERMINAL_FRONT.getTextures()[2], ForgeDirection.SOUTH, renderer);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderStatic(int x, int y, int z, IPartRenderHelper rh, RenderBlocks renderer) {
        final IPartHost host = getHost();

        // Render front screen
        rh.setBounds(3, 3, 15, 13, 13, 16);
        Tessellator.instance.setColorOpaque_I(host.getColor().blackVariant);
        rh.renderFace(x, y, z, TextureManager.TERMINAL_FRONT.getTextures()[0], ForgeDirection.SOUTH, renderer);
        Tessellator.instance.setColorOpaque_I(host.getColor().mediumVariant);
        rh.renderFace(x, y, z, TextureManager.TERMINAL_FRONT.getTextures()[1], ForgeDirection.SOUTH, renderer);
        Tessellator.instance.setColorOpaque_I(host.getColor().whiteVariant);
        rh.renderFace(x, y, z, TextureManager.TERMINAL_FRONT.getTextures()[2], ForgeDirection.SOUTH, renderer);

        renderFrontPanel(x, y, z, rh, renderer);
        renderBackPanel(x, y, z, rh, renderer);
        renderPowerStatus(x, y, z, rh, renderer);
    }

    public void sendCurrentFluid() {
        for (Object containerFluidTerminal : this.containers) {
            sendCurrentFluid(containerFluidTerminal);
        }
    }

    public void sendCurrentFluid(Object container) {
        if (container instanceof ContainerFluidTerminal) {
            ContainerFluidTerminal containerFluidTerminal = (ContainerFluidTerminal) container;
            new PacketFluidTerminal(containerFluidTerminal.getPlayer(), this.currentFluid)
                    .sendPacketToPlayer(containerFluidTerminal.getPlayer());
        } else if (container instanceof ContainerGasTerminal) {
            ContainerGasTerminal containerGasTerminal = (ContainerGasTerminal) container;
            new PacketFluidTerminal(containerGasTerminal.getPlayer(), this.currentFluid)
                    .sendPacketToPlayer(containerGasTerminal.getPlayer());
        }
    }

    public void setCurrentFluid(Fluid _currentFluid) {
        this.currentFluid = _currentFluid;
        sendCurrentFluid();
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int TicksSinceLastCall) {
        doWork();
        return TickRateModulation.FASTER;
    }

    @Override
    public void writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setTag("inventory", this.inventory.writeToNBT());
    }
}
