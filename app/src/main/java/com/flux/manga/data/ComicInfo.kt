package com.flux.manga.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException

data class ComicInfo(val title: String? = null, val author: String? = null,
    val volume: String? = null, val summary: String? = null) {
    val hasValues get() = listOf(title, author, volume, summary).any { !it.isNullOrBlank() }
}
data class ComicInfoResult(val info: ComicInfo? = null, val message: String)
data class CbzImportPreview(val fileName: String, val metadata: ComicInfoResult, val existing: Boolean)

/** A small adapter over the platform XML parser, never an XML/ZIP implementation of our own. */
internal object ComicInfoReader {
    private const val MAX_XML = 256 * 1024
    fun read(file: File, checkpoint: () -> Unit): ComicInfoResult = try {
        checkpoint()
        ZipFile(file).use { zip ->
            if (zip.size() > 4096) throw IOException("归档条目过多")
            val candidates = zip.entries().asSequence().filter {
                checkpoint()
                !it.isDirectory && !it.name.startsWith("__MACOSX/", true) &&
                    it.name.substringAfterLast('/').equals("ComicInfo.xml", true)
            }.toList()
            val root = candidates.filter { '/' !in it.name }
            val choices = root.ifEmpty { candidates }
            if (choices.isEmpty()) return@use ComicInfoResult(message = "未找到 ComicInfo.xml，可按文件名导入")
            if (choices.size != 1) return@use ComicInfoResult(message = "找到多个 ComicInfo.xml，未自动选择，可不采用元数据")
            val entry = choices.single()
            if (entry.size !in 0L..MAX_XML.toLong()) throw IOException("ComicInfo.xml 超过 256 KiB 或大小未知")
            val bytes = ByteArrayOutputStream()
            val crc = CRC32()
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_XML) throw IOException("ComicInfo.xml 超过 256 KiB")
                    bytes.write(buffer, 0, count); crc.update(buffer, 0, count)
                }
            }
            if (bytes.size().toLong() != entry.size || crc.value != entry.crc) throw IOException("ComicInfo.xml 校验失败")
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(bytes.toByteArray().inputStream(), null)
            val values = mutableMapOf<String, String>()
            val limits = mapOf("Title" to 200, "Series" to 200, "Writer" to 1000,
                "Penciller" to 1000, "Volume" to 64, "Summary" to 10000)
            var rootSeen = false
            var field: String? = null
            val value = StringBuilder()
            while (true) {
                checkpoint()
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL -> throw IOException("不支持包含 DTD 的 ComicInfo.xml")
                    XmlPullParser.START_TAG -> {
                        if (parser.depth > 32) throw IOException("ComicInfo.xml 层级过深")
                        if (parser.depth == 1) {
                            if (rootSeen || parser.name != "ComicInfo") throw IOException("不是 ComicInfo 文档")
                            rootSeen = true
                        } else if (parser.depth == 2 && parser.name in limits) {
                            field = parser.name
                            if (parser.name in values) throw IOException("ComicInfo.xml 存在重复字段")
                            value.setLength(0)
                        } else if (field != null) throw IOException("元数据字段包含不支持的嵌套标记")
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF, XmlPullParser.IGNORABLE_WHITESPACE -> {
                        if (parser.eventType == XmlPullParser.ENTITY_REF && parser.text == null) throw IOException("元数据包含未解析的实体")
                        field?.let { name ->
                            value.append(parser.text.orEmpty())
                            if (value.length > limits.getValue(name)) throw IOException("$name 字段过长，未采用元数据")
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.depth == 2 && field != null) {
                        values[requireNotNull(field)] = value.toString().trim(); field = null
                    }
                }
            }
            if (!rootSeen) throw IOException("ComicInfo.xml 为空")
            fun text(name: String) = values[name]?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
            val info = ComicInfo(text("Title") ?: text("Series"), text("Writer") ?: text("Penciller"),
                text("Volume")?.takeUnless { it == "-1" }, values["Summary"]?.takeIf { it.isNotBlank() })
            ComicInfoResult(info.takeIf { it.hasValues }, if (info.hasValues) "已识别 ComicInfo.xml" else "ComicInfo.xml 中没有可补充的字段")
        }
    } catch (c: CancellationException) { throw c }
    catch (_: Exception) { ComicInfoResult(message = "ComicInfo.xml 无法识别（格式、校验或长度不符合要求），可不采用元数据继续导入") }
}
