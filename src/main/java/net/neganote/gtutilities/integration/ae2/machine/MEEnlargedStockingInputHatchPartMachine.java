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
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.neganote.gtutilities.config.UtilConfig;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.Predicate;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class MEEnlargedStockingInputHatchPartMachine extends MEStockingHatchPartMachine {

    private static final int SLOTS_PER_ROW = 8;
    private static final int TOTAL_ROWS = Math.min(64, UtilConfig.INSTANCE.features.enlargedStockingSizeRows);
    private static final int TOTAL_SLOTS = SLOTS_PER_ROW * TOTAL_ROWS;

    @SaveField
    @SyncToClient
    private boolean enlargedAutoPull = false;
    private Predicate<GenericStack> enlargedAutoPullTest = $ -> false;

    public MEEnlargedStockingInputHatchPartMachine(BlockEntityCreationInfo holder) {
        super(holder);
        super.setAutoPull(false);
    }

    @Override
    protected NotifiableFluidTank createTank(int initialCapacity, int slots) {
        this.aeFluidHandler = new ExportOnlyAEStockingFluidList(this, TOTAL_SLOTS);
        return this.aeFluidHandler;
    }

    @Override
    public boolean isAutoPull() {
        return enlargedAutoPull;
    }

    @Override
    public void setAutoPull(boolean autoPull) {
        this.enlargedAutoPull = autoPull;

        if (!isRemote()) {
            if (!this.enlargedAutoPull) {
                this.aeFluidHandler.clearInventory(0);
            } else if (updateMEStatus()) {
                refreshListEnlarged();
                updateTankSubscription();
            }
        }

        super.setAutoPull(false);
    }

    @Override
    public void setAutoPullTest(Predicate<GenericStack> autoPullTest) {
        this.enlargedAutoPullTest = autoPullTest;
        super.setAutoPullTest(autoPullTest);
    }

    @Override
    public void autoIO() {
        super.autoIO();

        if (getOffsetTimer() % (long) getTicksPerCycle() == 0L) {
            if (!isRemote() && enlargedAutoPull) {
                if (updateMEStatus()) {
                    refreshListEnlarged();
                    super.syncME();
                    updateTankSubscription();
                }
            }
        }
    }

    private void refreshListEnlarged() {
        IGrid grid = this.getMainNode().getGrid();
        if (grid == null) {
            aeFluidHandler.clearInventory(0);
            return;
        }

        MEStorage networkStorage = grid.getStorageService().getInventory();
        var counter = networkStorage.getAvailableStacks();

        final int size = this.aeFluidHandler.getTanks();
        final int min = this.getMinStackSize();

        PriorityQueue<Object2LongMap.Entry<AEKey>> topFluids = new PriorityQueue<>(
                Comparator.comparingLong(Object2LongMap.Entry<AEKey>::getLongValue));

        for (Object2LongMap.Entry<AEKey> entry : counter) {
            long amount = entry.getLongValue();
            AEKey what = entry.getKey();

            if (amount <= 0) continue;
            if (!(what instanceof AEFluidKey fluidKey)) continue;

            long request = networkStorage.extract(what, amount, Actionable.SIMULATE, actionSource);
            if (request == 0) continue;

            if (enlargedAutoPullTest != null && !enlargedAutoPullTest.test(new GenericStack(fluidKey, amount)))
                continue;

            if (amount >= min) {
                if (topFluids.size() < size) {
                    topFluids.offer(entry);
                } else if (amount > topFluids.peek().getLongValue()) {
                    topFluids.poll();
                    topFluids.offer(entry);
                }
            }
        }

        int index;
        int fluidAmount = topFluids.size();
        for (index = 0; index < size; index++) {
            if (topFluids.isEmpty()) break;

            Object2LongMap.Entry<AEKey> entry = topFluids.poll();
            AEKey what = entry.getKey();
            long amount = entry.getLongValue();

            long request = networkStorage.extract(what, amount, Actionable.SIMULATE, actionSource);

            var slot = this.aeFluidHandler.getInventory()[fluidAmount - index - 1];
            slot.setConfig(new GenericStack(what, 1));
            slot.setStock(new GenericStack(what, request));
        }

        aeFluidHandler.clearInventory(index);
    }

    @Override
    protected CompoundTag writeConfigToTag() {
        if (!enlargedAutoPull) {
            CompoundTag tag = super.writeConfigToTag();
            tag.putBoolean("AutoPull", false);
            return tag;
        }
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("AutoPull", true);
        tag.putByte("GhostCircuit",
                (byte) 0);
        return tag;
    }

    @Override
    protected void readConfigFromTag(CompoundTag tag) {
        if (tag.getBoolean("AutoPull")) {
            setAutoPull(true);

            return;
        }
        setAutoPull(false);
        super.readConfigFromTag(tag);
    }

    private class ExportOnlyAEStockingFluidList extends ExportOnlyAEFluidList {

        public ExportOnlyAEStockingFluidList(MetaMachine holder, int slots) {
            super(holder, slots, ExportOnlyAEStockingFluidSlot::new);
        }

        @Override
        public boolean isStocking() {
            return true;
        }

        @Override
        public boolean isAutoPull() {
            return MEEnlargedStockingInputHatchPartMachine.this.isAutoPull();
        }

        @Override
        public boolean hasStackInConfig(GenericStack stack, boolean checkExternal) {
            boolean inThisBus = hasStackInternal(stack);
            if (inThisBus) return true;
            if (checkExternal) {
                return testConfiguredInOtherPart(stack);
            }
            return false;
        }

        private boolean hasStackInternal(GenericStack stack) {
            if (stack == null || stack.amount() <= 0) return false;
            for (int i = 0; i < MEEnlargedStockingInputHatchPartMachine.this.aeFluidHandler.getTanks(); i++) {
                var slot = MEEnlargedStockingInputHatchPartMachine.this.aeFluidHandler.getConfigurableSlot(i);
                GenericStack config = slot.getConfig();
                if (config != null && config.what().equals(stack.what())) {
                    return true;
                }
            }
            return false;
        }

        public void fireContentsChanged() {
            super.onContentsChanged();
        }
    }

    private class ExportOnlyAEStockingFluidSlot extends ExportOnlyAEFluidSlot {

        public ExportOnlyAEStockingFluidSlot() {
            super();
        }

        public ExportOnlyAEStockingFluidSlot(@Nullable GenericStack config, @Nullable GenericStack stock) {
            super(config, stock);
        }

        @Override
        public ExportOnlyAEFluidSlot copy() {
            return new ExportOnlyAEStockingFluidSlot(
                    this.config == null ? null : copy(this.config),
                    this.stock == null ? null : copy(this.stock));
        }

        @Override
        public FluidStack drain(int maxDrain, IFluidHandler.FluidAction action) {
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
                        if (this.stock.amount() == 0) this.stock = null;
                        if (this.onContentsChanged != null) this.onContentsChanged.run();
                    }
                    return resultStack;
                }
            }
            return FluidStack.EMPTY;
        }
    }
}
