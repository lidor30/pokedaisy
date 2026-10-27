package android.content

import java.io.File
import java.io.InputStream

/** Reads from the process's working directory - the render task points it at app/src/main/assets. */
class AssetManager(private val root: File) {
    fun open(name: String): InputStream = (File(root, name).takeIf { it.isFile }
        // Generated at build time (licenses.txt): app/build/generated/licenses, next to the assets' src dir.
        ?: File(root, "../../../build/generated/licenses/$name")).inputStream()
}

/** Context.applicationInfo's one field the app reads (RomIntake: the private data directory). */
class ContextAppInfo(val dataDir: String? = null)

open class Context {
    val applicationContext: Context get() = this
    val packageName = "com.pokedaisy.app"
    val applicationInfo = ContextAppInfo()
    val resources = android.content.res.Resources()
    val assets = AssetManager(File(System.getProperty("user.dir")))
    val filesDir = File(System.getProperty("scratch") ?: System.getProperty("java.io.tmpdir"), "files").apply { mkdirs() }
    val cacheDir = File(System.getProperty("scratch") ?: System.getProperty("java.io.tmpdir"), "cache").apply { mkdirs() }
    fun getExternalFilesDir(t: String?): File? = filesDir
    fun getSharedPreferences(n: String, mode: Int): SharedPreferences = SharedPreferences.shared
    open fun getSystemService(n: String): Any? = null
    fun checkSelfPermission(p: String) = 0

    companion object {
        const val MODE_PRIVATE = 0
        const val CLIPBOARD_SERVICE = "clipboard"
        const val CONNECTIVITY_SERVICE = "connectivity"
        const val WIFI_SERVICE = "wifi"
    }
}

/** In-memory, process-wide. */
class SharedPreferences {
    val m = HashMap<String, Any?>()
    fun getString(k: String, d: String?) = (m[k] as String?) ?: d
    fun getInt(k: String, d: Int) = (m[k] as Int?) ?: d
    fun getFloat(k: String, d: Float) = (m[k] as Float?) ?: d
    fun getBoolean(k: String, d: Boolean) = (m[k] as Boolean?) ?: d
    fun getLong(k: String, d: Long) = (m[k] as Long?) ?: d
    fun contains(k: String) = k in m
    fun edit() = Editor(this)

    class Editor(val p: SharedPreferences) {
        fun putString(k: String, v: String?) = apply { p.m[k] = v }
        fun putInt(k: String, v: Int) = apply { p.m[k] = v }
        fun putFloat(k: String, v: Float) = apply { p.m[k] = v }
        fun putBoolean(k: String, v: Boolean) = apply { p.m[k] = v }
        fun putLong(k: String, v: Long) = apply { p.m[k] = v }
        fun remove(k: String) = apply { p.m.remove(k) }
        fun apply() {}
        fun commit() = true
    }

    companion object { val shared = SharedPreferences() }
}
