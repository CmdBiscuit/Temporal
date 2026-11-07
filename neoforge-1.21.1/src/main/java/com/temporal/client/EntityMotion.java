package com.temporal.client;

import com.mojang.blaze3d.platform.GlStateManager;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * Where moving entities are and how far they moved since the last frame, for motion vectors and TAA reprojection.
 *
 * <p>Motion otherwise comes from reprojecting depth with the camera alone, so anything moving by itself (mobs, players,
 * dropped items, minecarts) gets the motion of a still object and leaves trails in TAA, DLSS and FSR 3 and lands in
 * the wrong place in generated frames. Each frame the nearest visible moving entities go into a small float texture as
 * boxes (their hitbox, a little enlarged for model parts that stick out) with their movement; the shaders give
 * every pixel inside a box that entity's movement (see {@code entity_motion.glsl}). Rigid movement only: limbs swinging
 * or a mob turning on the spot still look still.
 */
public final class EntityMotion {
    /** Entities considered per frame; beyond this the nearest win. */
    private static final int MAX = 64;
    private static final double MAX_DISTANCE = 96.0;
    /** Below this movement per frame (in blocks) an entity counts as still. */
    private static final double MIN_MOVE = 1.0e-4;
    /** Beyond this it teleported (ender pearl, respawn): no motion to follow. */
    private static final double MAX_MOVE = 4.0;
    /** Model parts (heads, tails, held items) often stick out of the hitbox. Not below it: that's the ground. */
    private static final double MARGIN = 0.2;

    /** Debug switch: leave moving entities with camera-only motion. */
    public static boolean debugDisabled;

    private static Map<Integer, Vec3> lastPositions = new HashMap<>();
    @Nullable private static ClientLevel lastLevel;
    private static final FloatBuffer data = MemoryUtil.memAllocFloat(MAX * 3 * 4);
    private static int texture = -1;
    private static int count;

    private record Moving(String name, Vec3 movement, AABB box, double distance) {}

    private static List<String> recorded = List.of();

    /**
     * Records this frame's moving entities. Called once per frame after the level is drawn, before anything reads
     * motion; {@code camera} is this frame's camera and {@code frustum} the one the level was culled with.
     */
    public static void update(Camera camera, DeltaTracker deltaTracker, Frustum frustum) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            count = 0;
            return;
        }
        if (level != lastLevel) {
            lastPositions = new HashMap<>();
            lastLevel = level;
        }
        Vec3 eye = camera.getPosition();
        Map<Integer, Vec3> positions = new HashMap<>();
        List<Moving> moving = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == camera.getEntity() && !camera.isDetached()) {
                continue; // first person: the camera's own entity isn't drawn
            }
            // Exactly the position LevelRenderer draws the entity at this frame.
            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(!level.tickRateManager().isEntityFrozen(entity));
            Vec3 position = new Vec3(Mth.lerp(partialTick, entity.xOld, entity.getX()),
                    Mth.lerp(partialTick, entity.yOld, entity.getY()), Mth.lerp(partialTick, entity.zOld, entity.getZ()));
            positions.put(entity.getId(), position);
            Vec3 last = lastPositions.get(entity.getId());
            if (last == null || debugDisabled) {
                continue;
            }
            Vec3 movement = position.subtract(last);
            double distance = position.distanceTo(eye);
            double moved = movement.lengthSqr();
            if (moved < MIN_MOVE * MIN_MOVE || moved > MAX_MOVE * MAX_MOVE || distance > MAX_DISTANCE) {
                continue;
            }
            AABB hitbox = entity.getBoundingBox().move(position.subtract(entity.position()));
            AABB box = new AABB(hitbox.minX - MARGIN, hitbox.minY - 0.02, hitbox.minZ - MARGIN,
                    hitbox.maxX + MARGIN, hitbox.maxY + MARGIN, hitbox.maxZ + MARGIN);
            if (frustum.isVisible(box)) {
                moving.add(new Moving(entity.getType().toShortString(), movement, box, distance));
            }
        }
        lastPositions = positions;

        moving.sort(Comparator.comparingDouble(Moving::distance));
        count = Math.min(MAX, moving.size());
        data.clear();
        for (int row = 0; row < 3; row++) {
            for (int i = 0; i < MAX; i++) {
                if (i >= count) {
                    data.put(0.0F).put(0.0F).put(0.0F).put(0.0F);
                    continue;
                }
                Moving m = moving.get(i);
                Vec3 value = switch (row) {
                    case 0 -> new Vec3(m.box().minX, m.box().minY, m.box().minZ).subtract(eye);
                    case 1 -> new Vec3(m.box().maxX, m.box().maxY, m.box().maxZ).subtract(eye);
                    default -> m.movement();
                };
                data.put((float) value.x).put((float) value.y).put((float) value.z).put(0.0F);
            }
        }
        data.flip();
        if (texture < 0) {
            texture = GlStateManager._genTexture();
            GlStateManager._bindTexture(texture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, MAX, 3, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        } else {
            GlStateManager._bindTexture(texture);
        }
        // Minecraft's own texture uploads leave row length / skips set; tightly packed rows here.
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, MAX, 3, GL11.GL_RGBA, GL11.GL_FLOAT, data);
        GlStateManager._bindTexture(0);
        recorded = moving.stream().limit(count).map(m -> m.name() + String.format(" %.2f", m.movement().length())).toList();
    }

    /** What was recorded this frame: entity type and movement in blocks (for debugging). */
    public static List<String> recorded() {
        return recorded;
    }

    /** Hands this frame's entities (and a shader pack's hand depth) to a shader that includes {@code entity_motion.glsl}. */
    public static void apply(ShaderInstance shader) {
        if (texture >= 0) {
            shader.setSampler("EntitySampler", texture);
        }
        shader.safeGetUniform("EntityCount").set((float) (texture >= 0 ? count : 0));
        shader.safeGetUniform("HandDepth").set(ShaderPacks.handDepth());
    }

    /** Moving entities recorded this frame (for debugging). */
    public static int count() {
        return count;
    }

    private EntityMotion() {}
}
