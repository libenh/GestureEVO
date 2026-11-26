package io.github.lumiagg.gestureevo.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * LSPosed 运行时入口。
 *
 * 这里仅负责确认模块在目标进程中的加载状态；具体系统 Hook 按目标进程拆分，
 * 并在完成兼容性检查后逐项接入，避免初始化异常影响 SystemUI 或 system_server。
 */
class GestureEvoXposedModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "Module loaded: process=${param.processName}, systemServer=${param.isSystemServer}, " +
                "framework=$frameworkName/$frameworkVersionCode, api=$apiVersion"
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "System server entry ready: classLoader=${param.classLoader}")
    }

    companion object {
        private const val TAG = "GestureEVO/LSPosed"
    }
}
