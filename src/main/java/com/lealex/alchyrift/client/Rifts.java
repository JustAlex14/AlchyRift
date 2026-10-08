package com.lealex.alchyrift.client;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.block.RiftAnchorBlock;
import com.lealex.alchyrift.block.entity.RiftAnchorBlockEntity;
import com.lealex.alchyrift.gate.GateMessages;
import com.lealex.alchyrift.registry.ModRegistries;
import com.lealex.alchyrift.room.RoomLayout;
import com.lealex.alchyx.animation.LoadedCores;
import com.lealex.alchyx.block.entity.MultiblockCoreBlockEntity;
import com.lealex.alchyx.client.NearLook;
import com.lealex.alchyx.client.Tear;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/**
 * What a rift looks like: a jagged slit pinched at both ends, its lips glowing violet, the End's starfield through
 * it. AlchyRift's own drawing of the shape AlchyX's corruption rifts have. Three places show it (AlchyX Tear):
 * <ul>
 *   <li>above a stabilizer: a small slanted one, facing whoever looks at it ({@link #submitHanging}, used by
 *       RiftVision);</li>
 *   <li>a room's wall: as the player walks toward it a first crack appears right in front of their eyes, and it
 *       tears wider with every step until it gapes; it follows them along the wall (AlchyX NearLook);</li>
 *   <li>a set gate: its opening is closed by a dark veil (reality stretched thin across the frame), and one tear
 *       opens in it (the rift blocks themselves are unseen). Standing in the air alone, the tear read as a picture
 *       floating on nothing; on the veil it tears something, as it does on a room's wall. It rests as a thin crack
 *       and tears open when a player comes near, so a gate just set, or just come upon, opens before their eyes.
 *       On a greater gate it gapes to the frame. Now and then amethyst lightning jumps from the frame into the open
 *       rift: rarely on a lesser gate, more often on a greater one. The instant a gate is set its rift tears open
 *       with a blast: a flash, shockwaves, a storm of bolts, the tear slamming wide and out of shape.</li>
 * </ul>
 * It is never steady: it shivers, and now and then jerks wider or snaps half shut and slips aside.
 */
@EventBusSubscriber(modid = AlchyRift.MODID, value = Dist.CLIENT)
public final class Rifts {
    private Rifts() {}

    /** The upright rift of doors and the small one of stabilizers, from a closed crack to torn open (tools/room_textures.py). */
    private static final int STAGE_COUNT = 14; // RIFT_STAGES in the script
    /**
     * Each stage is drawn FRAMES times (RIFT_FRAMES in the script): light runs along the lips, the glow flickers, the
     * sparks jump, the shape and its hole staying put. A rift standing by itself plays them; a room's wall keeps to
     * the first (AlchyX NearLook takes one list).
     */
    private static final int FRAMES = 6, TICKS_PER_FRAME = 2;
    private static final List<List<Tear.Stage>> DOOR = frames("rift_door"), SMALL = frames("rift_small");
    private static final List<Tear.Stage> STAGES = DOOR.get(0);
    /** The greater gate's: the same crack, gaping to the frame once open. */
    private static final List<List<Tear.Stage>> WIDE = frames("rift_gate");
    /** The skin over a set gate's opening (4 x 4 blocks of it), reaching this far into the frame's ragged stone. */
    private static final Identifier VEIL = Identifier.fromNamespaceAndPath(AlchyRift.MODID, "textures/misc/rift_veil.png");
    private static final float VEIL_TILE = 4.0F, VEIL_INTO_FRAME = 0.3F;

    private static List<List<Tear.Stage>> frames(String name) {
        List<List<Tear.Stage>> frames = new ArrayList<>();
        for (int frame = 0; frame < FRAMES; frame++) {
            List<Tear.Stage> stages = new ArrayList<>();
            for (int i = 0; i < STAGE_COUNT; i++) {
                String picture = "textures/misc/" + name + "_" + i + (frame == 0 ? "" : "_f" + frame) + ".png";
                stages.add(new Tear.Stage(Identifier.fromNamespaceAndPath(AlchyRift.MODID, picture),
                        Identifier.fromNamespaceAndPath(AlchyRift.MODID, "textures/misc/" + name + "_" + i + "_hole.png")));
            }
            frames.add(List.copyOf(stages));
        }
        return List.copyOf(frames);
    }

    /** The stages to draw at this instant. */
    private static List<Tear.Stage> now(List<List<Tear.Stage>> frames, float time) {
        return frames.get(Math.floorMod((int) (time / TICKS_PER_FRAME), FRAMES));
    }

    /**
     * The small rift, hanging at the pose's origin and facing the camera, {@code size} blocks across, {@code open}
     * from 0 (a closed crack) to 1 (torn open).
     */
    public static void submitHanging(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera, float size, float open, float time) {
        if (open <= 0) return;
        poseStack.pushPose();
        poseStack.mulPose(camera.orientation);
        Tear.submit(poseStack, collector, now(SMALL, time), open, size / 2, time, false);
        poseStack.popPose();
    }

    private static final double RANGE = 128;
    /** A gate's rift tears open when a player is this close, and rests this far open otherwise. */
    private static final double WAKE = 5;
    private static final float REST = 0.14F;
    /** How open each gate's rift is, by anchor: {a tick ago, now}. */
    private static final Map<BlockPos, float[]> OPENING = new HashMap<>();

    /** Every tick: each gate's rift eases toward open or toward rest (about two seconds to tear open). */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            OPENING.clear();
            COLLAPSES.clear();
            return;
        }
        if (minecraft.isPaused()) return;
        Map<BlockPos, float[]> next = new HashMap<>();
        for (MultiblockCoreBlockEntity core : LoadedCores.snapshot()) {
            if (!(core instanceof RiftAnchorBlockEntity anchor) || core.isRemoved() || core.getLevel() != level || !core.isFormed()) continue;
            BlockPos rift = core.getBlockPos().offset(anchor.gate().riftMin().rotate(anchor.rotation()));
            if (!level.getBlockState(rift).is(ModRegistries.RIFT_CRACK)) continue; // not set: no rift (it starts shut when it is)
            float[] open = OPENING.getOrDefault(core.getBlockPos(), new float[2]);
            boolean near = minecraft.player.distanceToSqr(Vec3.atCenterOf(rift)) <= WAKE * WAKE;
            open[0] = open[1];
            open[1] = Mth.clamp(open[1] + (near ? 0.035F + (1 - open[1]) * 0.04F : -0.02F), near ? 0.0F : REST, 1.0F);
            if (!near && open[0] < REST) open[1] = Math.min(REST, open[0] + 0.01F); // a new rift: first the crack appears
            next.put(core.getBlockPos().immutable(), open);
        }
        OPENING.clear();
        OPENING.putAll(next);
    }

    // ---- The blast of a rift tearing open ----

    /** How long the blast of a gate just set lasts (the server stamps the instant: RiftAnchorBlockEntity.openedAt). */
    private static final float BLAST_TICKS = 46;

    /**
     * What the eye sees of the explosion, in the gate's plane (the pose): a white-violet flash over the opening for
     * the first ticks, then shockwaves racing out, two upright through the frame and one flat over the floor, each
     * a ring torn out of shape and redrawn every other tick. Sound, debris and the push come from the server.
     */
    private static void blast(PoseStack poseStack, SubmitNodeCollector collector, long seed, float time, float age, float strength,
                              float width, float height) {
        float reach = Math.max(width, height) * 0.5F + 4.5F;
        if (age < 7) {
            int alpha = (int) (230 * (1 - age / 7));
            float w = width / 2 + 0.6F + age * 0.25F, h = height / 2 + 0.6F + age * 0.25F;
            collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
                float[][] q = {{-w, -h}, {w, -h}, {w, h}, {-w, h}};
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 0; i < 4; i++) {
                        float[] c = q[pass == 0 ? i : 3 - i];
                        buffer.addVertex(pose, c[0], c[1], 0).setColor(alpha << 24 | 0xF0DEFF);
                    }
                }
            });
        }
        // {delay, speed, flat}
        float[][] waves = {{0, 1.0F, 0}, {5, 0.8F, 0}, {2, 1.15F, 1}};
        int step = (int) (time / 2);
        for (int index = 0; index < waves.length; index++) {
            float[] wave = waves[index];
            float lived = (age - wave[0]) / (BLAST_TICKS - wave[0]);
            if (lived <= 0 || lived >= 1) continue;
            float radius = reach * wave[1] * (1 - (1 - lived) * (1 - lived)); // fast, then slowing
            float fade = (1 - lived) * (1 - lived);
            int segments = 28;
            float[] points = new float[(segments + 1) * 3];
            for (int k = 0; k <= segments; k++) {
                int at = k % segments;
                float angle = at * Mth.TWO_PI / segments;
                int jolt = mix(seed + index * 7L + step, at);
                float r = radius * (1 + (unit(jolt) - 0.5F) * 0.3F);
                points[k * 3] = Mth.cos(angle) * r;
                points[k * 3 + 1] = Mth.sin(angle) * r;
                points[k * 3 + 2] = (unit(jolt >>> 8) - 0.5F) * 0.5F * radius * 0.2F;
            }
            int halo = (int) (120 * fade) << 24 | 0xB26BFF, white = (int) (255 * fade) << 24 | 0xF0DEFF;
            float thick = 0.05F + 0.12F * (1 - lived);
            poseStack.pushPose();
            if (wave[2] > 0) {
                poseStack.translate(0, -height / 2 + 0.08F, 0);
                poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
            }
            collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
                for (int k = 0; k + 5 < points.length; k += 3) {
                    ribbon(pose, buffer, points, k, thick * 2.6F, halo);
                    ribbon(pose, buffer, points, k, thick * 0.7F, white);
                }
            });
            poseStack.popPose();
        }
    }

    // ---- Lightning off a gate's frame ----

    static int mix(long a, int b) {
        long n = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL;
        n = (n ^ (n >>> 29)) * 0xBF58476D1CE4E5B9L;
        return (int) (n ^ (n >>> 32));
    }

    static float unit(int hash) {
        return (hash & 0xFFFF) / 65535.0F;
    }

    /**
     * Amethyst lightning from the frame into the rift, in the gate's plane (the pose: middle of the opening, X
     * across it, Y up). Up to {@code slots} bolts live three ticks each and jitter every tick; each slot holds one
     * {@code chance} of the time, so most of the time there is none.
     */
    private static void bolts(PoseStack poseStack, SubmitNodeCollector collector, long seed, float time, float halfWidth, float halfHeight,
                              int slots, float chance) {
        int tick = (int) time;
        float wander = Math.min(0.4F, halfWidth * 0.5F); // a narrow gate's bolts stay inside it
        List<float[]> lines = new ArrayList<>();
        for (int slot = 0; slot < slots; slot++) {
            int id = mix(seed, ((tick + slot * 5) / 3) * 31 + slot);
            if (unit(id) > chance) continue;
            // It leaves one of the pillars' inner faces or the underside of the beam...
            float where = unit(mix(id, 1)), along = unit(mix(id, 2)) * 2 - 1;
            float startX = where < 0.36F ? -halfWidth - 0.15F : where < 0.72F ? halfWidth + 0.15F : along * halfWidth;
            float startY = where < 0.72F ? along * halfHeight : halfHeight + 0.15F;
            // ...and strikes the crack
            float endX = (unit(mix(id, 3)) - 0.5F) * wander, endY = (unit(mix(id, 4)) - 0.5F) * halfHeight;
            int segments = 6;
            float[] points = new float[(segments + 1) * 3];
            for (int k = 0; k <= segments; k++) {
                float f = k / (float) segments, wild = k == 0 || k == segments ? 0 : 1;
                int jolt = mix(id * 7L + tick, k);
                points[k * 3] = Mth.lerp(f, startX, endX) + (unit(jolt) - 0.5F) * wander * wild;
                points[k * 3 + 1] = Mth.lerp(f, startY, endY) + (unit(jolt >>> 8) - 0.5F) * wander * wild;
                points[k * 3 + 2] = (unit(jolt >>> 16) - 0.5F) * 0.3F * wild;
            }
            lines.add(points);
        }
        if (lines.isEmpty()) return;
        collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
            for (float[] points : lines) {
                for (int k = 0; k + 5 < points.length; k += 3) {
                    ribbon(pose, buffer, points, k, 0.07F, 0x70B26BFF);  // the violet around it
                    ribbon(pose, buffer, points, k, 0.022F, 0xFFF0DEFF); // the white of the bolt
                }
            }
        });
    }

    /** One stretch of a bolt as two crossed strips, so it shows from every side. */
    static void ribbon(PoseStack.Pose pose, VertexConsumer buffer, float[] p, int k, float width, int color) {
        float ax = p[k], ay = p[k + 1], az = p[k + 2], bx = p[k + 3], by = p[k + 4], bz = p[k + 5];
        float dx = bx - ax, dy = by - ay;
        float length = Math.max(0.001F, Mth.sqrt(dx * dx + dy * dy));
        float nx = -dy / length * width, ny = dx / length * width;
        float[][] flat = {{ax - nx, ay - ny, az}, {ax + nx, ay + ny, az}, {bx + nx, by + ny, bz}, {bx - nx, by - ny, bz}};
        float[][] deep = {{ax, ay, az - width}, {ax, ay, az + width}, {bx, by, bz + width}, {bx, by, bz - width}};
        for (float[][] quad : new float[][][] {flat, deep}) {
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < 4; i++) {
                    float[] c = quad[pass == 0 ? i : 3 - i];
                    buffer.addVertex(pose, c[0], c[1], c[2]).setColor(color);
                }
            }
        }
    }

    /** Called once from the client entry point. */
    public static void register() {
        // A wall cracks at 5 blocks and is torn open at 1.2; the slit runs nearly the height of its picture, so a
        // picture 3.2 blocks tall makes a rift a player walks into
        NearLook.register(NearLook.Rule.tear(
                level -> level.dimension() == RoomLayout.DIMENSION,
                state -> state.is(ModRegistries.RIFT_WALL),
                STAGES, 1.2F, 5.0F, 1.6F, true));
    }

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) return;
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        float time = level.getGameTime() % 24000L + minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack poseStack = event.getPoseStack();
        for (MultiblockCoreBlockEntity core : LoadedCores.snapshot()) {
            if (!(core instanceof RiftAnchorBlockEntity anchor) || core.isRemoved() || core.getLevel() != level || !core.isFormed()) continue;
            if (Vec3.atCenterOf(core.getBlockPos()).distanceToSqr(camera) > RANGE * RANGE) continue;
            RiftAnchorBlock.Gate gate = anchor.gate();
            Rotation rotation = anchor.rotation();
            BlockPos a = core.getBlockPos().offset(gate.riftMin().rotate(rotation)), b = core.getBlockPos().offset(gate.riftMax().rotate(rotation));
            if (!level.getBlockState(a).is(ModRegistries.RIFT_CRACK)) continue; // not set: no rift
            // The opening stands across the pattern's depth: seen along that axis once turned with the gate
            boolean alongX = rotation.rotate(Direction.SOUTH).getAxis() == Direction.Axis.X;
            float width = (alongX ? Math.abs(a.getZ() - b.getZ()) : Math.abs(a.getX() - b.getX())) + 1;
            float height = Math.abs(a.getY() - b.getY()) + 1;
            poseStack.pushPose();
            poseStack.translate((a.getX() + b.getX()) / 2.0 + 0.5 - camera.x, (a.getY() + b.getY()) / 2.0 + 0.5 - camera.y,
                    (a.getZ() + b.getZ()) / 2.0 + 0.5 - camera.z);
            if (alongX) poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));
            // One sheet in the middle of the frame: the veil over the opening and, in it, the slit about as tall as
            // the opening; as open as the gate is now, breathing a little once torn
            float[] opening = OPENING.get(core.getBlockPos());
            float partialTicks = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            float eased = opening == null ? 0 : Mth.lerp(partialTicks, opening[0], opening[1]);
            // Just set: it slams wide open whatever the distance, then settles back to what it would be
            float age = anchor.openedAt() <= 0 ? BLAST_TICKS : level.getGameTime() - anchor.openedAt() + partialTicks;
            float blast = age < 0 || age >= BLAST_TICKS ? 0 : 1 - age / BLAST_TICKS;
            if (blast > 0) eased = Math.max(eased, Math.min(1.0F, age / 3.0F) * (float) Math.sqrt(blast));
            float open = eased * (0.93F + 0.07F * Mth.sin(time * 0.13F + core.getBlockPos().getX()));
            // The veil comes with the first crack of a rift just set, and is cut at the floor
            int veilAlpha = (int) (255 * Mth.clamp(eased / REST, 0.0F, 1.0F));
            Tear.Veil veil = new Tear.Veil(VEIL, -width / 2 - VEIL_INTO_FRAME, width / 2 + VEIL_INTO_FRAME, -height / 2,
                    height / 2 + VEIL_INTO_FRAME, VEIL_TILE, veilAlpha << 24 | 0xFFFFFF);
            boolean greater = gate.changeable();
            // ...and while the blast lasts the whole tear swells and shrinks, out of shape
            float warp = 1 + 0.45F * blast * blast * Mth.sin(age * 1.9F);
            Tear.submit(poseStack, event.getSubmitNodeCollector(), now(greater ? WIDE : DOOR, time), open,
                    Math.max(width, height) * (greater ? 0.6F : 0.7F) * warp, time, eased > 0.5F, veil);
            if (blast > 0) {
                blast(poseStack, event.getSubmitNodeCollector(), core.getBlockPos().asLong(), time, age, blast, width, height);
                bolts(poseStack, event.getSubmitNodeCollector(), core.getBlockPos().asLong() + 1, time, width / 2, height / 2, 5, blast);
            }
            if (anchor.hasLightning() && eased > 0.6F) {
                // A greater gate: a bolt about a quarter of the time; a lesser one: now and then
                bolts(poseStack, event.getSubmitNodeCollector(), core.getBlockPos().asLong(), time, width / 2, height / 2,
                        greater ? 2 : 1, greater ? 0.13F : 0.07F);
            }
            poseStack.popPose();
        }
        collapses(level, minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false), time, camera, poseStack, event.getSubmitNodeCollector());
    }

    // ---- A rift closing: it falls in on itself ----

    /** How long a closing rift takes to be gone. */
    private static final float COLLAPSE_TICKS = 34;

    /** A rift that just closed: where it stood, and when (game time). Its blocks, and maybe its gate, are already gone. */
    private record Collapse(GateMessages.Closed rift, long since) {}

    private static final List<Collapse> COLLAPSES = new ArrayList<>();

    /** The server says a rift closed (GateMessages.Closed): from now it is drawn collapsing. */
    public static void closed(GateMessages.Closed rift) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        COLLAPSES.removeIf(old -> old.rift().from().equals(rift.from()));
        COLLAPSES.add(new Collapse(rift, level.getGameTime()));
    }

    /**
     * Draws the closing rifts. For a breath the tear flares wide, then it is pulled shut: it shrinks, shaking, toward
     * its middle while shockwaves run INWARD through the frame and over the floor, growing brighter as they close
     * in, and bolts snap at it; the veil goes with it, and where they meet there is a last white spark.
     */
    private static void collapses(ClientLevel level, float partialTicks, float time, Vec3 camera, PoseStack poseStack, SubmitNodeCollector collector) {
        if (COLLAPSES.isEmpty()) return;
        long now = level.getGameTime();
        COLLAPSES.removeIf(collapse -> now - collapse.since() >= COLLAPSE_TICKS || now < collapse.since());
        for (Collapse collapse : COLLAPSES) {
            BlockPos a = collapse.rift().from(), b = collapse.rift().to();
            boolean alongX = collapse.rift().alongX(), greater = collapse.rift().greater();
            float width = (alongX ? Math.abs(a.getZ() - b.getZ()) : Math.abs(a.getX() - b.getX())) + 1;
            float height = Math.abs(a.getY() - b.getY()) + 1;
            float age = now - collapse.since() + partialTicks;
            float gone = Mth.clamp(age / COLLAPSE_TICKS, 0.0F, 1.0F), left = 1 - gone;
            long seed = a.asLong();
            poseStack.pushPose();
            poseStack.translate((a.getX() + b.getX()) / 2.0 + 0.5 - camera.x, (a.getY() + b.getY()) / 2.0 + 0.5 - camera.y,
                    (a.getZ() + b.getZ()) / 2.0 + 0.5 - camera.z);
            if (alongX) poseStack.mulPose(Axis.YP.rotationDegrees(90.0F));

            // The tear: wide at first, then drawn shut and small, never still
            float open = (float) Math.pow(left, 1.4);
            float size = Math.max(width, height) * (greater ? 0.6F : 0.7F) * (0.35F + 0.65F * left) * (1 + 0.3F * left * Mth.sin(age * 2.3F));
            // The veil shrinks toward the middle with it and fades at the very end
            float keep = gone < 0.45F ? 1 : 1 - (gone - 0.45F) / 0.55F;
            int veilAlpha = (int) (255 * Mth.clamp(left * 4, 0.0F, 1.0F));
            Tear.Veil veil = new Tear.Veil(VEIL, (-width / 2 - VEIL_INTO_FRAME) * keep, (width / 2 + VEIL_INTO_FRAME) * keep,
                    -height / 2 * keep, (height / 2 + VEIL_INTO_FRAME) * keep, VEIL_TILE, veilAlpha << 24 | 0xFFFFFF);
            if (keep > 0.02F) Tear.submit(poseStack, collector, now(greater ? WIDE : DOOR, time), open, size, time, true, veil);

            // Shockwaves running inward: {delay, flat}
            float reach = Math.max(width, height) * 0.5F + 4.0F;
            float[][] waves = {{0, 0}, {6, 0}, {3, 1}};
            for (int index = 0; index < waves.length; index++) {
                float lived = (age - waves[index][0]) / (COLLAPSE_TICKS - 4 - waves[index][0]);
                if (lived <= 0 || lived >= 1) continue;
                ring(poseStack, collector, seed + index * 7L, (int) (time / 2), reach * (1 - lived) * (1 - lived), 0.25F + 0.75F * lived,
                        0.05F + 0.1F * lived, waves[index][1] > 0, height);
            }
            if (left > 0.15F) bolts(poseStack, collector, seed + 3, time, width / 2, height / 2, 3, 0.6F * left);

            // The last spark, where everything met
            if (gone > 0.82F) {
                float spark = (1 - gone) / 0.18F;
                float r = 0.15F + 0.9F * spark;
                int alpha = (int) (255 * spark);
                collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
                    // a four-pointed star: two thin crossed diamonds
                    float[][][] diamonds = {{{-r, 0}, {0, -r * 0.12F}, {r, 0}, {0, r * 0.12F}}, {{-r * 0.12F, 0}, {0, -r}, {r * 0.12F, 0}, {0, r}}};
                    for (float[][] q : diamonds) {
                        for (int pass = 0; pass < 2; pass++) {
                            for (int i = 0; i < 4; i++) {
                                float[] c = q[pass == 0 ? i : 3 - i];
                                buffer.addVertex(pose, c[0], c[1], 0).setColor(alpha << 24 | 0xF0DEFF);
                            }
                        }
                    }
                });
            }
            poseStack.popPose();
        }
    }

    /**
     * One shockwave: a ring in the pose's plane (or flat over the floor, {@code height} / 2 under the pose), torn
     * out of shape and different at every {@code step}.
     */
    private static void ring(PoseStack poseStack, SubmitNodeCollector collector, long seed, int step, float radius, float fade, float thick,
                             boolean flat, float height) {
        int segments = 28;
        float[] points = new float[(segments + 1) * 3];
        for (int k = 0; k <= segments; k++) {
            int at = k % segments;
            float angle = at * Mth.TWO_PI / segments;
            int jolt = mix(seed + step, at);
            float r = radius * (1 + (unit(jolt) - 0.5F) * 0.3F);
            points[k * 3] = Mth.cos(angle) * r;
            points[k * 3 + 1] = Mth.sin(angle) * r;
            points[k * 3 + 2] = (unit(jolt >>> 8) - 0.5F) * 0.1F * radius;
        }
        int halo = (int) (120 * fade) << 24 | 0xB26BFF, white = (int) (255 * fade) << 24 | 0xF0DEFF;
        poseStack.pushPose();
        if (flat) {
            poseStack.translate(0, -height / 2 + 0.08F, 0);
            poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
        }
        collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
            for (int k = 0; k + 5 < points.length; k += 3) {
                ribbon(pose, buffer, points, k, thick * 2.6F, halo);
                ribbon(pose, buffer, points, k, thick * 0.7F, white);
            }
        });
        poseStack.popPose();
    }
}
