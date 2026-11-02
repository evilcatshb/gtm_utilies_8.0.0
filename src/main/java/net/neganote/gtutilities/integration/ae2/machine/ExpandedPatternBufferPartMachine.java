package net.neganote.gtutilities.integration.ae2.machine;

import com.gregtechceu.gtceu.api.blockentity.BlockEntityCreationInfo;
import com.gregtechceu.gtceu.integration.ae2.machine.MEPatternBufferPartMachine;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.world.item.ItemStack;
import net.neganote.gtutilities.integration.ae2.machine.trait.ExpandedInternalSlotRecipeHandler;

import appeng.api.inventories.InternalInventory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class ExpandedPatternBufferPartMachine extends MEPatternBufferPartMachine {

    protected static final int EXPANDED_MAX_PATTERN_COUNT = 72;
    private ExpandedInternalSlotRecipeHandler expandedRecipeHandler;

    public ExpandedPatternBufferPartMachine(BlockEntityCreationInfo info) {
        super(info);
        this.getPatternInventory().setSize(EXPANDED_MAX_PATTERN_COUNT);

        try {
            Field internalInvField = MEPatternBufferPartMachine.class.getDeclaredField("internalInventory");
            internalInvField.setAccessible(true);

            InternalSlot[] newInternalInv = new InternalSlot[EXPANDED_MAX_PATTERN_COUNT];
            for (int i = 0; i < EXPANDED_MAX_PATTERN_COUNT; i++) {
                newInternalInv[i] = new InternalSlot();
            }
            internalInvField.set(this, newInternalInv);

            Field recipeHandlerField = MEPatternBufferPartMachine.class.getDeclaredField("bufferRecipeHandler");
            recipeHandlerField.setAccessible(true);
            this.expandedRecipeHandler = new ExpandedInternalSlotRecipeHandler(this, newInternalInv);
            recipeHandlerField.set(this, this.expandedRecipeHandler.getSlotHandlers().get(0));

            Field handlersField = MEPatternBufferPartMachine.class.getDeclaredField("bufferHandlers");
            handlersField.setAccessible(true);
            handlersField.set(this, new ExpandedInternalSlotRecipeHandler(this, newInternalInv).getSlotHandlers());

            Field internalPatternInventoryField = MEPatternBufferPartMachine.class
                    .getDeclaredField("internalPatternInventory");
            internalPatternInventoryField.setAccessible(true);
            internalPatternInventoryField.set(this, new InternalInventory() {

                @Override
                public int size() {
                    return EXPANDED_MAX_PATTERN_COUNT;
                }

                @Override
                public ItemStack getStackInSlot(int slotIndex) {
                    return getPatternInventory().getStackInSlot(slotIndex);
                }

                @Override
                public void setItemDirect(int slotIndex, ItemStack stack) {
                    getPatternInventory().setStackInSlot(slotIndex, stack);
                    getPatternInventory().onContentsChanged(slotIndex);
                    callPrivatePatternChange(slotIndex);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("FATAL: Failed to initialize Expanded Pattern Buffer via reflection.", e);
        }
    }

    private void callPrivatePatternChange(int index) {
        try {
            Method method = MEPatternBufferPartMachine.class.getDeclaredMethod("onPatternChange", int.class);
            method.setAccessible(true);
            method.invoke(this, index);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke onPatternChange for index " + index, e);
        }
    }

    public ExpandedInternalSlotRecipeHandler getInternalRecipeHandler() {
        return expandedRecipeHandler;
    }

    private String getCustomNameExpanded() {
        try {
            Field field = MEPatternBufferPartMachine.class.getDeclaredField("customName");
            field.setAccessible(true);
            return (String) field.get(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve customName via reflection", e);
        }
    }
}
