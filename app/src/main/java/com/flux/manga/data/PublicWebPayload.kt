package com.flux.manga.data

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/** Parses Nuxt's serialized literal/IIFE data, never evaluates page JavaScript. */
internal fun publicNuxt(document: Document): JSONObject {
    val script = document.select("script").firstOrNull { it.data().contains("window.__NUXT__=") || it.data().contains("window.__NUXT__ =") }
        ?.data() ?: error("快看页面数据缺失")
    val text = script.substringAfter("window.__NUXT__").trimStart().removePrefix("=").trim()
    val header = Regex("^\\(function\\(([^)]*)\\)\\s*\\{").find(text)
    if (header == null) return JSONObject(PublicLiteral(text).value() as? Map<*, *> ?: error("快看数据格式已变化"))
    val names = header.groupValues[1].split(',').map(String::trim)
    val body = PublicLiteral(text, header.range.last + 1)
    val assignments = mutableListOf<Triple<String, Any, Any?>>()
    while (!body.takeReturn()) {
        require(assignments.size < 100_000)
        assignments += body.assignment()
    }
    val value = body.value()
    body.take(';'); body.expect('}'); body.expect('(')
    val args = body.arguments(')')
    require(names.size == args.size) { "快看数据参数不匹配" }
    val bindings = names.zip(args).toMap().toMutableMap()
    for ((name, property, item) in assignments) {
        bindings[name] = when (property) {
            is Int -> {
                val array = (bindings[name] as? List<*>)?.toMutableList() ?: error("快看数组引用无法识别")
                require(property in array.indices)
                array.apply { this[property] = item }
            }
            is String -> {
                val obj = (bindings[name] as? Map<*, *>)?.entries
                    ?.associate { it.key.toString() to it.value }?.toMutableMap() ?: error("快看对象引用无法识别")
                obj.apply { this[property] = item }
            }
            else -> error("快看数据属性无法识别")
        }
    }
    fun resolve(item: Any?, depth: Int = 0): Any? {
        require(depth < 64) { "快看数据引用过深" }
        return when (item) {
            is PublicSymbol -> { require(bindings.containsKey(item.name)) { "快看数据引用无法识别" }; resolve(bindings[item.name], depth + 1) }
            is Map<*, *> -> item.entries.associate { it.key.toString() to resolve(it.value, depth + 1) }
            is List<*> -> item.map { resolve(it, depth + 1) }
            else -> item
        }
    }
    return JSONObject(resolve(value) as? Map<*, *> ?: error("快看数据格式已变化"))
}

private data class PublicSymbol(val name: String)

/** Only JSON-like literals, parameter references and the Array(n) sparse-array encoding. */
private class PublicLiteral(private val text: String, private var at: Int = 0) {
    private var depth = 0
    private fun space() { while (at < text.length && text[at].isWhitespace()) at++ }
    fun take(char: Char): Boolean { space(); return (text.getOrNull(at) == char).also { if (it) at++ } }
    fun expect(char: Char) { require(take(char)) { "网页数据格式已变化" } }
    fun takeReturn(): Boolean {
        space()
        if (!text.startsWith("return", at)) return false
        at += 6
        return true
    }
    fun assignment(): Triple<String, Any, Any?> {
        val name = identifier()
        val property: Any = if (take('.')) identifier() else {
            expect('[')
            val index = value() as? Long ?: error("网页数组下标无效")
            require(index in 0..10_000)
            expect(']')
            index.toInt()
        }
        expect('=')
        val item = value(); expect(';')
        return Triple(name, property, item)
    }
    fun arguments(end: Char): List<Any?> {
        val list = mutableListOf<Any?>()
        if (take(end)) return list
        do {
            require(list.size < 100_000)
            list += value()
            if (take(end)) return list
            expect(',')
        } while (!take(end))
        return list
    }
    fun value(): Any? {
        require(++depth <= 64)
        try {
            space()
            return when (text.getOrNull(at)) {
                '"', '\'' -> string()
                '{' -> {
                    at++
                    val obj = linkedMapOf<String, Any?>()
                    if (!take('}')) do {
                        space()
                        val name = if (text.getOrNull(at) in listOf('"', '\'')) string() else identifier()
                        expect(':'); obj[name] = value()
                        require(obj.size < 100_000)
                        if (take('}')) break
                        expect(',')
                    } while (!take('}'))
                    obj
                }
                '[' -> { at++; arguments(']') }
                '!' -> { at++; val n = value(); require(n == 0L || n == 1L); n == 0L }
                '-', in '0'..'9' -> {
                    val start = at++
                    while (text.getOrNull(at)?.let { it.isDigit() || it in ".eE+-" } == true) at++
                    val number = text.substring(start, at)
                    number.toLongOrNull() ?: number.toDoubleOrNull() ?: error("网页数字无效")
                }
                else -> when (val name = identifier()) {
                    "null", "undefined" -> JSONObject.NULL
                    "true" -> true
                    "false" -> false
                    "void" -> { require(value() == 0L); JSONObject.NULL }
                    "Array" -> {
                        expect('('); val size = value() as? Long ?: error("网页数组无效"); expect(')')
                        require(size in 0..10_000); List(size.toInt()) { JSONObject.NULL }
                    }
                    else -> PublicSymbol(name)
                }
            }
        } finally { depth-- }
    }
    private fun identifier(): String {
        space(); val start = at
        while (text.getOrNull(at)?.let { it.isLetterOrDigit() || it == '_' || it == '$' } == true) at++
        require(at > start) { "网页表达式不受支持" }
        return text.substring(start, at)
    }
    private fun string(): String {
        val quote = text[at++]
        val out = StringBuilder()
        while (at < text.length) {
            val char = text[at++]
            if (char == quote) return out.toString()
            if (char != '\\') out.append(char) else {
                require(at < text.length)
                when (val escape = text[at++]) {
                    'u', 'x' -> {
                        val length = if (escape == 'u') 4 else 2
                        require(at + length <= text.length)
                        out.append(text.substring(at, at + length).toInt(16).toChar()); at += length
                    }
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000c')
                    else -> out.append(escape)
                }
            }
        }
        error("网页字符串不完整")
    }
}

/** Packer/LZ decoding adapted from Keiyoushi Manhuagui + Unpacker (Apache-2.0).
 * Modified 2026-11-11: no JS execution, decode only; see public-web-NOTICE.txt. */
internal fun manhuaguiPayload(document: Document): JSONObject {
    val compressed = Regex("""['"]([0-9A-Za-z+/=]+)['"]\[['"].*?['"]]\(['"].*?['"]\)""")
    val script = document.select("script").map { it.data() }.firstOrNull { it.contains("}('") && compressed.containsMatchIn(it) }
        ?: error("漫画柜章节图片数据缺失或受限")
    val decoded = compressed.replace(script) { "'${PublicLzString.decode(it.groupValues[1])}'.split('|')" }
    val packed = decoded.substringAfter("}('", "").substringBefore(".split('|'),0,{}))")
    val end = Regex("""',\s*(\d+),\s*(\d+),\s*'""").find(packed) ?: error("漫画柜解包格式已变化")
    val radix = end.groupValues[1].toInt()
    require(radix in 2..62)
    val dictionary = packed.substring(end.range.last + 1).substringBeforeLast("'").split('|')
    val payload = packed.substring(0, end.range.first).replace("\\'", "-")
    val unpacked = Regex("[A-Za-z0-9_]+").replace(payload) { match ->
        var index = 0L
        for (char in match.value) {
            val n = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(char)
            if (n < 0 || n >= radix) return@replace match.value
            index = index * radix + n
            if (index >= dictionary.size) return@replace match.value
        }
        dictionary.getOrNull(index.toInt())?.takeIf(String::isNotEmpty) ?: match.value
    }
    val start = unpacked.indexOf('{'); val last = unpacked.lastIndexOf('}')
    require(start >= 0 && last > start)
    return JSONObject(unpacked.substring(start, last + 1))
}

internal fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }
