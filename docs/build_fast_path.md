# Debug Build Fast Path

This project can spend a long time in Gradle startup if the local Gradle native cache is locked. Use this note as the default path before trying alternatives.

## Fastest stable command

Use the exact command below and give it enough time. In the July 10 UI rebuild, the successful clean debug build took about 7 minutes after earlier failed attempts.

```powershell
gradle --no-daemon --max-workers=1 --console=plain clean assembleDebug
```

Recommended timeout for Codex/tooling: 10 minutes (`600000 ms`). Do not stop it at 2-5 minutes unless there is clear error output.

## Scripted entry

```powershell
.\scripts\build_debug_fast.ps1
```

Install after build:

```powershell
.\scripts\build_debug_fast.ps1 -Install
```

Show possible stale Java/Gradle processes before building:

```powershell
.\scripts\build_debug_fast.ps1 -ShowProcesses
```

## What worked

1. Use the standard project Gradle installation from `C:\tmp\gradle-8.7\bin\gradle`.
2. Use `--no-daemon --max-workers=1 --console=plain`.
3. Prefer the already-approved full command `clean assembleDebug` for Codex sessions.
4. After a successful build, install with:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Known slow/failure modes

- `Failed to load native library 'native-platform.dll'` with `native-platform.dll.lock` means the local Gradle native cache is locked. This is an environment/cache issue, not a project compile error.
- Short command timeouts can leave Java/Gradle processes running. Check them before retrying:

```powershell
Get-Process | Where-Object { $_.ProcessName -like '*gradle*' -or $_.ProcessName -like '*java*' } | Select-Object ProcessName,Id,CPU,StartTime
```

- Do not repeatedly try custom `GRADLE_USER_HOME` or `org.gradle.native.dir` paths first. In this environment those attempts caused extra permission/cache failures and wasted time.
- If a stale Gradle native lock must be removed from `C:\Users\EDY\.gradle\native\...`, request explicit approval because it is outside the workspace.

## Device verification notes

- The work-profile launcher alias may not be startable from normal adb shell because the shell user can lack permission to access work profile user 12.
- Direct main activity launch for smoke testing:

```powershell
adb shell am start -n com.system.launcher.tools/.MainActivity
```

## Last verified

- Date: 2026-07-10
- Command: `gradle --no-daemon --max-workers=1 --console=plain clean assembleDebug`
- Result: `BUILD SUCCESSFUL in 7m`
- Install: `adb install -r app\build\outputs\apk\debug\app-debug.apk` returned `Success`.
