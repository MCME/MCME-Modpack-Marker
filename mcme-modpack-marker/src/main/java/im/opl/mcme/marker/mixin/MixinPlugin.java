package im.opl.mcme.marker.mixin;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies the mixins into another mod (in a sub-package named after it) only when that mod is installed. */
public class MixinPlugin implements IMixinConfigPlugin {
	private static final String PACKAGE = "im.opl.mcme.marker.mixin.";

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.startsWith(PACKAGE + "dh.")) return FabricLoader.getInstance().isModLoaded("distanthorizons");
		if (mixinClassName.startsWith(PACKAGE + "iris.")) return FabricLoader.getInstance().isModLoaded("iris");
		if (mixinClassName.startsWith(PACKAGE + "sodium.")) return FabricLoader.getInstance().isModLoaded("sodium");
		return true;
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
