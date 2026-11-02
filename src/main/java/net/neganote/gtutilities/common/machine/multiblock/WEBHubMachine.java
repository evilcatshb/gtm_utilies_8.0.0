package net.neganote.gtutilities.common.machine.multiblock;

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo;
import com.gregtechceu.gtceu.api.capability.IControllable;
import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.ConditionalSubscriptionHandler;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.machine.trait.recipe.RecipeLogic;
import com.gregtechceu.gtceu.api.misc.EnergyContainerList;
import com.gregtechceu.gtceu.api.multiblock.MultiPredicate;
import com.gregtechceu.gtceu.api.multiblock.error.PatternStringError;
import com.gregtechceu.gtceu.api.recipe.ingredient.FluidIngredient;
import com.gregtechceu.gtceu.api.sync_system.annotations.SaveField;
import com.gregtechceu.gtceu.api.sync_system.annotations.SyncToClient;
import com.gregtechceu.gtceu.common.data.GTRecipeCapabilities;
import com.gregtechceu.gtceu.common.machine.multiblock.part.EnergyHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.LaserHatchPartMachine;
import com.gregtechceu.gtceu.config.ConfigHolder;
import com.gregtechceu.gtceu.utils.FormattingUtil;
import com.gregtechceu.gtceu.utils.GTUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neganote.gtutilities.common.materials.UtilMaterials;
import net.neganote.gtutilities.config.UtilConfig;
import net.neganote.gtutilities.utils.EnergyUtils;

import brachy.modularui.api.widget.IWidget;
import brachy.modularui.value.sync.PanelSyncManager;
import com.mojang.datafixers.util.Pair;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.gregtechceu.gtceu.api.multiblock.Predicates.abilities;

// A lot of this is copied from the Active Transformer
public class WEBHubMachine extends WorkableElectricMultiblockMachine
                           implements IControllable {

    public static Map<Integer, Set<Pair<ResourceLocation, BlockPos>>> ENERGY_INPUTS = new HashMap<>();
    public static Map<Integer, Set<Pair<ResourceLocation, BlockPos>>> ENERGY_OUTPUTS = new HashMap<>();

    public static void addEnergyInputs(int freq, List<MultiblockPartMachine> parts) {
        Set<Pair<ResourceLocation, BlockPos>> inputPairs = ENERGY_INPUTS.computeIfAbsent(freq,
                (f) -> new HashSet<>());
        for (MultiblockPartMachine part : parts) {
            ServerLevel level = (ServerLevel) part.getLevel();
            assert level != null;
            ResourceLocation dimension = level.dimension().location();
            BlockPos pos = part.self().getBlockPos();
            inputPairs.add(new Pair<>(dimension, pos));
        }
        ENERGY_INPUTS.put(freq, inputPairs);
    }

    public static void removeEnergyInputs(int freq, List<MultiblockPartMachine> parts) {
        Set<Pair<ResourceLocation, BlockPos>> inputPairs = ENERGY_INPUTS.computeIfAbsent(freq,
                (f) -> new HashSet<>());
        for (MultiblockPartMachine part : parts) {
            ServerLevel level = (ServerLevel) part.getLevel();
            assert level != null;
            ResourceLocation dimension = level.dimension().location();
            BlockPos pos = part.self().getBlockPos();
            inputPairs.remove(new Pair<>(dimension, pos));
        }
        ENERGY_INPUTS.put(freq, inputPairs);
    }

    public static void addEnergyOutputs(int freq, List<MultiblockPartMachine> parts) {
        Set<Pair<ResourceLocation, BlockPos>> outputPairs = ENERGY_OUTPUTS.computeIfAbsent(freq,
                (f) -> new HashSet<>());
        for (MultiblockPartMachine part : parts) {
            ServerLevel level = (ServerLevel) part.getLevel();
            assert level != null;
            ResourceLocation dimension = level.dimension().location();
            BlockPos pos = part.self().getBlockPos();
            outputPairs.add(new Pair<>(dimension, pos));
        }
        ENERGY_OUTPUTS.put(freq, outputPairs);
    }

    public static void removeEnergyOutputs(int freq, List<MultiblockPartMachine> parts) {
        Set<Pair<ResourceLocation, BlockPos>> outputPairs = ENERGY_OUTPUTS.computeIfAbsent(freq,
                (f) -> new HashSet<>());
        for (MultiblockPartMachine part : parts) {
            ServerLevel level = (ServerLevel) part.getLevel();
            assert level != null;
            ResourceLocation dimension = level.dimension().location();
            BlockPos pos = part.self().getBlockPos();
            outputPairs.remove(new Pair<>(dimension, pos));
        }
        ENERGY_OUTPUTS.put(freq, outputPairs);
    }

    public static EnergyContainerList getWirelessEnergyInputs(int freq, ServerLevel serverLevel) {
        Set<Pair<ResourceLocation, BlockPos>> inputPairs = ENERGY_INPUTS.computeIfAbsent(freq,
                (f) -> new HashSet<>());
        List<IEnergyContainer> energyContainerList = new ArrayList<>();
        for (Pair<ResourceLocation, BlockPos> pair : inputPairs) {
            ServerLevel dimension = serverLevel.getServer()
                    .getLevel(ResourceKey.create(Registries.DIMENSION, pair.getFirst()));
            if (dimension != null) {
                MetaMachine machine = MetaMachine.getMachine(dimension, pair.getSecond());
                if (machine instanceof EnergyHatchPartMachine hatch) {
                    energyContainerList.add(hatch.energyContainer);
                }
                if (machine instanceof LaserHatchPartMachine hatch) {
                    for (var handlerList : hatch.getRecipeHandlers()) {
                        var containers = handlerList.getCapability(EURecipeCapability.CAP).stream()
                                .filter(IEnergyContainer.class::isInstance)
                                .map(IEnergyContainer.class::cast)
                                .toList();
                        energyContainerList.addAll(containers);
                    }
                }
            }
        }
        return new EnergyContainerList(energyContainerList);
    }

    public static EnergyContainerList getWirelessEnergyOutputs(int freq, ServerLevel serverLevel) {
        Set<Pair<ResourceLocation, BlockPos>> outputPairs = ENERGY_OUTPUTS.computeIfAbsent(freq,
                (f) -> new HashSet<>());
        List<IEnergyContainer> energyContainerList = new ArrayList<>();
        for (Pair<ResourceLocation, BlockPos> pair : outputPairs) {
            ServerLevel dimension = serverLevel.getServer()
                    .getLevel(ResourceKey.create(Registries.DIMENSION, pair.getFirst()));
            if (dimension != null) {
                MetaMachine machine = MetaMachine.getMachine(dimension, pair.getSecond());
                if (machine instanceof EnergyHatchPartMachine hatch) {
                    energyContainerList.add(hatch.energyContainer);
                }
                if (machine instanceof LaserHatchPartMachine hatch) {
                    for (var handlerList : hatch.getRecipeHandlers()) {
                        var containers = handlerList.getCapability(EURecipeCapability.CAP).stream()
                                .filter(IEnergyContainer.class::isInstance)
                                .map(IEnergyContainer.class::cast)
                                .toList();
                        energyContainerList.addAll(containers);
                    }
                }
            }
        }
        return new EnergyContainerList(energyContainerList);
    }

    private List<MultiblockPartMachine> localPowerInput;

    protected ConditionalSubscriptionHandler converterSubscription;

    @Getter
    private int coolantDrain;

    @SaveField
    @SyncToClient
    @Getter
    private int frequency;

    @SaveField
    @SyncToClient
    private int coolantTimer = 0;

    public WEBHubMachine(BlockEntityCreationInfo holder) {
        super(holder);
        this.localPowerInput = new ArrayList<>();

        this.converterSubscription = new ConditionalSubscriptionHandler(this, this::convertEnergyTick,
                this::isSubscriptionActive);

        this.frequency = 0;
    }

    public void explode() {
        removeWirelessEnergy();

        long inputVoltage = 0;

        if (!localPowerInput.isEmpty()) {
            EnergyContainerList localInputs = EnergyUtils.getEnergyListFromMultiParts(localPowerInput);
            inputVoltage = localInputs.getInputVoltage();
        }

        long tier = GTUtil.getFloorTierByVoltage(inputVoltage);

        getLevel().explode(null, self().getBlockPos().getX() + 0.5, self().getBlockPos().getY() + 0.5,
                self().getBlockPos().getZ() + 0.5, 15f + tier, true,
                net.minecraft.world.level.Level.ExplosionInteraction.BLOCK);
    }

    public void convertEnergyTick() {
        if (frequency == 0) {
            getRecipeLogic().setStatus(RecipeLogic.Status.SUSPEND);
            return;
        }
        if (isWorkingEnabled()) {
            getRecipeLogic()
                    .setStatus(isSubscriptionActive() ? RecipeLogic.Status.WORKING : RecipeLogic.Status.SUSPEND);
        }
        if (isWorkingEnabled() && getRecipeLogic().getStatus() == RecipeLogic.Status.WORKING &&
                UtilConfig.coolantEnabled() && coolantTimer == 0 && frequency != 0) {

            var coolantTanks = getCapabilitiesFlat(IO.IN, GTRecipeCapabilities.FLUID).stream()
                    .map(NotifiableFluidTank.class::cast).toList();

            List<FluidIngredient> left = List
                    .of(FluidIngredient.of(UtilMaterials.QuantumCoolant.getFluid(), coolantDrain));

            for (var tank : coolantTanks) {
                left = tank.handleRecipe(IO.IN, null, left, false);
                if (left == null) {
                    break;
                }
            }

            if (left != null && !left.isEmpty()) {
                if (!ConfigHolder.INSTANCE.machines.harmlessActiveTransformers) {
                    explode();
                } else {
                    coolantTimer = 0;
                    getRecipeLogic().setStatus(RecipeLogic.Status.SUSPEND);
                    converterSubscription.updateSubscription();
                    return;
                }
            }
        }
        if (isWorkingEnabled() && getRecipeLogic().getStatus() == RecipeLogic.Status.WORKING) {
            coolantTimer = (coolantTimer + 1) % 20;
        }
        if (isWorkingEnabled() && frequency != 0) {
            if (getLevel() instanceof ServerLevel serverLevel) {
                EnergyContainerList powerInput = getWirelessEnergyInputs(frequency, serverLevel);
                EnergyContainerList powerOutput = getWirelessEnergyOutputs(frequency, serverLevel);
                long canDrain = powerInput.getEnergyStored();
                long totalDrained = powerOutput.changeEnergy(canDrain);
                powerInput.removeEnergy(totalDrained);
            }
        }
        converterSubscription.updateSubscription();
    }

    private int calculateCoolantDrain() {
        long inputAmperage = 0;
        long inputVoltage = 0;

        if (!localPowerInput.isEmpty()) {
            EnergyContainerList localInputs = EnergyUtils.getEnergyListFromMultiParts(localPowerInput);
            inputAmperage = localInputs.getInputAmperage();
            inputVoltage = localInputs.getInputVoltage();
        }

        long scalingFactor = inputAmperage * inputVoltage;

        int coolantDrain = (int) (UtilConfig.INSTANCE.features.webCoolantBaseDrain +
                UtilConfig.INSTANCE.features.webCoolantIOMultiplier * (Math.log(scalingFactor) / Math.log(2.0)));
        if (coolantDrain <= 0) {
            coolantDrain = 1;
        }
        return coolantDrain;
    }

    @SuppressWarnings("RedundantIfStatement")
    protected boolean isSubscriptionActive() {
        if (!isFormed()) return false;
        if (localPowerInput == null) return false;
        return true;
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

        List<MultiblockPartMachine> localPowerInput = new ArrayList<>();

        for (MultiblockPartMachine part : getPrioritySortedParts()) {
            for (var handlerList : part.getRecipeHandlers()) {
                var handlerIO = handlerList.getHandlerIO();
                var energyContainers = handlerList.getCapability(EURecipeCapability.CAP).stream()
                        .filter(IEnergyContainer.class::isInstance)
                        .map(IEnergyContainer.class::cast)
                        .toList();
                if (!energyContainers.isEmpty() && handlerIO == IO.IN) {
                    localPowerInput.add(part);
                }
            }
        }

        if (localPowerInput.isEmpty()) {
            invalidateStructure(structureName);
            getDefaultPatternState().setError(PatternStringError.literal("No suitable energy part found"));
            return;
        }

        this.localPowerInput = localPowerInput;
        this.coolantDrain = calculateCoolantDrain();

        if (frequency != 0 && isWorkingEnabled()) {
            addWirelessEnergy();
        }

        converterSubscription.updateSubscription();
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
        coolantTimer = 0;
        removeWirelessEnergy();
        if ((isWorkingEnabled() && recipeLogic.getStatus() == RecipeLogic.Status.WORKING) &&
                !ConfigHolder.INSTANCE.machines.harmlessActiveTransformers) {
            explode();
        }
        super.invalidateStructure(structureName);
        this.localPowerInput = new ArrayList<>();
        setWorkingEnabled(false);
        converterSubscription.unsubscribe();
    }

    private void removeWirelessEnergy() {
        removeEnergyInputs(frequency, localPowerInput);
    }

    private void addWirelessEnergy() {
        addEnergyInputs(frequency, localPowerInput);
    }

    public static MultiPredicate getHatchPredicates() {
        var predicate = abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1)
                .or(abilities(PartAbility.SUBSTATION_INPUT_ENERGY).setPreviewCount(1))
                .or(abilities(PartAbility.INPUT_LASER).setPreviewCount(1));
        if (UtilConfig.coolantEnabled()) {
            predicate = predicate.or(abilities(PartAbility.IMPORT_FLUIDS).setExactLimit(1));
        }
        return predicate;
    }

    @Override
    public List<IWidget> getWidgetsForDisplay(PanelSyncManager syncManager) {
        List<IWidget> widgets = super.getWidgetsForDisplay(syncManager);
        if (isFormed()) {
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
                long inputAmperage = 0;
                long inputVoltage = 0;
                if (!localPowerInput.isEmpty()) {
                    EnergyContainerList localInputs = EnergyUtils.getEnergyListFromMultiParts(localPowerInput);
                    inputAmperage = localInputs.getInputAmperage();
                    inputVoltage = localInputs.getInputVoltage();
                }
                final long inputTotal = inputVoltage * inputAmperage;
                widgets.add(new brachy.modularui.widgets.TextWidget<>(
                        () -> Component.translatable("gtceu.multiblock.running")));
                if (inputTotal > 0) {
                    widgets.add(new brachy.modularui.widgets.TextWidget<>(
                            () -> Component.translatable("gtceu.multiblock.active_transformer.max_input",
                                    FormattingUtil.formatNumbers(Math.abs(inputTotal)))));
                }
                if (UtilConfig.coolantEnabled()) {
                    widgets.add(new brachy.modularui.widgets.TextWidget<>(
                            () -> Component.translatable("gtmutils.multiblock.web_hub_machine.coolant_usage",
                                    FormattingUtil.formatNumbers(coolantDrain),
                                    UtilMaterials.QuantumCoolant.getLocalizedName())));
                }
                if (!ConfigHolder.INSTANCE.machines.harmlessActiveTransformers) {
                    widgets.add(new brachy.modularui.widgets.TextWidget<>(
                            () -> Component.translatable("gtceu.multiblock.active_transformer.danger_enabled")));
                }
            } else {
                widgets.add(new brachy.modularui.widgets.TextWidget<>(
                        () -> Component.translatable("gtceu.multiblock.idling")));
            }
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
        if (!isWorkingAllowed) {
            coolantTimer = 0;
        }
    }
}
