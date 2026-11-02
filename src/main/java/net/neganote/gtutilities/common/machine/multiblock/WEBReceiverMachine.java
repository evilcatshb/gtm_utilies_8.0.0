package net.neganote.gtutilities.common.machine.multiblock;

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo;
import com.gregtechceu.gtceu.api.capability.IControllable;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.recipe.RecipeLogic;
import com.gregtechceu.gtceu.api.misc.EnergyContainerList;
import com.gregtechceu.gtceu.api.multiblock.MultiPredicate;
import com.gregtechceu.gtceu.api.multiblock.error.PatternStringError;
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField;
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.gregtechceu.gtceu.utils.FormattingUtil;
import com.gregtechceu.gtceu.utils.GTUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.neganote.gtutilities.utils.EnergyUtils;

import brachy.modularui.api.widget.IWidget;
import brachy.modularui.value.sync.PanelSyncManager;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.gregtechceu.gtceu.api.multiblock.Predicates.abilities;
import static net.neganote.gtutilities.common.machine.multiblock.WEBHubMachine.*;

// A lot of this is copied from the Active Transformer
public class WEBReceiverMachine extends WorkableElectricMultiblockMachine
                                implements IControllable {

    private List<MultiblockPartMachine> localPowerOutput;

    @SaveField
    @SyncToClient
    @Getter
    private int frequency;

    public WEBReceiverMachine(BlockEntityCreationInfo holder) {
        super(holder);
        this.localPowerOutput = new ArrayList<>();
        this.frequency = 0;
    }

    public void explode() {
        removeWirelessEnergy();

        long outputVoltage = 0;

        if (!localPowerOutput.isEmpty()) {
            EnergyContainerList localOutputs = EnergyUtils.getEnergyListFromMultiParts(localPowerOutput);
            outputVoltage = localOutputs.getOutputVoltage();
        }

        long tier = GTUtil.getFloorTierByVoltage(outputVoltage);

        getLevel().explode(null, self().getBlockPos().getX() + 0.5, self().getBlockPos().getY() + 0.5,
                self().getBlockPos().getZ() + 0.5, 15f + tier, true,
                net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
    }

    @Override
    public boolean onWorking() {
        return super.onWorking();
    }

    @Override
    public void formStructure(String structureName) {
        super.formStructure(structureName);

        if (frequency == 0) {
            setWorkingEnabled(false);
        }

        List<MultiblockPartMachine> localPowerOutput = new ArrayList<>();

        for (MultiblockPartMachine part : getPrioritySortedParts()) {
            for (var handlerList : part.getRecipeHandlers()) {
                var handlerIO = handlerList.getHandlerIO();
                var energyContainers = handlerList.getCapability(EURecipeCapability.CAP).stream()
                        .filter(IEnergyContainer.class::isInstance)
                        .map(IEnergyContainer.class::cast)
                        .toList();
                if (!energyContainers.isEmpty() && handlerIO == IO.OUT) {
                    localPowerOutput.add(part);
                }
            }
        }

        if (localPowerOutput.isEmpty()) {
            invalidateStructure(structureName);
            getDefaultPatternState().setError(PatternStringError.literal("No suitable energy part found"));
            return;
        }

        this.localPowerOutput = localPowerOutput;

        if (frequency != 0 && isWorkingEnabled()) {
            addWirelessEnergy();
        }
    }

    @NotNull
    private List<MultiblockPartMachine> getPrioritySortedParts() {
        return getParts().stream().sorted(Comparator.comparing(part -> {
            Block partBlock = part.getBlockState().getBlock();
            if (PartAbility.OUTPUT_ENERGY.isApplicable(partBlock)) return 1;
            if (PartAbility.SUBSTATION_OUTPUT_ENERGY.isApplicable(partBlock)) return 2;
            if (PartAbility.OUTPUT_LASER.isApplicable(partBlock)) return 3;
            return 4;
        })).toList();
    }

    @Override
    public void invalidateStructure(String structureName) {
        removeWirelessEnergy();
        if ((isWorkingEnabled() && recipeLogic.getStatus() == RecipeLogic.Status.WORKING) &&
                !ConfigHolder.INSTANCE.machines.harmlessActiveTransformers) {
            explode();
        }
        super.invalidateStructure(structureName);
        this.localPowerOutput = new ArrayList<>();
        setWorkingEnabled(false);
    }

    private void removeWirelessEnergy() {
        removeEnergyOutputs(frequency, localPowerOutput);
    }

    private void addWirelessEnergy() {
        addEnergyOutputs(frequency, localPowerOutput);
    }

    public static MultiPredicate getHatchPredicates() {
        return abilities(PartAbility.OUTPUT_ENERGY).setPreviewCount(2)
                .or(abilities(PartAbility.SUBSTATION_OUTPUT_ENERGY).setPreviewCount(1))
                .or(abilities(PartAbility.OUTPUT_LASER).setPreviewCount(1));
    }

    @Override
    public List<IWidget> getWidgetsForDisplay(PanelSyncManager syncManager) {
        List<IWidget> widgets = super.getWidgetsForDisplay(syncManager);
        if (frequency == 0) {
            widgets.add(new brachy.modularui.widgets.TextWidget<>(
                    () -> Component.translatable("gtmutils.web_machines.invalid_frequency")
                            .withStyle(ChatFormatting.RED)));
            return widgets;
        }
        if (!isWorkingEnabled()) {
            widgets.add(new brachy.modularui.widgets.TextWidget<>(
                    () -> Component.translatable("gtceu.multiblock.work_paused")));
        } else if (isActive()) {
            long outputAmperage = 0;
            long outputVoltage = 0;
            if (!localPowerOutput.isEmpty()) {
                EnergyContainerList localOutputs = EnergyUtils.getEnergyListFromMultiParts(localPowerOutput);
                outputAmperage = localOutputs.getOutputAmperage();
                outputVoltage = localOutputs.getOutputVoltage();
            }
            final long outputTotal = outputVoltage * outputAmperage;
            widgets.add(new brachy.modularui.widgets.TextWidget<>(
                    () -> Component.translatable("gtceu.multiblock.running")));
            if (outputTotal > 0) {
                widgets.add(new brachy.modularui.widgets.TextWidget<>(
                        () -> Component.translatable("gtceu.multiblock.active_transformer.max_output",
                                FormattingUtil.formatNumbers(Math.abs(outputTotal)))));
            }
            if (!ConfigHolder.INSTANCE.machines.harmlessActiveTransformers) {
                widgets.add(new brachy.modularui.widgets.TextWidget<>(
                        () -> Component.translatable("gtceu.multiblock.active_transformer.danger_enabled")));
            }
        } else {
            widgets.add(new brachy.modularui.widgets.TextWidget<>(
                    () -> Component.translatable("gtceu.multiblock.idling")));
        }
        return widgets;
    }

    public void setFrequencyFromString(String str) {
        removeWirelessEnergy();
        frequency = Integer.parseInt(str);
        if (frequency == 0) {
            setWorkingEnabled(false);
        }
        if (frequency != 0) {
            addWirelessEnergy();
        }
    }

    public String getFrequencyString() {
        return Integer.toString(frequency);
    }

    @Override
    public void setWorkingEnabled(boolean isWorkingAllowed) {
        if (frequency == 0) {
            super.setWorkingEnabled(false);
            return;
        }
        super.setWorkingEnabled(isWorkingAllowed);
        if (frequency != 0) {
            if (isWorkingAllowed) {
                addWirelessEnergy();
            } else {
                removeWirelessEnergy();
            }
        }
    }
}
