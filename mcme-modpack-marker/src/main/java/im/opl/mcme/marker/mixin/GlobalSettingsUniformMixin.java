package im.opl.mcme.marker.mixin;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.McmeConfig;
import im.opl.mcme.marker.ShaderClock;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Vanilla's shaders are given real time for the world's ({@link ShaderClock}),
 * so that what moves by it doesn't skip, or stand still, when the server sets
 * the client's clock. GameTime is the second float the Globals block is
 * filled with, after GlintAlpha.
 */
@Mixin(GlobalSettingsUniform.class)
public abstract class GlobalSettingsUniformMixin {
	@ModifyArg(method = "update", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 1), require = 0)
	private float mcme$realGameTime(float gameTime) {
		if (!McmeConfig.get().realTimeClock) return gameTime;
		if (!mcme$logged) {
			mcme$logged = true;
			MCMEModpackMarker.LOGGER.info("Shaders' GameTime runs on real time (MCME)");
		}
		return ShaderClock.dayFraction();
	}

	private static boolean mcme$logged;
}
