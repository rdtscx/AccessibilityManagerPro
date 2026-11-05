package com.acsmanager.pro.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 授权通道探测与命令执行。
 * 三种通道：
 *  - APP_GRANTED：已通过 adb shell pm grant 授予 WRITE_SECURE_SETTINGS，可直接写 Settings.Secure/Global
 *  - ROOT：设备已 Root，通过 su 执行
 *  - SHIZUKU：已连接 Shizuku 且已授权，通过 Shizuku 提供的 shell 进程执行
 *
 * 7.0.2 修复：
 *  - Shizuku 通道改用 `Shizuku.newProcess` 直接起 shell，放弃 UserService 方案。
 *    原 CommandService 继承 android.app.Service，而 Shizuku 只接受「带 Context 构造参数的 IBinder」
 *    实现，实例化即失败，bindUserService 永远等不到回调，Shizuku 通道形同虚设。
 *  - 新增 shellChannel()：APP_GRANTED 只能写 Settings，不能执行 shell 命令。原实现用 bestChannel()
 *    分流，用户先用 ADB 授权过就会一直拿到 APP_GRANTED，于是 Root/Shizuku 永远不会被用于
 *    pm grant / am startservice / pidof 等命令，表现为「Root 和 Shizuku 都不生效」。
 *  - Root 改为交互式 su（写 stdin），失败再退到 `su -c`，兼容各版本 su 对参数解析的差异。
 */
object Privilege {

    private const val TAG = "AcsPrivilege"
    const val PERM = "android.permission.WRITE_SECURE_SETTINGS"
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    /** Shizuku 官方最低支持 Android 7.0（API 24）；低于此版本硬性禁用 Shizuku 通道。 */
    private const val MIN_SHIZUKU_API = 24

    enum class Channel(val labelRes: Int) {
        APP_GRANTED(com.acsmanager.pro.R.string.status_channel_app),
        ROOT(com.acsmanager.pro.R.string.status_channel_root),
        SHIZUKU(com.acsmanager.pro.R.string.status_channel_shizuku),
        NONE(com.acsmanager.pro.R.string.status_channel_none)
    }

    /** 应用自身是否已被授予 WRITE_SECURE_SETTINGS（ADB 通道的产物）。 */
    fun hasAppGranted(ctx: Context): Boolean = try {
        ctx.checkPermission(PERM, android.os.Process.myPid(), android.os.Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    // ---------- Root 探测（带缓存，避免反复 fork su 耗电/弹授权框） ----------

    @Volatile
    private var rootConfirmed = false

    @Volatile
    private var rootLastFailMs = 0L

    /** Root 失败结果缓存时长：15 秒内不重复探测（应对 su 授权框被拒/短暂不可用）。 */
    private const val ROOT_FAIL_CACHE_MS = 15_000L

    private fun isMainThread(): Boolean =
        android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    /** Root 异步探测是否已在排队（幂等，避免重复 fork su）。 */
    @Volatile
    private var rootProbePending = false

    /** 后台线程池：承载 su 探测等可能阻塞的操作。 */
    private val bgExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "acs-privilege-bg").apply { isDaemon = true }
        }

    /**
     * 后台异步探测 Root（结果写回缓存）。
     *
     * su 探测可能弹出 Magisk/SuperSU 授权框并阻塞数秒，若在主线程执行会直接 ANR，
     * 因此主线程调用方一律不阻塞，只触发本方法并把结果留待下一次读取。
     */
    fun requestRootProbeAsync() {
        if (rootConfirmed || rootProbePending) return
        if (System.currentTimeMillis() - rootLastFailMs < ROOT_FAIL_CACHE_MS) return
        rootProbePending = true
        try {
            bgExecutor.execute {
                try {
                    probeRootInternal()
                } catch (t: Throwable) {
                    Log.w(TAG, "async root probe failed", t)
                } finally {
                    rootProbePending = false
                }
            }
        } catch (t: Throwable) {
            rootProbePending = false
        }
    }

    private fun probeRootInternal(): Boolean {
        val out = runSu(arrayOf("id"), 8)
        val ok = out != null && out.contains("uid=0")
        if (ok) {
            rootConfirmed = true
        } else {
            rootLastFailMs = System.currentTimeMillis()
        }
        return ok
    }

    /**
     * 探测 Root（结果缓存）。
     *
     * 主线程调用**不**阻塞 fork su，只返回已知缓存状态，实际探测转交后台线程；
     * 后台线程调用才会同步探测。
     */
    fun hasRoot(): Boolean {
        if (rootConfirmed) return true
        if (isMainThread()) {
            requestRootProbeAsync()
            return false
        }
        if (System.currentTimeMillis() - rootLastFailMs < ROOT_FAIL_CACHE_MS) return false
        return probeRootInternal()
    }

    /** su 明确返回权限拒绝时失效 Root 缓存，下一轮重新探测。 */
    private fun invalidateRoot() {
        rootConfirmed = false
        rootLastFailMs = System.currentTimeMillis()
    }

    fun shizukuReady(): Boolean = if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) false else try {
        shizukuConnected() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun shizukuConnected(): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) return false
        return try {
            // 优先使用 Application 中维护的 Binder 存活状态（由 OnBinderReceived/Dead 监听器实时更新），
            // 避免每次检测都跨进程 ping 导致 Shizuku 服务端频繁校验客户端兼容性。
            if (com.acsmanager.pro.App.shizukuBinderAlive) true else Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
    }

    fun shizukuGranted(): Boolean = if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) false else try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    fun hasShizukuInstalled(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) return false
        return try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 能执行 shell 命令的通道：只可能是 ROOT / SHIZUKU / NONE。
     * APP_GRANTED 仅能写 Settings，不具备 shell 身份，不能作为执行通道。
     */
    fun shellChannel(ctx: Context): Channel = when {
        hasRoot() -> Channel.ROOT
        shizukuReady() -> Channel.SHIZUKU
        else -> Channel.NONE
    }

    /** 当前可用的最佳通道（用于状态展示）。Root 结果已缓存，重复调用开销极小。 */
    fun bestChannel(ctx: Context): Channel = when {
        hasAppGranted(ctx) -> Channel.APP_GRANTED
        else -> shellChannel(ctx)
    }

    /**
     * 执行系统命令（需要 shell 身份）。
     * 用于 pm grant/revoke、am startservice、settings put、pidof 等系统级操作。
     */
    fun execShell(ctx: Context, vararg cmd: String): String? = when (shellChannel(ctx)) {
        Channel.ROOT -> runSu(cmd, 10)
        Channel.SHIZUKU -> shizukuExec(cmd, 10)
        else -> null
    }

    // ---------- Root 通道 ----------

    /**
     * 以 root 身份执行命令。
     *
     * 先走交互式 su（把命令写进 stdin）：不同 su 实现（Magisk / KernelSU / 旧 SuperSU）对
     * `-c` 的参数解析并不一致，交互式写法最通用；失败再退到 `su -c`。
     */
    private fun runSu(cmd: Array<out String>, timeoutSec: Long): String? {
        val line = joinForShell(cmd)
        val interactive = interactiveSu(line, timeoutSec)
        // null = 超时或 su 不可用，再试 `-c` 只会再等一轮，直接判失败
        if (interactive == null) return null
        if (!isDenied(interactive)) return interactive
        invalidateRoot()
        val direct = runProcess(arrayOf("su", "-c", line), timeoutSec)
        if (direct != null && !isDenied(direct)) return direct
        if (direct != null) invalidateRoot()
        return null
    }

    private fun interactiveSu(command: String, timeoutSec: Long): String? {
        var started: Process? = null
        return try {
            val proc = ProcessBuilder("su").redirectErrorStream(true).start()
            started = proc
            // 命令与 exit 一起写入：su 读到 exit 后退出，避免进程挂起等超时
            proc.outputStream.use { it.write("$command\nexit\n".toByteArray()) }
            readWithTimeout(proc, timeoutSec)
        } catch (t: Throwable) {
            Log.w(TAG, "interactive su failed", t)
            started?.let { destroyProcessCompat(it) }
            null
        }
    }

    // ---------- Shizuku 通道 ----------

    /**
     * 通过 Shizuku 起一个 shell 进程执行命令。
     *
     * Shizuku 以 shell 身份（uid 2000）派生该进程，因此能执行 settings / pm / am 等命令。
     * newProcess 需要已安装 Shizuku、已连接且已授权；任一条缺失时直接返回 null，
     * 由调用方判定失败，不会阻塞或抛给上层。
     */
    private fun shizukuExec(cmd: Array<out String>, timeoutSec: Long): String? {
        if (Build.VERSION.SDK_INT < MIN_SHIZUKU_API) return null
        if (!shizukuReady()) {
            Log.w(TAG, "shizuku not ready, skip: ${cmd.joinToString(" ")}")
            return null
        }
        var started: Process? = null
        return try {
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", joinForShell(cmd)), null, null)
            started = proc
            readWithTimeout(proc, timeoutSec)
        } catch (t: Throwable) {
            Log.w(TAG, "shizuku exec failed: ${cmd.joinToString(" ")}", t)
            started?.let {
                try { it.destroy() } catch (_: Throwable) {}
            }
            null
        }
    }

    // ---------- 进程执行与读流 ----------

    /** 通用进程执行（带超时保护）。 */
    private fun runProcess(cmd: Array<String>, timeoutSec: Long = 10): String? {
        var started: Process? = null
        return try {
            val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            started = proc
            readWithTimeout(proc, timeoutSec)
        } catch (t: Throwable) {
            Log.w(TAG, "shell failed: ${cmd.joinToString(" ")}", t)
            started?.let { destroyProcessCompat(it) }
            null
        }
    }

    /**
     * 读进程输出并等待结束，带真实超时。
     *
     * 读流放独立线程：只要子进程不结束（su 等授权框、su 环境损坏、sh 被挂起），
     * 同步读流会永久阻塞，超时逻辑形同虚设。主流程只等 waitFor(timeout)，超时立即强杀。
     */
    private fun readWithTimeout(proc: Process, timeoutSec: Long): String? {
        var out = ""
        val reader = Thread {
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { br -> out = br.readText() }
            } catch (t: Throwable) {
                // 进程被强杀时流会提前关闭，属正常路径
            }
        }.apply {
            isDaemon = true
            name = "acs-shell-reader"
        }
        reader.start()
        val finished = if (Build.VERSION.SDK_INT >= 26) {
            proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        } else {
            val worker = Thread {
                try { proc.waitFor() } catch (t: Throwable) {}
            }.apply { isDaemon = true; start() }
            worker.join(timeoutSec * 1000L)
            !worker.isAlive
        }
        if (!finished) {
            destroyProcessCompat(proc)
            try { reader.interrupt() } catch (t: Throwable) {}
            Log.w(TAG, "shell timeout after ${timeoutSec}s")
            return null
        }
        // 进程已退出但读线程可能还剩缓冲：限时等待，避免再次无限阻塞
        try { reader.join(1500L) } catch (t: Throwable) {}
        return out.trim()
    }

    private fun destroyProcessCompat(p: java.lang.Process) {
        try {
            if (Build.VERSION.SDK_INT >= 26) p.destroyForcibly() else p.destroy()
        } catch (t: Throwable) {}
    }

    /** su 明确返回权限拒绝时视为执行失败，同时失效 Root 缓存。 */
    private fun isDenied(out: String): Boolean =
        out.contains("permission denied", ignoreCase = true)

    /**
     * 将命令参数拼接为单条 shell 命令字符串，逐个参数做 POSIX 单引号转义，
     * 避免含空格/特殊字符的参数被 shell 错误拆分。
     */
    private fun joinForShell(cmd: Array<out String>): String =
        cmd.joinToString(" ") { shellQuote(it) }

    /** POSIX shell 单引号转义：' -> '\''；安全字符集免引号。 */
    private fun shellQuote(s: String): String {
        if (s.isEmpty()) return "''"
        if (s.all { it.isLetterOrDigit() || it in "_-./:@%+=" }) return s
        return "'" + s.replace("'", "'\\''") + "'"
    }

    /** Shizuku 状态细分（首页授权卡片展示用）。 */
    enum class ShizukuStatus {
        NOT_INSTALLED,   // 未安装 Shizuku
        NOT_CONNECTED,   // 已安装但未启动
        NO_PERMISSION,   // 已连接但未授权本应用
        READY            // 已连接且已授权
    }

    fun shizukuStatus(ctx: Context): ShizukuStatus = when {
        !hasShizukuInstalled(ctx) -> ShizukuStatus.NOT_INSTALLED
        !shizukuConnected() -> ShizukuStatus.NOT_CONNECTED
        !shizukuGranted() -> ShizukuStatus.NO_PERMISSION
        else -> ShizukuStatus.READY
    }
}
