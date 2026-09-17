package com.n3d.spectra.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/* ---------------------------------------------------------------------------
   Updating the app from inside the app.

   These apps are not on Play. They are downloaded as APKs from n3d-store.com,
   which means that without this file the only way anybody learns a new version
   exists is by going back to the website and looking — so in practice nobody
   ever does, and every install in the world stays on whatever version it was
   the day it was fetched.

   The whole cycle lives here: ask the store what the newest build is, compare
   it against what is actually installed, download it, prove the bytes are the
   ones the store meant, and hand the file to Android's package installer.

   Three things make that safe enough to do without a store in front of it:

   - **The checksum is the contract.** The server hashes the APK on disk at boot
     and publishes that digest in the same response as the URL. The download is
     hashed as it streams, and a file that does not match is deleted and never
     offered. A truncated download and a substituted one fail identically, which
     is the point.
   - **The signature must be ours.** Android already refuses to replace an app
     with one signed by a different key, but it refuses *at the end*, after the
     user has sat through a download and an install prompt. Checking it here
     turns that into one honest sentence before anything is installed.
   - **Nothing installs itself.** Android shows its own confirmation and the
     user taps it. `REQUEST_INSTALL_PACKAGES` is not a licence to install
     silently and this does not try to behave like one.

   Written against java.net and org.json to match the rest of these apps: one
   JSON object and one file download do not justify a networking dependency.
   --------------------------------------------------------------------------- */

/** What the store says the newest build is. */
data class Release(
    val versionName: String,
    val versionCode: Long,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
    val minSdk: Int,
    /** One or two sentences about this version, or null if the store sent none. */
    val notes: String?,
)

/**
 * Why an update attempt stopped.
 *
 * A code rather than a sentence because one of these apps is bilingual and all
 * of them phrase things their own way; the words live next to the UI that shows
 * them. `Network` and `Server` are deliberately not merged — "your phone never
 * reached the store" and "the store answered with nonsense" want different
 * things from the person reading them.
 */
enum class UpdateError {
    /** Never reached n3d-store.com at all. */
    Network,

    /** Reached it and got something that was not a usable manifest. */
    Server,

    /** The download does not hash to what the store published. */
    Checksum,

    /** The download is signed by a different key, so it can never replace this app. */
    Signature,

    /** The download is a different app entirely. */
    Package,

    /** The new version needs a newer Android than this phone runs. */
    TooOld,

    /** No room on the device, or the cache directory is not writable. */
    Storage,

    /** Android's package installer refused it. `detail` carries its own words. */
    Install,
}

/** Where the update is up to. One value, so the UI can be a single `when`. */
sealed interface UpdateState {
    /** Nothing asked for yet, or the last answer was dismissed. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** Asked, and this is already the newest build. */
    data class UpToDate(val checkedAt: Long) : UpdateState

    data class Available(val release: Release) : UpdateState

    /** `total` is 0 when the server sent no Content-Length. */
    data class Downloading(val release: Release, val bytes: Long, val total: Long) : UpdateState

    /** Downloaded and proven, waiting on the permission that lets us install it. */
    data class Ready(val release: Release, val file: File) : UpdateState

    /** Handed to Android. Its confirmation dialog is what comes next. */
    data class Installing(val release: Release) : UpdateState

    data class Failed(val error: UpdateError, val detail: String? = null) : UpdateState
}

class UpdateManager(context: Context) {

    private val app: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var work: Job? = null

    /** What is running right now — read once; a successful update restarts the process. */
    val installedVersionName: String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName
    }.getOrNull().orEmpty()

    val installedVersionCode: Long = runCatching {
        PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
    }.getOrDefault(0L)

    /**
     * Whether to look for a new version by itself.
     *
     * On by default. An update nobody is told about is an update nobody
     * installs, and the check is one small JSON response at most once a day.
     * Off means the Settings button is the only thing that ever reaches the
     * network for this — which for two of these apps is the only thing that
     * reaches the network at all.
     */
    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO, value) }

    val lastCheckedAt: Long get() = prefs.getLong(KEY_LAST, 0L)

    /**
     * True when this build cannot replace itself whatever the store says —
     * a debug build with a suffixed application id, or a sideloaded fork.
     *
     * Worth catching early: the install would otherwise succeed and leave two
     * copies of the app on the phone, which looks like the updater is broken in
     * a much more confusing way than being told it is not available.
     */
    val canSelfUpdate: Boolean get() = app.packageName == PACKAGE

    // ---- checking -----------------------------------------------------------

    /**
     * The once-a-day background look, for MainActivity to call on start.
     *
     * Silent in both directions: it never shows a spinner and never leaves an
     * error on the Settings screen, because the person did not ask for this and
     * a dropped connection at launch is not news. Only a genuine new version
     * changes what they see.
     */
    fun checkOnLaunch() {
        if (!autoCheck || !canSelfUpdate) return
        if (System.currentTimeMillis() - lastCheckedAt < AUTO_INTERVAL_MS) return
        if (_state.value != UpdateState.Idle) return
        check(silent = true)
    }

    /** The Settings button. Says what happened either way. */
    fun check(silent: Boolean = false) {
        if (work?.isActive == true) return
        if (!canSelfUpdate) return
        if (!silent) _state.value = UpdateState.Checking
        work = scope.launch {
            try {
                val release = fetchManifest()
                prefs.edit { putLong(KEY_LAST, System.currentTimeMillis()) }
                _state.value = when {
                    release == null || release.versionCode <= installedVersionCode ->
                        if (silent) UpdateState.Idle else UpdateState.UpToDate(System.currentTimeMillis())
                    release.minSdk > Build.VERSION.SDK_INT ->
                        if (silent) UpdateState.Idle else UpdateState.Failed(UpdateError.TooOld)
                    else -> UpdateState.Available(release)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = if (silent) UpdateState.Idle else UpdateState.Failed(errorFor(e))
            }
        }
    }

    // ---- downloading and installing ----------------------------------------

    /**
     * Fetch the APK, prove it, and hand it over.
     *
     * Everything is verified before the installer is ever opened, so the worst
     * case is a message on this screen rather than a half-finished install.
     */
    fun download(release: Release) {
        if (work?.isActive == true) return
        work = scope.launch {
            val target = File(cacheDir(), "${SLUG}-${release.versionCode}.apk")
            try {
                _state.value = UpdateState.Downloading(release, 0, release.sizeBytes)
                val digest = fetchApk(release, target)

                if (!digest.equals(release.sha256, ignoreCase = true)) {
                    target.delete()
                    _state.value = UpdateState.Failed(UpdateError.Checksum)
                    return@launch
                }
                verifyArchive(target)?.let { problem ->
                    target.delete()
                    _state.value = UpdateState.Failed(problem)
                    return@launch
                }

                // The permission is asked for at the last possible moment: by
                // now the file is on the device and proven, so granting it is
                // one tap away from a finished update rather than the start of
                // a download that might still fail.
                if (canInstallPackages()) install(release, target) else _state.value = UpdateState.Ready(release, target)
            } catch (e: CancellationException) {
                target.delete()
                throw e
            } catch (e: Exception) {
                target.delete()
                _state.value = UpdateState.Failed(errorFor(e))
            }
        }
    }

    /** Coming back from the "install unknown apps" screen with the file already here. */
    fun resumeInstall() {
        val ready = _state.value as? UpdateState.Ready ?: return
        if (!ready.file.exists()) {
            _state.value = UpdateState.Available(ready.release)
            return
        }
        if (canInstallPackages()) scope.launch { install(ready.release, ready.file) }
    }

    fun cancel() {
        work?.cancel()
        work = null
        _state.value = UpdateState.Idle
    }

    /** Back to a screen with no update news on it. */
    fun dismiss() {
        if (work?.isActive == true) return
        _state.value = UpdateState.Idle
    }

    fun canInstallPackages(): Boolean = app.packageManager.canRequestPackageInstalls()

    /**
     * The Settings screen that grants "install unknown apps", scoped to this app.
     *
     * There is no dialog for this permission and no way to ask for it in one:
     * `ACTION_MANAGE_UNKNOWN_APP_SOURCES` with a package: URI is the only route,
     * and it drops the person on a system screen with a single switch.
     */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${app.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // ---- the network --------------------------------------------------------

    private fun fetchManifest(): Release? {
        val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", userAgent())
        }
        val body = try {
            if (conn.responseCode !in 200..299) throw ServerException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }

        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ServerException("unparseable manifest")
        }
        // An APK missing from the server is not an error to shout about — it
        // simply means there is nothing to offer yet.
        if (!json.optBoolean("available", false)) return null

        val sha = json.optString("sha256").orEmpty()
        val url = json.optString("url").orEmpty()
        val code = json.optLong("versionCode", -1L)
        if (sha.length != 64 || !url.startsWith("https://") || code <= 0) {
            throw ServerException("incomplete manifest")
        }
        return Release(
            versionName = json.optString("versionName").ifBlank { code.toString() },
            versionCode = code,
            sizeBytes = json.optLong("size", 0L),
            sha256 = sha,
            url = url,
            minSdk = json.optInt("minSdk", 0),
            notes = json.optString("notes").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /** Streams the APK to `target`, returns its SHA-256. Hashed as it arrives, so
        the file is never read twice and a partial download can never be hashed. */
    private fun fetchApk(release: Release, target: File): String {
        target.parentFile?.mkdirs()
        val conn = (URL(release.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", userAgent())
        }
        try {
            if (conn.responseCode !in 200..299) throw ServerException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastEmit = 0L

            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        // Emitting every chunk would recompose a progress bar
                        // three hundred times a second for no visible gain.
                        if (done - lastEmit >= 128 * 1024) {
                            lastEmit = done
                            _state.value = UpdateState.Downloading(release, done, total)
                        }
                    }
                }
            }
            _state.value = UpdateState.Downloading(release, done, total)
            return digest.digest().hex()
        } finally {
            conn.disconnect()
        }
    }

    // ---- proving the file ---------------------------------------------------

    /**
     * Everything that can be known about the APK before installing it: that it
     * is this app, and that it is signed by the key this copy was signed with.
     *
     * Returns null when the file is good. An unreadable archive is not treated
     * as a mismatch — some ROMs hand back nothing here — because the checksum
     * has already established that these are the bytes the store published;
     * this is the second lock, not the first.
     */
    private fun verifyArchive(file: File): UpdateError? {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val info = runCatching { pm.getPackageArchiveInfo(file.absolutePath, flags) }.getOrNull()
            ?: return null

        if (info.packageName != app.packageName) return UpdateError.Package

        val theirs = certificatesOf(info) ?: return null
        val ours = certificatesOf(
            runCatching { pm.getPackageInfo(app.packageName, flags) }.getOrNull() ?: return null,
        ) ?: return null
        return if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs != ours) UpdateError.Signature else null
    }

    /* The API 28 branch is dead weight in the apps whose minSdk is already
       above it — lint says so — but this file is one text shared by five apps,
       two of which go back to Android 8. Deleting it here means deleting it
       from those too. */
    @Suppress("DEPRECATION")
    private fun certificatesOf(info: PackageInfo): Set<String>? {
        val signatures = (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners
            else info.signatures
            ) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { signature -> digest.digest(signature.toByteArray()).hex() }.toSet()
    }

    // ---- handing it to Android ---------------------------------------------

    private fun install(release: Release, apk: File) {
        _state.value = UpdateState.Installing(release)
        try {
            registerInstallReceiver()
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(app.packageName) }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // FLAG_MUTABLE is required: the system fills this intent in with
                // the status and, for the confirmation step, with an intent of
                // its own. An immutable PendingIntent silently arrives empty.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(
                    app,
                    sessionId,
                    Intent(ACTION_INSTALL_STATUS).setPackage(app.packageName),
                    flags,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(UpdateError.Install, e.message)
        }
    }

    private var receiver: BroadcastReceiver? = null

    /**
     * Registered at run time rather than in the manifest so that this whole
     * feature is one file per app, and so the state it reports goes straight
     * back into the flow the Settings screen is already watching.
     */
    private fun registerInstallReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // Android's own confirmation. This is the only moment
                        // anything is actually installed, and a person does it.
                        val confirm =
                            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        if (confirm != null) {
                            runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { _state.value = UpdateState.Failed(UpdateError.Install, it.message) }
                        } else {
                            _state.value = UpdateState.Failed(UpdateError.Install)
                        }
                    }
                    // Nothing follows success: the process is replaced, and the
                    // next thing anybody sees is the new version starting.
                    PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle
                    PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Idle
                    Int.MIN_VALUE -> Unit
                    else -> _state.value = UpdateState.Failed(
                        UpdateError.Install,
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                    )
                }
            }
        }
        ContextCompat.registerReceiver(
            app,
            r,
            IntentFilter(ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = r
    }

    // ---- odds and ends ------------------------------------------------------

    /** Kept in the cache directory on purpose: Android may reclaim it, and an
        abandoned APK should not count against the app's storage forever. */
    private fun cacheDir(): File = File(app.cacheDir, "updates").also { dir ->
        dir.mkdirs()
        // Anything left from an earlier attempt is either already installed or
        // was never wanted.
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun userAgent(): String = "$NAME/$installedVersionName (Android ${Build.VERSION.RELEASE})"

    private class ServerException(message: String) : IOException(message)

    private fun ByteArray.hex(): String =
        joinToString("") { byte -> "%02x".format(byte) }

    private fun errorFor(e: Exception): UpdateError = when {
        e is ServerException -> UpdateError.Server
        e is IOException && e.message?.contains("space", true) == true -> UpdateError.Storage
        e is IOException -> UpdateError.Network
        else -> UpdateError.Server
    }

    companion object {
        /** This app's entry in the store catalogue. */
        const val SLUG = "spectra"

        /** The published application id. A build whose id differs from this one
            cannot replace the published app, so it does not offer to. */
        const val PACKAGE = "com.n3d.spectra"

        private const val NAME = "Spectra"
        private const val MANIFEST_URL = "https://n3d-store.com/api/apps/$SLUG/update"
        private const val PREFS = "n3d_update"
        private const val KEY_AUTO = "auto_check"
        private const val KEY_LAST = "last_check"
        private const val ACTION_INSTALL_STATUS = "com.n3d.spectra.UPDATE_INSTALL_STATUS"

        /** Once a day is enough for an app released a few times a year, and it
            keeps the request invisible on any data plan. */
        private const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

        /** "3.8 MB" — for the download button, so a person on mobile data knows
            what they are agreeing to before they tap it. */
        fun formatSize(bytes: Long): String = when {
            bytes <= 0L -> ""
            bytes < 1024L * 1024 -> "${(bytes + 1023) / 1024} kB"
            else -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
