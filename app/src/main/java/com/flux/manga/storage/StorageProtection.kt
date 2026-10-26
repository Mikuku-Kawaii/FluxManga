package com.flux.manga.storage

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

// Keep these three values compatible with the original M0 instrumentation cases.
enum class SecretKeyFailure { MISSING_ENVELOPE, MISSING_KEYSTORE_KEY, INVALID_ENVELOPE }
class SecretKeyException(val reason: SecretKeyFailure) : GeneralSecurityException(reason.name)

internal enum class StorageProblem(val title: String, val guidance: String) {
    MISSING_ENVELOPE("密钥文件缺失", "保留原库与现有密钥文件；仅复制数据库不能恢复访问，当前不提供自动补钥。"),
    MISSING_MASTER("设备密钥缺失", "可能发生过应用数据恢复或密钥丢失；原库保留，当前无法重新生成可解密旧库的密钥。"),
    INVALID_ENVELOPE("密钥文件校验失败", "密钥文件可能不完整或与此安装不匹配；保留原文件，不以新密钥覆盖。"),
    KEY_INVALIDATED("设备密钥已失效", "当前无法解密原数据；保留原库，不卸载、不清数据，不自动重建。"),
    KEY_UNAVAILABLE("设备密钥暂不可用", "先解锁手机，稍后重试；持续失败时保留数据和现有安装。"),
    DATABASE_INCOMPLETE("数据库文件不完整", "检测到空库，或有密钥 / 数据库残留却缺少主文件；已停止打开，不自动创建空库替代。"),
    DATABASE_REJECTED("数据库无法打开", "可能是校验、版本或迁移问题；保留原库与当前安装，更新兼容版本后重试，不自动删除或重建。"),
    DATABASE_INTEGRITY("数据库结构检查未通过", "检查副本发现结构异常；原库未修复或改写，请保留现有安装和文件。"),
    DATABASE_RELATIONS("数据库关联检查未通过", "副本中存在不完整的记录关联；原库保持，不自动删除记录或修复。"),
    DATABASE_VERSION("数据库版本不兼容", "仅能在副本上检查应用已支持的迁移；请使用兼容版本，原库未迁移或重建。"),
    DIAGNOSTIC_SPACE("检查副本所需空间不足", "请先释放设备空间，再重新检查；本次没有修改原库或密钥。"),
    PAYLOAD_INVALID("加密内容校验失败", "未返回未校验内容；原记录保留，可重新进入页面核对。"),
    IO("存储暂不可用", "检查可用空间并稍后重试；原库与密钥不重置。"),
}
internal class StorageAccessException(val problem: StorageProblem, cause: Throwable? = null) :
    IOException(problem.title, cause)

/** Only fixed, actionable text reaches UI; raw paths / SQL / source credentials stay out. */
internal fun storageProblem(failure: Throwable): StorageProblem {
    var current: Throwable? = failure
    var fallback = StorageProblem.IO
    repeat(12) {
        val item = current ?: return fallback
        if (item is StorageAccessException) return item.problem
        if (item is SecretKeyException) return when(item.reason) {
            SecretKeyFailure.MISSING_ENVELOPE -> StorageProblem.MISSING_ENVELOPE
            SecretKeyFailure.MISSING_KEYSTORE_KEY -> StorageProblem.MISSING_MASTER
            SecretKeyFailure.INVALID_ENVELOPE -> StorageProblem.INVALID_ENVELOPE
        }
        when (item.javaClass.simpleName) {
            "KeyPermanentlyInvalidatedException" -> return StorageProblem.KEY_INVALIDATED
            "UserNotAuthenticatedException", "UnrecoverableKeyException", "KeyStoreException" -> fallback = StorageProblem.KEY_UNAVAILABLE
        }
        if (generateSequence<Class<*>>(item.javaClass) { it.superclass }.any { it.simpleName == "SQLiteException" }) {
            return StorageProblem.DATABASE_REJECTED
        }
        current = item.cause
    }
    return fallback
}
internal fun storageMessage(failure: Throwable): String = storageProblem(failure).let {
    "${it.title}；${it.guidance}"
}

internal object DatabaseArtifacts {
    fun sidecars(file: File) = listOf("-wal", "-shm", "-journal").map { File(file.path + it) }
    fun requireUsableMain(file: File, hasKeyArtifacts: Boolean = false) {
        if ((file.exists() && (!file.isFile || file.length() == 0L)) ||
            (!file.exists() && (hasKeyArtifacts || sidecars(file).any { it.exists() }))) {
            throw StorageAccessException(StorageProblem.DATABASE_INCOMPLETE)
        }
    }
}

internal enum class ProtectionState { NOT_CREATED, KEYS_READABLE, INCOMPLETE, BLOCKED }
internal data class ProtectionReport(val state: ProtectionState, val detail: String)
