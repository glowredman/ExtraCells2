package extracells.part;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.events.*;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartRenderHelper;
import appeng.api.storage.ICellContainer;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.util.AEColor;
import appeng.client.texture.CableBusTextures;
import appeng.helpers.IPriorityHost;
import appeng.items.parts.ItemMultiPart;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import extracells.container.ContainerBusFluidStorage;
import extracells.gui.GuiBusFluidStorage;
import extracells.inventory.HandlerPartStorageFluid;
import extracells.network.packet.other.IFluidSlotPartOrBlock;
import extracells.network.packet.other.PacketFluidSlot;
import extracells.network.packet.part.PacketBusFluidStorage;
import extracells.render.TextureManager;
import extracells.util.PermissionUtil;
import extracells.util.inventory.ECPrivateInventory;
import extracells.util.inventory.IInventoryUpdateReceiver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

public class PartFluidStorage extends PartECBase
        implements ICellContainer, IInventoryUpdateReceiver, IFluidSlotPartOrBlock, IPriorityHost {

    private final HashMap<IAEFluidStack, Long> fluidList = new HashMap<IAEFluidStack, Long>();
    private int priority = 0;
    protected HandlerPartStorageFluid handler = new HandlerPartStorageFluid(this);
    private final Fluid[] filterFluids = new Fluid[54];
    private AccessRestriction access = AccessRestriction.READ_WRITE;
    private final ECPrivateInventory upgradeInventory = new ECPrivateInventory("", 1, 1, this) {

        @Override
        public boolean isItemValidForSlot(int i, ItemStack itemStack) {
            return itemStack != null
                    && AEApi.instance().definitions().materials().cardInverter().isSameAs(itemStack);
        }
    };

    @Override
    public void getDrops(List<ItemStack> drops, boolean wrenched) {
        for (ItemStack stack : upgradeInventory.slots) {
            if (stack == null) continue;
            drops.add(stack);
        }
    }

    @Override
    public void blinkCell(int slot) {}

    @Override
    public int cableConnectionRenderTo() {
        return 3;
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        bch.addBox(2, 2, 15, 14, 14, 16);
        bch.addBox(4, 4, 14, 12, 12, 15);
        bch.addBox(5, 5, 13, 11, 11, 14);
    }

    @Override
    public List<IMEInventoryHandler> getCellArray(StorageChannel channel) {
        List<IMEInventoryHandler> list = new ArrayList<IMEInventoryHandler>();
        if (channel == StorageChannel.FLUIDS) {
            list.add(this.handler);
        }
        updateNeighborFluids();
        return list;
    }

    @Override
    public Object getClientGuiElement(EntityPlayer player) {
        return new GuiBusFluidStorage(this, player);
    }

    @Override
    public double getPowerUsage() {
        return 1.0D;
    }

    @Override
    public int getPriority() {
        return this.priority;
    }

    @Override
    public void setPriority(int priority) {
        this.priority = priority;
    }

    @Override
    public int getLightLevel() {
        return this.isPowered() ? 9 : 0;
    }

    @Override
    public Object getServerGuiElement(EntityPlayer player) {
        return new ContainerBusFluidStorage(this, player);
    }

    public ECPrivateInventory getUpgradeInventory() {
        return this.upgradeInventory;
    }

    @Override
    public boolean onActivate(EntityPlayer player, Vec3 pos) {
        return PermissionUtil.hasPermission(player, SecurityPermissions.BUILD, (IPart) this)
                && super.onActivate(player, pos);
    }

    @Override
    public void onInventoryChanged() {
        this.handler.setInverted(AEApi.instance()
                .definitions()
                .materials()
                .cardInverter()
                .isSameAs(this.upgradeInventory.getStackInSlot(0)));
        saveData();
    }

    @Override
    public void onNeighborChanged() {
        this.handler.onNeighborChange();
        IGridNode node = getGridNode();
        if (node != null) {
            IGrid grid = node.getGrid();
            if (grid != null && this.wasChanged()) {
                grid.postEvent(new MENetworkCellArrayUpdate());
                node.getGrid()
                        .postEvent(new MENetworkStorageEvent(getGridBlock().getFluidMonitor(), StorageChannel.FLUIDS));
                node.getGrid().postEvent(new MENetworkCellArrayUpdate());
            }
            getHost().markForUpdate();
        }
    }

    @MENetworkEventSubscribe
    public void powerChange(MENetworkPowerStatusChange event) {
        IGridNode node = getGridNode();
        if (node != null) {
            boolean isNowActive = node.isActive();
            if (isNowActive != isActive()) {
                setActive(isNowActive);
                onNeighborChanged();
                getHost().markForUpdate();
            }
        }
        node.getGrid().postEvent(new MENetworkStorageEvent(getGridBlock().getFluidMonitor(), StorageChannel.FLUIDS));
        node.getGrid().postEvent(new MENetworkCellArrayUpdate());
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        this.priority = data.getInteger("priority");
        for (int i = 0; i < 54; i++) {
            this.filterFluids[i] = FluidRegistry.getFluid(data.getString("FilterFluid#" + i));
        }
        if (data.hasKey("access")) {
            try {
                this.access = AccessRestriction.valueOf(data.getString("access"));
            } catch (Throwable e) {
            }
        }
        this.upgradeInventory.readFromNBT(data.getTagList("upgradeInventory", 10));
        onInventoryChanged();
        onNeighborChanged();
        this.handler.setPrioritizedFluids(this.filterFluids);
        this.handler.setAccessRestriction(this.access);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderInventory(IPartRenderHelper rh, RenderBlocks renderer) {
        final IIcon sideTexture = CableBusTextures.PartStorageSides.getIcon();
        final IIcon backTexture = CableBusTextures.PartStorageBack.getIcon();
        final IIcon frontTexture = TextureManager.STORAGE_FRONT.getTexture();
        final IIcon noTexture = ItemMultiPart.instance.getIconFromDamage(220);

        rh.setTexture(sideTexture, sideTexture, noTexture, frontTexture, sideTexture, sideTexture);
        rh.setBounds(3, 3, 15, 13, 13, 16);
        rh.renderInventoryBox(renderer);

        rh.setTexture(sideTexture, sideTexture, backTexture, noTexture, sideTexture, sideTexture);
        rh.setBounds(2, 2, 14, 14, 14, 15);
        rh.renderInventoryBox(renderer);

        rh.setBounds(5, 5, 12, 11, 11, 14);
        rh.renderInventoryBox(renderer);

        rh.setBounds(2, 2, 15, 14, 14, 16);
        rh.setInvColor(AEColor.Cyan.blackVariant);
        Tessellator.instance.setBrightness(15 << 20 | 15 << 4);
        rh.renderInventoryFace(TextureManager.STORAGE_FRONT.getTextures()[1], ForgeDirection.SOUTH, renderer);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderStatic(int x, int y, int z, IPartRenderHelper rh, RenderBlocks renderer) {
        final IPartHost host = getHost();
        final IIcon sideTexture = CableBusTextures.PartStorageSides.getIcon();
        final IIcon backTexture = CableBusTextures.PartStorageBack.getIcon();
        final IIcon frontTexture = TextureManager.STORAGE_FRONT.getTexture();
        final IIcon noTexture = ItemMultiPart.instance.getIconFromDamage(220);

        rh.setTexture(sideTexture, sideTexture, noTexture, frontTexture, sideTexture, sideTexture);
        rh.setBounds(3, 3, 15, 13, 13, 16);
        rh.renderBlock(x, y, z, renderer);

        Tessellator.instance.setColorOpaque_I(host.getColor().blackVariant);
        if (isActive()) Tessellator.instance.setBrightness(14 << 20 | 14 << 4);
        rh.renderFace(x, y, z, TextureManager.STORAGE_FRONT.getTextures()[1], ForgeDirection.SOUTH, renderer);

        rh.setTexture(sideTexture, sideTexture, backTexture, noTexture, sideTexture, sideTexture);
        rh.setBounds(2, 2, 14, 14, 14, 15);
        rh.renderBlock(x, y, z, renderer);

        rh.setBounds(5, 5, 12, 11, 11, 13);
        rh.renderBlock(x, y, z, renderer);

        rh.setBounds(5, 5, 13, 11, 11, 14);
        rh.renderBlock(x, y, z, renderer);

        renderPowerStatus(x, y, z, rh, renderer);
    }

    @Override
    public void saveChanges(IMEInventory cellInventory) {
        saveData();
    }

    public void sendInformation(EntityPlayer player) {
        new PacketFluidSlot(Arrays.asList(this.filterFluids)).sendPacketToPlayer(player);
        new PacketBusFluidStorage(player, this.access, true).sendPacketToPlayer(player);
    }

    @Override
    public void setFluid(int _index, Fluid _fluid, EntityPlayer _player) {
        this.filterFluids[_index] = _fluid;
        this.handler.setPrioritizedFluids(this.filterFluids);
        sendInformation(_player);
        saveData();
    }

    public void updateAccess(AccessRestriction access) {
        this.access = access;
        this.handler.setAccessRestriction(access);
        onNeighborChanged();
    }

    @MENetworkEventSubscribe
    public void updateChannels(MENetworkChannelsChanged channel) {
        IGridNode node = getGridNode();
        if (node != null) {
            boolean isNowActive = node.isActive();
            if (isNowActive != isActive()) {
                setActive(isNowActive);
                onNeighborChanged();
                getHost().markForUpdate();
            }
        }
        node.getGrid().postEvent(new MENetworkStorageEvent(getGridBlock().getFluidMonitor(), StorageChannel.FLUIDS));
        node.getGrid().postEvent(new MENetworkCellArrayUpdate());
    }

    @Override
    public void writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setInteger("priority", this.priority);
        for (int i = 0; i < this.filterFluids.length; i++) {
            Fluid fluid = this.filterFluids[i];
            if (fluid != null) data.setString("FilterFluid#" + i, fluid.getName());
            else data.setString("FilterFluid#" + i, "");
        }
        data.setTag("upgradeInventory", this.upgradeInventory.writeToNBT());
        data.setString("access", this.access.name());
    }

    private void updateNeighborFluids() {
        fluidList.clear();
        if (access == AccessRestriction.READ || access == AccessRestriction.READ_WRITE) {
            for (IAEFluidStack stack :
                    handler.getAvailableItems(AEApi.instance().storage().createFluidList())) {
                fluidList.put(stack, stack.getStackSize());
            }
        }
    }

    private boolean wasChanged() {
        HashMap<IAEFluidStack, Long> fluids = new HashMap<IAEFluidStack, Long>();
        for (IAEFluidStack stack :
                handler.getAvailableItems(AEApi.instance().storage().createFluidList())) {
            fluids.put(stack, stack.getStackSize());
        }
        return !fluids.equals(fluidList);
    }
}
