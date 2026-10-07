package im.opl.mcme.marker.mixin.dh;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.McmeConfig;
import im.opl.mcme.marker.dh.LodColors;
import im.opl.mcme.marker.dh.ModelQuads;
import net.minecraft.world.level.block.state.BlockState;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distant Horizons works out each block state's LOD colour once
 * (ClientBlockStateColorCache.resolveColors), from one face's texture. For
 * models with no full-cube faces - MCME's .obj leaves and plants - it's
 * replaced, as it's settled, by all their faces' average (LodColors).
 *
 * <p>Water's opacity too: DH takes it from the water texture's (two thirds to
 * three quarters), so a waterfall one block thick shows the dark cliff behind
 * it far off. Close up, MCME's water seen from afar is all but opaque (its
 * murk, water.glsl), so far off it's WATER_OPACITY.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.block.ClientBlockStateColorCache", remap = false)
public abstract class DhBlockColorMixin {
	@Shadow @Final private BlockState blockState;
	@Shadow private int baseColor;
	@Shadow private boolean needPostTinting;
	@Shadow private int tintIndex;

	private static final int WATER_OPACITY = 245;
	private static boolean mcme$failed;

	/**
	 * DH colours a slab by its double slab's faces, and a huge mushroom block
	 * by its default state's - the same look in vanilla, not in MCME's packs,
	 * which give those states models of their own: they're coloured by their own.
	 */
	@ModifyArg(method = "getQuadsForDirection", index = 0, require = 0,
		at = @At(value = "INVOKE", target = "Lcom/seibel/distanthorizons/common/wrappers/block/QuadWrapper;getQuadsForDirection(Lnet/minecraft/world/level/block/state/BlockState;Lcom/seibel/distanthorizons/core/enums/EDhDirection;)Ljava/util/List;"))
	private BlockState mcme$ownState(BlockState dhState) {
		if (mcme$failed || dhState == blockState || !McmeConfig.get().dhExactColors) return dhState;
		try {
			return ModelQuads.looksLike(blockState, dhState) ? dhState : blockState;
		} catch (RuntimeException | LinkageError e) {
			mcme$failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't compare {} with {} for Distant Horizons, so leaves DH's colours alone", blockState, dhState, e);
			return dhState;
		}
	}

	@Inject(method = "resolveColors", require = 0,
		at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
			target = "Lcom/seibel/distanthorizons/common/wrappers/block/ClientBlockStateColorCache;isColorResolved:Z"))
	private void mcme$wholeModel(CallbackInfo ci) {
		if (mcme$failed || !McmeConfig.get().dhExactColors) return;
		if (blockState.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
			&& blockState.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) {
			baseColor = WATER_OPACITY << 24 | baseColor & 0xFFFFFF;
			return;
		}
		if (!blockState.getFluidState().isEmpty()) return;
		try {
			LodColors.Color color = LodColors.of(blockState);
			if (color == null) return;
			baseColor = baseColor & 0xFF000000 | color.rgb();
			needPostTinting = color.tinted();
			if (color.tinted()) tintIndex = color.tintIndex();
		} catch (RuntimeException | LinkageError e) {
			mcme$failed = true;
			MCMEModpackMarker.LOGGER.warn("MCME couldn't work out Distant Horizons' colour of {}, so leaves DH's colours alone", blockState, e);
		}
	}
}
