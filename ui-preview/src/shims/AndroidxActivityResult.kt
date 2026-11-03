package androidx.activity.result

open class ActivityResultLauncher<I> {
    fun launch(i: I) {}
    fun launch(i: I, options: androidx.core.app.ActivityOptionsCompat?) {}
}

/** StartActivityForResult's answer. */
class ActivityResult(val resultCode: Int, val data: android.content.Intent?)
