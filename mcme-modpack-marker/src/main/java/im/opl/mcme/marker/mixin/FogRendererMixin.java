package im.opl.mcme.marker.mixin;

import im.opl.mcme.marker.dh.DhFog;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla's Fog block, as it is sent each frame: with Distant Horizons' fog at the fire eye in it (DhFog). */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	@Inject(method = "updateBuffer(Lnet/minecraft/client/renderer/fog/FogData;)V", at = @At("HEAD"), require = 0)
	private void mcme$eyeFog(FogData fog, CallbackInfo ci) {
		DhFog.encode(fog);
	}
}
