package extracells.inventory;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.util.prioitylist.DefaultPriorityList;
import appeng.util.prioitylist.IPartitionList;

public class MEAdvancedInventoryHandler<T extends IAEStack<T>> implements IMEInventoryHandler<T> {

    private final IMEInventoryHandler<T> internal;
    private int myPriority;
    private IncludeExclude myWhitelist;
    private AccessRestriction myAccess;
    private IPartitionList<T> myPartitionList;

    private AccessRestriction cachedAccessRestriction;
    private boolean hasReadAccess;
    private boolean hasWriteAccess;

    public MEAdvancedInventoryHandler(final IMEInventory<T> i, final StorageChannel channel) {
        if (i instanceof MEAdvancedInventoryHandler) {
            this.internal = (MEAdvancedInventoryHandler<T>) i;
        } else {
            this.internal = new MEPassThrough<T>(i, channel);
        }

        this.myPriority = 0;
        this.myWhitelist = IncludeExclude.WHITELIST;
        this.setBaseAccess(AccessRestriction.READ_WRITE);
        this.myPartitionList = new DefaultPriorityList<T>();
    }

    IncludeExclude getWhitelist() {
        return this.myWhitelist;
    }

    public void setWhitelist(final IncludeExclude myWhitelist) {
        this.myWhitelist = myWhitelist;
    }

    public AccessRestriction getBaseAccess() {
        return this.myAccess;
    }

    public void setBaseAccess(final AccessRestriction myAccess) {
        this.myAccess = myAccess;
        this.cachedAccessRestriction = this.myAccess.restrictPermissions(this.internal.getAccess());
        this.hasReadAccess = this.cachedAccessRestriction.hasPermission(AccessRestriction.READ);
        this.hasWriteAccess = this.cachedAccessRestriction.hasPermission(AccessRestriction.WRITE);
    }

    IPartitionList<T> getPartitionList() {
        return this.myPartitionList;
    }

    public void setPartitionList(final IPartitionList<T> myPartitionList) {
        this.myPartitionList = myPartitionList;
    }

    @Override
    public T injectItems(final T input, final Actionable type, final BaseActionSource src) {
        if (!this.canAccept(input)) {
            return input;
        }

        return this.internal.injectItems(input, type, src);
    }

    @Override
    public T extractItems(final T request, final Actionable type, final BaseActionSource src) {
        if (!this.hasReadAccess) {
            return null;
        }

        return this.internal.extractItems(request, type, src);
    }

    @Override
    public IItemList<T> getAvailableItems(final IItemList<T> out) {
        if (!this.hasReadAccess) {
            return out;
        }

        return this.internal.getAvailableItems(out);
    }

    @Override
    public StorageChannel getChannel() {
        return this.internal.getChannel();
    }

    @Override
    public AccessRestriction getAccess() {
        return this.cachedAccessRestriction;
    }

    @Override
    public boolean isPrioritized(final T input) {
        if (this.myWhitelist == IncludeExclude.WHITELIST) {
            return this.myPartitionList.isListed(input) || this.internal.isPrioritized(input);
        }
        return false;
    }

    @Override
    public boolean canAccept(final T input) {
        if (!this.hasWriteAccess) {
            return false;
        }

        if (this.myWhitelist == IncludeExclude.BLACKLIST && this.myPartitionList.isListed(input)) {
            return false;
        }
        if (this.myPartitionList.isEmpty() || this.myWhitelist == IncludeExclude.BLACKLIST) {
            return this.internal.canAccept(input);
        }
        return this.myPartitionList.isListed(input) && this.internal.canAccept(input);
    }

    @Override
    public int getPriority() {
        return this.myPriority;
    }

    public void setPriority(final int myPriority) {
        this.myPriority = myPriority;
    }

    @Override
    public int getSlot() {
        return this.internal.getSlot();
    }

    @Override
    public boolean validForPass(final int i) {
        return true;
    }

    public IMEInventory<T> getInternal() {
        return this.internal;
    }
}
