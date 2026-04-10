package me.limeglass.sodiumfixer.ext;

/**
 * Extension interface added to {@link net.caffeinemc.mods.sodium.client.render.chunk.RenderSection}
 * via mixin to track async BFS state.
 */
public interface IRenderSectionExtension {
	/**
	 * Whether this section has already been submitted to the rebuild queue by the async tracker.
	 */
	boolean sodiumlodfixer_isSubmittedRebuild();

	void sodiumlodfixer_setSubmittedRebuild(boolean value);

	/**
	 * Whether this section has been seen (enqueued) by the current async BFS iteration.
	 */
	boolean sodiumlodfixer_isSeen();

	void sodiumlodfixer_setSeen(boolean value);
}

