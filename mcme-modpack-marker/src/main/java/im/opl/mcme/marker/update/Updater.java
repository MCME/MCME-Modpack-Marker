package im.opl.mcme.marker.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import im.opl.mcme.marker.MCMEModpackMarker;
import im.opl.mcme.marker.McmeConfig;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

/**
 * Keeps this mod up to date by itself (McmeConfig.autoUpdate). At start, in
 * the background, it asks GitHub for MCME/MCME-Modpack-Marker's releases and
 * takes the newest jar for this Minecraft version - an asset named
 * mcme-modpack-marker-[version]-mc[Minecraft version].jar, with its SHA-256
 * beside it in [that name].sha256 - if it's newer than the running one. It
 * downloads it into .minecraft/mcme/update/, checks its hash and that it is
 * this mod at that version, and when the game closes {@link Swapper} puts it
 * in place of the running jar. The update applies from the next start.
 *
 * <p>Anything amiss - no connection, no such release, a wrong hash - and it
 * leaves things as they are, and says so in the log only.
 */
public final class Updater {
	private static final String RELEASES = "https://api.github.com/repos/MCME/MCME-Modpack-Marker/releases?per_page=20";
	private static final Pattern ASSET = Pattern.compile("mcme-modpack-marker-(\\d+(?:\\.\\d+)*)-mc(.+)\\.jar");
	private static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("mcme/update");

	private static Path running;
	private static boolean scheduled;

	private Updater() {
	}

	public static void start() {
		if (!McmeConfig.get().autoUpdate) return;
		running = runningJar();
		if (running == null) return;    // not from a jar: a development run
		Thread thread = new Thread(Updater::check, "MCME updater");
		thread.setDaemon(true);
		thread.start();
	}

	private record Found(String version, String name, String jarUrl, String hashUrl) {
	}

	private static void check() {
		try {
			String minecraft = FabricLoader.getInstance().getModContainer("minecraft")
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
			String current = MCMEModpackMarker.version();
			HttpClient http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(10))
				.build();
			HttpResponse<String> response = http.send(request(RELEASES, current), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				MCMEModpackMarker.LOGGER.info("MCME update check: GitHub answered {}", response.statusCode());
				return;
			}
			Found best = null;
			for (JsonElement element : JsonParser.parseString(response.body()).getAsJsonArray()) {
				JsonObject release = element.getAsJsonObject();
				if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()) continue;
				String jar = null, hash = null, version = null, name = null;
				for (JsonElement a : release.getAsJsonArray("assets")) {
					String assetName = a.getAsJsonObject().get("name").getAsString();
					Matcher m = ASSET.matcher(assetName);
					if (m.matches() && m.group(2).equals(minecraft)) {
						jar = a.getAsJsonObject().get("browser_download_url").getAsString();
						version = m.group(1);
						name = assetName;
					}
				}
				if (jar == null) continue;
				for (JsonElement a : release.getAsJsonArray("assets")) {
					if (a.getAsJsonObject().get("name").getAsString().equals(name + ".sha256")) {
						hash = a.getAsJsonObject().get("browser_download_url").getAsString();
					}
				}
				if (hash == null) continue;     // never without a hash to check it by
				if (newer(version, current) && (best == null || newer(version, best.version))) {
					best = new Found(version, name, jar, hash);
				}
			}
			if (best == null) {
				MCMEModpackMarker.LOGGER.info("MCME mod {} is up to date for Minecraft {}", current, minecraft);
				return;
			}
			String expected = http.send(request(best.hashUrl, current), HttpResponse.BodyHandlers.ofString())
				.body().trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
			Files.createDirectories(DIR);
			Path ready = DIR.resolve(best.name);
			if (!(Files.isRegularFile(ready) && sha256(ready).equals(expected))) {
				Path part = DIR.resolve(best.name + ".part");
				HttpResponse<Path> download = http.send(request(best.jarUrl, current), HttpResponse.BodyHandlers.ofFile(part));
				if (download.statusCode() != 200 || !sha256(part).equals(expected)) {
					Files.deleteIfExists(part);
					MCMEModpackMarker.LOGGER.warn("MCME update {}: the download didn't match its hash, so it was dropped", best.version);
					return;
				}
				Files.move(part, ready, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!isThisMod(ready, best.version)) {
				Files.deleteIfExists(ready);
				MCMEModpackMarker.LOGGER.warn("MCME update {}: the jar isn't this mod at that version, so it was dropped", best.version);
				return;
			}
			schedule(ready);
			String version = best.version;
			MCMEModpackMarker.LOGGER.info("MCME mod {} downloaded; it replaces {} when the game closes", version, current);
			Minecraft.getInstance().execute(() -> SystemToast.add(Minecraft.getInstance().gui.toastManager(),
				SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal("MCME mod updated to " + version),
				Component.literal("It applies when you restart the game")));
		} catch (Exception e) {
			MCMEModpackMarker.LOGGER.info("MCME update check failed: {}", e.toString());
		}
	}

	private static HttpRequest request(String url, String version) {
		return HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(60))
			.header("Accept", "application/vnd.github+json")
			.header("User-Agent", "mcme-modpack-marker/" + version)
			.build();
	}

	/** Puts ready in place of the running jar when the game closes. */
	private static synchronized void schedule(Path ready) {
		if (scheduled) return;
		scheduled = true;
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				// the swapper runs from a copy: the running jar is the one it replaces
				Path swapper = DIR.resolve("swapper.jar");
				Files.copy(running, swapper, StandardCopyOption.REPLACE_EXISTING);
				String java = ProcessHandle.current().info().command()
					.orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
				new ProcessBuilder(java, "-cp", swapper.toString(), Swapper.class.getName(),
					Long.toString(ProcessHandle.current().pid()), running.toString(), ready.toString(),
					running.resolveSibling(ready.getFileName()).toString())
					.redirectErrorStream(true)
					.redirectOutput(DIR.resolve("swapper.out").toFile())
					.start();
			} catch (Exception e) {
				MCMEModpackMarker.LOGGER.warn("Couldn't start the MCME mod's update", e);
			}
		}, "MCME update"));
	}

	private static Path runningJar() {
		return FabricLoader.getInstance().getModContainer(MCMEModpackMarker.MOD_ID)
			.map(ModContainer::getOrigin)
			.flatMap(origin -> origin.getPaths().stream().findFirst())
			.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
			.orElse(null);
	}

	/** Whether a is a later version than b, part by part: 1.10.0 after 1.9.2. */
	static boolean newer(String a, String b) {
		String[] x = a.split("\\D+"), y = b.split("\\D+");
		for (int i = 0; i < Math.max(x.length, y.length); i++) {
			int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
			int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
			if (p != q) return p > q;
		}
		return false;
	}

	private static String sha256(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[65536];
			for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static boolean isThisMod(Path jar, String version) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) return false;
			try (InputStream in = zip.getInputStream(entry)) {
				JsonObject meta = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
				return MCMEModpackMarker.MOD_ID.equals(meta.get("id").getAsString())
					&& version.equals(meta.get("version").getAsString());
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}
}
