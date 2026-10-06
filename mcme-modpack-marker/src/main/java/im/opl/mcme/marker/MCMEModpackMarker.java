package im.opl.mcme.marker;

import net.fabricmc.api.ClientModInitializer;
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

		LOGGER.info("MCME Modpack Marker ready.");
	}

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
