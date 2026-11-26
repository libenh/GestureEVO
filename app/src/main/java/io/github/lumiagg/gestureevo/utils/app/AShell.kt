package io.github.lumiagg.gestureevo.utils.app

import io.github.lumiagg.gestureevo.diagnostics.AppLogger
import io.github.lumiagg.gestureevo.diagnostics.LogLevel
import io.github.lumiagg.gestureevo.diagnostics.LogModule
import android.content.Context
import android.system.Os
import io.github.lumiagg.gestureevo.R
import io.github.lumiagg.gestureevo.utils.ToastHelper
import io.github.lumiagg.gestureevo.utils.root.RootShell

object AShell {
    /** Executes privileged commands through root only; Shizuku is not used as a fallback. */
    suspend fun doCmd(context: Context, command: String): Pair<Boolean, String> {
        val result = RootShell.execute(command)
        AppLogger.event(
            LogModule.ROOT, "command_returned",
            if (result.isSuccess) LogLevel.DEBUG else LogLevel.WARN,
            fields = mapOf("method" to "root", "exitCode" to result.exitCode)
        )
        return result.isSuccess to result.output
    }

    fun doCmdWithRoot(command: String): Pair<Boolean, String> {
        val result = RootShell.executeBlocking(command)
        AppLogger.event(
            LogModule.SHIZUKU, "command_returned",
            if (result.isSuccess) LogLevel.DEBUG else LogLevel.WARN,
            fields = mapOf("method" to "root", "exitCode" to result.exitCode)
        )
        return result.isSuccess to result.output
    }

    fun setAppEnabledSettings(packageName: String, enable: Boolean, context: Context): Boolean {
        if (context.getAppEnabledState(packageName) == null) return false
        val userId = Os.getuid() / ANDROID_UIDS_PER_USER
        val changeState = packageStateCommand(packageName, enable, userId)
        val command = if (enable) {
            "pm unhide --user $userId ${shellArgument(packageName)}; $changeState"
        } else changeState
        val (success, _) = doCmdWithRoot(command)
        val changed = success && context.getAppEnabledState(packageName) == enable
        if (!changed) {
            ToastHelper.showInUIThread(context, context.getString(R.string.root_shizuku_permission_failed))
        }
        return changed
    }
}
