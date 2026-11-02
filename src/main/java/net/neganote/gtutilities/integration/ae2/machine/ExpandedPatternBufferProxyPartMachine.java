package net.neganote.gtutilities.integration.ae2.machine;

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo;
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferProxyPartMachine;

import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ExpandedPatternBufferProxyPartMachine extends MEPatternBufferProxyPartMachine {

    public ExpandedPatternBufferProxyPartMachine(BlockEntityCreationInfo info) {
        super(info);
    }
}
