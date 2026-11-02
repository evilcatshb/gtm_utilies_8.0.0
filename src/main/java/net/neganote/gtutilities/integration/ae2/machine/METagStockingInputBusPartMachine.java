package net.neganote.gtutilities.integration.ae2.machine;

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField;
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient;
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neganote.gtutilities.integration.ae2.gridservice.ITagStockingPart;
import net.neganote.gtutilities.utils.TagMatcher;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNodeListener;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Predicate;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class METagStockingInputBusPartMachine extends MEStockingBusPartMachine implements ITagStockingPart {

    @SaveField
    @SyncToClient
    protected String whitelistExpr = "";
    @SaveField
    @SyncToClient
    protected String blacklistExpr = "";
    @SyncToClient
    protected String Wltmp = "";
    @SyncToClient
    protected String Bltmp = "";

    @SyncToClient
    protected boolean whitelistBadSyntax = false;

    @SyncToClient
    protected boolean blacklistBadSyntax = false;

    private Predicate<GenericStack> tagAutoPullTest = ($) -> true;

    private transient String wlLast = null;
    private transient String blLast = null;
    private transient TagMatcher.Compiled wlCompiled = TagMatcher.compile("");
    private transient TagMatcher.Compiled blCompiled = TagMatcher.compile("");

    private final transient Object2ByteOpenHashMap<AEItemKey> decisionCache = new Object2ByteOpenHashMap<>();
    private static final int DECISION_CACHE_LIMIT = 8192;

    public METagStockingInputBusPartMachine(BlockEntityCreationInfo holder) {
        super(holder);
        decisionCache.defaultReturnValue((byte) -1);
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        boolean wasOnline = isOnline();
        super.onMainNodeStateChanged(reason);
        if (isOnline() != wasOnline) {
            markTagRefresh();
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!isRemote()) {
            if (super.isAutoPull()) {
                super.setAutoPull(false);
            }

            invalidateFilterCaches();

            if (updateMEStatus()) {
                markTagRefresh();
                updateInventorySubscription();
            }
        }
    }

    @Override
    public void autoIO() {
        if (!isRemote() && super.isAutoPull()) {
            super.setAutoPull(false);
        }
        if (!isRemote() && updateMEStatus()) {
            updateInventorySubscription();
        }
    }

    private static String norm(@Nullable String s) {
        if (s == null) return "";
        s = s.trim();
        return s;
    }

    private void invalidateFilterCaches() {
        wlLast = null;
        blLast = null;
        decisionCache.clear();
    }

    private void ensureCompiledUpToDate() {
        String wl = norm(whitelistExpr);
        String bl = norm(blacklistExpr);

        if (!Objects.equals(wl, wlLast)) {
            wlLast = wl;
            wlCompiled = TagMatcher.compile(wl);
            whitelistBadSyntax = !wlCompiled.isValid();
            decisionCache.clear();
        }
        if (!Objects.equals(bl, blLast)) {
            blLast = bl;
            blCompiled = TagMatcher.compile(bl);
            blacklistBadSyntax = !blCompiled.isValid();
            decisionCache.clear();
        }

        if (decisionCache.size() > DECISION_CACHE_LIMIT) {
            decisionCache.clear();
        }
    }

    protected boolean isAllowed(AEItemKey key) {
        ensureCompiledUpToDate();
        if (whitelistBadSyntax || blacklistBadSyntax) return false;

        if ((wlLast == null || wlLast.isEmpty()) && (blLast == null || blLast.isEmpty())) return false;

        byte cached = decisionCache.getByte(key);
        if (cached != -1) {
            return cached == 1;
        }

        boolean allowed;
        if (blLast != null && !blLast.isEmpty() && blCompiled != null && blCompiled.isValid()) {
            if (TagMatcher.doesItemMatch(key, blCompiled)) {
                decisionCache.put(key, (byte) 0);
                return false;
            }
        }

        if (wlLast != null && !wlLast.isEmpty() && wlCompiled != null && wlCompiled.isValid()) {
            allowed = TagMatcher.doesItemMatch(key, wlCompiled);
        } else {
            allowed = false;
        }

        decisionCache.put(key, allowed ? (byte) 1 : (byte) 0);
        return allowed;
    }

    @Override
    public boolean isTagKeyAllowed(AEKey key, long amount) {
        if (!(key instanceof AEItemKey itemKey)) return false;
        if (!isAllowed(itemKey)) return false;
        return tagAutoPullTest == null || tagAutoPullTest.test(new GenericStack(itemKey, amount));
    }

    @Override
    protected CompoundTag writeConfigToTag() {
        CompoundTag tag = super.writeConfigToTag();
        tag.putString("WhitelistExpr", whitelistExpr);
        tag.putString("BlacklistExpr", blacklistExpr);
        tag.putBoolean("TagMode", true);
        return tag;
    }

    @Override
    protected void readConfigFromTag(CompoundTag tag) {
        super.readConfigFromTag(tag);
        if (!isRemote() && super.isAutoPull()) {
            super.setAutoPull(false);
        }

        if (tag.contains("WhitelistExpr")) whitelistExpr = tag.getString("WhitelistExpr");
        if (tag.contains("BlacklistExpr")) blacklistExpr = tag.getString("BlacklistExpr");

        invalidateFilterCaches();

        if (!isRemote() && updateMEStatus()) {
            markTagRefresh();
            updateInventorySubscription();
        }
    }

    @Override
    public void setAutoPullTest(Predicate<GenericStack> autoPullTest) {
        this.tagAutoPullTest = autoPullTest;
        super.setAutoPullTest(autoPullTest);
    }

    // We can't call super to avoid the auto-pull toggle...

    private class ExportOnlyAETagStockingItemList extends ExportOnlyAEItemList {

        public ExportOnlyAETagStockingItemList(MetaMachine holder, int slots) {
            super(slots, () -> METagStockingInputBusPartMachine.this.new ExportOnlyAETagStockingItemSlot());
        }

        @Override
        public boolean isAutoPull() {
            return true;
        }

        @Override
        public boolean isStocking() {
            return true;
        }

        @Override
        public boolean hasStackInConfig(GenericStack stack, boolean checkExternal) {
            boolean inThisBus = super.hasStackInConfig(stack, false);
            if (inThisBus) return true;
            return checkExternal && METagStockingInputBusPartMachine.this.testConfiguredInOtherPart(stack);
        }
    }

    private class ExportOnlyAETagStockingItemSlot extends ExportOnlyAEItemSlot {

        public ExportOnlyAETagStockingItemSlot() {}

        public ExportOnlyAETagStockingItemSlot(@Nullable GenericStack config, GenericStack stock) {
            super(config, stock);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot == 0 && this.stock != null && this.config != null) {
                if (!METagStockingInputBusPartMachine.this.isOnline()) return ItemStack.EMPTY;

                IGrid grid = METagStockingInputBusPartMachine.this.getMainNode().getGrid();
                if (grid == null) return ItemStack.EMPTY;

                MEStorage aeNetwork = grid.getStorageService().getInventory();
                Actionable action = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
                AEKey key = this.config.what();

                long extracted = aeNetwork.extract(key, amount, action,
                        METagStockingInputBusPartMachine.this.actionSource);
                if (extracted > 0L) {
                    ItemStack result;
                    if (key instanceof AEItemKey itemKey) result = itemKey.toStack((int) extracted);
                    else result = ItemStack.EMPTY;

                    if (!simulate) {
                        this.stock = ExportOnlyAESlot.copy(this.stock, this.stock.amount() - extracted);
                        if (this.stock.amount() == 0L) this.stock = null;
                        if (this.onContentsChanged != null) this.onContentsChanged.run();
                    }
                    return result;
                }
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ExportOnlyAETagStockingItemSlot copy() {
            return METagStockingInputBusPartMachine.this.new ExportOnlyAETagStockingItemSlot(
                    this.config == null ? null : copy(this.config),
                    this.stock == null ? null : copy(this.stock));
        }
    }
}
