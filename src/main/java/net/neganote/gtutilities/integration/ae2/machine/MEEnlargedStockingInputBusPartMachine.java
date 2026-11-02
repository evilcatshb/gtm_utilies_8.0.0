package net.neganote.gtutilities.integration.ae2.machine;

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField;
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient;
import com.gregtechceu.gtceu.common.item.behavior.IntCircuitBehaviour;
import com.gregtechceu.gtceu.integration.ae2.machine.MEStockingBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.neganote.gtutilities.config.UtilConfig;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEItemKey;
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
public class MEEnlargedStockingInputBusPartMachine extends MEStockingBusPartMachine {

    private static final int SLOTS_PER_ROW = 8;
    private static final int TOTAL_ROWS = Math.min(64, UtilConfig.INSTANCE.features.enlargedStockingSizeRows);
    private static final int TOTAL_SLOTS = SLOTS_PER_ROW * TOTAL_ROWS;

    @SaveField
    @SyncToClient
    private boolean enlargedAutoPull = false;

    private Predicate<GenericStack> enlargedAutoPullTest = $ -> false;

    public MEEnlargedStockingInputBusPartMachine(BlockEntityCreationInfo holder) {
        super(holder);
        super.setAutoPull(false);
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
                this.aeItemHandler.clearInventory(0);
            } else if (updateMEStatus()) {
                refreshListEnlarged();
                updateInventorySubscription();
            }
        }

        super.setAutoPull(false);
    }

    @Override
    public void autoIO() {
        super.autoIO();

        if (getTicksPerCycle() == 0) {}

        if (getOffsetTimer() % (long) getTicksPerCycle() == 0L) {
            if (!isRemote() && enlargedAutoPull) {
                if (updateMEStatus()) {
                    refreshListEnlarged();
                    super.syncME();
                    updateInventorySubscription();
                }
            }
        }
    }

    private void refreshListEnlarged() {
        IGrid grid = this.getMainNode().getGrid();
        if (grid == null) {
            aeItemHandler.clearInventory(0);
            return;
        }

        MEStorage networkStorage = grid.getStorageService().getInventory();
        var counter = networkStorage.getAvailableStacks();

        final int size = this.aeItemHandler.getSlots();
        final int min = this.getMinStackSize();

        PriorityQueue<Object2LongMap.Entry<AEKey>> topItems = new PriorityQueue<>(
                Comparator.comparingLong(Object2LongMap.Entry<AEKey>::getLongValue));

        for (Object2LongMap.Entry<AEKey> entry : counter) {
            long amount = entry.getLongValue();
            AEKey what = entry.getKey();

            if (amount <= 0) continue;
            if (!(what instanceof AEItemKey itemKey)) continue;

            long request = networkStorage.extract(what, amount, Actionable.SIMULATE, actionSource);
            if (request == 0) continue;

            if (enlargedAutoPullTest != null && !enlargedAutoPullTest.test(new GenericStack(itemKey, amount))) continue;

            if (amount >= min) {
                if (topItems.size() < size) {
                    topItems.offer(entry);
                } else if (amount > topItems.peek().getLongValue()) {
                    topItems.poll();
                    topItems.offer(entry);
                }
            }
        }

        int index;
        int itemAmount = topItems.size();
        for (index = 0; index < size; index++) {
            if (topItems.isEmpty()) break;

            Object2LongMap.Entry<AEKey> entry = topItems.poll();
            AEKey what = entry.getKey();
            long amount = entry.getLongValue();

            long request = networkStorage.extract(what, amount, Actionable.SIMULATE, actionSource);

            ExportOnlyAEItemSlot slot = this.aeItemHandler.getInventory()[itemAmount - index - 1];
            slot.setConfig(new GenericStack(what, 1));
            slot.setStock(new GenericStack(what, request));
        }

        aeItemHandler.clearInventory(index);
    }

    @Override
    public void setAutoPullTest(Predicate<GenericStack> autoPullTest) {
        this.enlargedAutoPullTest = autoPullTest;
        super.setAutoPullTest(autoPullTest);
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
                (byte) IntCircuitBehaviour.getCircuitConfiguration(getCircuitSlot().storage.getStackInSlot(0)));
        return tag;
    }

    @Override
    protected void readConfigFromTag(CompoundTag tag) {
        if (tag.getBoolean("AutoPull")) {
            setAutoPull(true);
            getCircuitSlot().storage.setStackInSlot(0, IntCircuitBehaviour.stack(tag.getByte("GhostCircuit")));
            return;
        }
        setAutoPull(false);
        super.readConfigFromTag(tag);
    }

    private class ExportOnlyAEStockingItemList extends ExportOnlyAEItemList {

        public ExportOnlyAEStockingItemList(MetaMachine holder, int slots) {
            super(slots, ExportOnlyAEStockingItemSlot::new);
        }

        @Override
        public boolean isStocking() {
            return true;
        }

        @Override
        public boolean isAutoPull() {
            return MEEnlargedStockingInputBusPartMachine.this.isAutoPull();
        }

        public void fireContentsChanged() {
            super.onContentsChanged();
        }
    }

    private class ExportOnlyAEStockingItemSlot extends ExportOnlyAEItemSlot {

        public ExportOnlyAEStockingItemSlot() {
            super();
        }

        public ExportOnlyAEStockingItemSlot(@Nullable GenericStack config, @Nullable GenericStack stock) {
            super(config, stock);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot == 0 && this.stock != null) {
                if (this.config != null) {
                    if (!isOnline()) return ItemStack.EMPTY;
                    MEStorage aeNetwork = getMainNode().getGrid().getStorageService().getInventory();

                    Actionable action = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
                    var key = config.what();
                    long extracted = aeNetwork.extract(key, amount, action, actionSource);

                    if (extracted > 0) {
                        ItemStack resultStack = key instanceof AEItemKey itemKey ? itemKey.toStack((int) extracted) :
                                ItemStack.EMPTY;

                        if (!simulate) {
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
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ExportOnlyAEStockingItemSlot copy() {
            return new ExportOnlyAEStockingItemSlot(
                    this.config == null ? null : copy(this.config),
                    this.stock == null ? null : copy(this.stock));
        }
    }
}
