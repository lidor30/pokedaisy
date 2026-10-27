package android.content

import android.net.Uri

open class Intent() {
    constructor(ctx: Context, cls: Class<*>) : this()
    constructor(action: String, uri: Uri? = null) : this() {
        this.action = action
        this.data = uri
    }
    constructor(o: Intent) : this() {
        action = o.action
        data = o.data
    }
    fun setClass(ctx: Context, cls: Class<*>) = this
    fun setClassName(pkg: String, cls: String) = this
    fun setFlags(f: Int) = this
    fun addFlags(f: Int) = this
    fun setDataAndType(u: android.net.Uri, type: String) = apply { data = u }

    var action: String? = null
    var data: Uri? = null
    private val extras = HashMap<String, String>()
    fun putExtra(k: String, v: String) = apply { extras[k] = v }
    fun putExtra(k: String, v: Boolean) = apply { extras[k] = v.toString() }
    fun getStringExtra(k: String): String? = extras[k]
    fun setType(t: String) = this

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        fun createChooser(i: Intent, title: CharSequence?) = i
        const val FLAG_GRANT_READ_URI_PERMISSION = 1
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 2
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
    }
}

class ClipData { companion object { fun newPlainText(l: String, t: String) = ClipData() } }

class ClipboardManager { fun setPrimaryClip(c: ClipData) {} }

class ContentResolver {
    fun openInputStream(u: Uri): java.io.InputStream? = null
    fun query(u: Uri, a: Array<String>?, b: String?, c: Array<String>?, d: String?): android.database.Cursor? = null
    fun takePersistableUriPermission(u: Uri, f: Int) {}
}
