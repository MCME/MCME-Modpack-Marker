# Releasing

## A release

1. Set `mod_version` in `mcme-modpack-marker/gradle.properties` (and
   `minecraft_version` and the dependency versions when moving to a new
   Minecraft).
2. Merge to `master`.
3. Tag it `v<mod_version>` and push the tag.

`.github/workflows/release.yml` then checks that the tag matches
`mod_version`, builds with JDK 25, and publishes a GitHub release with
`mcme-modpack-marker-<version>-mc<Minecraft>.jar` and the jar's SHA-256 in
`<jar>.sha256`. Those two names are what the updater looks for: don't rename
them.

## The updater

At start, if `autoUpdate` is on, `Updater` asks GitHub for
MCME/MCME-Modpack-Marker's latest 20 releases. It takes the newest
non-draft, non-prerelease release that has a jar for this Minecraft version,
a `.sha256` next to it, and a higher version than the running one. Then it:

1. downloads the jar into `.minecraft/mcme/update/`;
2. checks its SHA-256, and that its `fabric.mod.json` is this mod at that version;
3. registers a shutdown hook and shows a toast ("applies when you restart").

When the game closes, the hook copies the running jar to `swapper.jar` and
starts `Swapper` from it in a separate Java process: Windows won't let the
game's own jar be replaced while the game has it open. `Swapper` waits for
the game's process to end, backs up the old jar, swaps in the new one, and
restores the backup if anything fails. It logs to `update.log`.

Anything amiss (no connection, no matching release, a wrong hash) leaves
everything as it is, and is only logged. Development runs (not from a jar)
never update.

Players on 1.0.0, which has no updater, must install 1.1.0 by hand once.

## A new Minecraft version

Release a jar per Minecraft version: the updater only takes assets whose
`-mc<version>` matches the running game. Then recheck every hook in
[architecture.md](architecture.md) against the new game, Iris and DH.
