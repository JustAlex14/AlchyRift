package com.lealex.alchyrift.client;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.ClientConfig;
import com.lealex.alchyrift.block.entity.RiftAnchorBlockEntity;
import com.lealex.alchyrift.vision.GateViews;
import com.lealex.alchyx.animation.LoadedCores;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.client.miniature.BoxMesh;
import com.lealex.alchyx.client.miniature.Miniatures;
import com.lealex.alchyx.client.miniature.Puppets;
import com.lealex.alchyx.miniature.BlockBox;
import com.lealex.alchyx.miniature.BoxWatch;
import com.lealex.alchyx.miniature.Figure;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.jspecify.annotations.Nullable;

/**
 * Rift vision, the picture side: a small rift hangs above the stabilizer of every set gate nearby. A player who
 * looks into it for two seconds sees it tear open and what the gate leads to come out of it in small, turning
 * slowly: its room, with the mobs and players inside, or the surroundings of the gate it leads to. It goes back in
 * a moment after they look away.
 *
 * The picture itself is AlchyX's miniature kit: the server sends a box of blocks and watches it (BoxWatch), the
 * box is turned into a mesh once (BoxMesh), the mobs are puppets (Puppets). This class decides which gates to ask
 * about, and draws the rift, the stare and the coming out.
 */
@EventBusSubscriber(modid = AlchyRift.MODID, value = Dist.CLIENT)
public final class RiftVision {
    private RiftVision() {}

    private static final int REQUEST_EVERY = 100;
    /** The miniature fits in this width and height (blocks), and floats this far above the stabilizer block's floor. */
    private static final float MAX_WIDTH = 0.9F, MAX_HEIGHT = 0.8F, LIFT = 0.95F;
    /** Degrees per tick. */
    private static final float SPIN = 0.6F;
    private static final ContextKey<List<Frame>> RENDER_KEY =
            new ContextKey<>(Identifier.fromNamespaceAndPath(AlchyRift.MODID, "rift_vision"));

    private static final Map<BlockPos, Long> REQUESTED = new HashMap<>();
    private static final Map<BlockPos, View> VIEWS = new HashMap<>();
    private static @Nullable ClientLevel lastLevel;

    /**
     * What a gate shows, ready to draw above its stabilizer. {@code key} is what AlchyX knows the gate by;
     * {@code light} follows the light around the stabilizer.
     */
    private record View(BlockPos gate, GlobalPos key, BlockPos stabilizer, BlockBox box, BoxMesh mesh, int light) {}

    /**
     * What one frame draws for a view: the view, how far the player has stared the rift open (0 to 1), how far the
     * picture has come out of it (0 to 1), and its figures as they stand at that instant.
     */
    private record Frame(View view, float gaze, float reveal, List<Puppets.Prepared> figures) {}

    // ---- The rift above a stabilizer, and looking into it ----

    /** Ticks of steady looking before the picture comes out, ticks it takes to come out, ticks it stays once the player looks away. */
    private static final int GAZE_TICKS = 40, REVEAL_TICKS = 12, LINGER_TICKS = 30;
    /** How far the rift can be stared into, how wide it is, how open it rests, how high its middle floats above the miniature's base. */
    private static final double GAZE_RANGE = 16.0;
    private static final float RIFT_SIZE = 0.9F, RIFT_REST = 0.32F, RIFT_LIFT = 0.45F;
    private static final Map<BlockPos, Gaze> GAZES = new HashMap<>();

    /** How a player's look has worked on one gate's rift. Previous values are kept to draw between two ticks. */
    private static final class Gaze {
        private float gaze, reveal, lastGaze, lastReveal;
        private int away;

        private void tick(boolean looking) {
            lastGaze = gaze;
            lastReveal = reveal;
            if (looking) {
                away = 0;
                gaze = Math.min(1, gaze + 1F / GAZE_TICKS);
                if (gaze >= 1) reveal = Math.min(1, reveal + 1F / REVEAL_TICKS);
            } else if (reveal > 0) {
                if (++away > LINGER_TICKS) reveal = Math.max(0, reveal - 1F / REVEAL_TICKS);
            } else {
                gaze = Math.max(0, gaze - 2F / GAZE_TICKS);
            }
        }
    }

    /** Whether the player's crosshair rests on the rift above a stabilizer. */
    private static boolean looksInto(Player player, BlockPos stabilizer) {
        Vec3 eye = player.getEyePosition();
        Vec3 toRift = new Vec3(stabilizer.getX() + 0.5, stabilizer.getY() + LIFT + RIFT_LIFT, stabilizer.getZ() + 0.5).subtract(eye);
        double distance = toRift.length();
        if (distance > GAZE_RANGE || distance < 0.01) return false;
        double radius = RIFT_SIZE * 0.6;
        return toRift.dot(player.getViewVector(1.0F)) / distance >= distance / Math.sqrt(distance * distance + radius * radius);
    }

    // ---- Asking the server, keeping the views up to date ----

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != lastLevel) { // new world or dimension: start clean
            REQUESTED.clear();
            VIEWS.values().forEach(view -> BoxWatch.clientForget(view.key));
            VIEWS.clear();
            lastLevel = level;
        }
        if (level == null || minecraft.player == null) return;

        // Every tick: the mobs of the room pictures move, and looking into a rift works on it
        Map<BlockPos, List<Figure>> reports = new HashMap<>();
        for (View view : VIEWS.values()) {
            if (view.box.trimmed) reports.put(view.gate, BoxWatch.clientFigures(view.key)); // a room, not a gate's surroundings
        }
        Puppets.tick(level, reports);
        GAZES.keySet().retainAll(VIEWS.keySet());
        for (View view : VIEWS.values()) {
            GAZES.computeIfAbsent(view.gate, key -> new Gaze()).tick(looksInto(minecraft.player, view.stabilizer));
        }

        if (level.getGameTime() % 10 != 0 || minecraft.getConnection() == null) return;
        if (!ClientConfig.enabled()) {
            VIEWS.clear();
            return;
        }
        long now = level.getGameTime();
        double range = ClientConfig.viewDistance();
        Map<BlockPos, View> kept = new HashMap<>();
        for (MultiblockCoreBlockEntity core : LoadedCores.snapshot()) {
            if (!(core instanceof RiftAnchorBlockEntity gate) || gate.isRemoved() || gate.getLevel() != level || !gate.isFormed()) continue;
            BlockPos pos = gate.getBlockPos();
            if (minecraft.player.distanceToSqr(Vec3.atCenterOf(pos)) > range * range) continue;
            GlobalPos key = GlobalPos.of(level.dimension(), pos);

            // Asking again every few seconds is what keeps the server watching
            Long asked = REQUESTED.get(pos);
            if (asked == null || now - asked >= REQUEST_EVERY) {
                REQUESTED.put(pos.immutable(), now);
                Miniatures.request(GateViews.KIND, key);
            }
            BlockBox box = BoxWatch.clientBox(key);
            if (box == null) continue;
            BlockPos stabilizer = gate.stabilizerPos();
            int light = LevelRenderer.getLightCoords(level, stabilizer.above());
            View view = VIEWS.get(pos);
            if (view == null || view.box != box || !view.stabilizer.equals(stabilizer) || view.mesh.isStale()) {
                view = new View(pos.immutable(), key, stabilizer, box, BoxMesh.build(level, stabilizer, box, ClientConfig.maxFaces()), light);
            } else if (view.light != light) {
                view = new View(view.gate, view.key, view.stabilizer, view.box, view.mesh, light);
            }
            kept.put(view.gate, view);
        }
        VIEWS.values().stream().filter(view -> !kept.containsKey(view.gate)).toList().forEach(view -> {
            REQUESTED.remove(view.gate);
            BoxWatch.clientForget(view.key);
        });
        VIEWS.clear();
        VIEWS.putAll(kept);
    }

    // ---- Drawing ----

    @SubscribeEvent
    public static void onExtract(ExtractLevelRenderStateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (VIEWS.isEmpty() || level == null) {
            event.getRenderState().setRenderData(RENDER_KEY, null);
            return;
        }
        Vec3 camera = event.getCamera().position();
        double range = ClientConfig.viewDistance();
        float partialTicks = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float time = level.getGameTime() + partialTicks;
        List<Frame> frames = new ArrayList<>();
        for (View view : VIEWS.values()) {
            if (view.box.isEmpty()) continue; // a gate that leads nowhere: no rift, no picture
            if (Vec3.atCenterOf(view.stabilizer).distanceToSqr(camera) >= range * range) continue;
            // Nothing is drawn unless the space above the stabilizer is on screen
            BlockPos at = view.stabilizer;
            if (!event.getFrustum().isVisible(new AABB(at.getX() - 0.5, at.getY() + 0.5, at.getZ() - 0.5, at.getX() + 1.5, at.getY() + 2.5, at.getZ() + 1.5))) continue;
            Gaze gaze = GAZES.get(view.gate);
            float stared = gaze == null ? 0 : Mth.lerp(partialTicks, gaze.lastGaze, gaze.gaze);
            float reveal = gaze == null ? 0 : Mth.lerp(partialTicks, gaze.lastReveal, gaze.reveal);
            // Mobs and players belong to a room's picture only (not a gate's surroundings), and only once it is out
            List<Puppets.Prepared> figures = view.box.trimmed && reveal > 0
                    ? Puppets.prepare(level, view.gate, view.stabilizer.above(), time, partialTicks) : List.of();
            frames.add(new Frame(view, stared, reveal, figures));
        }
        event.getRenderState().setRenderData(RENDER_KEY, frames.isEmpty() ? null : frames);
    }

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        List<Frame> frames = event.getLevelRenderState().getRenderData(RENDER_KEY);
        Minecraft minecraft = Minecraft.getInstance();
        if (frames == null || minecraft.level == null) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        float time = minecraft.level.getGameTime() + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Frame frame : frames) {
            View view = frame.view;
            BlockBox box = view.box;
            // The rift: it rests half open, tears wider as the player stares, and closes as the picture comes out of it
            if (frame.reveal < 1) {
                float open = Mth.lerp(frame.gaze, RIFT_REST + 0.04F * Mth.sin(time * 0.1F), 1.0F) * (1 - frame.reveal);
                poseStack.pushPose();
                poseStack.translate(view.stabilizer.getX() + 0.5 - camera.x, view.stabilizer.getY() + LIFT + RIFT_LIFT - camera.y, view.stabilizer.getZ() + 0.5 - camera.z);
                Rifts.submitHanging(poseStack, collector, event.getLevelRenderState().cameraRenderState, RIFT_SIZE, open, time % 24000.0F);
                poseStack.popPose();
            }
            if (frame.reveal <= 0) continue;
            // The picture grows out of the rift's middle down to its place
            float out = frame.reveal * frame.reveal * (3 - 2 * frame.reveal);
            float scale = out * Math.min(MAX_WIDTH / Math.max(box.sizeX, box.sizeZ), MAX_HEIGHT / box.sizeY);
            poseStack.pushPose();
            poseStack.translate(view.stabilizer.getX() + 0.5 - camera.x, view.stabilizer.getY() + LIFT + RIFT_LIFT * (1 - out) - camera.y, view.stabilizer.getZ() + 0.5 - camera.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(time * SPIN));
            poseStack.scale(scale, scale, scale);
            poseStack.translate(-box.sizeX / 2.0, 0.0, -box.sizeZ / 2.0);
            Miniatures.submit(poseStack, collector, event.getLevelRenderState().cameraRenderState, view.mesh, view.light, frame.figures);
            poseStack.popPose();
        }
    }
}
