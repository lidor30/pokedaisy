package com.pokedaisy.app.n3ds

import java.io.File

/**
 * What Azahar's Android file code calls back into (its `common/android_utils.cpp`, which its own app provides from Java:
 * AndroidUtils::InitJNI takes this class). Every method it looks up must exist with its exact signature, or the lookup
 * fails under CheckJNI. PokeDaisy uses plain file access ([getBuildFlavor] isn't Azahar's Play build), with every path
 * Azahar uses relative to [root] (a path starting with "!" is a real one: the cart's). The storage-access ones
 * below are only there to be found; they act on that folder.
 */
@Suppress("unused") // called from native code
object N3dsAndroidUtils {
    @Volatile var root: String = ""

    private fun file(path: String) = File(root, path.removePrefix("/"))

    @JvmStatic fun getBuildFlavor(): String = "vanilla"
    @JvmStatic fun getUserDirectory(): String = root
    @JvmStatic fun isPortraitMode(): Boolean = false
    @JvmStatic fun isUsingAngleForOpenGL(): Boolean = false

    @JvmStatic fun createFile(directory: String, filename: String): Boolean = runCatching { File(file(directory), filename).createNewFile() || true }.getOrDefault(false)
    @JvmStatic fun createDir(directory: String, filename: String): Boolean = File(file(directory), filename).let { it.isDirectory || it.mkdirs() }
    @JvmStatic fun openContentUri(path: String, openMode: String): Int = -1
    @JvmStatic fun getFilesName(path: String): Array<String> = file(path).list() ?: emptyArray()
    @JvmStatic fun copyFile(source: String, destinationPath: String, destinationFilename: String): Boolean =
        runCatching { file(source).copyTo(File(file(destinationPath), destinationFilename), overwrite = true); true }.getOrDefault(false)
    @JvmStatic fun renameFile(source: String, filename: String): Boolean = file(source).let { it.renameTo(File(it.parentFile, filename)) }
    @JvmStatic fun updateDocumentLocation(sourcePath: String, destinationPath: String): Boolean = true
    @JvmStatic fun moveFile(filename: String, sourceDirPath: String, destinationDirPath: String): Boolean =
        File(file(sourceDirPath), filename).renameTo(File(file(destinationDirPath), filename))
    @JvmStatic fun isDirectory(path: String): Boolean = file(path).isDirectory
    @JvmStatic fun fileExists(path: String): Boolean = file(path).exists()
    @JvmStatic fun getSize(path: String): Long = file(path).length()
    @JvmStatic fun deleteDocument(path: String): Boolean = file(path).deleteRecursively()
}
