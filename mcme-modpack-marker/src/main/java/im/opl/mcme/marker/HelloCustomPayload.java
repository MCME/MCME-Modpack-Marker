package im.opl.mcme.marker;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record HelloCustomPayload(String json) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<HelloCustomPayload> TYPE = new CustomPacketPayload.Type<>(MCMEModpackMarker.CHANNEL_ID);
	public static final StreamCodec<ByteBuf, HelloCustomPayload> CODEC = ByteBufCodecs.STRING_UTF8.map(HelloCustomPayload::new, HelloCustomPayload::json);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
