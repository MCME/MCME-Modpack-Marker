package im.opl.mcme.marker;

import im.opl.mcme.marker.dh.DhDefaults;
import im.opl.mcme.marker.update.Updater;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ServerboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCMEModpackMarker implements ClientModInitializer {
	public static final String MOD_ID = "mcme-modpack-marker";

	public static final Identifier CHANNEL_ID = Identifier.fromNamespaceAndPath(MOD_ID, "hello");

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		PayloadTypeRegistry.serverboundPlay().register(HelloCustomPayload.TYPE, HelloCustomPayload.CODEC);

		ServerboundPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
			if (channels.contains(HelloCustomPayload.TYPE.id())) {
				ClientPlayNetworking.send(new HelloCustomPayload("{\"sodiumVersion\":" + getModVersion("sodium") + "}"));
			}
		});

		DhDefaults.register();
		Updater.start();
		// once every mod has loaded: whether all hooks into Iris and DH still match
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(client -> Hooks.check());
		if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			net.fabricmc.fabric.api.resource.v1.ResourceLoader.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES).registerReloadListener(
				Identifier.fromNamespaceAndPath(MOD_ID, "dh_shaders"),
				(net.minecraft.server.packs.resources.ResourceManagerReloadListener) resources -> {
					im.opl.mcme.marker.dh.DhFog.forgetEye();
					im.opl.mcme.marker.dh.DhReload.afterReload();
				});
		}

		// /mcme: the settings - opened on the next tick, once the chat that ran it has closed;
		// /mcme dh: what Distant Horizons makes of the block looked at (dh/LodColors);
		// /mcme hooks: whether every hook into Iris and DH still matches (Hooks)
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
			dispatcher.register(ClientCommands.literal("mcme").executes(command -> {
				openSettings = true;
				return 1;
			}).then(ClientCommands.literal("hooks").executes(command -> {
				for (String line : Hooks.report()) command.getSource().sendFeedback(net.minecraft.network.chat.Component.literal(line));
				return 1;
			})).then(ClientCommands.literal("dh").executes(command -> {
				var client = net.minecraft.client.Minecraft.getInstance();
				if (!(client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) || client.level == null) {
					command.getSource().sendFeedback(net.minecraft.network.chat.Component.literal("Look at a block"));
					return 0;
				}
				for (String line : im.opl.mcme.marker.dh.LodColors.describe(client.level.getBlockState(hit.getBlockPos()))) {
					command.getSource().sendFeedback(net.minecraft.network.chat.Component.literal(line));
					LOGGER.info("MCME dh: {}", line);
				}
				return 1;
			}))));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (openSettings) {
				openSettings = false;
				client.gui.setScreen(new SettingsScreen(client.gui.screen()));
			}
		});

		LOGGER.info("MCME Modpack Marker ready.");
	}

	private static boolean openSettings;

	/** This mod's version. */
	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(modContainer -> modContainer.getMetadata().getVersion().getFriendlyString())
			.orElse("?");
	}

	private String getModVersion(String modId) {
		return FabricLoader.getInstance()
			.getModContainer(modId)
			.map(modContainer -> "\"" + modContainer.getMetadata().getVersion().toString() + "\"")
			.orElse("null");
	}
}
