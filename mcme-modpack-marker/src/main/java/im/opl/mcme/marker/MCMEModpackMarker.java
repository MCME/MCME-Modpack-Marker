package im.opl.mcme.marker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCMEModpackMarker implements ClientModInitializer {
	public static final String MOD_ID = "mcme-modpack-marker";

	public static final Identifier CHANNEL_ID = Identifier.of(MOD_ID, "hello");

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		PayloadTypeRegistry.playC2S().register(HelloCustomPayload.ID, HelloCustomPayload.CODEC);

		C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> {
			if (channels.contains(HelloCustomPayload.ID.id())) {
				sender.sendPacket(new CustomPayloadC2SPacket(new HelloCustomPayload("{\"sodiumVersion\":" + getModVersion("sodium") + "}")));
			}
		});

		LOGGER.info("MCME Modpack Marker ready.");
	}

	private String getModVersion(String modId) {
		return FabricLoader.getInstance()
			.getModContainer(modId)
			.map(modContainer -> "\"" + modContainer.getMetadata().getVersion().toString() + "\"")
			.orElse("null");
	}
}
