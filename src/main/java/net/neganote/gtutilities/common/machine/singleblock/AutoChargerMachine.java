package net.neganote.gtutilities.common.machine.singleblock;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo;
import com.gregtechceu.gtceu.api.capability.*;
import com.gregtechceu.gtceu.api.capability.compat.FeCompat;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.TieredEnergyMachine;
import com.gregtechceu.gtceu.api.machine.property.GTMachineModelProperties;
import com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableEnergyContainer;
import com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.sync_system.annotations.RerenderOnChanged;
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField;
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient;
import com.gregtechceu.gtceu.common.machine.electric.BatteryBufferMachine;
import com.gregtechceu.gtceu.common.mui.GTGuiTextures;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.gregtechceu.gtceu.utils.GTTransferUtils;
import com.gregtechceu.gtceu.utils.GTUtil;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.ItemHandlerHelper;

import brachy.modularui.factory.PosGuiData;
import brachy.modularui.screen.UISettings;
import brachy.modularui.value.sync.PanelSyncManager;
import brachy.modularui.widget.ParentWidget;
import brachy.modularui.widgets.layout.Flow;
import brachy.modularui.widgets.slot.ItemSlot;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class AutoChargerMachine extends TieredEnergyMachine
                                implements com.gregtechceu.gtceu.api.machine.feature.IMuiMachine {

    public static final long AMPS_PER_ITEM = 4L;
    private final int inventorySize;

    @SaveField
    private boolean isWorkingEnabled = true;

    @SaveField
    protected final NotifiableItemStackHandler chargerInventory;

    @SyncToClient
    @RerenderOnChanged
    private BatteryBufferMachine.State state = BatteryBufferMachine.State.IDLE;

    @SaveField
    @SyncToClient
    @RerenderOnChanged
    protected Direction outputFacingItems;

    @SaveField
    @SyncToClient
    @RerenderOnChanged
    protected boolean autoOutputItems;

    @SaveField
    protected boolean allowInputFromOutputSideItems;

    @Nullable
    protected TickableSubscription autoOutputSubs;

    public AutoChargerMachine(BlockEntityCreationInfo holder, int tier, int inventorySize) {
        super(holder, tier, new EnergyBatteryTrait(tier, inventorySize));

        this.chargerInventory = new NotifiableItemStackHandler(inventorySize, IO.BOTH, IO.BOTH);
        this.chargerInventory.setFilter(stack -> GTCapabilityHelper.getElectricItem(stack) != null ||
                (ConfigHolder.INSTANCE.compat.energy.nativeEUToFE &&
                        GTCapabilityHelper.getForgeEnergyItem(stack) != null));
        this.inventorySize = inventorySize;
        this.outputFacingItems = getFrontFacing().getOpposite();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (!isRemote()) updateAutoOutputSubscription();
    }

    @Override
    public void onMachineDestroyed() {
        for (int i = 0; i < chargerInventory.getSlots(); i++) {
            chargerInventory.setStackInSlot(i, ItemStack.EMPTY);
        }
        super.onMachineDestroyed();
    }

    protected void updateAutoOutputSubscription() {
        if (autoOutputItems && !chargerInventory.isEmpty()) {
            autoOutputSubs = subscribeServerTick(autoOutputSubs, this::autoOutput);
        } else if (autoOutputSubs != null) {
            autoOutputSubs.unsubscribe();
            autoOutputSubs = null;
        }
    }

    protected void autoOutput() {
        if (getOffsetTimer() % 5 == 0 && autoOutputItems) {
            boolean exported = false;
            for (int i = 0; i < chargerInventory.getSlots(); i++) {
                ItemStack stack = chargerInventory.getStackInSlot(i);
                if (stack.isEmpty() || !isFullyCharged(stack)) continue;
                final int slotIndex = i;
                var handlerCap = GTTransferUtils.getItemHandler(getLevel(),
                        self().getBlockPos().relative(outputFacingItems),
                        outputFacingItems.getOpposite());
                handlerCap.ifPresent(handler -> {
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(handler, stack, false);
                    chargerInventory.setStackInSlot(slotIndex, remainder);
                });
                if (chargerInventory.getStackInSlot(slotIndex).getCount() != stack.getCount()) exported = true;
            }
            if (exported) self().setChanged();
        }
    }

    private boolean isFullyCharged(ItemStack stack) {
        var elec = GTCapabilityHelper.getElectricItem(stack);
        if (elec != null) {
            return elec.getCharge() >= elec.getMaxCharge();
        }
        var fe = GTCapabilityHelper.getForgeEnergyItem(stack);
        if (fe != null) {
            return fe.getEnergyStored() >= fe.getMaxEnergyStored();
        }
        return false;
    }

    public boolean isAutoOutputItems() {
        return this.autoOutputItems;
    }

    public void setAutoOutputItems(boolean allow) {
        this.autoOutputItems = allow;
        updateAutoOutputSubscription();
    }

    public boolean isAllowInputFromOutputSideItems() {
        return this.allowInputFromOutputSideItems;
    }

    public void setAllowInputFromOutputSideItems(boolean b) {
        this.allowInputFromOutputSideItems = b;
    }

    public Direction getOutputFacingItems() {
        return outputFacingItems;
    }

    public void setOutputFacingItems(@Nullable Direction direction) {
        this.outputFacingItems = direction == null ? getFrontFacing().getOpposite() : direction;
        updateAutoOutputSubscription();
    }

    @Override
    public void buildMainUI(ParentWidget<?> parent, PosGuiData guiData, PanelSyncManager syncManager,
                            UISettings settings) {
        com.gregtechceu.gtceu.api.machine.feature.IMuiMachine.super.buildMainUI(parent, guiData, syncManager, settings);

        int rowSize = (int) Math.sqrt(inventorySize);
        int colSize = rowSize;
        if (inventorySize == 8) {
            rowSize = 4;
            colSize = 2;
        }

        var grid = Flow.column().coverChildren();
        for (int y = 0; y < colSize; y++) {
            var row = Flow.row().coverChildren();
            for (int x = 0; x < rowSize; x++) {
                int index = y * rowSize + x;
                row.addChild(new ItemSlot()
                        .slot(chargerInventory, index)
                        .background(GTGuiTextures.SLOT, GTGuiTextures.CHARGER_OVERLAY), -1);
            }
            grid.addChild(row, -1);
        }

        parent.addChild(grid, -1);
    }

    protected static class EnergyBatteryTrait extends NotifiableEnergyContainer {

        protected EnergyBatteryTrait(int tier, int invSize) {
            super(GTValues.V[tier] * invSize * 32L, GTValues.V[tier], invSize * AMPS_PER_ITEM, 0L, 0L);
            setSideInputCondition(side -> ((AutoChargerMachine) getMachine()).isWorkingEnabled);
            setSideOutputCondition(side -> false);
        }

        private AutoChargerMachine owner() {
            return (AutoChargerMachine) getMachine();
        }

        private List<Object> getNonFullElectricItem() {
            List<Object> electricItems = new ArrayList<>();
            for (int i = 0; i < owner().chargerInventory.getSlots(); i++) {
                var electricItemStack = owner().chargerInventory.getStackInSlot(i);
                var electricItem = GTCapabilityHelper.getElectricItem(electricItemStack);
                if (electricItem != null) {
                    if (electricItem.getCharge() < electricItem.getMaxCharge()) {
                        electricItems.add(electricItem);
                    }
                } else if (ConfigHolder.INSTANCE.compat.energy.nativeEUToFE) {
                    var energyStorage = GTCapabilityHelper.getForgeEnergyItem(electricItemStack);
                    if (energyStorage != null) {
                        if (energyStorage.getEnergyStored() < energyStorage.getMaxEnergyStored()) {
                            electricItems.add(energyStorage);
                        }
                    }
                }
            }
            return electricItems;
        }

        private void changeState(BatteryBufferMachine.State newState) {
            if (owner().state != newState) {
                owner().state = newState;
                owner().setRenderState(
                        owner().getRenderState().setValue(GTMachineModelProperties.CHARGER_STATE, newState));
            }
        }

        @Override
        public long acceptEnergyFromNetwork(@Nullable Direction side, long voltage, long amperage) {
            var latestTimeStamp = getMachine().getOffsetTimer();
            if (lastTimeStamp < latestTimeStamp) {
                amps = 0;
                lastTimeStamp = latestTimeStamp;
            }
            if (amperage <= 0 || voltage <= 0) {
                changeState(BatteryBufferMachine.State.IDLE);
                return 0;
            }

            var electricItems = getNonFullElectricItem();
            var maxAmps = electricItems.size() * AMPS_PER_ITEM - amps;
            var usedAmps = Math.min(maxAmps, amperage);
            if (maxAmps <= 0) {
                return 0;
            }

            if (side == null || inputsEnergy(side)) {
                if (voltage > getInputVoltage()) {
                    getLevel().explode(null, getBlockPos().getX() + 0.5, getBlockPos().getY() + 0.5,
                            getBlockPos().getZ() + 0.5, GTUtil.getExplosionPower(voltage),
                            net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
                    return usedAmps;
                }

                long internalAmps = Math.min(maxAmps, Math.max(0, getInternalStorage() / voltage));

                usedAmps = Math.min(usedAmps, maxAmps - internalAmps);
                amps += usedAmps;

                long energy = (usedAmps + internalAmps) * voltage;
                long distributed = energy / electricItems.size();

                boolean changed = false;
                for (var electricItem : electricItems) {
                    long charged = 0;
                    if (electricItem instanceof IElectricItem item) {
                        charged = item.charge(Math.min(distributed, GTValues.V[item.getTier()] * AMPS_PER_ITEM),
                                owner().getTier(), true, false);
                    } else if (electricItem instanceof IEnergyStorage energyStorage) {
                        charged = FeCompat.insertEu(energyStorage,
                                Math.min(distributed, GTValues.V[owner().getTier()] * AMPS_PER_ITEM), false);
                    }
                    if (charged > 0) {
                        changed = true;
                    }
                    energy -= charged;
                    energyInputPerSec += charged;
                }

                if (changed) {
                    owner().self().setChanged();
                    changeState(BatteryBufferMachine.State.RUNNING);
                }

                setEnergyStored(getInternalStorage() - internalAmps * voltage + energy);
                return usedAmps;
            }
            return 0;
        }

        @Override
        public long getEnergyCapacity() {
            long energyCapacity = 0L;
            for (int i = 0; i < owner().chargerInventory.getSlots(); i++) {
                var electricItemStack = owner().chargerInventory.getStackInSlot(i);
                var electricItem = GTCapabilityHelper.getElectricItem(electricItemStack);
                if (electricItem != null) {
                    energyCapacity += electricItem.getMaxCharge();
                } else if (ConfigHolder.INSTANCE.compat.energy.nativeEUToFE) {
                    var energyStorage = GTCapabilityHelper.getForgeEnergyItem(electricItemStack);
                    if (energyStorage != null) {
                        energyCapacity += FeCompat.toEu(energyStorage.getMaxEnergyStored(),
                                FeCompat.ratio(false));
                    }
                }
            }

            if (energyCapacity == 0) {
                changeState(BatteryBufferMachine.State.IDLE);
            }

            return energyCapacity;
        }

        @Override
        public long getEnergyStored() {
            long energyStored = 0L;
            for (int i = 0; i < owner().chargerInventory.getSlots(); i++) {
                var electricItemStack = owner().chargerInventory.getStackInSlot(i);
                var electricItem = GTCapabilityHelper.getElectricItem(electricItemStack);
                if (electricItem != null) {
                    energyStored += electricItem.getCharge();
                } else if (ConfigHolder.INSTANCE.compat.energy.nativeEUToFE) {
                    var energyStorage = GTCapabilityHelper.getForgeEnergyItem(electricItemStack);
                    if (energyStorage != null) {
                        energyStored += FeCompat.toEu(energyStorage.getEnergyStored(),
                                FeCompat.ratio(false));
                    }
                }
            }

            var capacity = getEnergyCapacity();
            if (capacity != 0 && capacity == energyStored) {
                changeState(BatteryBufferMachine.State.FINISHED);
            }

            return energyStored;
        }

        private long getInternalStorage() {
            return energyStored;
        }
    }
}
