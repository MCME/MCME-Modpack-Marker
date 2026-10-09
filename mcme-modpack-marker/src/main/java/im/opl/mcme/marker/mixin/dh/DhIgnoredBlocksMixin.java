package im.opl.mcme.marker.mixin.dh;

import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.McmeConfig;
import im.opl.mcme.marker.dh.LodColors;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distant Horizons draws a block it has no business drawing - one no one can
 * see, every face's texture see-through, such as MCME's shadow block (tinted
 * glass with a blank texture, which darkens canopies by blocking skylight) -
 * by its texture's pixels: black. Its opacity doesn't keep it out of a LOD;
 * the blocks DH ignores do, which it treats as air
 * (BlockStateWrapper.getRendererIgnoredBlocks, from its "ignored render
 * blocks" setting). Such blocks are added to that list as DH makes it
 * (LodColors.unseen), and given an opacity of 0 (DhBlockOpacityMixin): any
 * wrapper made before they were known gets it here. The shade they cast
 * stays, in the light DH keeps.
 */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper", remap = false)
public abstract class DhIgnoredBlocksMixin {
	private static WeakReference<Object> mcme$done = new WeakReference<>(null);
	private static boolean mcme$failed;

	@Inject(method = "getRendererIgnoredBlocks", at = @At("RETURN"), require = 0)
	private static void mcme$unseen(@Coerce Object level, CallbackInfoReturnable<Object> cir) {
		Object ignored = cir.getReturnValue();
		if (mcme$failed || ignored == null || mcme$done.get() == ignored || !McmeConfig.get().dhExactColors) return;
		synchronized (DhIgnoredBlocksMixin.class) {
			if (mcme$done.get() == ignored) return;
			try {
				List<BlockState> unseen = LodColors.unseen();
				if (unseen == null) return;     // models not loaded yet: next time
				Method wrap = Class.forName("com.seibel.distanthorizons.common.wrappers.block.BlockStateWrapper").getMethod("fromBlockState",
					BlockState.class, Class.forName("com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper"));
				@SuppressWarnings("unchecked")
				Set<Object> set = (Set<Object>) ignored;
				java.lang.reflect.Field opacity = null;
				for (BlockState state : unseen) {
					Object wrapper = wrap.invoke(null, state, level);
					set.add(wrapper);
					if (opacity == null) {
						opacity = wrapper.getClass().getDeclaredField("opacity");
						opacity.setAccessible(true);
					}
					opacity.setInt(wrapper, 0);
				}
				mcme$done = new WeakReference<>(ignored);
				MCMEModpackMarker.LOGGER.info("Distant Horizons leaves out {} block states no one can see (MCME){}", unseen.size(),
					unseen.isEmpty() ? "" : ", such as " + unseen.get(0));
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				mcme$failed = true;
				MCMEModpackMarker.LOGGER.warn("MCME couldn't have Distant Horizons leave out blocks no one can see", e);
			}
		}
	}
}
