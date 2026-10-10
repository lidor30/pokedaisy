package androidx.activity.result.contract

import android.net.Uri

abstract class ActivityResultContract<I, O>

object ActivityResultContracts {
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>()
    class OpenMultipleDocuments : ActivityResultContract<Array<String>, List<Uri>>()
    class GetContent : ActivityResultContract<String, Uri?>()
    open class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>() {
        open fun createIntent(context: android.content.Context, input: Uri?): android.content.Intent = android.content.Intent()
    }
}
