# SQLCipher preserving adapter

PreservingDatabaseFactory.kt adapts the forwarding arrangement of SQLCipher Android SupportHelper.java at commit ffe6c588b06ab042d60264f1f0da79a7ac56db0f (BSD-3-Clause, copyright ZETETIC LLC). Original source and fixed hashes: docs/verification/m4-storage-foundation-local/upstream/. Full license: LICENSE in this directory.

Modifications: Kotlin implementation; explicit non-deleting corruption handler; minimumSupportedVersion = 0; Framework SQLite branch with a non-deleting callback and allowDataLossOnRecovery = false. Room remains responsible for existing schema migrations. No upstream engine source is added to the application; the existing SQLCipher binary dependency remains unchanged.
