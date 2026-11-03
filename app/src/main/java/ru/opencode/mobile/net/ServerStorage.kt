package ru.opencode.mobile.net

/** Свободное место и размер кэша загруженных файлов на сервере. */
data class StorageInfo(
    val freeBytes: Long?,
    val uploadsBytes: Long?
)

object ServerStorageCommands {

    /** df/du в килобайтах: FREE=<доступно>, USED=<занято папкой загрузок>. */
    const val INFO =
        "d=\"\$HOME/opencode-uploads\"; mkdir -p \"\$d\"; " +
            "df -Pk \"\$d\" 2>/dev/null | tail -1 | awk '{print \"FREE=\" \$4}'; " +
            "du -sk \"\$d\" 2>/dev/null | awk '{print \"USED=\" \$1}'"

    const val CLEAR =
        "d=\"\$HOME/opencode-uploads\"; mkdir -p \"\$d\"; " +
            "find \"\$d\" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} + 2>/dev/null; " +
            "echo CLEARED"

    /** Только имена файлов; размер папки показывает INFO. */
    const val LIST =
        "d=\"\$HOME/opencode-uploads\"; " +
            "find \"\$d\" -mindepth 1 -maxdepth 1 -type f -printf '%f\\n' 2>/dev/null " +
            "|| ls -1p \"\$d\" 2>/dev/null | sed '/\\/$/d'"

    fun parse(output: String): StorageInfo {
        var free: Long? = null
        var used: Long? = null
        output.lineSequence().forEach { line ->
            val value = line.substringAfter('=', "").trim().toLongOrNull() ?: return@forEach
            when {
                line.startsWith("FREE=") -> free = value * 1024
                line.startsWith("USED=") -> used = value * 1024
            }
        }
        return StorageInfo(free, used)
    }
}
