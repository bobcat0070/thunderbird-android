package app.k9mail.feature.widget.message.list

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Hands the home screen the widget's sender pictures as files it may read, rather than as bitmaps inside the widget
 * update.
 *
 * On Android 17 the bitmaps of a list's rows travel in one shared list, and each row names its picture by its
 * position there. After a refresh that put a new message at the top, every row showed the picture of the row below
 * it, and a single refresh doubled the widget's picture memory - which has a limit past which refreshes are dropped.
 * A link to a file is plain text in the update, so neither happens.
 *
 * The files are read through [WidgetPictureFileProvider], which is not exported: only the home screen is granted
 * each picture.
 */
internal class WidgetPictureStore(private val context: Context) {
    private val directory = File(context.cacheDir, DIRECTORY)
    private val authority = "${context.packageName}$AUTHORITY_SUFFIX"
    private val homeScreenPackages: List<String> by lazy { findHomeScreenPackages() }

    /**
     * The link to [picture] for the widget row, readable by the home screen.
     */
    @WorkerThread
    fun getPictureUri(picture: Bitmap): Uri {
        val bytes = ByteArrayOutputStream().use { output ->
            picture.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
            output.toByteArray()
        }

        val file = storePicture(directory, bytes, now = System.currentTimeMillis())
        val uri = FileProvider.getUriForFile(context, authority, file)
        for (packageName in homeScreenPackages) {
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return uri
    }

    /**
     * Deletes the pictures no row has used for a while, so the directory holds little more than the current list's.
     */
    @WorkerThread
    fun removeUnusedPictures() {
        removePicturesOlderThan(directory, cutoff = System.currentTimeMillis() - MAX_UNUSED_MILLIS)
    }

    /**
     * The home screen the user has chosen, which is where the widget sits. Only when none is chosen yet - the system
     * then answers with its own chooser - every installed home screen, since the widget can only be on one of them.
     */
    private fun findHomeScreenPackages(): List<String> {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val packageManager = context.packageManager

        val defaultHomeScreen = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
        if (defaultHomeScreen != null && defaultHomeScreen != SYSTEM_CHOOSER_PACKAGE) {
            return listOf(defaultHomeScreen)
        }

        return packageManager.queryIntentActivities(homeIntent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
    }

    private companion object {
        const val DIRECTORY = "widget_pictures"
        const val AUTHORITY_SUFFIX = ".widget.pictures"
        const val PNG_QUALITY = 100
        const val SYSTEM_CHOOSER_PACKAGE = "android"
        const val MAX_UNUSED_MILLIS = 2L * 24 * 60 * 60 * 1000
    }
}
