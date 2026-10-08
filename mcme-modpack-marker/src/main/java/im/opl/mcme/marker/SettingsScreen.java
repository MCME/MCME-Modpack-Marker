package im.opl.mcme.marker;

import im.opl.mcme.marker.dh.DhDefaults;
import im.opl.mcme.marker.dh.DhReload;
import java.util.function.Predicate;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The settings for what this mod does (McmeConfig), each an on/off button:
 * from Mod Menu, or /mcme. Saved when closed.
 */
public final class SettingsScreen extends Screen {
	private final Screen parent;

	public SettingsScreen(Screen parent) {
		super(Component.literal("MCME settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		McmeConfig config = McmeConfig.get();
		column = 0;
		int x = width / 2 - 155;
		int y = 40;
		y = toggle(x, y, "Update automatically", "Download new versions of this mod by itself; they're put in place when the game closes.",
			c -> c.autoUpdate, (c, v) -> c.autoUpdate = v, config);
		y = toggle(x, y, "Shader pack recipes", "Draw the fire eye and lava into the shader packs MCME knows. Takes effect when the shader pack reloads.",
			c -> c.shaderPackRecipes, (c, v) -> c.shaderPackRecipes = v, config);
		y = toggle(x, y, "Fluids in shader packs", "Show the resource pack's water, lava and tar in any shader pack. Takes effect when the shader pack reloads.",
			c -> c.shaderPackTerrain, (c, v) -> c.shaderPackTerrain = v, config);
		y = toggle(x, y, "Fire eye over shader packs", "Draw the fire eye over shader packs that have no MCME recipe.",
			c -> c.shaderPackEye, (c, v) -> c.shaderPackEye = v, config);
		y = toggle(x, y, "Distant Horizons shaders", "Draw Distant Horizons' LODs with the resource pack's shaders: the fire eye and lava far off. Takes effect after a restart.",
			c -> c.dhResourcePackShaders, (c, v) -> c.dhResourcePackShaders = v, config);
		y = toggle(x, y, "Smooth animation clock", "Animate water, lava and the eye by real time, so they don't jump when the game stutters.",
			c -> c.realTimeClock, (c, v) -> c.realTimeClock = v, config);
		y = toggle(x, y, "Fix grey rivers far off", "Turn Distant Horizons' biome blending off, which leaves rivers grey in its LODs.",
			c -> c.dhBiomeBlendingOff, (c, v) -> {
				c.dhBiomeBlendingOff = v;
				DhDefaults.apply();
				DhReload.afterColorSetting();
			}, config);
		y = toggle(x, y, "True colours far off", "Colour Distant Horizons' LODs as the blocks look: custom models such as MCME's leaves by their own faces, slabs by their own model, the leaf slabs on doors and trapdoors not left out, blocks that can't be seen left out, and waterfalls whole and not see-through.",
			c -> c.dhExactColors, (c, v) -> {
				c.dhExactColors = v;
				DhReload.afterColorSetting();
			}, config);
		if (column == 1) y += 24;
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width / 2 - 100, y + 12, 200, 20).build());
	}

	private interface Setter {
		void set(McmeConfig config, boolean value);
	}

	// one setting's button, two to a row; returns the next row's y
	private int column;

	private int toggle(int x, int y, String name, String tip, Predicate<McmeConfig> get, Setter set, McmeConfig config) {
		CycleButton<Boolean> button = CycleButton.onOffBuilder(get.test(config))
			.create(x + column * 160, y, 150, 20, Component.literal(name), (b, value) -> {
				set.set(config, value);
				config.save();
			});
		button.setTooltip(Tooltip.create(Component.literal(tip)));
		addRenderableWidget(button);
		column = 1 - column;
		return column == 0 ? y + 24 : y;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);
		graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal("Shader changes apply when shaders reload, or after a restart"), width / 2, 27, 0xFFA0A0A0);
	}

	@Override
	public void onClose() {
		McmeConfig.get().save();
		minecraft.gui.setScreen(parent);
	}
}
