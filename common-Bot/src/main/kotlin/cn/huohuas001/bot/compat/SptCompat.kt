package cn.huohuas001.bot.compat

import cn.huohuas001.bot.tools.PluginFileLog
import io.github.kloping.qqbot.Starter
import io.github.kloping.spt.Setting
import io.github.kloping.spt.StarterObjectApplication
import io.github.kloping.spt.interfaces.component.PackageScanner
import java.io.Closeable
import java.io.File
import java.io.Flushable
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLDecoder
import java.util.IdentityHashMap
import java.util.Timer
import java.util.concurrent.ExecutorService
import java.util.jar.JarFile

/**
 * kloping SDK（jv-spt + qqbot）运行时兼容层。
 *
 * 1.5.4.2 引入，解决两个 NeoForge 模组环境（JPMS 模块类加载）特有的问题：
 *
 * 问题一：QQ 机器人启动时包扫描 NPE 崩溃
 * - 现象：`PackageScannerImpl.scan` 抛 `NullPointerException: classNames is null`
 *   （StarterObjectApplication.work → PackageScannerImpl.scan）；
 * - 根因：SDK 原生扫描只认 `ClassLoader.getResource("包路径")` 返回的 `file:` / `jar:`
 *   两种协议；NeoForge 把 mod jar 作为命名模块加载（ModLauncher
 *   TransformingClassLoader），包目录要么查不到资源、要么返回 `union:` 协议 URL，
 *   两个分支都不命中 → classNames 保持 null → 遍历时 NPE；
 * - 组件装配随之失败（wssWorker 未创建，后续 `submit(null)` 二次 NPE），命令系统全瘫。
 *
 * 修复：在 SDK 扫描前（PRE_SCAN_RUNNABLE 时序点，此时 Setting.INSTANCE 已创建）
 * 反射替换 `Setting.packageScanner` 为兼容实现——优先走原生 classpath 扫描
 * （Spigot 行为零变化），失败或为空时按 jar 条目直接枚举兜底。
 *
 * 问题二：服务器无法正常关闭（stop 后进程挂起）
 * - 根因：SDK 的 spt 框架与 common 库创建了 50+ 个非 daemon 线程
 *   （QueueExecutor 20+20、TimeMethodManager 5+Timer、Public 静态池 8+1、
 *   Java-WebSocket 收发线程），`Starter.shutdown()` 只关 WSS，进程因此退不出去；
 * - 修复：停机时反射清扫对象图，shutdownNow 全部线程池、cancel Timer、
 *   关闭 WSS 与日志 Writer（只触碰 io.github.kloping.* 对象，不波及平台类）。
 */
object SptCompat {

    /** 注入模块化包扫描兼容层（须在 Starter.run 之前调用）。 */
    fun injectModuleCompatScanner(application: StarterObjectApplication) {
        try {
            application.PRE_SCAN_RUNNABLE.add(Runnable {
                try {
                    val setting = application.INSTANCE ?: return@Runnable
                    val field = Setting::class.java.getDeclaredField("packageScanner")
                    field.isAccessible = true
                    val original = field.get(setting) as? PackageScanner ?: return@Runnable
                    if (original is ModuleCompatPackageScanner) return@Runnable
                    field.set(setting, ModuleCompatPackageScanner(original))
                    PluginFileLog.infoAndKeep("[兼容] 模块化包扫描兼容层已挂载")
                } catch (t: Throwable) {
                    PluginFileLog.warnAndKeep("[兼容] 包扫描兼容层挂载失败: ${t.message}")
                }
            })
        } catch (_: Throwable) {
        }
    }

    /**
     * 停机清扫：回收 kloping 运行时的全部非 daemon 线程资源。
     * 在 Starter.shutdown()（关 WSS）之后调用。
     */
    fun shutdownSptRuntime(starter: Starter?) {
        if (starter == null) return
        val closed = mutableListOf<String>()
        try {
            sweepObjectGraph(starter, closed)
        } catch (_: Throwable) {
        }
        try {
            sweepStaticPools(closed)
        } catch (_: Throwable) {
        }
        if (closed.isNotEmpty()) {
            PluginFileLog.infoAndKeep("[停机] 已回收 kloping 线程池: ${closed.joinToString("、")}")
        }
    }

    // ---------- 停机清扫实现 ----------

    /** 递归遍历对象图（仅 io.github.kloping.* 类），终止一切线程/定时/IO 资源。 */
    private fun sweepObjectGraph(root: Any, closed: MutableList<String>) {
        val visited = IdentityHashMap<Any, Any>()
        walk(root, 0, visited, closed)
    }

    private fun walk(obj: Any, depth: Int, visited: IdentityHashMap<Any, Any>, closed: MutableList<String>) {
        if (depth > 4 || visited.put(obj, obj) != null) return
        if (!isKlopingClass(obj.javaClass)) return
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null && clazz != Any::class.java) {
            for (field in clazz.declaredFields) {
                val value = try {
                    field.isAccessible = true
                    field.get(obj)
                } catch (_: Throwable) {
                    null
                } ?: continue
                when (value) {
                    is ExecutorService -> terminate(value, field.name, closed)
                    is Timer -> try {
                        value.cancel()
                        closed.add("${field.name}(timer)")
                    } catch (_: Throwable) {
                    }
                    is org.java_websocket.client.WebSocketClient -> try {
                        if (!value.isClosed) {
                            value.close()
                            closed.add("wss-socket")
                        }
                    } catch (_: Throwable) {
                    }
                    is Closeable -> try {
                        if (value is Flushable) value.flush()
                        value.close()
                        closed.add("${field.name}(writer)")
                    } catch (_: Throwable) {
                    }
                    else -> if (depth < 4 && isKlopingClass(value.javaClass)) {
                        walk(value, depth + 1, visited, closed)
                    }
                }
            }
            clazz = clazz.superclass
        }
    }

    /** 清扫 io.github.kloping.common.Public 的静态线程池（wssWork 用的 EXECUTOR_SERVICE1 等）。 */
    private fun sweepStaticPools(closed: MutableList<String>) {
        val publicClass = try {
            Class.forName("io.github.kloping.common.Public")
        } catch (_: Throwable) {
            null
        } ?: return
        for (field in publicClass.declaredFields) {
            if (!Modifier.isStatic(field.modifiers)) continue
            val value = try {
                field.isAccessible = true
                field.get(null)
            } catch (_: Throwable) {
                null
            }
            if (value is ExecutorService) terminate(value, "Public.${field.name}", closed)
        }
    }

    private fun terminate(pool: ExecutorService, name: String, closed: MutableList<String>) {
        try {
            pool.shutdownNow()
            closed.add(name)
        } catch (_: Throwable) {
        }
    }

    private fun isKlopingClass(clazz: Class<*>): Boolean =
        clazz.name.startsWith("io.github.kloping.")
}

/**
 * 模块化环境兼容的包扫描器（包装 SDK 原生实现）。
 *
 * 优先委托原生扫描（Spigot classpath 环境行为与旧版完全一致）；
 * 原生扫描抛错或返回空（NeoForge JPMS：包目录资源不可见 / union: 协议）
 * 时，定位包含目标包的物理 jar（file: / jar: / union: 三种 URL 均可解析），
 * 按 zip 条目直接枚举 .class 并用原 ClassLoader 装载。
 */
class ModuleCompatPackageScanner(private val delegate: PackageScanner) : PackageScanner {

    override fun getDefaultClass(): MutableList<Class<*>> =
        try {
            @Suppress("UNCHECKED_CAST")
            (delegate.defaultClass as? MutableList<Class<*>>) ?: mutableListOf()
        } catch (_: Throwable) {
            mutableListOf()
        }

    override fun scan(klass: Class<*>, loader: ClassLoader, packName: String): Array<Class<*>> {
        // 1. 常规 classpath 环境（Spigot）：走 SDK 原生扫描，行为不变
        try {
            val result = delegate.scan(klass, loader, packName)
            if (result.isNotEmpty()) return result
        } catch (_: Throwable) {
            // 原生扫描在模块化环境抛 NPE（classNames 为 null），走兜底
        }
        // 2. 模块化环境兜底（NeoForge/JPMS）：直接枚举物理 jar 条目
        val scanned = scanFromPhysicalJars(klass, loader, packName)
        if (scanned.isNotEmpty()) {
            PluginFileLog.infoAndKeep(
                "[兼容] 包扫描兜底生效：$packName（jar 条目枚举 ${scanned.size} 个类）"
            )
        }
        return scanned
    }

    /** 定位包含目标包的物理 jar，按条目枚举类并装载。 */
    private fun scanFromPhysicalJars(klass: Class<*>, loader: ClassLoader, packName: String): Array<Class<*>> {
        val packPath = packName.replace('.', '/')
        val results = LinkedHashSet<Class<*>>()
        for (jar in locatePhysicalJars(klass, loader, packPath)) {
            try {
                JarFile(jar).use { jarFile ->
                    val entries = jarFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val name = entry.name
                        if (!name.endsWith(".class")) continue
                        if (!name.startsWith(packPath)) continue
                        // 包边界对齐：io/github/kloping/qqbot 不应匹配 …qqbotextra/
                        if (name.length > packPath.length && name[packPath.length] != '/') continue
                        if (name.startsWith("META-INF/")) continue
                        val binary = name.removeSuffix(".class")
                        if (binary.endsWith("module-info") || binary.endsWith("package-info")) continue
                        try {
                            results.add(loader.loadClass(binary.replace('/', '.')))
                        } catch (_: Throwable) {
                            // 单个类装载失败不影响整包扫描
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return results.toTypedArray()
    }

    /** 汇总多个来源候选 jar（去重），任一可解析即可用。 */
    private fun locatePhysicalJars(klass: Class<*>, loader: ClassLoader, packPath: String): List<File> {
        val candidates = mutableListOf<URL?>()
        candidates += runCatching { klass.protectionDomain?.codeSource?.location }.getOrNull()
        candidates += runCatching { ModuleCompatPackageScanner::class.java.protectionDomain?.codeSource?.location }.getOrNull()
        candidates += runCatching { loader.getResource(packPath) }.getOrNull()
        candidates += runCatching { loader.getResource("META-INF/MANIFEST.MF") }.getOrNull()
        candidates += runCatching { ModuleCompatPackageScanner::class.java.getResource("/META-INF/MANIFEST.MF") }.getOrNull()
        return candidates.mapNotNull { urlToJarFile(it) }
            .distinctBy { it.absolutePath }
            .filter { it.isFile }
    }

    /** 把 file: / jar: / union: 三种协议的 URL 解析为物理 jar 文件。 */
    private fun urlToJarFile(url: URL?): File? {
        if (url == null) return null
        return try {
            when (url.protocol) {
                "file" -> {
                    val file = File(url.toURI())
                    if (file.isFile) file else null
                }
                "jar" -> {
                    // jar:file:/path/to.jar!/entry
                    var spec = url.file ?: return null
                    val cut = spec.indexOf('!')
                    if (cut >= 0) spec = spec.substring(0, cut)
                    File(URL(spec).toURI())
                }
                "union" -> {
                    // union:/path/to/mod.jar%23118!/entry（%23 是 URL 编码的 #）
                    var spec = url.toString()
                    val cut = spec.indexOf("!/")
                    if (cut >= 0) spec = spec.substring(0, cut)
                    spec = spec.removePrefix("union:")
                    val decoded = URLDecoder.decode(spec, Charsets.UTF_8)
                    val hash = decoded.indexOf('#')
                    File(if (hash >= 0) decoded.substring(0, hash) else decoded)
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
