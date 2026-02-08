package im.opl.mcme.marker;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;

public record HelloCustomPayload(String json) implements CustomPayload {
	public static final CustomPayload.Id<HelloCustomPayload> ID = new Id<>(MCMEModpackMarker.CHANNEL_ID);
	public static final PacketCodec<PacketByteBuf, HelloCustomPayload> CODEC = PacketCodecs.STRING.xmap(HelloCustomPayload::new, HelloCustomPayload::json).cast();

	@Override
	public Id<? extends CustomPayload> getId() {
		return ID;
	}
}
