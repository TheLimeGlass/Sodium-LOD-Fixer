package me.limeglass.sodiumfixer;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.limeglass.sodiumfixer.ext.IRenderSectionExtension;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.Future;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Async occlusion culling tracker inspired by Nvidium's AsyncOcclusionTracker.
 * <p>
 * Runs the BFS graph traversal ({@link OcclusionCuller#findVisible}) on a dedicated background thread
 * so the render thread is not blocked by expensive visibility checks at high render distances.
 * <p>
 * After BFS completes, post-processing (sorting by distance, collecting block entities / animated sprites /
 * rebuild candidates) is parallelized across a {@link ForkJoinPool} sized by {@code Fobby.CONFIG.threads}.
 * <p>
 * Results are exchanged lock-free with the render thread using {@link AtomicReference}.
 */
public class AsyncOcclusionTracker {
	private static final Logger LOGGER = LogManager.getLogger("Fobby/AsyncBFS");

	private final OcclusionCuller occlusionCuller;
	private final Future<?> runTask;
	private final ForkJoinPool workerPool;
	private final Level world;

	private volatile boolean running = true;
	private volatile int frame = 0;
	private volatile Viewport viewport = null;

	private final Semaphore framesAhead = new Semaphore(0);

	private final AtomicReference<List<RenderSection>> atomicBfsResult = new AtomicReference<>();
	private final AtomicReference<List<RenderSection>> atomicBfsPriorityResult = new AtomicReference<>();
	private final AtomicReference<List<RenderSection>> blockEntitySectionsRef = new AtomicReference<>(new ArrayList<>());
	private final AtomicReference<TextureAtlasSprite[]> visibleAnimatedSpritesRef = new AtomicReference<>();

	/** Visible sections sorted by distance from camera, produced by the async thread. */
	private final AtomicReference<RenderSection[]> sortedVisibleSectionsRef = new AtomicReference<>(new RenderSection[0]);

	private final Map<TaskQueueType, ArrayDeque<RenderSection>> outputRebuildQueue;

	private final float renderDistance;
	private final int renderDistanceChunks;
	private volatile long iterationTimeMillis;
	private volatile boolean shouldUseOcclusionCulling = true;

	private volatile int chunkVisibilityCount = 0;

	public AsyncOcclusionTracker(int renderDistance, Long2ReferenceMap<RenderSection> sections, Level world,
                                 Map<TaskQueueType, ArrayDeque<RenderSection>> outputRebuildQueue) {
		this.occlusionCuller = new OcclusionCuller(sections, world);
		this.workerPool = new ForkJoinPool(5, pool -> {
			ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
			t.setName("Fobby Cull Worker-" + t.getPoolIndex());
			t.setDaemon(true);
			return t;
		}, null, false);

		// Submit the long-running cull loop as a worker task so it runs on our pool.
		// This reserves one pool worker for the loop; the rest serve parallel post-processing.
		this.runTask = this.workerPool.submit(this::run);
		this.renderDistance = renderDistance * 16f;
		this.renderDistanceChunks = renderDistance;

		this.outputRebuildQueue = outputRebuildQueue;
		this.world = world;
	}

	private void run() {
		while (running) {
			framesAhead.acquireUninterruptibly();
			if (!running) break;
			long startTime = System.currentTimeMillis();

			final Viewport currentViewport = this.viewport;
			final int camX = currentViewport.getChunkCoord().getX();
			final int camY = currentViewport.getChunkCoord().getY();
			final int camZ = currentViewport.getChunkCoord().getZ();

			// ---- Phase 1: BFS (sequential — graph traversal with dependencies) ----
			// The visitor is kept minimal: just collect every visible section into a list.
			List<RenderSection> allVisible = new ArrayList<>();
			final RenderSectionVisitor visitor = allVisible::add;

			frame++;
			float searchDistance = this.renderDistance;
			boolean useOcclusionCulling = this.shouldUseOcclusionCulling;

			try {
				// Skip frustum culling on the async thread — the viewport frustum is from a previous
				// frame, so it would incorrectly cull sections at the screen edges when turning.
				// Distance culling alone keeps the section count bounded.
				AsyncBfsState.SKIP_FRUSTUM_CULLING.set(true);
				this.occlusionCuller.findVisible(visitor, currentViewport, searchDistance, useOcclusionCulling, frame);
			} catch (Throwable e) {
				LOGGER.error("Error during async BFS traversal", e);
			} finally {
				AsyncBfsState.SKIP_FRUSTUM_CULLING.set(false);
			}

			// ---- Phase 2: parallel post-processing ----
			RenderSection[] sortedVisible = allVisible.toArray(new RenderSection[0]);

			// Parallel sort by distance from camera (nearest first)
			// Use our workerPool so we don't rely on ForkJoinPool.commonPool
			if (sortedVisible.length > 1) {
				workerPool.invoke(new ParallelSort(sortedVisible, camX, camY, camZ, 0, sortedVisible.length));
			}

			// Parallel collection of rebuild candidates, block entities, animated sprites, geometry count.
			// Each worker processes a stripe of the sorted array into thread-local lists, then merge.
			// Rebuild candidates are split into "near" (within server render distance) and "far" (LODs)
			// so the render thread can prioritize nearby chunk rebuilds.
			final boolean animateVisibleSpritesOnly = SodiumClientMod.options().performance.animateOnlyVisibleTextures;
			final int nearRadiusSq = renderDistanceChunks * renderDistanceChunks;
			int len = sortedVisible.length;
			int threadCount = Math.max(1, Math.min(workerPool.getParallelism(), len));
			int chunkSize = (len + threadCount - 1) / threadCount;

			// Thread-local accumulators
			@SuppressWarnings("unchecked")
			List<RenderSection>[] nearUpdateBuckets = new List[threadCount];
			@SuppressWarnings("unchecked")
			List<RenderSection>[] farUpdateBuckets = new List[threadCount];
			@SuppressWarnings("unchecked")
			List<RenderSection>[] blockEntityBuckets = new List[threadCount];
			@SuppressWarnings("unchecked")
			Set<TextureAtlasSprite>[] spriteBuckets = animateVisibleSpritesOnly ? new Set[threadCount] : null;
			int[] geometryCounts = new int[threadCount];

			try {
				CountDownLatch latch = new CountDownLatch(threadCount);
				for (int t = 0; t < threadCount; t++) {
					final int threadIdx = t;
					final int from = t * chunkSize;
					final int to = Math.min(from + chunkSize, len);
					workerPool.execute(() -> {
						try {
							List<RenderSection> localNearUpdates = new ArrayList<>();
							List<RenderSection> localFarUpdates = new ArrayList<>();
							List<RenderSection> localBlockEntities = new ArrayList<>();
							Set<TextureAtlasSprite> localSprites = animateVisibleSpritesOnly ? new HashSet<>() : null;
							int localGeometry = 0;

							for (int i = from; i < to; i++) {
								RenderSection section = sortedVisible[i];
								int flags = section.getFlags();

								// Rebuild candidates — split into near (priority) and far (LOD)
								if (section.getPendingUpdate() > 0) {
									IRenderSectionExtension ext = (IRenderSectionExtension) section;
									if (!ext.sodiumlodfixer_isSubmittedRebuild() && !ext.sodiumlodfixer_isSeen()) {
										ext.sodiumlodfixer_setSeen(true);
										int dx = section.getChunkX() - camX;
										int dz = section.getChunkZ() - camZ;
										if (dx * dx + dz * dz <= nearRadiusSq) {
											localNearUpdates.add(section);
										} else {
											localFarUpdates.add(section);
										}
									}
								}

								// Geometry count
								if ((flags & (1 << RenderSectionFlags.HAS_BLOCK_GEOMETRY)) != 0) {
									localGeometry++;
								}

								// Block entities (within 33 chunk distance)
								if ((flags & (1 << RenderSectionFlags.HAS_BLOCK_ENTITIES)) != 0 &&
									section.getPosition().closerThan(currentViewport.getChunkCoord(), 33)) {
									localBlockEntities.add(section);
								}

								// Animated sprites (within 33 chunk distance)
								if (localSprites != null &&
									(flags & (1 << RenderSectionFlags.HAS_ANIMATED_SPRITES)) != 0 &&
									section.getPosition().closerThan(currentViewport.getChunkCoord(), 33)) {
									var animatedSprites = section.getAnimatedSprites();
									if (animatedSprites != null) {
										Collections.addAll(localSprites, animatedSprites);
									}
								}
							}

							nearUpdateBuckets[threadIdx] = localNearUpdates;
							farUpdateBuckets[threadIdx] = localFarUpdates;
							blockEntityBuckets[threadIdx] = localBlockEntities;
							if (spriteBuckets != null) spriteBuckets[threadIdx] = localSprites;
							geometryCounts[threadIdx] = localGeometry;
						} finally {
							latch.countDown();
						}
					});
				}
				latch.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			// Merge results — keep near (priority) and far (LOD) updates separate
			List<RenderSection> nearUpdates = new ArrayList<>();
			List<RenderSection> farUpdates = new ArrayList<>();
			List<RenderSection> blockEntitySections = new ArrayList<>();
			Set<TextureAtlasSprite> animatedSpriteSet = animateVisibleSpritesOnly ? new HashSet<>() : null;
			int totalGeometry = 0;

			for (int t = 0; t < threadCount; t++) {
				if (nearUpdateBuckets[t] != null) nearUpdates.addAll(nearUpdateBuckets[t]);
				if (farUpdateBuckets[t] != null) farUpdates.addAll(farUpdateBuckets[t]);
				if (blockEntityBuckets[t] != null) blockEntitySections.addAll(blockEntityBuckets[t]);
				if (animatedSpriteSet != null && spriteBuckets != null && spriteBuckets[t] != null) {
					animatedSpriteSet.addAll(spriteBuckets[t]);
				}
				totalGeometry += geometryCounts[t];
			}

			// ---- Publish results ----
			sortedVisibleSectionsRef.set(sortedVisible);

		// Publish priority (near) rebuild candidates
		if (!nearUpdates.isEmpty()) {
			var previous = atomicBfsPriorityResult.getAndSet(nearUpdates);
			if (previous != null) {
				for (var section : previous) {
					if (section.isDisposed()) continue;
					((IRenderSectionExtension) section).sodiumlodfixer_setSeen(false);
				}
			}
		}

		// Publish far (LOD) rebuild candidates
		if (!farUpdates.isEmpty()) {
			var previous = atomicBfsResult.getAndSet(farUpdates);
			if (previous != null) {
				for (var section : previous) {
					if (section.isDisposed()) continue;
					((IRenderSectionExtension) section).sodiumlodfixer_setSeen(false);
				}
			}
		}

			this.chunkVisibilityCount = totalGeometry;
			blockEntitySectionsRef.set(blockEntitySections);
			visibleAnimatedSpritesRef.set(animatedSpriteSet == null ? null : animatedSpriteSet.toArray(new TextureAtlasSprite[0]));
			iterationTimeMillis = System.currentTimeMillis() - startTime;
		}
	}

	/**
	 * Called from the render thread each frame to push a new viewport and consume async results.
	 */
	public void update(Viewport viewport, Camera camera, boolean spectator) {
		this.shouldUseOcclusionCulling = this.shouldUseOcclusionCulling(camera, spectator);
		this.viewport = viewport;

		// Cap at 5 frames ahead to prevent runaway when traversal is slower than frame time
		if (framesAhead.availablePermits() < 5) {
			framesAhead.release();
		}

		// Consume async BFS results and submit to rebuild queues.
		// Priority (near) sections are submitted first so chunks around the player
		// always get rebuilt before distant LODs.
		sodiumlodfixer$consumeBfsResult(atomicBfsPriorityResult.getAndSet(null));
		sodiumlodfixer$consumeBfsResult(atomicBfsResult.getAndSet(null));
	}

	private void sodiumlodfixer$consumeBfsResult(List<RenderSection> bfsResult) {
		if (bfsResult == null) return;
		TaskQueueType importantRebuildQueueType = SodiumClientMod.options().performance.chunkBuildDeferMode.getImportantRebuildQueueType();
		for (var section : bfsResult) {
			if (section.isDisposed()) continue;

			int type = section.getPendingUpdate();
			if (type != 0 && section.getRunningJob() == null) {
				TaskQueueType queueType = ChunkUpdateTypes.getQueueType(type, importantRebuildQueueType, importantRebuildQueueType);
				var queue = outputRebuildQueue.get(queueType);
				if (queue != null && queue.size() < queueType.queueSizeLimit()) {
					((IRenderSectionExtension) section).sodiumlodfixer_setSubmittedRebuild(true);
					queue.add(section);
				}
			}
			// Reset seen flag whether submitted or not
			((IRenderSectionExtension) section).sodiumlodfixer_setSeen(false);
		}
	}

	/**
	 * Stops the background thread and waits for it to finish.
	 */
	public void delete() {
		running = false;
		// Wake up the cull task if it's waiting
		framesAhead.release(1000);
		try {
			// Wait a short time for the run task to finish
			runTask.get(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (TimeoutException te) {
			// Cancel if still running
			runTask.cancel(true);
		} catch (ExecutionException ee) {
			LOGGER.error("Cull task terminated with an exception", ee.getCause());
		} finally {
			// Shutdown worker pool
			workerPool.shutdownNow();
		}
	}

	private boolean shouldUseOcclusionCulling(Camera camera, boolean spectator) {
		BlockPos origin = camera.blockPosition();
		if (spectator && this.world.getBlockState(origin).isSolidRender()) {
			return false;
		}
		return Minecraft.getInstance().smartCull;
	}

	public int getFrame() {
		return frame;
	}

	public List<RenderSection> getLatestSectionsWithEntities() {
		return blockEntitySectionsRef.get();
	}

	@Nullable
	public TextureAtlasSprite[] getVisibleAnimatedSprites() {
		return visibleAnimatedSpritesRef.get();
	}

	public long getIterationTime() {
		return this.iterationTimeMillis;
	}

	public int getLastVisibilityCount() {
		return this.chunkVisibilityCount;
	}

	/**
	 * Returns the latest distance-sorted array of visible sections produced by the async thread.
	 * Sorted nearest-to-camera first.
	 */
	public RenderSection[] getSortedVisibleSections() {
		return sortedVisibleSectionsRef.get();
	}

	/**
	 * Simple parallel merge sort task that sorts RenderSection[] by squared distance to a camera chunk.
	 * Runs inside our workerPool so we control parallelism.
	 */
	private static final class ParallelSort extends RecursiveAction {
		private static final int THRESHOLD = 2048; // tuneable - sorts <= this size with Arrays.sort
		private final RenderSection[] array;
		private final int camX, camY, camZ;
		private final int lo, hi; // [lo, hi)

		ParallelSort(RenderSection[] array, int camX, int camY, int camZ, int lo, int hi) {
			this.array = array;
			this.camX = camX;
			this.camY = camY;
			this.camZ = camZ;
			this.lo = lo;
			this.hi = hi;
		}

		@Override
		protected void compute() {
			int len = hi - lo;
			if (len <= THRESHOLD) {
				Arrays.sort(array, lo, hi, Comparator.comparingInt(a -> {
					int dx = a.getChunkX() - camX, dy = a.getChunkY() - camY, dz = a.getChunkZ() - camZ;
					return dx * dx + dy * dy + dz * dz;
				}));
				return;
			}
			int mid = lo + (len >> 1);
			ParallelSort left = new ParallelSort(array, camX, camY, camZ, lo, mid);
			ParallelSort right = new ParallelSort(array, camX, camY, camZ, mid, hi);
			invokeAll(left, right);
			// merge into temp
			RenderSection[] tmp = new RenderSection[len];
			int i = lo, j = mid, k = 0;
			while (i < mid && j < hi) {
				RenderSection a = array[i];
				RenderSection b = array[j];
				int ad = distSq(a, camX, camY, camZ);
				int bd = distSq(b, camX, camY, camZ);
				if (ad <= bd) {
					tmp[k++] = a; i++;
				} else {
					tmp[k++] = b; j++;
				}
			}
			while (i < mid) tmp[k++] = array[i++];
			while (j < hi) tmp[k++] = array[j++];
			System.arraycopy(tmp, 0, array, lo, len);
		}

		private static int distSq(RenderSection s, int cx, int cy, int cz) {
			int dx = s.getChunkX() - cx;
			int dy = s.getChunkY() - cy;
			int dz = s.getChunkZ() - cz;
			return dx * dx + dy * dy + dz * dz;
		}
	}
}
