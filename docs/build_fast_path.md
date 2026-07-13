# Debug Build Fast Path

This project is small, but Windows/Gradle file locks can make builds look much slower than the codebase warrants. Prefer the incremental path first and use a clean build only when outputs are suspected stale.

## Daily fast path

```powershell
.\scripts\build_debug_fast.ps1 -CleanNativeLocks -BuildAfterCleanup
```

Install after an incremental build:

```powershell
.\scripts\build_debug_fast.ps1 -CleanNativeLocks -BuildAfterCleanup -Install
```

Run a full clean build only when needed:

```powershell
.\scripts\build_debug_fast.ps1 -Clean
```

## Preflight checks

Show possible stale Java/Gradle processes and Gradle native lock files without building:

```powershell
.\scripts\build_debug_fast.ps1 -ShowProcesses -CheckOnly
```

If there are Gradle native lock files and no Java/Gradle processes are running, remove only those lock files explicitly. This command only cleans locks and then exits:

```powershell
.\scripts\build_debug_fast.ps1 -CleanNativeLocks
```

Clean locks and immediately continue with a build only when that is intentional:

```powershell
.\scripts\build_debug_fast.ps1 -CleanNativeLocks -BuildAfterCleanup
```

If Gradle still fails with `native-platform.dll` after locks are gone, remove only the native-platform cache files while keeping the Gradle native directory structure:

```powershell
.\scripts\build_debug_fast.ps1 -ResetNativeCache
```

Reset native cache and immediately continue with a build only when that is intentional:

```powershell
.\scripts\build_debug_fast.ps1 -ResetNativeCache -BuildAfterCleanup
```

The script refuses to remove native locks or native-platform cache files while Java/Gradle processes are running.

## Stable Codex fallback

When a fully fresh APK is required, use the exact command below and give it enough time. In the July 13 debug build, the successful clean debug build took about 7 minutes 37 seconds after stale native locks were removed.

```powershell
gradle --no-daemon --max-workers=1 --console=plain clean assembleDebug
```

Recommended timeout for Codex/tooling: 10 minutes (`600000 ms`). Do not stop it at 2-5 minutes unless there is clear error output.

## What makes builds slow here

1. `C:\Users\EDY\.gradle\native\...\*.lock` can block Gradle before project compilation starts.
2. Windows file locks can also block incremental resource cleanup under `app\build\intermediates`.
3. `clean assembleDebug` forces Hilt/kapt, resource processing, dexing, and packaging to rerun.
4. `--no-daemon --max-workers=1` is stable for Codex sessions but slower than a normal local daemon build.
5. Jetifier is disabled in `gradle.properties` because the project uses AndroidX dependencies; the `android.support.FILE_PROVIDER_PATHS` manifest key is the AndroidX FileProvider compatibility meta-data name and does not require Jetifier.

## Known failure modes

- `Failed to load native library 'native-platform.dll'` with `native-platform.dll.lock` means the local Gradle native cache is locked. This is an environment/cache issue, not a project compile error.
- `Could not extract native JNI library` after a previous failed or timed-out build usually means native lock cleanup is needed.
- `Couldn't delete ... mergeDebugResources ... values.xml` means another process is holding an intermediate build output. Stop stale Java/Gradle processes and retry before using `clean`.

## Device verification notes

- The work-profile launcher alias may not be startable from normal adb shell because the shell user can lack permission to access work profile user 12.
- Direct main activity launch for smoke testing:

```powershell
adb shell am start -n com.system.launcher.tools/.MainActivity
```

Install an already built APK:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Last verified

- Date: 2026-07-13
- Command: `gradle --no-daemon --max-workers=1 --console=plain clean assembleDebug`
- Result: `BUILD SUCCESSFUL in 7m 37s`
- Install: `adb install -r app\build\outputs\apk\debug\app-debug.apk` returned `Success`.
