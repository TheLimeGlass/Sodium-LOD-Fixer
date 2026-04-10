package me.limeglass.sodiumfixer.mixin;

import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import me.limeglass.sodiumfixer.ext.IRenderSectionExtension;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@IfModLoaded("sodium")
@Mixin(value = RenderSection.class, remap = false)
public class SodiumRenderSectionMixin implements IRenderSectionExtension {
	@Unique
	private volatile boolean sodiumlodfixer$submittedRebuild;

	@Unique
	private volatile boolean sodiumlodfixer$seen;

	@Override
	public boolean sodiumlodfixer_isSubmittedRebuild() {
		return sodiumlodfixer$submittedRebuild;
	}

	@Override
	public void sodiumlodfixer_setSubmittedRebuild(boolean value) {
		sodiumlodfixer$submittedRebuild = value;
	}

	@Override
	public boolean sodiumlodfixer_isSeen() {
		return sodiumlodfixer$seen;
	}

	@Override
	public void sodiumlodfixer_setSeen(boolean value) {
		sodiumlodfixer$seen = value;
	}
}
