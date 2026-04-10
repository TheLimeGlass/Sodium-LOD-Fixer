package me.limeglass.sodiumfixer;

/**
 * Shared state between the async BFS thread and the OcclusionCuller mixin.
 * <p>
 * When the async cull thread is running, it sets {@link #SKIP_FRUSTUM_CULLING} to {@code true}
 * so the overwritten {@code processQueue} skips the expensive frustum check (which would use a
 * stale viewport from a previous frame and cause visible pop-in at the screen edges).
 */
public final class AsyncBfsState {
	/**
	 * When set to {@code true} on the current thread, processQueue will skip the frustum check
	 * and only use the cheap distance pre-filter.
	 */
	public static final ThreadLocal<Boolean> SKIP_FRUSTUM_CULLING = ThreadLocal.withInitial(() -> false);

	private AsyncBfsState() {}
}

