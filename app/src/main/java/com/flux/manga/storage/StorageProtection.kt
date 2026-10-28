package com.flux.manga.storage

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

// Keep these three values compatible with the original M0 instrumentation cases.
enum class SecretKeyFailure { MISSING_ENVELOPE, MISSING_KEYSTORE_KEY, INVALID_ENVELOPE }
class SecretKeyException(val reason: SecretKeyFailure) : GeneralSecurityException(reason.name)

internal enum class StorageProblem(val title: String, val guidance: String) {
    MISSING_ENVELOPE("密钥文件缺失", "请保留数据库与密钥文件，无法自动补回密钥。"),
    MISSING_MASTER("设备密钥缺失", "原数据库保留，但缺少设备密钥，暂时无法解密。"),
    INVALID_ENVELOPE("密钥文件校验失败", "密钥文件损坏或与当前安装不匹配，请保留原文件。"),
    KEY_INVALIDATED("设备密钥已失效", "无法解密原数据，请勿卸载应用或清除数据。"),
    KEY_UNAVAILABLE("设备密钥暂不可用", "请解锁手机后重试；仍失败时请保留现有安装和数据。"),
    DATABASE_INCOMPLETE("数据库文件不完整", "数据库文件缺失或为空，已停止打开。请保留现有文件。"),
    DATABASE_REJECTED("数据库无法打开", "数据库校验或版本不兼容，请保留数据，更新应用后重试。"),
    DATABASE_INTEGRITY("数据库结构检查未通过", "副本结构异常，原数据库保留。请勿卸载或清除数据。"),
    DATABASE_RELATIONS("数据库关联检查未通过", "副本存在不完整记录，原数据库保留。"),
    DATABASE_VERSION("数据库版本不兼容", "请使用兼容版本，原数据库保留。"),
    DIAGNOSTIC_SPACE("检查副本所需空间不足", "请释放设备空间后重试。"),
    PAYLOAD_INVALID("加密内容校验失败", "内容校验失败，请重新进入页面。原记录保留。"),
    IO("存储暂不可用", "请检查剩余空间后重试。"),
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
