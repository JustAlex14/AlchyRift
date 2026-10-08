package com.lealex.alchyrift.client;

import com.lealex.alchyrift.AlchyRift;
import com.lealex.alchyrift.block.RiftClumpBlock;
import com.lealex.alchyrift.conduit.RiftConduitBlock;
import com.lealex.alchyrift.registry.ModRegistries;
import com.lealex.alchyrift.relay.RiftRelayBlock;
import com.lealex.alchyrift.relay.RiftRelayBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.model.object.crystal.EndCrystalModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EndCrystalRenderer;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What a rift relay holds over its nest of shards: a crack in reality, caged. The cage is vanilla's end crystal
 * model (two frames turning one inside the other; no heart, no base) wearing a skin of obsidian shards and amethyst
 * (textures/entity/*_relay_cage.png, tools/block_textures.py), and inside it hangs the small rift (the stabilizer's).
 * Asleep, the cage barely drifts and the crack is a hairline. Linked, it turns and the crack parts. While something
 * goes through a greater relay, it spins and the rift tears open.
 */
public class RiftRelayRenderer implements BlockEntityRenderer<RiftRelayBlockEntity, RiftRelayRenderer.State> {
    private static final Identifier[] CAGES = {
            Identifier.fromNamespaceAndPath(AlchyRift.MODID, "textures/entity/rift_relay_cage.png"),
            Identifier.fromNamespaceAndPath(AlchyRift.MODID, "textures/entity/greater_rift_relay_cage.png")};
    /** Per tier: the cage's size (of vanilla's model), where its origin sits above the block's bottom, the rift's width. */
    private static final float[] SCALE = {0.55F, 0.66F}, LIFT = {-0.05F, -0.16F}, RIFT_SIZE = {0.42F, 0.56F};

    public static class State extends BlockEntityRenderState {
        public final EndCrystalRenderState cage = new EndCrystalRenderState();
        public int tier;
        public float open, time;
        /** One per side a conduit reaches the relay from: the side's ordinal, and the colour of what that conduit carries. */
        public final List<int[]> tethers = new ArrayList<>();
        /** For each of them, the clump of amethyst at the end of the conduit's arm (a look block, turned to that side). */
        public final List<MovingBlockRenderState> clumps = new ArrayList<>();
        public long seed;
    }

    /** What ties a conduit to the cage: the colour of what it carries (items, fluids, energy). */
    private static final int[] KIND_COLORS = {0xB26BFF, 0x5ADCD0, 0xFFC044};

    /** Per relay: its accumulated turn, the last time it was advanced, its speed now, how open its crack is. */
    private static final Map<RiftRelayBlockEntity, double[]> MOTION = new WeakHashMap<>();

    private final EndCrystalModel model;

    public RiftRelayRenderer(BlockEntityRendererProvider.Context context) {
        this.model = new EndCrystalModel(context.bakeLayer(ModelLayers.END_CRYSTAL));
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(RiftRelayBlockEntity relay, State state, float partialTicks,
                                   Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(relay, state, partialTicks, cameraPosition, breakProgress);
        Level level = relay.getLevel();
        BlockState block = relay.getBlockState();
        boolean linked = block.hasProperty(RiftRelayBlock.LINKED) && block.getValue(RiftRelayBlock.LINKED);
        boolean open = block.hasProperty(RiftRelayBlock.OPEN) && block.getValue(RiftRelayBlock.OPEN);
        double now = level == null ? 0 : level.getGameTime() + (double) partialTicks;
        double speed = open ? 2.2 : linked ? 0.7 : 0.12, opening = open ? 1.0 : linked ? 0.42 : 0.1;

        // Advanced by its own speed each frame, never computed from the game time: a change of state can't make it jump
        double[] motion = MOTION.computeIfAbsent(relay, r -> new double[] {(r.getBlockPos().asLong() & 1023), now, speed, opening});
        double dt = Math.max(0, Math.min(now - motion[1], 10));
        motion[2] += (speed - motion[2]) * Math.min(1, dt * 0.08);
        motion[3] += (opening - motion[3]) * Math.min(1, dt * 0.1);
        motion[0] = (motion[0] + dt * motion[2]) % 120000;
        motion[1] = now;

        state.tier = Math.min(2, Math.max(1, relay.tier())) - 1;
        state.cage.ageInTicks = (float) motion[0];
        state.cage.showsBottom = false;
        state.time = (float) (now % 24000.0);
        state.open = (float) motion[3] * (0.94F + 0.06F * (float) Math.sin(now * 0.17));

        // The conduits that reach it: each is tied to the crack by a thread of light
        state.tethers.clear();
        state.clumps.clear();
        state.seed = relay.getBlockPos().asLong();
        if (level instanceof ClientLevel client) {
            BlockPos here = relay.getBlockPos();
            for (Direction direction : Direction.values()) {
                BlockState beside = level.getBlockState(here.relative(direction));
                if (beside.getBlock() instanceof RiftConduitBlock conduit
                        && beside.getValue(RiftConduitBlock.property(direction.getOpposite())).joins()) {
                    state.tethers.add(new int[] {direction.ordinal(), KIND_COLORS[conduit.kind().ordinal() % KIND_COLORS.length]});
                    MovingBlockRenderState clump = new MovingBlockRenderState();
                    clump.randomSeedPos = here;
                    clump.blockPos = here;
                    RiftClumpBlock.Kind[] kinds = RiftClumpBlock.Kind.values();
                    clump.blockState = ModRegistries.RIFT_CLUMP.get().defaultBlockState().setValue(RiftClumpBlock.FACING, direction)
                            .setValue(RiftClumpBlock.KIND, kinds[conduit.kind().ordinal() % kinds.length]);
                    clump.biome = client.getBiome(here);
                    clump.cardinalLighting = client.cardinalLighting();
                    clump.lightEngine = client.getLightEngine();
                    state.clumps.add(clump);
                }
            }
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        float scale = SCALE[state.tier], lift = LIFT[state.tier];
        poseStack.pushPose();
        poseStack.translate(0.5F, lift, 0.5F);
        poseStack.scale(scale, scale, scale);
        collector.submitModel(model, state.cage, poseStack, CAGES[state.tier], state.lightCoords, OverlayTexture.NO_OVERLAY, 0, state.breakProgress);
        poseStack.popPose();

        // The crack, in the middle of the cage wherever it bobs (the model hangs its frames 1.5 above its origin)
        float middle = lift + scale * (1.5F + EndCrystalRenderer.getY(state.cage.ageInTicks) / 2.0F);
        poseStack.pushPose();
        poseStack.translate(0.5F, middle, 0.5F);
        Rifts.submitHanging(poseStack, collector, camera, RIFT_SIZE[state.tier], state.open, state.time);
        poseStack.popPose();

        for (MovingBlockRenderState clump : state.clumps) collector.submitMovingBlock(poseStack, clump);
        if (state.tethers.isEmpty()) return;
        // From the clump of amethyst at the end of each conduit's arm (its point pokes into this cell) to the crack:
        // small lightning, restless, brighter the wider the crack is open
        int tick = (int) state.time;
        int alpha = (int) (90 + 165 * state.open);
        collector.submitCustomGeometry(poseStack, RenderTypes.lightning(), (pose, buffer) -> {
            for (int[] tether : state.tethers) {
                Direction side = Direction.values()[tether[0]];
                float fromX = 0.5F + side.getStepX() * 0.34F, fromY = 0.5F + side.getStepY() * 0.34F, fromZ = 0.5F + side.getStepZ() * 0.34F;
                int segments = 5;
                float[] points = new float[(segments + 1) * 3];
                for (int k = 0; k <= segments; k++) {
                    float f = k / (float) segments, wild = k == 0 || k == segments ? 0 : 0.09F;
                    int jolt = Rifts.mix(state.seed + tether[0] * 31L + tick / 2, k);
                    points[k * 3] = Mth.lerp(f, fromX, 0.5F) + (Rifts.unit(jolt) - 0.5F) * wild;
                    points[k * 3 + 1] = Mth.lerp(f, fromY, middle) + (Rifts.unit(jolt >>> 8) - 0.5F) * wild;
                    points[k * 3 + 2] = Mth.lerp(f, fromZ, 0.5F) + (Rifts.unit(jolt >>> 16) - 0.5F) * wild;
                }
                for (int k = 0; k + 5 < points.length; k += 3) {
                    Rifts.ribbon(pose, buffer, points, k, 0.035F, (alpha / 2) << 24 | tether[1]);
                    Rifts.ribbon(pose, buffer, points, k, 0.012F, alpha << 24 | 0xF0DEFF);
                }
            }
        });
    }

    @Override
    public AABB getRenderBoundingBox(RiftRelayBlockEntity relay) {
        return new AABB(relay.getBlockPos()).inflate(0.4);
    }
}
