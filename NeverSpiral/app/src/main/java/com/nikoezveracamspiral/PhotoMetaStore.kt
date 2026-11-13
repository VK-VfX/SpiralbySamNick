package com.nikoezveracamspiral

import android.content.Context

/**
 * A tiny record of each Ultra HDR photo's details, keyed by file name. Those files can't have
 * their EXIF header rewritten without breaking the pointer to their HDR gain map, so the Details
 * tab reads from here for them. Only the most recent few hundred are kept.
 */
object PhotoMetaStore {
    private const val FILE = "photo_meta"
    private const val SEP = '\u001F'
    private const val KEEP = 400

    fun save(context: Context, name: String, meta: PhotoMeta) {
        if (name.isEmpty()) return
        try {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val value = listOf(
                System.currentTimeMillis().toString(),
                meta.exposureNanos?.toString().orEmpty(),
                meta.iso?.toString().orEmpty(),
                meta.aperture?.toString().orEmpty(),
                meta.focalLengthMm?.toString().orEmpty(),
                meta.software,
                meta.description,
            ).joinToString(SEP.toString())
            val edit = prefs.edit().putString(name, value)
            val all = prefs.all
            if (all.size >= KEEP) {
                all.entries
                    .sortedBy { (it.value as? String)?.substringBefore(SEP)?.toLongOrNull() ?: 0L }
                    .take(all.size - KEEP + 20)
                    .forEach { edit.remove(it.key) }
            }
            edit.apply()
        } catch (e: Exception) {
            android.util.Log.w("PhotoMetaStore", "Couldn't save photo details", e)
        }
    }

    fun load(context: Context, name: String): PhotoMeta? {
        return try {
            val value = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(name, null) ?: return null
            val p = value.split(SEP)
            if (p.size < 7) return null
            PhotoMeta(
                exposureNanos = p[1].toLongOrNull(),
                iso = p[2].toIntOrNull(),
                aperture = p[3].toFloatOrNull(),
                focalLengthMm = p[4].toFloatOrNull(),
                software = p[5],
                description = p[6],
            )
        } catch (e: Exception) {
            null
        }
    }
}
