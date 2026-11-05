package com.geostep.app.transfer

import android.content.Context
import android.net.Uri
import com.geostep.core.model.RoutePoint
import com.geostep.core.route.Gpx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Activity Result（`OpenDocument` / `CreateDocument`）拿到的 Uri 读写都收在这里。
 *
 * 之前这段逻辑长在 ViewModel 里，好处是少一个类，坏处是 ViewModel 既要管模拟状态又要管字节流；
 * 现在单独一个对象，读文件的大小上限、编码、错误文案只有一处定义。
 */
internal object GpxTransfer {

    /** 8 MB 上限：GPX 一秒一个点的话足够跑 20 小时以上，再大基本是导错了文件。 */
    private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024

    suspend fun readText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_IMPORT_BYTES) { "文件超过 8 MB，请拆分后再导入" }
                output.write(buffer, 0, count)
            }
            output.toString("UTF-8")
        } ?: error("无法读取所选文件")
    }

    suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(text) }
            ?: error("无法写入所选文件")
    }

    fun routeGpx(name: String, points: List<RoutePoint>): String = Gpx.export(name, points)
}
