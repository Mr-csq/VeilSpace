package com.system.launcher.tools.ui.migration

import android.app.admin.DevicePolicyManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.system.launcher.tools.data.model.InstallVerification
import com.system.launcher.tools.data.repository.ProfileAppStore
import com.system.launcher.tools.databinding.ActivityWorkProfileMigrationBinding
import com.system.launcher.tools.ui.common.SpaceUi
import com.system.launcher.tools.ui.common.showSpace
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

class WorkProfileMigrationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWorkProfileMigrationBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var serverSocket: ServerSocket? = null
    @Volatile private var generationRunning = false
    @Volatile private var archiveReady = false
    @Volatile private var cancelRequested = false
    private var downloadToken = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val devicePolicyManager = getSystemService(DevicePolicyManager::class.java)
        if (!devicePolicyManager.isProfileOwnerApp(packageName)) {
            finish()
            return
        }

        configureWindow()
        binding = ActivityWorkProfileMigrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SpaceUi.applySystemBarInsets(binding.pageContent)
        SpaceUi.reveal(binding.pageContent)
        setupActions()
        setupBackHandling()
        clearPreviousArchive()
    }

    override fun onDestroy() {
        cancelRequested = true
        serverSocket?.runCatching { close() }
        worker.shutdownNow()
        WorkProfileMigrationArchive.clearStaleArchive(this)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun setupActions() {
        SpaceUi.setSafeClickListener(binding.btnBack) { finish() }
        SpaceUi.setSafeClickListener(binding.btnGenerate) { confirmGeneration() }
        SpaceUi.attachPressScale(binding.btnBack, 0.9f)
        SpaceUi.attachPressScale(binding.btnGenerate)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (generationRunning) {
                    publishStatus("正在生成迁移包，请完成后再返回")
                } else {
                    finish()
                }
            }
        })
    }

    private fun clearPreviousArchive() {
        binding.btnGenerate.isEnabled = false
        publishStatus("正在清理上次生成的临时迁移包…")
        worker.execute {
            WorkProfileMigrationArchive.clearStaleArchive(this)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                binding.btnGenerate.isEnabled = true
                publishStatus("尚未生成迁移包。需要迁移时再点击下方按钮。")
            }
        }
    }

    private fun confirmGeneration() {
        if (generationRunning) return
        MaterialAlertDialogBuilder(this)
            .setTitle("生成新的迁移包？")
            .setMessage(
                "将临时打包可读取的应用 APK、VeilSpace 配置和公共文件。" +
                    "不会包含第三方应用登录态、私有数据库、密钥或支付凭据。\n\n" +
                    "生成期间请保持页面前台和屏幕常亮。"
            )
            .setPositiveButton("开始生成") { _, _ -> startGeneration() }
            .setNegativeButton("取消", null)
            .showSpace()
    }

    private fun startGeneration() {
        if (generationRunning) return
        generationRunning = true
        archiveReady = false
        cancelRequested = false
        downloadToken = UUID.randomUUID().toString()
        binding.btnGenerate.isEnabled = false
        binding.progressMigration.visibility = View.VISIBLE
        binding.progressMigration.isIndeterminate = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        publishStatus("正在扫描工作资料中的可迁移内容…")
        worker.execute(::prepareMigrationArchive)
    }

    private fun prepareMigrationArchive() {
        val archive = WorkProfileMigrationArchive.file(this)
        val manifestEntries = JSONArray()
        var writtenFiles = 0
        var writtenBytes = 0L

        runCatching {
            archive.parentFile?.mkdirs()
            if (archive.exists() && !archive.delete()) error("无法替换旧迁移包")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(archive))).use { zip ->
                ensureNotCancelled()
                zip.setLevel(Deflater.NO_COMPRESSION)
                val packages = collectPackageMetadata()
                val metadata = JSONObject().apply {
                    put("formatVersion", 1)
                    put("createdAt", System.currentTimeMillis())
                    put("sourcePackage", packageName)
                    put("sourceUserIsProfileOwner", true)
                    put("packages", packages)
                    put("excluded", JSONArray().apply {
                        put("Third-party app private data under /data/user/12")
                        put("Accounts, login sessions, hardware-bound keys and credentials")
                        put("System permissions and Profile Owner provisioning state")
                    })
                }
                writeBytes(zip, "metadata/device-and-packages.json", metadata.toString(2).toByteArray(), manifestEntries)

                ownDataRoots().forEach { (prefix, root) ->
                    ensureNotCancelled()
                    val result = addDirectory(zip, root, prefix, manifestEntries, writtenFiles, writtenBytes)
                    writtenFiles = result.first
                    writtenBytes = result.second
                }
                apkFiles().forEach { (entryName, file) ->
                    ensureNotCancelled()
                    val digest = addFile(zip, file, entryName)
                    manifestEntries.put(manifestEntry(entryName, file.length(), digest))
                    writtenFiles += 1
                    writtenBytes += file.length()
                    publishProgress(writtenFiles, writtenBytes, entryName)
                }
                publicStorageRoots().forEach { (prefix, root) ->
                    ensureNotCancelled()
                    val result = addDirectory(zip, root, prefix, manifestEntries, writtenFiles, writtenBytes)
                    writtenFiles = result.first
                    writtenBytes = result.second
                }

                val manifest = JSONObject().apply {
                    put("fileCount", writtenFiles)
                    put("uncompressedBytes", writtenBytes)
                    put("entries", manifestEntries)
                }
                writeBytes(zip, "metadata/manifest.json", manifest.toString(2).toByteArray(), null)
            }

            val archiveHash = sha256(archive)
            archiveReady = true
            generationRunning = false
            Log.i(TAG, "READY token=$downloadToken port=$SERVER_PORT bytes=${archive.length()} sha256=$archiveHash")
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                binding.progressMigration.visibility = View.GONE
                binding.btnGenerate.isEnabled = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                publishStatus(
                    "迁移包已准备完成\n\n" +
                        "文件：$writtenFiles 个\n" +
                        "大小：${formatBytes(archive.length())}\n" +
                        "SHA-256：$archiveHash\n\n" +
                        "请连接电脑下载并校验；退出本页会删除手机中的临时包。"
                )
            }
            serveArchive(archive)
        }.onFailure { error ->
            generationRunning = false
            archiveReady = false
            WorkProfileMigrationArchive.clearStaleArchive(this)
            Log.e(TAG, "Migration archive failed", error)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                binding.progressMigration.visibility = View.GONE
                binding.btnGenerate.isEnabled = true
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                publishStatus("迁移包生成失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun collectPackageMetadata(): JSONArray {
        val result = JSONArray()
        ProfileAppStore.loadApps(this).sortedBy { it.packageName }.forEach { cachedApp ->
            val applicationInfo = resolveApplicationInfo(cachedApp.packageName)
            val packageInfo = runCatching {
                packageManager.getPackageInfo(cachedApp.packageName, PackageManager.PackageInfoFlags.of(0))
            }.getOrNull()
            val systemApp = cachedApp.isSystemApp ||
                ((applicationInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) ?: 0) != 0)
            val installed = cachedApp.installVerification == InstallVerification.CONFIRMED_INSTALLED
            result.put(JSONObject().apply {
                put("packageName", cachedApp.packageName)
                put("label", cachedApp.appName)
                put("systemApp", systemApp)
                put("showOnHome", cachedApp.showOnHome)
                put("sortOrder", cachedApp.sortOrder)
                put("keepAlive", cachedApp.keepAlive)
                put("installVerification", cachedApp.installVerification.name)
                put("launchVerification", cachedApp.launchVerification.name)
                put("versionName", packageInfo?.versionName ?: JSONObject.NULL)
                put("versionCode", packageInfo?.longVersionCode ?: JSONObject.NULL)
                put(
                    "apkIncluded",
                    installed && !systemApp && applicationInfo != null &&
                        applicationApkFiles(applicationInfo).isNotEmpty()
                )
            })
        }
        return result
    }

    private fun apkFiles(): List<Pair<String, File>> {
        return ProfileAppStore.loadApps(this)
            .asSequence()
            .filter { it.installVerification == InstallVerification.CONFIRMED_INSTALLED }
            .filterNot { it.isSystemApp }
            .mapNotNull { resolveApplicationInfo(it.packageName) }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .flatMap { applicationInfo ->
                applicationApkFiles(applicationInfo).map { file ->
                    "apks/${safeSegment(applicationInfo.packageName)}/${safeSegment(file.name)}" to file
                }
            }
            .toList()
    }

    private fun resolveApplicationInfo(packageName: String): ApplicationInfo? {
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS
        return runCatching {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(flags.toLong())
            )
        }.getOrNull()
    }

    private fun applicationApkFiles(applicationInfo: ApplicationInfo): List<File> {
        return (listOfNotNull(applicationInfo.sourceDir) + applicationInfo.splitSourceDirs.orEmpty())
            .map(::File)
            .filter { it.isFile && it.canRead() }
    }

    private fun ownDataRoots(): List<Pair<String, File>> {
        val dataDir = File(applicationInfo.dataDir)
        return listOf(
            "veilspace/shared_prefs" to File(dataDir, "shared_prefs"),
            "veilspace/files" to filesDir
        )
    }

    private fun publicStorageRoots(): List<Pair<String, File>> {
        return listOf(
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_MUSIC
        ).map { directory ->
            "public/$directory" to Environment.getExternalStoragePublicDirectory(directory)
        }.distinctBy { it.second.absolutePath }
    }

    private fun addDirectory(
        zip: ZipOutputStream,
        root: File,
        prefix: String,
        manifestEntries: JSONArray,
        initialFiles: Int,
        initialBytes: Long
    ): Pair<Int, Long> {
        if (!root.exists() || !root.canRead()) return initialFiles to initialBytes
        val canonicalRoot = root.canonicalFile
        var fileCount = initialFiles
        var byteCount = initialBytes
        val pending = ArrayDeque<File>().apply { add(canonicalRoot) }
        while (pending.isNotEmpty()) {
            ensureNotCancelled()
            val current = pending.removeFirst()
            current.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                ensureNotCancelled()
                val canonicalChild = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
                if (!canonicalChild.path.startsWith(canonicalRoot.path + File.separator)) return@forEach
                when {
                    canonicalChild.isDirectory && canonicalChild.canRead() -> pending.add(canonicalChild)
                    canonicalChild.isFile && canonicalChild.canRead() -> {
                        val relative = canonicalChild.relativeTo(canonicalRoot).invariantSeparatorsPath
                        val entryName = "$prefix/$relative"
                        val digest = addFile(zip, canonicalChild, entryName)
                        manifestEntries.put(manifestEntry(entryName, canonicalChild.length(), digest))
                        fileCount += 1
                        byteCount += canonicalChild.length()
                        publishProgress(fileCount, byteCount, canonicalChild.name)
                    }
                }
            }
        }
        return fileCount to byteCount
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
        DigestInputStream(BufferedInputStream(FileInputStream(file)), digest).use { input ->
            copyWithCancellation(input, zip)
        }
        zip.closeEntry()
        return digest.digest().toHex()
    }

    private fun writeBytes(
        zip: ZipOutputStream,
        entryName: String,
        bytes: ByteArray,
        manifestEntries: JSONArray?
    ) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(bytes)
        zip.closeEntry()
        manifestEntries?.put(
            manifestEntry(
                entryName,
                bytes.size.toLong(),
                MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
            )
        )
    }

    private fun manifestEntry(path: String, size: Long, sha256: String): JSONObject {
        return JSONObject().apply {
            put("path", path)
            put("size", size)
            put("sha256", sha256)
        }
    }

    private fun serveArchive(archive: File) {
        serverSocket = ServerSocket(SERVER_PORT, 1, InetAddress.getByName("127.0.0.1"))
        while (!Thread.currentThread().isInterrupted && archiveReady) {
            val socket = serverSocket?.accept() ?: return
            socket.use { client ->
                runCatching {
                    val requestLine = client.getInputStream().bufferedReader().readLine().orEmpty()
                    val allowed = requestLine.startsWith(
                        "GET /${WorkProfileMigrationArchive.ARCHIVE_NAME}?token=$downloadToken "
                    )
                    val output = BufferedOutputStream(client.getOutputStream())
                    if (!allowed) {
                        output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    } else {
                        output.write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/zip\r\n" +
                                "Content-Length: ${archive.length()}\r\n" +
                                "Connection: close\r\n\r\n").toByteArray()
                        )
                        FileInputStream(archive).use { copyWithCancellation(it, output) }
                        output.flush()
                        archiveReady = false
                        WorkProfileMigrationArchive.clearStaleArchive(this)
                        mainHandler.post {
                            if (isFinishing || isDestroyed) return@post
                            binding.btnGenerate.isEnabled = true
                            binding.btnGenerate.text = "生成迁移包"
                            publishStatus("电脑下载完成，手机中的临时迁移包已自动清理。")
                        }
                        serverSocket?.runCatching { close() }
                        return
                    }
                    output.flush()
                }
            }
        }
    }

    private fun publishProgress(files: Int, bytes: Long, currentName: String) {
        if (files % PROGRESS_INTERVAL != 0) return
        publishStatus("正在打包：$files 个文件，${formatBytes(bytes)}\n$currentName")
    }

    private fun publishStatus(message: String) {
        mainHandler.post { if (!isFinishing && !isDestroyed) binding.tvStatus.text = message }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(BufferedInputStream(FileInputStream(file)), digest).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (input.read(buffer) >= 0) Unit
        }
        return digest.digest().toHex()
    }

    private fun copyWithCancellation(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            ensureNotCancelled()
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
        }
    }

    private fun ensureNotCancelled() {
        if (cancelRequested || Thread.currentThread().isInterrupted) {
            throw InterruptedException("Migration cancelled")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun safeSegment(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun formatBytes(bytes: Long): String {
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) "%.2f GB".format(gib) else "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    private companion object {
        const val TAG = "VeilSpaceMigration"
        const val SERVER_PORT = 8766
        const val COPY_BUFFER_SIZE = 256 * 1024
        const val PROGRESS_INTERVAL = 25
    }
}
