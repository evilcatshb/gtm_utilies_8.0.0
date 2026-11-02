package net.neganote.gtutilities.utils;

import com.gregtechceu.gtceu.api.capability.IEnergyContainer;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gregtechceu.gtceu.api.machine.trait.notifiable.NotifiableLaserContainer;
import com.gregtechceu.gtceu.api.misc.EnergyContainerList;
import com.gregtechceu.gtceu.common.machine.multiblock.part.EnergyHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.LaserHatchPartMachine;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods for handling energy containers from multiblock parts.
 * Updated for GTCEu 8.0.0 where parts are represented by {@link MultiblockPartMachine}.
 */
public class EnergyUtils {

    /**
     * Build an {@link EnergyContainerList} from a list of multiblock parts.
     *
     * @param parts list of parts (typically the result of {@code getParts()} on a multiblock machine)
     * @return an {@link EnergyContainerList} containing all energy containers found in the parts
     */
    public static EnergyContainerList getEnergyListFromMultiParts(List<MultiblockPartMachine> parts) {
        List<IEnergyContainer> energyContainerList = new ArrayList<>();
        for (MultiblockPartMachine part : parts) {
            if (part instanceof EnergyHatchPartMachine hatch) {
                energyContainerList.add(hatch.energyContainer);
            }
            if (part instanceof LaserHatchPartMachine hatch) {
                // Laser hatch buffers are exposed via the NotifiableLaserContainer trait
                hatch.getTraits(NotifiableLaserContainer.class)
                        .stream()
                        .filter(IEnergyContainer.class::isInstance)
                        .map(IEnergyContainer.class::cast)
                        .forEach(energyContainerList::add);
            }
        }
        return new EnergyContainerList(energyContainerList);
    }
}
