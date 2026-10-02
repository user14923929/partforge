# PartForge

On-device partition tool for rooted Android. Flash, dump and erase partitions and read
boot variables straight from the phone: **no PC, no Fastboot**, only root and the CLI
utilities Android already ships (`dd`, `head`, `sync`, `blkdiscard`, `bootctl`, `getprop`).

Anything that genuinely needs the bootloader (unlocking, OEM commands, writing
hardware-protected bootloader partitions) is intentionally **not** included.

## Features

| Area | What it does |
|------|--------------|
| Partitions | Lists `/dev/block/by-name` (physical) and `/dev/block/mapper` (logical/dynamic) with size, slot and risk level |
| Flash | Raw images and **Android sparse images** (expanded on the fly, no `simg2img` needed), streamed straight into `dd` |
| Dump | Streams a partition into any file you pick (Storage Access Framework), with SHA-256 |
| Erase | Quick (first 4 MiB), Discard (`blkdiscard`) or Full zero-fill |
| Verify | Re-reads the partition after flashing and compares SHA-256 |
| Variables | Device, bootloader, verified boot, A/B and dynamic-partition props, `/proc/cmdline`, `bootctl` slot info; copy as text |
| Slots | Switch the active slot with `bootctl set-active-boot-slot` |
| Safety | Critical partitions blocked unless Expert mode, mounted partitions blocked, image-type sanity checks, typed confirmation, auto-backup |

## Safety model

* **Critical** partitions (`xbl`, `abl`, `aboot`, `tz`, `hyp`, `rpm`, `keymaster`, `preloader`, `lk` ...) are blocked unless Expert mode is on.
* **Sensitive** partitions (`modem`, `persist`, `efs`, `fsg`, `misc`, `metadata`, `userdata` ...) show a warning.
* Mounted partitions are blocked unless Expert mode is on.
* Boot/vbmeta images are checked against the target partition (`ANDROID!`, `VNDRBOOT`, `AVB0`).
* Every write needs you to type the partition name.
* Before writing, the partition is backed up to `Android/data/org.user14923929.partforge/files/backups/` (partitions up to 2 GiB; configurable).
* Writes are not cancellable. An interrupted write can leave a partition half-written, so keep the device charged.

**You can brick your device with this tool. Use at your own risk.**

## Limitations

* Needs root (`su -c`). Tested logic: sparse decoding, streaming write/read/verify, erase and the safety rules are unit-checked against a fake `su`; the Android UI has not been run on a device yet.
* "Don't care" chunks in sparse images are written as zeros.
* Some devices keep `boot0/boot1` read-only or block writes via SELinux/OEM firmware. Those writes fail with an error instead of silently doing nothing.
* Logical partitions inside `super` can be written only up to their current size; resizing `super` is not supported.
* No foreground service yet: the app holds a wake lock and keeps the screen on during operations.

## Build

```sh
gradle wrapper --gradle-version 8.9   # once, generates gradlew + the wrapper jar
./gradlew assembleDebug
```

JDK 17 is required (`gradle.properties` points to `/usr/lib/jvm/java-17-openjdk`).
Tagging `vX.Y.Z` builds a debug APK in GitHub Actions and attaches it to a release.

## Layout

```
app/src/main/java/org/user14923929/partforge/
  core/   RootExecutor, PartitionScanner, PartitionOps, Sources (raw/sparse/zero),
          SafetyGuard, VarReader, CapabilityDetector, Models
  ui/     AppViewModel, Screens (Compose Material 3), Theme
```

## License

GPL-3.0, see `LICENSE`.
