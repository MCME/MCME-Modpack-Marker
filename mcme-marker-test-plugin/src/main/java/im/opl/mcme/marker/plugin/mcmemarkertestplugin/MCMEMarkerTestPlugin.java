package im.opl.mcme.marker.plugin.mcmemarkertestplugin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class MCMEMarkerTestPlugin extends JavaPlugin implements Listener {
    private static final String CHANNEL_ID = "mcme-modpack-marker:hello";

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL_ID, (channel, player, message) -> {
            getLogger().info("Received message from player " + player.getName() + " on channel " + channel + " with data " + Arrays.toString(message));
			try (var dataStream = new DataInputStream(new ByteArrayInputStream(message))) {
                int stringLength = readVarInt(dataStream);
                String jsonString = new String(dataStream.readNBytes(stringLength));
                getLogger().info("data = " + jsonString);
            } catch (IOException e) {
                getLogger().warning("Received invalid MCME Modpack marker data from player " + player.getName() + " (" + player.getUniqueId() + "): " + Arrays.toString(message));
            }
        });

        Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL_ID);
    }

    private int readVarInt(DataInputStream dataStream) throws IOException {
        // This will break for strings over 127 characters long.
        int accumulator = 0;
        for (int index = 0; true; index++) {
            if (index >= 4) {
                throw new IllegalArgumentException("VarInt too long. Reached index " + index);
            }

            int nextByte = dataStream.readUnsignedByte();
            accumulator <<= 8;
            accumulator |= nextByte & 0x7f;
            if ((nextByte & 0x80) == 0) break;
        }

        return accumulator;
    }
}
