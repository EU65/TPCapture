package local.appcapture.viewer.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

object Root {
    @Volatile
    var lastError: String? = null
        private set

    suspend fun run(command: String, input: ByteArray? = null, timeoutMs: Long = 20_000): String =
        withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            } catch (e: Exception) {
                val message = "无法启动 su：${e.message ?: e.javaClass.simpleName}"
                lastError = message
                throw IllegalStateException(message)
            }
            val killer = Thread {
                try {
                    Thread.sleep(timeoutMs)
                    process.destroy()
                } catch (_: InterruptedException) {
                }
            }
            killer.isDaemon = true
            killer.start()
            try {
                process.outputStream.use { if (input != null) it.write(input) }
                val out = ByteArrayOutputStream()
                process.inputStream.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = try {
                            stream.read(buffer)
                        } catch (e: Exception) {
                            -1
                        }
                        if (n < 0) break
                        if (out.size() + n > 2 * 1024 * 1024) {
                            process.destroy()
                            throw IllegalStateException("命令输出过大")
                        }
                        out.write(buffer, 0, n)
                    }
                }
                val code = process.waitFor()
                val text = out.toString("UTF-8")
                if (code != 0) {
                    val message = if (text.isBlank()) "命令失败，退出码 $code（可能未授权 root 或超时）" else text.trim()
                    lastError = message
                    throw IllegalStateException(message)
                }
                text
            } finally {
                killer.interrupt()
                process.destroy()
            }
        }
}

fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

fun scriptCommand(dataDir: String, installDir: String, action: String): String {
    val prefix = if (dataDir.isBlank()) "" else "APPCAPTURE_DATA_DIR=${quote(dataDir)} "
    return prefix + "sh " + quote("$installDir/appcapture.sh") + " " + action
}
