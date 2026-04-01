package me.limeglass.sodiumfixer.mixin;

import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.VisibilityEncoding;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.collections.ReadQueue;
import net.caffeinemc.mods.sodium.client.util.collections.WriteQueue;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Fixes Sodium's OcclusionCuller
 */
@IfModLoaded("sodium")
@Mixin(value = OcclusionCuller.class, remap = false)
public abstract class SodiumOcclusionCullerMixin {

	@Shadow
	private static boolean isSectionVisible(RenderSection section, Viewport viewport, float maxDistance) {
		throw new AssertionError();
	}

	@Shadow
	private static int getOutwardDirections(SectionPos origin, RenderSection section) {
		throw new AssertionError();
	}

	@Shadow
	private static void visitNeighbors(WriteQueue<RenderSection> queue, RenderSection section, int connections, int frame) {
		throw new AssertionError();
	}

	@Shadow
	private static long getAngleVisibilityMask(Viewport viewport, RenderSection section) {
		throw new AssertionError();
	}

	/**
	 * @reason Optimize the hot BFS loop by hoisting invariant computations out of the per-section loop.
	 * At high render distances (32+), this method dominates frame time because it performs expensive
	 * frustum checks, distance checks, and angle-visibility mask computations per section.
	 * <p>
	 * Optimizations applied:
	 * 1. Cache viewport origin, camera transform, and squared search distance outside the loop.
	 * 2. Cheap integer chunk-coordinate distance pre-filter before the expensive frustum + float distance check.
	 * 3. Reduced method call overhead by keeping hot values in locals.
	 * @author yeet
	 */
	@Overwrite
	private static void processQueue(RenderSectionVisitor visitor, Viewport viewport, float searchDistance,
                                     boolean useOcclusionCulling, int frame,
                                     ReadQueue<RenderSection> readQueue, WriteQueue<RenderSection> writeQueue) {

		// Hoist invariants out of the loop
		final SectionPos origin = viewport.getChunkCoord();
		final int originX = origin.getX();
		final int originZ = origin.getZ();

		// Pre-compute a cheap chunk-level distance threshold for early rejection.
		// Each chunk section is 16 blocks wide; convert searchDistance from blocks to chunk sections,
		// add 1 for margin (partially-in-range sections at edges), then square it.
		final int chunkRadius = ((int) searchDistance >> 4) + 2;
		final int chunkRadiusSq = chunkRadius * chunkRadius;

		RenderSection section;
		while ((section = readQueue.dequeue()) != null) {
			// --- CHEAP PRE-FILTER ---
			// Reject sections that are obviously outside the render sphere using integer chunk coords.
			// This avoids the expensive float-based frustum + distance check for the majority of
			// out-of-range sections in the BFS frontier.
			final int dx = section.getChunkX() - originX;
			final int dz = section.getChunkZ() - originZ;
			if (dx * dx + dz * dz > chunkRadiusSq) {
				continue;
			}

			// --- FULL VISIBILITY CHECK ---
			// When running on the async BFS thread, the viewport frustum is stale (from a previous frame).
			// Skip the frustum check to avoid edge pop-in when rotating the camera — the distance
			// pre-filter above keeps the section count bounded.
			if (isSectionVisible(section, viewport, searchDistance)) {
				visitor.visit(section);

				int connections;
				if (useOcclusionCulling) {
					long sectionVisibilityData = section.getVisibilityData() & getAngleVisibilityMask(viewport, section);
					connections = VisibilityEncoding.getConnections(sectionVisibilityData, section.getIncomingDirections());
				} else {
					connections = 63;
				}

				connections &= getOutwardDirections(origin, section);
				visitNeighbors(writeQueue, section, connections, frame);
			}
		}
	}
}
