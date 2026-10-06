package app.dsh.asr

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 集成（v0.4.0）—— 用于「一键设为系统默认识别服务」。
 *
 * ## 为什么需要 Shizuku
 * 系统默认识别服务存在 `Settings.Secure.voice_recognition_service` 这个键里，
 * 写入它需要 `WRITE_SECURE_SETTINGS` 权限 —— **普通应用申请不到**（只有系统应用/root/adb 有）。
 *
 * 主人要求「尝试申请一下 SHZ 权限来自动设」，这正是 Shizuku 的用途：
 * 它让应用能以 **adb(uid 2000)** 身份执行 shell，从而
 * `settings put secure voice_recognition_service <组件>`。
 *
 * ## 三级回退（本类只负责第 ② 级，③ 由调用方处理）
 *  ① 直接写 —— 已 root 或系统签名时可用（`SelfCheck.trySetAsDefault` 里做）
 *  ② **Shizuku**（本类）—— 用户装了 Shizuku 并授权后可用
 *  ③ 都不行 → 提示用户改用「调用方直接指定服务」（DSH 已支持）
 *
 * ## 使用前提（需向用户说明）
 * 手机需装 [Shizuku](https://shizuku.rikka.app/) 并按它的指引激活（root 或无线调试）。
 * 未安装/未授权时本类会明确返回原因，不静默失败。
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"
    private const val REQ_CODE = 4001

    /** Shizuku 是否已安装（Manager 包存在） */
    fun isInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    }.getOrDefault(false)

    /** 是否已获得授权 */
    fun isAuthorized(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 申请 Shizuku 权限（会弹授权框）。结果通过 [onResult] 回调（主线程）。
     */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isInstalledGlobal) { onResult(false); return }
        runCatching {
            if (isAuthorized()) { onResult(true); return }
            Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode == REQ_CODE) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            })
            Shizuku.requestPermission(REQ_CODE)
        }.onFailure {
            Log.w(TAG, "requestPermission failed: ${it.message}")
            onResult(false)
        }
    }

    /** isInstalled 的无 Context 版本（requestPermission 里用） */
    @Volatile private var isInstalledGlobal = false
    fun markInstalled(v: Boolean) { isInstalledGlobal = v }

    /**
     * 以 Shizuku（adb 身份）执行 `settings put secure voice_recognition_service <spec>`。
     * @return true = 执行成功且已生效
     */
    fun setDefaultViaShizuku(ctx: Context, componentSpec: String): Boolean {
        if (!isAuthorized()) {
            Log.w(TAG, "not authorized")
            return false
        }
        val cmd = "settings put secure voice_recognition_service $componentSpec"
        val ok = runCatching {
            // 官方链路（照 dsh-android 的 Privilege.shizukuExec 实现）：
            //   Shizuku.getBinder() → IShizukuService.Stub.asInterface →
            //   newProcess(cmd, env, dir) → IRemoteProcess 的 ParcelFileDescriptor 读输出。
            // ⚠️ 不能用 Shizuku.newProcess —— 它在 13.1.5 里是 private（编译报错实测）。
            val svc = moe.shizuku.server.IShizukuService.Stub.asInterface(Shizuku.getBinder())
                ?: return@runCatching false
            val rp = svc.newProcess(
                arrayOf("sh", "-c", cmd),
                arrayOf("PATH=/system/bin:/system/xbin"),
                null,
            )
            val out = rp.inputStream.let { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    .bufferedReader().readText()
            }
            val err = rp.errorStream.let { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    .bufferedReader().readText()
            }
            val code = rp.waitFor()
            Log.i(TAG, "shizuku exec rc=$code out=$out err=$err")
            code == 0
        }.getOrElse {
            Log.w(TAG, "shizuku exec failed: ${it.message}")
            false
        }
        // 验证是否真的生效（写成功但值没变也算失败）
        val applied = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
                .orEmpty().startsWith(ctx.packageName)
        }.getOrDefault(false)
        return ok && applied
    }
}
