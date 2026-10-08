package com.lealex.alchyrift.client;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.room.RoomLayout;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * The eye in the sky of the rift dimension. It hangs in the void far above the open ceiling, at a fixed height, and
 * drifts slowly after whoever is in the room, so it never jumps or bobs with them. It watches: its iris turns to
 * where the player is, it blinks now and then, and when the player looks up at it, its pupil narrows to a slit and
 * it burns brighter. It does nothing else. Yet.
 */
@EventBusSubscriber(modid = AlchyRift.MODID, value = Dist.CLIENT)
public final class SkyEye {
    private SkyEye() {}

    private static final Identifier WHITE = Identifier.fromNamespaceAndPath(AlchyRift.MODID, "textures/misc/eye_white.png");
    private static final Identifier IRIS = Identifier.fromNamespaceAndPath(AlchyRift.MODID, "textures/misc/eye_iris.png");
    private static final Identifier GLARE = Identifier.fromNamespaceAndPath(AlchyRift.MODID, "textures/misc/eye_iris_glare.png");

    /** Where it hangs (world height: every room's floor is at the same one), and how wide it is. */
    private static final float HEIGHT = RoomLayout.FLOOR_Y + 70.0F, SIZE = 60.0F;
    /** The iris, as a part of the eye's width, and how far below the white it floats (so the two never fight). */
    private static final float IRIS_SIZE = 0.42F, IRIS_DROP = 0.8F;
    /** How fast it drifts after the player (part of the way each tick), and how far its iris can turn (eye widths). */
    private static final double DRIFT = 0.03;
    private static final float LOOK = 0.13F;
    /** A blink every so many ticks, lasting this many. */
    private static final int BLINK_EVERY = 230, BLINK_TICKS = 7;

    // Where it is, this tick and the one before (drawn in between), and how hard it glares
    private static double x, z, oldX, oldZ;
    private static float glare, oldGlare;
    private static boolean placed;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (minecraft.level == null || player == null || minecraft.level.dimension() != RoomLayout.DIMENSION) {
            placed = false;
            return;
        }
        if (minecraft.isPaused()) return;
        if (!placed || Math.abs(player.getX() - x) > 96 || Math.abs(player.getZ() - z) > 96) { // just arrived: it is already there
            x = player.getX();
            z = player.getZ();
            glare = 0;
            placed = true;
        }
        oldX = x;
        oldZ = z;
        oldGlare = glare;
        x += (player.getX() - x) * DRIFT;
        z += (player.getZ() - z) * DRIFT;
        // Looking up at it: it notices
        float up = Mth.clamp((-player.getXRot() - 45.0F) / 25.0F, 0.0F, 1.0F);
        glare += (up - glare) * 0.12F;
    }

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (!placed || level == null || player == null || level.dimension() != RoomLayout.DIMENSION) return;
        float partialTicks = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long tick = level.getGameTime();
        double eyeX = Mth.lerp(partialTicks, oldX, x), eyeZ = Mth.lerp(partialTicks, oldZ, z);
        float glaring = Mth.lerp(partialTicks, oldGlare, glare);

        // The iris turns to where the player stands under it
        Vec3 feet = player.getPosition(partialTicks);
        float lookX = Mth.clamp((float) (feet.x - eyeX) / SIZE * 0.8F, -LOOK, LOOK);
        float lookZ = Mth.clamp((float) (feet.z - eyeZ) / SIZE * 0.8F, -LOOK, LOOK);

        // A blink: the lids close to a line and open again
        float sinceBlink = (tick % BLINK_EVERY) + partialTicks;
        float open = sinceBlink < BLINK_TICKS ? Math.max(0.04F, Math.abs(sinceBlink / BLINK_TICKS * 2 - 1)) : 1.0F;

        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        poseStack.pushPose();
        poseStack.translate(eyeX - camera.x, HEIGHT - camera.y, eyeZ - camera.z);
        float half = SIZE / 2;
        quad(poseStack, collector, WHITE, 0, 0, 0, half, half * open, 255);
        float iris = half * IRIS_SIZE;
        float irisX = lookX * SIZE, irisZ = lookZ * SIZE * open;
        // The calm iris is always there while the eye is open; the glaring one fades in over it
        quad(poseStack, collector, IRIS, irisX, -IRIS_DROP, irisZ, iris, iris * open, 255);
        if (glaring > 0.01F) quad(poseStack, collector, GLARE, irisX, -2 * IRIS_DROP, irisZ, iris, iris * open, (int) (255 * glaring));
        poseStack.popPose();
    }

    /** A flat picture lying in the sky, seen from below, its middle at (x, y, z) from the pose. */
    private static void quad(PoseStack poseStack, SubmitNodeCollector collector, Identifier texture, float x, float y, float z,
                             float halfX, float halfZ, int alpha) {
        int color = alpha << 24 | 0xFFFFFF;
        float[][] corners = {{x - halfX, z - halfZ, 0, 0}, {x + halfX, z - halfZ, 1, 0}, {x + halfX, z + halfZ, 1, 1}, {x - halfX, z + halfZ, 0, 1}};
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(texture), (pose, buffer) -> {
            for (int pass = 0; pass < 2; pass++) { // both windings: seen whatever the culling
                for (int k = 0; k < 4; k++) {
                    float[] c = corners[pass == 0 ? k : 3 - k];
                    buffer.addVertex(pose, c[0], y, c[1]).setColor(color).setUv(c[2], c[3])
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose, 0, -1, 0);
                }
            }
        });
    }
}
