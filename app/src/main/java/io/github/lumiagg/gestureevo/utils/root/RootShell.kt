package io.github.lumiagg.gestureevo.utils.root

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Root-only system control layer.
 *
 * All privileged operations go through one su command path. The UI and feature modules should
 * call these typed methods instead of constructing shell commands themselves.
 */
data class RootCommandResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut
}

object RootShell {
    private const val DEFAULT_TIMEOUT_MS = 4_000L
    private const val MAX_OUTPUT_CHARS = 48 * 1024

    /**
     * Executes a command as UID 0. A timeout forcibly terminates a stuck shell process.
     * Root managers show their authorization prompt when this is the first request.
     */
    suspend fun execute(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): RootCommandResult =
        withContext(Dispatchers.IO) { executeBlocking(command, timeoutMs) }

    /** Requests root and verifies the returned UID instead of trusting that su merely exists. */
    suspend fun isAvailable(): Boolean {
        val result = execute("id -u")
        return result.isSuccess && result.output.trim() == "0"
    }

    /** Injects a tap through Android's input service. Coordinates are physical screen pixels. */
    suspend fun tap(x: Int, y: Int): RootCommandResult {
        require(x >= 0 && y >= 0) { "Tap coordinates must be non-negative" }
        return execute("/system/bin/input tap $x $y")
    }

    /** Injects a swipe. Duration is bounded to avoid accidentally leaving a gesture running. */
    suspend fun swipe(
        startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300L
    ): RootCommandResult {
        require(listOf(startX, startY, endX, endY).all { it >= 0 })
        require(durationMs in 1L..10_000L)
        return execute("/system/bin/input swipe $startX $startY $endX $endY $durationMs")
    }

    /** Sends a numeric Android key code, e.g. 4 for Back or 3 for Home. */
    suspend fun keyEvent(keyCode: Int): RootCommandResult {
        require(keyCode in 0..1000)
        return execute("/system/bin/input keyevent $keyCode")
    }

    /** Replaces the common global navigation actions with root shell equivalents. */
    suspend fun performGlobalAction(action: Int): RootCommandResult = when (action) {
        1 -> keyEvent(4)       // Back
        2 -> keyEvent(3)       // Home
        3 -> keyEvent(187)     // Recent apps
        4 -> execute("cmd statusbar expand-notifications")
        5 -> execute("cmd statusbar expand-settings")
        8 -> keyEvent(223)     // Lock screen
        else -> RootCommandResult(-1, "Unsupported system action: $action")
    }

    /**
     * Captures the display through screencap and reads the result from the app cache.
     * This does not depend on AccessibilityService.takeScreenshot().
     */
    suspend fun captureScreen(context: Context): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "root-screen-${System.nanoTime()}.png")
        try {
            val result = executeBlocking("/system/bin/screencap -p ${quote(file.absolutePath)}", 8_000L)
            if (!result.isSuccess || !file.isFile || file.length() == 0L) return@withContext null
            BitmapFactory.decodeFile(file.absolutePath)
        } finally {
            file.delete()
        }
    }

    /**
     * Reads the resumed application from ActivityManager, with WindowManager as a fallback.
     * The result is best-effort because OEM dumpsys formats are not fully standardized.
     */
    suspend fun foregroundPackage(): String? = withContext(Dispatchers.IO) {
        val activity = executeBlocking("dumpsys activity activities", 5_000L)
        val activityPattern = Regex("""(?:mResumedActivity|topResumedActivity)\s*[:=]\s*.*?\bu\d+\s+([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/""")
        activityPattern.find(activity.output)?.groupValues?.getOrNull(1)
            ?: executeBlocking("dumpsys window windows", 5_000L).output.let { windowOutput ->
                Regex("""mCurrentFocus=Window\{[^}]*\bu\d+\s+([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)/""")
                    .find(windowOutput)?.groupValues?.getOrNull(1)
            }
    }

    /** Shell-escapes one argument. Never interpolate arbitrary app or file names directly. */
    fun quote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    internal fun executeBlocking(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RootCommandResult {
        require(command.isNotBlank())
        require(timeoutMs > 0)
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (failure: Exception) {
            return RootCommandResult(-1, failure.message.orEmpty())
        }
        val reader = Executors.newSingleThreadExecutor { task ->
            Thread(task, "GestureEVO-root-output").apply { isDaemon = true }
        }
        val outputFuture = reader.submit<String> {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { input ->
                val output = StringBuilder()
                val buffer = CharArray(2048)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.length < MAX_OUTPUT_CHARS) {
                        output.append(buffer, 0, count.coerceAtMost(MAX_OUTPUT_CHARS - output.length))
                    }
                }
                output.toString()
            }
        }
        // Use a Future timeout instead of Process.waitFor(timeout, unit), which is not
        // available on every Android release supported by this app (minSdk 24).
        val exitFuture = reader.submit<Int> { process.waitFor() }
        return try {
            val exitCode = exitFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
            RootCommandResult(exitCode, outputFuture.get(1, TimeUnit.SECONDS))
        } catch (_: TimeoutException) {
            process.destroy()
            RootCommandResult(-1, runCatching {
                outputFuture.get(500, TimeUnit.MILLISECONDS)
            }.getOrDefault("Root command timed out"), timedOut = true)
        } catch (failure: Exception) {
            process.destroy()
            RootCommandResult(-1, failure.message.orEmpty())
        } finally {
            reader.shutdownNow()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}
