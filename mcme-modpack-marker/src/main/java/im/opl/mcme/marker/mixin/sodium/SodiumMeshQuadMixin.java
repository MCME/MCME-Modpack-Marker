package im.opl.mcme.marker.mixin.sodium;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium 0.9.2 for 26.3 crashed building chunks with Special Model Loader's
 * models: "Cannot invoke ModelQuadFacing.ordinal() because facing is null",
 * in BakedChunkModelBuilder.getVertexBuffer. Those models are baked into
 * Fabric renderer meshes, and Sodium copies a mesh's quads in with
 * QuadViewImpl.load(), which marks their geometry worked out
 * (isGeometryInvalid false) without setting the normalFace field that 26.3's
 * Sodium now keeps - so a quad got the facing of the one before it, or none,
 * and crashed. (26.2's Sodium has no such field: it works the facing out
 * when asked.) Marked invalid again here, a loaded quad's facing, normal and
 * flags are worked out from its corners on first use, as every other quad's are.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.model.QuadViewImpl", remap = false)
public abstract class SodiumMeshQuadMixin {
	@Shadow(remap = false)
	protected boolean isGeometryInvalid;

	@Inject(method = "load", at = @At("TAIL"), require = 0)
	private void mcme$workOutGeometry(CallbackInfo ci) {
		this.isGeometryInvalid = true;
	}
}
