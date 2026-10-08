package com.lealex.alchyrift;

import com.lealex.alchyrift.command.RiftCommands;
import com.lealex.alchyrift.registry.ModRegistries;
import com.lealex.alchyrift.room.RoomLoading;
import com.lealex.alchyrift.vision.GateViews;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/** AlchyRift: rifts into pocket spaces inside the void, built on AlchyX. */
@Mod(AlchyRift.MODID)
public class AlchyRift {
    public static final String MODID = "alchyrift";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AlchyRift(IEventBus modEventBus, ModContainer modContainer) {
        ModRegistries.register(modEventBus);
        GateViews.register();
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> RiftCommands.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener(this::serverStarted);
    }

    /** Rooms are kept loaded as the config says (it may have changed since the world was last open). */
    private void serverStarted(ServerStartedEvent event) {
        RoomLoading.refresh(event.getServer());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("AlchyRift common setup");
    }
}
