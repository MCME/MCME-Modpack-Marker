package im.opl.mcme.marker.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Puts a downloaded update in place once the game has closed - started by
 * {@link Updater} as the game shuts down, in a process of its own, from a copy
 * of this jar: the game's own jar can't be replaced while the game has it
 * open (on Windows). Uses nothing but the JDK.
 *
 * <p>Waits for the game to end, then deletes the old jar and moves the new one
 * into the same folder. If the old one can't be deleted it leaves the new one
 * where it is, to try again next time: with both in the mods folder the game
 * wouldn't start.
 *
 * <p>Arguments: the game's process id, the old jar, the new jar, where to put it.
 */
public final class Swapper {
	private Swapper() {
	}

	public static void main(String[] args) throws Exception {
		long pid = Long.parseLong(args[0]);
		Path old = Path.of(args[1]);
		Path fresh = Path.of(args[2]);
		Path target = Path.of(args[3]);
		Path log = fresh.resolveSibling("update.log");
		ProcessHandle.of(pid).ifPresent(game -> {
			try {
				game.onExit().get(10, TimeUnit.MINUTES);
			} catch (Exception e) {
				// carry on: tried below either way
			}
		});
		// the old one kept aside, to put back if the new one can't be put in
		Path backup = fresh.resolveSibling("previous.jar");
		Files.copy(old, backup, StandardCopyOption.REPLACE_EXISTING);
		IOException last = null;
		for (int attempt = 0; attempt < 60 && Files.exists(old); attempt++) {
			try {
				Files.delete(old);
			} catch (IOException e) {
				last = e;
				Thread.sleep(1000);
			}
		}
		if (Files.exists(old)) {
			write(log, "couldn't remove " + old + ", will try again next time: " + last);
			return;
		}
		try {
			Files.copy(fresh, target, StandardCopyOption.REPLACE_EXISTING);
			Files.delete(fresh);
			write(log, "updated: " + old.getFileName() + " -> " + target.getFileName());
		} catch (IOException e) {
			Files.copy(backup, old, StandardCopyOption.REPLACE_EXISTING);
			write(log, "couldn't put in " + target + ", put back " + old.getFileName() + ": " + e);
		}
	}

	private static void write(Path log, String line) {
		try {
			Files.writeString(log, Instant.now() + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
		}
	}
}
