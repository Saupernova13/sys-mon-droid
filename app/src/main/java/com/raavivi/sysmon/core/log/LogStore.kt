package com.raavivi.sysmon.core.log

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Where log files live. Kept tiny so rotation/pruning can be tested with a fake. */
interface LogStore {
    /** Names of this app's log files (`sysmon-*`). */
    fun list(): List<String>
    fun size(name: String): Long
    fun append(name: String, data: ByteArray)
    fun delete(name: String)
    fun open(name: String): InputStream?
}

/** Plain-directory store: API 26-28 (app external files dir) and tests. */
class DirLogStore(private val dir: File) : LogStore {
    init {
        dir.mkdirs()
    }

    override fun list(): List<String> =
        dir.listFiles()?.filter { it.isFile && LogRules.isLogName(it.name) }?.map { it.name }.orEmpty()

    override fun size(name: String): Long = File(dir, name).let { if (it.exists()) it.length() else 0L }

    override fun append(name: String, data: ByteArray) {
        dir.mkdirs()
        FileOutputStream(File(dir, name), true).use { it.write(data) }
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }

    override fun open(name: String): InputStream? =
        File(dir, name).takeIf { it.exists() }?.inputStream()
}
