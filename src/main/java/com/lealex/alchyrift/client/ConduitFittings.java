package com.lealex.alchyrift.client;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.block.ConduitFittingBlock;
import com.lealex.alchyrift.block.RiftClumpBlock;
import com.lealex.alchyrift.conduit.FittingSync;
import com.lealex.alchyrift.conduit.RiftConduitBlock;
import com.lealex.alchyrift.registry.ModRegistries;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * Draws what is set on conduit ends: a sieve's collar around the arm, surge crystals standing off it. The server
 * sends the list (conduit/FittingSync); each is drawn as the look block ConduitFittingBlock over the conduit, lit
 * like it, while that conduit is within sight and still there.
 */
@EventBusSubscriber(modid = AlchyRift.MODID, value = Dist.CLIENT)
public final class ConduitFittings {
    private ConduitFittings() {}

    private static final double RANGE = 48;

    /** One fitted end, with what is drawn for it (made the first time it is seen). */
    private static final class Shown {
        final FittingSync.Fitting fitting;
        MovingBlockRenderState look;
        ClientLevel madeFor; // a look is lit by its level: a new level (another dimension and back) needs a new one
        RiftConduitBlock madeOn; // and coloured by its conduit: items, fluids or energy

        Shown(FittingSync.Fitting fitting) {
            this.fitting = fitting;
        }
    }

    private static List<Shown> shown = List.of();

    /** The server's new list. */
    public static void receive(FittingSync.Fittings fittings) {
        List<Shown> next = new ArrayList<>();
        for (FittingSync.Fitting fitting : fittings.all()) next.add(new Shown(fitting));
        shown = next;
    }

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        if (shown.isEmpty()) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        for (Shown one : shown) {
            FittingSync.Fitting fitting = one.fitting;
            if (fitting.conduit().dimension() != level.dimension()) continue;
            BlockPos pos = fitting.conduit().pos();
            if (pos.distToCenterSqr(camera.x, camera.y, camera.z) > RANGE * RANGE) continue;
            if (!(level.getBlockState(pos).getBlock() instanceof RiftConduitBlock conduit)) continue;
            if (one.look == null || one.madeFor != level || one.madeOn != conduit) {
                ConduitFittingBlock.Sieve[] sieves = ConduitFittingBlock.Sieve.values();
                BlockState state = ModRegistries.CONDUIT_FITTING.get().defaultBlockState()
                        .setValue(ConduitFittingBlock.FACING, fitting.side())
                        .setValue(ConduitFittingBlock.SIEVE, sieves[Mth.clamp(fitting.sieve(), 0, sieves.length - 1)])
                        .setValue(ConduitFittingBlock.SURGE, Mth.clamp(fitting.surge(), 0, 3))
                        .setValue(ConduitFittingBlock.KIND, RiftClumpBlock.Kind.values()[conduit.kind().ordinal() % 3]);
                MovingBlockRenderState look = new MovingBlockRenderState();
                look.randomSeedPos = pos;
                look.blockPos = pos;
                look.blockState = state;
                look.biome = level.getBiome(pos);
                look.cardinalLighting = level.cardinalLighting();
                look.lightEngine = level.getLightEngine();
                one.look = look;
                one.madeFor = level;
                one.madeOn = conduit;
            }
            poseStack.pushPose();
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            event.getSubmitNodeCollector().submitMovingBlock(poseStack, one.look);
            poseStack.popPose();
        }
    }
}
