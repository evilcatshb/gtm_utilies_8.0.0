package net.neganote.gtutilities;

import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.GTCEuAPI;
import com.gregtechceu.gtceu.api.data.chemical.material.event.MaterialEvent;
import com.gregtechceu.gtceu.api.data.chemical.material.event.PostMaterialEvent;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.placeholder.Placeholder;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;
import com.gregtechceu.gtceu.common.data.GTCreativeModeTabs;
import com.gregtechceu.gtceu.common.data.GTItems;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neganote.gtutilities.client.keybind.UtilKeybinds;
import net.neganote.gtutilities.client.renderer.SprayCanHudOverlay;
import net.neganote.gtutilities.client.renderer.UtilShaders;
import net.neganote.gtutilities.common.data.UtilPlaceholders;
import net.neganote.gtutilities.common.item.InfiniteSprayCanBehaviour;
import net.neganote.gtutilities.common.item.UtilItems;
import net.neganote.gtutilities.common.machine.UtilMachines;
import net.neganote.gtutilities.common.materials.UtilMaterials;
import net.neganote.gtutilities.common.tools.UtilToolConnection;
import net.neganote.gtutilities.config.UtilConfig;
import net.neganote.gtutilities.datagen.UtilDatagen;
import net.neganote.gtutilities.network.UtilsNetwork;

import com.tterrag.registrate.util.entry.RegistryEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(GregTechModernUtilities.MOD_ID)
public class GregTechModernUtilities {

    public static final String MOD_ID = "gtmutils";
    public static final Logger LOGGER = LogManager.getLogger();
    // GTRegistrate.create() auto-registers event listeners in 8.0.0; no registerRegistrate() call needed.
    public static GTRegistrate REGISTRATE = GTRegistrate.create(GregTechModernUtilities.MOD_ID);

    public static RegistryEntry<CreativeModeTab> UTIL_CREATIVE_TAB = null;

    public GregTechModernUtilities() {
        GregTechModernUtilities.init();
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);
        if (GTCEu.isClientSide()) {
            modEventBus.addListener(this::clientSetup);
            modEventBus.addListener(UtilKeybinds::register);
            modEventBus.register(UtilShaders.class);
        }
        // MaterialRegistryEvent was removed in GTCEu 8.0.0; material namespace registration
        // now happens automatically through the @GTAddon annotation on GTMUtilitiesGTAddon.
        modEventBus.addListener(this::addMaterials);
        modEventBus.addListener(this::modifyMaterials);
        modEventBus.addGenericListener(GTRecipeType.class, this::registerRecipeTypes);
        modEventBus.addGenericListener(MachineDefinition.class, this::registerMachines);
        modEventBus.addGenericListener(Placeholder.class, this::registerPlaceholders);

        // Most other events are fired on Forge's bus.
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(UtilEvents.class);
        MinecraftForge.EVENT_BUS.addListener(InfiniteSprayCanBehaviour::onBlockPlaced);
        MinecraftForge.EVENT_BUS.addListener(InfiniteSprayCanBehaviour::onBlockBreak);
    }

    public static void init() {
        UtilConfig.init();
        // Force UtilItems class initialization while Registrate is still collecting registrations.
        // The client setup event runs too late: if UtilItems has not been initialized yet,
        // OMNIBREAKER remains null and ItemEntry#get() throws "Registry entry not present".
        if (UtilConfig.INSTANCE.features.omnibreakerEnabled) {
            @SuppressWarnings("unused")
            Object ignored = UtilItems.OMNIBREAKER;
        }
        if (UtilConfig.INSTANCE.features.omnibreakerEnabled) {
            UTIL_CREATIVE_TAB = REGISTRATE
                    .defaultCreativeTab(GregTechModernUtilities.MOD_ID,
                            builder -> builder
                                    .displayItems(new GTCreativeModeTabs.RegistrateDisplayItemsGenerator(
                                            GregTechModernUtilities.MOD_ID, REGISTRATE))
                                    .title(REGISTRATE.addLang("itemGroup", GregTechModernUtilities.id("creative_tab"),
                                            "GregTech Modern Utilities"))
                                    .icon(UtilItems.OMNIBREAKER::asStack)
                                    .build())
                    .register();
        } else {
            UTIL_CREATIVE_TAB = REGISTRATE
                    .defaultCreativeTab(GregTechModernUtilities.MOD_ID,
                            builder -> builder
                                    .displayItems(new GTCreativeModeTabs.RegistrateDisplayItemsGenerator(
                                            GregTechModernUtilities.MOD_ID, REGISTRATE))
                                    .title(REGISTRATE.addLang("itemGroup", GregTechModernUtilities.id("creative_tab"),
                                            "GregTech Modern Utilities"))
                                    .icon(GTItems.INTEGRATED_CIRCUIT_HV::asStack)
                                    .build())
                    .register();
        }
        // registerRegistrate() no longer exists in GTCEu 8.0.0; GTRegistrate.create() handles it.
        UtilDatagen.init();
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            UtilsNetwork.init();
            LOGGER.info("Hello from common setup! This is *after* registries are done, so we can do this:");
            LOGGER.info("Look, I found a {}!", Items.DIAMOND);
        });
    }

    @OnlyIn(Dist.CLIENT)
    @SubscribeEvent
    public void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (UtilConfig.INSTANCE.features.omnibreakerEnabled) {
                ItemProperties.register(UtilItems.OMNIBREAKER.get(), id("omnibreaker_name"),
                        (itemStack, clientLevel, livingEntity, i) -> {
                            String hoverName = itemStack.getHoverName().getString().toLowerCase();
                            if (hoverName.equals("monibreaker") || hoverName.equals("moni-breaker")) {
                                return 1;
                            } else if (hoverName.equals("meownibreaker") || hoverName.equals("meowni-breaker")) {
                                return 2;
                            }
                            return 0;
                        });
            }
        });
    }

    @Mod.EventBusSubscriber(modid = GregTechModernUtilities.MOD_ID,
                            bus = Mod.EventBusSubscriber.Bus.MOD,
                            value = Dist.CLIENT)
    public static class ClientModBusEvents {

        @SubscribeEvent
        public static void registerGuiOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("spray_can_info", SprayCanHudOverlay.HUD_SPRAY_CAN);
        }
    }

    private void addMaterials(MaterialEvent event) {
        UtilMaterials.register();
    }

    private void modifyMaterials(PostMaterialEvent event) {
        UtilToolConnection.modifyMaterials();
    }

    private void registerRecipeTypes(GTCEuAPI.RegisterEvent<ResourceLocation, GTRecipeType> event) {
        // CustomRecipeTypes.init();
    }

    private void registerMachines(GTCEuAPI.RegisterEvent<ResourceLocation, MachineDefinition> event) {
        // GTCEu 8.0.0 fires this generic registration event while the machine registry is open.
        // MachineBuilder.register() must run here; initializeAddon()/addRecipes() are too late.
        @SuppressWarnings("unused")
        Object ignored = UtilMachines.AUTO_CHARGER_4;
    }

    private void registerPlaceholders(GTCEuAPI.RegisterEvent<ResourceLocation, Placeholder> event) {
        UtilPlaceholders.init();
    }
}
