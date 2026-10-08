package com.lealex.alchyrift;

import com.lealex.alchyrift.client.RiftFilterScreen;
import com.lealex.alchyrift.client.RiftGateScreen;
import com.lealex.alchyrift.client.RiftRelayRenderer;
import com.lealex.alchyrift.client.Rifts;
import com.lealex.alchyrift.conduit.FilterMessages;
import com.lealex.alchyrift.gate.GateMessages;
import com.lealex.alchyrift.registry.ModRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client-only registrations (never loaded on a dedicated server). */
@Mod(value = AlchyRift.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = AlchyRift.MODID, value = Dist.CLIENT)
public class AlchyRiftClient {
    public AlchyRiftClient(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
        GateMessages.clientOpener = RiftGateScreen::show;
        GateMessages.clientCloser = Rifts::closed;
        FilterMessages.clientOpener = RiftFilterScreen::show;
        com.lealex.alchyrift.conduit.FittingSync.clientReceiver = com.lealex.alchyrift.client.ConduitFittings::receive;
        // The notebook's paper: old at first, then chapter by chapter the rift gets into it (tools/book_textures.py)
        java.util.List<net.minecraft.resources.Identifier> paper = new java.util.ArrayList<>();
        for (int chapter = 0; chapter < 6; chapter++) {
            paper.add(net.minecraft.resources.Identifier.fromNamespaceAndPath(AlchyRift.MODID,
                    "textures/gui/notebook" + (chapter == 0 ? "" : "_" + chapter) + ".png"));
        }
        com.lealex.alchyx.client.BookPapers.register(net.minecraft.resources.Identifier.fromNamespaceAndPath(AlchyRift.MODID, "guide"),
                java.util.List.of("leaving", "door", "room", "supply", "greater", "eye"), paper, null, null,
                // Not every page is marked: dust on one, a smear or a stain on another, and many left bare
                new com.lealex.alchyx.client.BookPapers.Marks(java.util.stream.IntStream.range(0, 6).mapToObj(mark ->
                        net.minecraft.resources.Identifier.fromNamespaceAndPath(AlchyRift.MODID, "textures/gui/notebook_marks_" + mark + ".png")).toList(), 0.4F));
        // A room's wall tears open where the player comes close (the wall itself is the way out)
        Rifts.register();
    }

    // Block entity renderers: what a block draws that a model can't (things that turn, hang, open)
    @SubscribeEvent
    static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModRegistries.RIFT_RELAY_BE.get(), RiftRelayRenderer::new);
    }
}
