# Developer Bring-Up

Everything needed to get the TagDrop Android app building and running from scratch.

---

## Prerequisites

| Tool | Minimum version | Notes |
|------|----------------|-------|
| Android Studio | Meerkat (2024.3.1) | Minimum version that supports AGP 9.x; ships with JBR 21 |
| JDK | 21 | Bundled JBR 21 in Android Studio Meerkat satisfies this; use the bundled JDK |
| Android SDK | API 34 | Install via SDK Manager (see below) |
| Gradle | 9.4.1 | Downloaded automatically by the Gradle wrapper — no manual install needed |

Android Studio is the recommended IDE. The project also works in IntelliJ IDEA with the Android plugin, but Android Studio is easier to set up.

---

## 1. Install Android Studio

Download from https://developer.android.com/studio and run the installer.

During the setup wizard:
- Choose **Standard** install type.
- Accept the SDK licences when prompted — this downloads the Android SDK (~1 GB).

If you skip the wizard, open **SDK Manager** (`Tools → SDK Manager`) and install:
- **SDK Platforms** → Android 14 (API 34) — tick *Android SDK Platform 34*
- **SDK Tools** → Android SDK Build-Tools 34, Android Emulator (optional)

---

## 2. Get the code

```bash
git clone https://github.com/mofosyne/tagdrop.git
cd tagdrop
```

---

## 3. Open in Android Studio

1. Launch Android Studio.
2. Choose **Open** (not *New Project*) and select the `tagdrop/` directory — the one containing `settings.gradle`.
3. Android Studio will detect the Gradle project and start syncing automatically. The first sync downloads Gradle 9.4.1 and all Maven dependencies (~150 MB). This can take a few minutes on a slow connection.
4. Wait for the "Gradle sync finished" message in the status bar before doing anything else.

If sync fails with a JDK error, verify Android Studio is using its bundled JDK:
`File → Project Structure → SDK Location → JDK location` should point to the bundled JDK inside the Android Studio installation directory (e.g. `<AS-install>/jbr`).

---

## 4. Run on a device or emulator

### Physical device (recommended for QR scanning)

1. On the Android device: **Settings → Developer options → USB debugging → On**.  
   (To enable Developer options: tap *Build number* 7 times in `Settings → About phone`.)
2. Connect via USB. Accept the "Allow USB debugging?" prompt on the device.
3. In Android Studio, select your device from the target device dropdown (top toolbar).
4. Click **Run ▶** or press `Shift+F10`.

### Emulator

1. Open **Device Manager** (`Tools → Device Manager`).
2. Click **Create Device**, choose a phone hardware profile (e.g. Pixel 6), then select a system image with API 34.
3. Start the emulator, then run the app as above.

Note: QR scanning via camera works on physical devices only. The emulator camera is simulated and will not reliably decode real QR codes.

---

## 5. Command-line build

The Gradle wrapper (`gradlew` / `gradlew.bat`) handles everything — no separate Gradle install needed.

```bash
# Debug APK (installs on connected device)
./gradlew installDebug

# Debug APK only (output: app/build/outputs/apk/debug/app-debug.apk)
./gradlew assembleDebug

# Release APK (unsigned; requires signing config for distribution)
./gradlew assembleRelease

# Full build check (compiles + lint + tests)
./gradlew check
```

On Windows use `gradlew.bat` instead of `./gradlew`.

---

## 6. Run the tests

### Unit tests (no device needed)

```bash
./gradlew testDebugUnitTest
```

Results appear in `app/build/reports/tests/testDebugUnitTest/index.html`.

The unit tests cover the format layer (Base41, MiniCbor, TagDropCodec, ChunkAssembler) and run entirely on the JVM — no Android SDK or emulator required.

### Instrumented tests (requires connected device or emulator)

```bash
./gradlew connectedDebugAndroidTest
```

---

## 7. Project structure

```
tagdrop/
├── app/src/main/java/com/github/mofosyne/tagdrop/
│   ├── data/
│   │   ├── db/          # Room database (AppDatabase, DAOs, entities)
│   │   └── format/      # Wire format: Base41, MiniCbor, TagDropCodec,
│   │                    #   ChunkAssembler, TagDropPayload, TagDropLinkResolver
│   ├── ui/              # RecyclerView adapters
│   ├── MainActivity.kt
│   ├── ReceiveActivity.kt    # QR scanner + payload assembly
│   ├── ViewDataUriActivity.kt# WebView content renderer
│   ├── CreateActivity.kt     # In-app QR generator
│   ├── CollectionDetailActivity.kt # Collection "map" / page list
│   └── ReadMeActivity.kt
├── app/src/test/            # JVM unit tests
├── tools/
│   ├── generator/index.html # Static HTML QR generator (no server needed)
│   └── reader/index.html    # Static HTML reader (scan + view in browser)
└── SPEC.md                  # Wire format specification
```

### Key dependencies

| Library | Purpose |
|---------|---------|
| `zxing-android-embedded` | In-app QR/barcode scanner (no external app required) |
| `com.google.zxing:core` | QR code encoding for CreateActivity |
| `androidx.room` | SQLite cache database (FoundCache + ScannedPaper) |
| `kotlin-kapt` | Annotation processor for Room DAO code generation |
| `lifecycle-livedata-ktx` | LiveData + coroutine extensions |

---

## 8. Common issues

**`SDK location not found`** — Create `local.properties` in the project root:
```
sdk.dir=/path/to/your/Android/Sdk
```
Android Studio does this automatically; only needed for pure command-line builds.

**`Unsupported class file major version`** — The build toolchain requires JDK 21. Check `JAVA_HOME` or point Gradle at the right JDK:
```bash
./gradlew -Dorg.gradle.java.home=/path/to/jdk21 assembleDebug
```
The app bytecode targets Java 17 compatibility, but the Gradle daemon itself must run on JDK 21 (enforced by `gradle/gradle-daemon-jvm.properties`).

**Gradle sync slow on first run** — Normal; Gradle 9.4.1 (~140 MB) and all Maven dependencies are downloaded once and cached in `~/.gradle/`. Subsequent syncs are fast.

**`INSTALL_FAILED_UPDATE_INCOMPATIBLE`** — Uninstall the existing app from the device before installing a debug build signed with a different key:
```bash
adb uninstall com.github.mofosyne.tagdrop
```
