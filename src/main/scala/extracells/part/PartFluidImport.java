package extracells.part;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.RedstoneMode;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartRenderHelper;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.util.AEColor;
import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import extracells.Extracells;
import extracells.render.TextureManager;
import extracells.util.PermissionUtil;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.IIcon;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

public class PartFluidImport extends PartFluidIO implements IFluidHandler {

    @Override
    public int cableConnectionRenderTo() {
        return 5;
    }

    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        return false;
    }

    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        return true;
    }

    @Override
    public TickRateModulation doWork(int rate, int TicksSinceLastCall) {
        if (!isActive() || getFacingTank() == null) return TickRateModulation.IDLE;
        boolean empty = true;

        List<Fluid> filter = new ArrayList<Fluid>();
        filter.add(this.filterFluids[4]);

        if (this.filterSize >= 1) {
            for (byte i = 1; i < 9; i += 2) {
                if (i != 4) {
                    filter.add(this.filterFluids[i]);
                }
            }
        }

        if (this.filterSize >= 2) {
            for (byte i = 0; i < 9; i += 2) {
                if (i != 4) {
                    filter.add(this.filterFluids[i]);
                }
            }
        }

        for (Fluid fluid : filter) {
            if (fluid != null) {
                empty = false;

                if (fillToNetwork(fluid, rate * TicksSinceLastCall)) {
                    return TickRateModulation.FASTER;
                }
            }
        }
        return (empty && fillToNetwork(null, rate * TicksSinceLastCall))
                ? TickRateModulation.FASTER
                : TickRateModulation.SLOWER;
    }

    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        return null;
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        return null;
    }

    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        boolean redstonePowered = isRedstonePowered();
        if (resource == null
                || redstonePowered && getRedstoneMode() == RedstoneMode.LOW_SIGNAL
                || !redstonePowered && getRedstoneMode() == RedstoneMode.HIGH_SIGNAL) return 0;
        int drainAmount =
                Math.min(Extracells.basePartSpeed() + this.speedState * Extracells.basePartSpeed(), resource.amount);
        FluidStack toFill = new FluidStack(resource.getFluid(), drainAmount);
        Actionable action = doFill ? Actionable.MODULATE : Actionable.SIMULATE;
        IAEFluidStack filled = injectFluid(AEApi.instance().storage().createFluidStack(toFill), action);
        if (filled == null) return toFill.amount;
        return toFill.amount - (int) filled.getStackSize();
    }

    protected boolean fillToNetwork(Fluid fluid, int toDrain) {
        FluidStack drained;
        IFluidHandler facingTank = getFacingTank();
        ForgeDirection side = getSide();
        if (fluid == null) {
            drained = facingTank.drain(side.getOpposite(), toDrain, false);
        } else {
            drained = facingTank.drain(side.getOpposite(), new FluidStack(fluid, toDrain), false);
            if (drained != null && drained.getFluidID() != fluid.getID()) {
                return false;
            }
        }

        if (drained == null || drained.amount <= 0 || drained.getFluidID() <= 0) return false;

        IAEFluidStack toFill = AEApi.instance().storage().createFluidStack(drained);
        IAEFluidStack notInjected = injectFluid(toFill, Actionable.SIMULATE);

        int amount = toFill.getFluidStack().amount;
        if (notInjected != null) {
            amount -= notInjected.getFluidStack().amount;
        }
        if (amount > 0) {
            FluidStack actuallyDrained;
            if (fluid == null) {
                actuallyDrained = facingTank.drain(side.getOpposite(), amount, true);
            } else {
                actuallyDrained = facingTank.drain(side.getOpposite(), new FluidStack(toFill.getFluid(), amount), true);
                if (actuallyDrained != null && actuallyDrained.getFluidID() != fluid.getID()) {
                    return false;
                }
            }
            if (actuallyDrained == null || actuallyDrained.amount <= 0) {
                return false;
            }
            toFill.setStackSize(actuallyDrained.amount);
            IAEFluidStack actuallyNotInjected = injectFluid(toFill, Actionable.MODULATE);
            if (actuallyNotInjected != null && actuallyNotInjected.getStackSize() > 0) {
                // attempt to return fluid
                int returned = facingTank.fill(side.getOpposite(), actuallyNotInjected.getFluidStack(), true);
                if (returned != actuallyNotInjected.getStackSize()) {
                    FMLLog.severe(
                            "[ExtraCells2] Import bus at %d:%d,%d,%d voided %d mL of %s",
                            tile.getWorldObj().provider.dimensionId,
                            tile.xCoord,
                            tile.yCoord,
                            tile.zCoord,
                            actuallyNotInjected.getStackSize() - returned,
                            fluid.getName());
                }
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        bch.addBox(4, 4, 14, 12, 12, 16);
        bch.addBox(5, 5, 13, 11, 11, 14);
        bch.addBox(6, 6, 12, 10, 10, 13);
        bch.addBox(6, 6, 11, 10, 10, 12);
    }

    @Override
    public double getPowerUsage() {
        return 1.0D;
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        return new FluidTankInfo[0];
    }

    @Override
    public boolean onActivate(EntityPlayer player, Vec3 pos) {
        return PermissionUtil.hasPermission(player, SecurityPermissions.BUILD, (IPart) this)
                && super.onActivate(player, pos);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderInventory(IPartRenderHelper rh, RenderBlocks renderer) {
        Tessellator ts = Tessellator.instance;

        IIcon side = TextureManager.IMPORT_SIDE.getTexture();
        rh.setTexture(side, side, side, TextureManager.IMPORT_FRONT.getTexture(), side, side);
        rh.setBounds(4, 4, 14, 12, 12, 16);
        rh.renderInventoryBox(renderer);

        rh.setTexture(side);
        rh.setBounds(5, 5, 13, 11, 11, 14);
        rh.renderInventoryBox(renderer);
        rh.setBounds(6, 6, 12, 10, 10, 13);
        rh.renderInventoryBox(renderer);

        rh.setBounds(4, 4, 14, 12, 12, 16);
        rh.setInvColor(AEColor.Cyan.blackVariant);
        ts.setBrightness(15 << 20 | 15 << 4);
        rh.renderInventoryFace(TextureManager.IMPORT_FRONT.getTextures()[1], ForgeDirection.SOUTH, renderer);

        rh.setBounds(6, 6, 11, 10, 10, 12);
        renderInventoryBusLights(rh, renderer);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderStatic(int x, int y, int z, IPartRenderHelper rh, RenderBlocks renderer) {
        Tessellator ts = Tessellator.instance;

        IIcon side = TextureManager.IMPORT_SIDE.getTexture();
        rh.setTexture(side, side, side, TextureManager.IMPORT_FRONT.getTextures()[0], side, side);
        rh.setBounds(4, 4, 14, 12, 12, 16);
        rh.renderBlock(x, y, z, renderer);

        ts.setColorOpaque_I(getHost().getColor().blackVariant);
        if (isActive()) ts.setBrightness(15 << 20 | 15 << 4);
        rh.renderFace(x, y, z, TextureManager.IMPORT_FRONT.getTextures()[1], ForgeDirection.SOUTH, renderer);

        rh.setTexture(side);
        rh.setBounds(5, 5, 13, 11, 11, 14);
        rh.renderBlock(x, y, z, renderer);
        rh.setBounds(6, 6, 12, 10, 10, 13);
        rh.renderBlock(x, y, z, renderer);

        rh.setBounds(6, 6, 11, 10, 10, 12);
        renderStaticBusLights(x, y, z, rh, renderer);
    }
}
