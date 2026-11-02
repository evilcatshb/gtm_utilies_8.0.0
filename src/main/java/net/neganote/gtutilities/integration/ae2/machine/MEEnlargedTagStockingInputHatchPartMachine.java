package net.neganote.gtutilities.integration.ae2.machine;

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField;
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient;
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingHatchPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot;
import com.gregtechceu.gtceu.integration.ae2.utils.AEUtil;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fluids.FluidStack;
import net.neganote.gtutilities.config.UtilConfig;
import net.neganote.gtutilities.integration.ae2.gridservice.ITagStockingPart;
import net.neganote.gtutilities.utils.TagMatcher;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
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
public class MEEnlargedTagStockingInputHatchPartMachine extends MEStockingHatchPartMachine implements ITagStockingPart {

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

    private final transient Object2ByteOpenHashMap<AEFluidKey> decisionCache = new Object2ByteOpenHashMap<>();
    private static final int DECISION_CACHE_LIMIT = 16384;

    private static final int SLOTS_PER_ROW = 8;
    private static final int TOTAL_ROWS = Math.min(64, UtilConfig.INSTANCE.features.enlargedStockingSizeRows);
    private static final int TOTAL_SLOTS = SLOTS_PER_ROW * TOTAL_ROWS;

    public MEEnlargedTagStockingInputHatchPartMachine(BlockEntityCreationInfo holder) {
        super(holder);
        decisionCache.defaultReturnValue((byte) -1);
    }

    @Override
    protected NotifiableFluidTank createTank(int initialCapacity, int slots) {
        this.aeFluidHandler = new ExportOnlyAEStockingFluidList(this, TOTAL_SLOTS);
        return this.aeFluidHandler;
    }

    @Override
    public IActionSource getActionSource() {
        return this.actionSource;
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
                updateTankSubscription();
            }
        }
    }

    @Override
    public void autoIO() {
        if (!isRemote() && super.isAutoPull()) {
            super.setAutoPull(false);
        }
        if (!isRemote() && updateMEStatus()) {
            updateTankSubscription();
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

    protected boolean isAllowed(AEFluidKey key) {
        ensureCompiledUpToDate();
        if (whitelistBadSyntax || blacklistBadSyntax) return false;

        if ((wlLast == null || wlLast.isEmpty()) && (blLast == null || blLast.isEmpty())) return false;

        byte cached = decisionCache.getByte(key);
        if (cached != -1) {
            return cached == 1;
        }

        boolean allowed;
        if (blLast != null && !blLast.isEmpty() && blCompiled != null && blCompiled.isValid()) {
            if (TagMatcher.doesFluidMatch(key, blCompiled)) {
                decisionCache.put(key, (byte) 0);
                return false;
            }
        }

        if (wlLast != null && !wlLast.isEmpty() && wlCompiled != null && wlCompiled.isValid()) {
            allowed = TagMatcher.doesFluidMatch(key, wlCompiled);
        } else {
            allowed = false;
        }

        decisionCache.put(key, allowed ? (byte) 1 : (byte) 0);
        return allowed;
    }

    @Override
    public boolean isTagKeyAllowed(AEKey key, long amount) {
        if (!(key instanceof AEFluidKey fluidKey)) return false;
        if (!isAllowed(fluidKey)) return false;
        return tagAutoPullTest == null || tagAutoPullTest.test(new GenericStack(fluidKey, amount));
    }

    @Override
    public void setAutoPullTest(Predicate<GenericStack> autoPullTest) {
        this.tagAutoPullTest = autoPullTest;
        super.setAutoPullTest(autoPullTest);
    }

    @Override
    protected CompoundTag writeConfigToTag() {
        CompoundTag tag = super.writeConfigToTag();
        tag.putString("WhitelistExpr", whitelistExpr == null ? "" : whitelistExpr);
        tag.putString("BlacklistExpr", blacklistExpr == null ? "" : blacklistExpr);
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
            updateTankSubscription();
        }
    }

    // We can't call super to avoid the auto-pull toggle...

    private class ExportOnlyAEStockingFluidList extends ExportOnlyAEFluidList {

        public ExportOnlyAEStockingFluidList(MetaMachine holder, int slots) {
            super(holder, slots,
                    () -> MEEnlargedTagStockingInputHatchPartMachine.this.new ExportOnlyAETagStockingFluidSlot());
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
            return checkExternal && MEEnlargedTagStockingInputHatchPartMachine.this.testConfiguredInOtherPart(stack);
        }

        public void fireContentsChanged() {
            super.onContentsChanged();
        }
    }

    private class ExportOnlyAETagStockingFluidSlot extends ExportOnlyAEFluidSlot {

        public ExportOnlyAETagStockingFluidSlot() {
            super();
        }

        public ExportOnlyAETagStockingFluidSlot(@Nullable GenericStack config, @Nullable GenericStack stock) {
            super(config, stock);
        }

        @Override
        public ExportOnlyAEFluidSlot copy() {
            return new MEEnlargedTagStockingInputHatchPartMachine.ExportOnlyAETagStockingFluidSlot(
                    this.config == null ? null : copy(this.config),
                    this.stock == null ? null : copy(this.stock));
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            if (this.stock != null && this.config != null) {
                if (!isOnline()) return FluidStack.EMPTY;
                MEStorage aeNetwork = getMainNode().getGrid().getStorageService().getInventory();

                Actionable actionable = action.simulate() ? Actionable.SIMULATE : Actionable.MODULATE;
                var key = config.what();
                long extracted = aeNetwork.extract(key, maxDrain, actionable, actionSource);

                if (extracted > 0) {
                    FluidStack resultStack = key instanceof AEFluidKey fluidKey ?
                            AEUtil.toFluidStack(fluidKey, extracted) : FluidStack.EMPTY;
                    if (action.execute()) {
                        this.stock = ExportOnlyAESlot.copy(stock, stock.amount() - extracted);
                        if (this.stock.amount() == 0) {
                            this.stock = null;
                        }
                        if (this.onContentsChanged != null) {
                            this.onContentsChanged.run();
                        }
                    }
                    return resultStack;
                }
            }
            return FluidStack.EMPTY;
        }
    }
}
