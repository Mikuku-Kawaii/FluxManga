import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.Properties
import java.io.StringWriter

// Existing checked-in evidence only: no network, dependency resolution or Python runtime.
val licenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
tasks.register("generateLicenseAssets") {
    inputs.files(rootProject.file("LICENSE"), rootProject.file("LICENSING.md"),
        rootProject.file("docs/third-party/inventory.json"),
        rootProject.file("docs/third-party/source-adapters.json"))
    inputs.files(rootProject.fileTree("docs/third-party") {
        include("licenses/**", "maven-notices/**", "rust-licenses/**", "update-delta/*.txt", "portable-backup/*.txt")
    }, fileTree("src/main/assets/licenses"))
    // Komga is an external protocol reference, not bundled server code.
    inputs.file(rootProject.file("docs/verification/detail-metadata-local/komga-MIT.txt"))
    outputs.dir(licenseAssets)
    doLast {
        val output = licenseAssets.get().asFile.resolve("open_source")
        require(output.canonicalFile.toPath().startsWith(layout.buildDirectory.get().asFile.canonicalFile.toPath()))
        output.deleteRecursively()
        output.resolve("texts").mkdirs()
        val properties = Properties()
        properties.setProperty("format", "1")
        properties.setProperty("snapshot", "2026-10-21（适配记录 2026-10-22）")
        var count = 0
        fun entry(id: String, name: String, group: String, license: String, project: String, files: List<File>) {
            val prefix = "entry.${count++}"
            mapOf("id" to id, "name" to name, "group" to group, "license" to license, "project" to project)
                .forEach { (key, value) -> properties.setProperty("$prefix.$key", value) }
            val docs = files.distinctBy { it.canonicalPath }
            require(docs.isNotEmpty()) { "$id has no local license material" }
            properties.setProperty("$prefix.count", docs.size.toString())
            docs.forEachIndexed { index, source ->
                val bytes = source.readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val dest = output.resolve("texts/$hash.txt")
                if (!dest.exists()) dest.writeBytes(bytes) // Preserve original bytes; deduplicate identical texts.
                properties.setProperty("$prefix.doc.$index.path", "open_source/texts/$hash.txt")
                properties.setProperty("$prefix.doc.$index.name", source.relativeTo(rootDir).invariantSeparatorsPath)
                properties.setProperty("$prefix.doc.$index.sha256", hash)
            }
        }
        entry("fluxmanga", "FluxManga", "project", "GPL-3.0-only", "",
            listOf(rootProject.file("LICENSE"), rootProject.file("LICENSING.md")))
        @Suppress("UNCHECKED_CAST")
        val adapters = (JsonSlurper().parse(rootProject.file("docs/third-party/source-adapters.json")) as Map<String, Any>)["adapters"] as List<Map<String, Any>>
        adapters.forEach { adapter ->
            val texts = listOfNotNull(adapter["license"], adapter["notice"]).map {
                rootProject.file((it as Map<*, *>)["path"] as String)
            }
            val name = mapOf("komga-rest" to "Komga 官方协议引用", "copymanga" to "拷贝漫画协议适配",
                "baozi-anonymous" to "包子漫画匿名协议适配", "baozi-account" to "包子漫画账号协议适配",
                "jm-image-transform" to "JMComic 协议与图片切片适配",
                "picacg" to "PicACG 账号与阅读协议适配",
                "ehentai" to "E-Hentai 账号与画廊协议适配",
                "exhentai" to "ExHentai 独立权限与画廊协议适配",
                "ehtag-format" to "EH / EX 中文标签数据格式与许可").getValue(adapter["id"] as String)
            entry("source:${adapter["id"]}", name, "adaptation",
                adapter["license_declared"] as String,
                "${adapter["repository"]}/tree/${adapter["commit"]}", texts)
        }
        entry("liquid-material", "液态材质核心 · AndroidLiquidGlass", "adaptation", "Apache-2.0",
            "https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6",
            listOf(file("src/main/assets/licenses/liquid-material-Apache-2.0.txt"), file("src/main/assets/licenses/liquid-material-NOTICE.txt")))
        entry("sqlcipher-adapter", "SQLCipher 保留原库适配", "adaptation", "BSD-3-Clause",
            "https://github.com/sqlcipher/sqlcipher-android/tree/ffe6c588b06ab042d60264f1f0da79a7ac56db0f",
            listOf(file("src/main/assets/licenses/sqlcipher-adapter-BSD-3-Clause.txt"), file("src/main/assets/licenses/sqlcipher-adapter-NOTICE.txt")))
        val deltaLicenses = rootProject.file("docs/third-party/update-delta")
        entry("android:androidx.webkit:webkit:1.14.0", "AndroidX WebKit 1.14.0 · 独立浏览器会话", "android", "Apache-2.0",
            "https://developer.android.com/jetpack/androidx/releases/webkit#1.14.0",
            listOf(rootProject.file("docs/third-party/licenses/Apache-2.0.txt")))
        entry("android:net.lingala.zip4j:zip4j:2.11.5", "Zip4j 2.11.5 · 加密书库备份", "android", "Apache-2.0",
            "https://github.com/srikanth-lingala/zip4j/tree/8e2b74980b0f12a6fe6a4a8cdbfed626782a15c8",
            listOf(rootProject.file("docs/third-party/portable-backup/LICENSE.txt"), rootProject.file("docs/third-party/portable-backup/NOTICE.txt")))
        entry("bsdiff40", "BSDIFF40 文件流适配", "adaptation", "BSD-2-Clause",
            "https://github.com/freebsd/freebsd-src/tree/725a9f47324d42037db93c27ceb40d4956872f3e/usr.bin/bsdiff",
            listOf(deltaLicenses.resolve("bsdiff-LICENSE.txt"), deltaLicenses.resolve("NOTICE.txt")))
        // These new declared dependencies supplement the older resolved inventory snapshot.
        listOf(
            Triple("org.apache.commons:commons-compress:1.28.0", "commons-compress", "compress"),
            Triple("commons-io:commons-io:2.20.0", "commons-io", "io"),
            Triple("org.apache.commons:commons-lang3:3.18.0", "commons-lang", "lang"),
            Triple("commons-codec:commons-codec:1.19.0", "commons-codec", "codec")
        ).forEach { (coordinate, filename, project) ->
            entry("android:$coordinate", coordinate, "android", "Apache-2.0",
                "https://commons.apache.org/proper/commons-$project/",
                listOf(deltaLicenses.resolve("$filename-LICENSE.txt"), deltaLicenses.resolve("$filename-NOTICE.txt")))
        }
        @Suppress("UNCHECKED_CAST")
        val inventory = JsonSlurper().parse(rootProject.file("docs/third-party/inventory.json")) as Map<String, Any>
        val thirdParty = rootProject.file("docs/third-party")
        @Suppress("UNCHECKED_CAST")
        (inventory["androidRuntime"] as List<Map<String, Any>>).forEach { component ->
            val coordinate = component["coordinate"] as String
            @Suppress("UNCHECKED_CAST")
            val declared = (component["licenses"] as List<Map<String, String>>).joinToString(" / ") { it["name"].orEmpty() }
            val notices = (component["notices"] as List<*>).map { thirdParty.resolve(it as String) } +
                listOfNotNull((component["licenseText"] as? String)?.let(thirdParty::resolve))
            entry("android:$coordinate", coordinate, "android", declared, component["project"] as? String ?: "", notices)
        }
        @Suppress("UNCHECKED_CAST")
        (inventory["cargoLock"] as List<Map<String, Any>>).forEach { component ->
            val name = "${component["name"]}:${component["version"]}"
            entry("cargo:$name", name, "cargo", component["license"] as String, component["project"] as? String ?: "",
                (component["notices"] as List<*>).map { thirdParty.resolve(it as String) })
        }
        properties.setProperty("count", count.toString())
        val writer = StringWriter()
        properties.store(writer, null)
        // Properties.store adds a timestamp. Drop it and sort for reproducible output.
        output.resolve("catalog.properties").writeText(writer.toString().lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("#") }.sorted().joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        logger.lifecycle("License catalogue: $count entries, ${output.resolve("texts").listFiles()!!.size} unique original texts")
    }
}
