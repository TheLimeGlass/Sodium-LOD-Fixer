package me.limeglass.sodiumfixer.mixin;

import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.limeglass.sodiumfixer.AsyncOcclusionTracker;
import me.limeglass.sodiumfixer.ext.IRenderSectionExtension;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * Hooks into Sodium's {@link RenderSectionManager} to move the expensive BFS occlusion culling
 * off the render thread onto a dedicated background thread, inspired by Nvidium.
 * <p>
 * The async thread runs {@link OcclusionCuller#findVisible} which marks each visible section's
 * {@code lastVisibleFrame}. On the render thread, we redirect the {@code findVisible} call inside
 * {@code createTerrainRenderList} to replay the async results: iterating all sections that were
 * marked visible by the async thread and calling the visitor on them. This lets the rest of
 * {@code createTerrainRenderList} build render lists normally from those visited sections.
 */
@IfModLoaded("sodium")
@Mixin(value = RenderSectionManager.class, remap = false)
public class SodiumRenderSectionManagerMixin {

	@Shadow
	@Final
	private Long2ReferenceMap<RenderSection> sectionByPosition;

	@Shadow
	private @NotNull Map<TaskQueueType, ArrayDeque<RenderSection>> taskLists;

	@Shadow
	@Final
	private int renderDistance;

	@Unique
	private AsyncOcclusionTracker sodiumlodfixer$asyncTracker;


	// ---- Lifecycle ----

	@Inject(method = "<init>", at = @At("TAIL"))
	private void sodiumlodfixer$init(ClientLevel level, int renderDistance, SortBehavior sortBehavior, CommandList commandList, CallbackInfo ci) {
		sodiumlodfixer$asyncTracker = new AsyncOcclusionTracker(renderDistance, sectionByPosition, level, taskLists);
	}

	@Inject(method = "destroy", at = @At("TAIL"))
	private void sodiumlodfixer$destroy(CallbackInfo ci) {
		if (sodiumlodfixer$asyncTracker != null) {
			sodiumlodfixer$asyncTracker.delete();
			sodiumlodfixer$asyncTracker = null;
		}
	}

	// ---- Feed the async tracker each frame ----

	@Inject(method = "update", at = @At("HEAD"))
	private void sodiumlodfixer$trackViewport(Camera camera, Viewport viewport, FogParameters fogParameters, boolean spectator, CallbackInfo ci) {
		if (sodiumlodfixer$asyncTracker != null) {
			sodiumlodfixer$asyncTracker.update(viewport, camera, spectator);
		}
	}

	// ---- Redirect findVisible inside createTerrainRenderList to replay async results ----
	// Instead of doing the expensive BFS on the render thread, we iterate all sections that
	// the async thread already marked visible and call the visitor on them.

	@Redirect(method = "createTerrainRenderList",
		at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/occlusion/OcclusionCuller;findVisible(Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/RenderSectionVisitor;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;FZI)V"))
	private void sodiumlodfixer$redirectFindVisible(OcclusionCuller instance, RenderSectionVisitor visitor,
										   Viewport viewport, float searchDistance,
										   boolean useOcclusionCulling, int frame) {
		if (sodiumlodfixer$asyncTracker == null) {
			// Async not active, run normally
			instance.findVisible(visitor, viewport, searchDistance, useOcclusionCulling, frame);
			return;
		}

		// Replay async results: the async thread already sorted visible sections by distance
		// from camera. Just iterate the pre-sorted array and visit non-disposed sections.
		RenderSection[] sorted = sodiumlodfixer$asyncTracker.getSortedVisibleSections();
		for (RenderSection section : sorted) {
			if (!section.isDisposed()) {
				visitor.visit(section);
			}
		}
	}

	// ---- Fix isSectionVisible for async frame delta ----

	@Unique
	private boolean sodiumlodfixer$isSectionVisibleAsync(RenderSection section) {
		int delta = Math.abs(section.getLastVisibleFrame() - sodiumlodfixer$asyncTracker.getFrame());
		return delta <= 1;
	}

	@Inject(method = "isSectionVisible", at = @At("HEAD"), cancellable = true)
	private void sodiumlodfixer$redirectIsSectionVisible(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (sodiumlodfixer$asyncTracker != null) {
			RenderSection section = sectionByPosition.get(
				net.minecraft.core.SectionPos.asLong(x, y, z)
			);
			if (section != null) {
				cir.setReturnValue(sodiumlodfixer$isSectionVisibleAsync(section));
			}
		}
	}

	// ---- Reset submitted flag when Sodium finishes submitting a section task ----

	@Redirect(method = "submitSectionTask",
		at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;clearPendingUpdate()V"))
	private void sodiumlodfixer$resetSubmittedOnEnqueue(RenderSection instance) {
		instance.clearPendingUpdate();
		if (sodiumlodfixer$asyncTracker != null) {
			((IRenderSectionExtension) instance).sodiumlodfixer_setSubmittedRebuild(false);
		}
	}

	// ---- Redirect visibility count ----

	@Inject(method = "getVisibleChunkCount", at = @At("HEAD"), cancellable = true)
	private void sodiumlodfixer$injectVisibilityCount(CallbackInfoReturnable<Integer> cir) {
		if (sodiumlodfixer$asyncTracker != null) {
			cir.setReturnValue(sodiumlodfixer$asyncTracker.getLastVisibilityCount());
		}
	}
}

