package io.blaha.groovitation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.net.URI

object JitsiMeetIntentFactory {
    private const val TAG = "JitsiMeetIntentFactory"
    internal const val JITSI_PACKAGE = "org.jitsi.meet"
    internal const val PLAY_STORE_WEB_URL = "https://play.google.com/store/apps/details?id=$JITSI_PACKAGE"

    internal fun isJwtBearingRoomUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return false
        val roomPath = uri.path?.trim('/').orEmpty()
        val query = uri.rawQuery.orEmpty()
        val hasJwt = query.split('&').any { part ->
            val key = part.substringBefore('=', missingDelimiterValue = part)
            key == "jwt" && part.substringAfter('=', missingDelimiterValue = "").isNotBlank()
        }
        return roomPath.isNotBlank() && hasJwt
    }

    internal fun buildRoomIntent(roomUrl: String): Intent? {
        if (!isJwtBearingRoomUrl(roomUrl)) return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(roomUrl.trim())).apply {
            setPackage(JITSI_PACKAGE)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    internal fun buildStoreIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$JITSI_PACKAGE")).apply {
            setPackage("com.android.vending")
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

    internal fun buildStoreWebIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_WEB_URL)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

    fun open(context: Context, roomUrl: String): JitsiLaunchResult {
        val roomIntent = buildRoomIntent(roomUrl) ?: return JitsiLaunchResult.Error
        return try {
            context.startActivity(roomIntent)
            JitsiLaunchResult.Opened
        } catch (missingApp: ActivityNotFoundException) {
            openStore(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Jitsi Meet", e)
            JitsiLaunchResult.Error
        }
    }

    private fun openStore(context: Context): JitsiLaunchResult {
        return try {
            context.startActivity(buildStoreIntent())
            JitsiLaunchResult.Store
        } catch (missingPlayStore: ActivityNotFoundException) {
            try {
                context.startActivity(buildStoreWebIntent())
                JitsiLaunchResult.Store
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open Jitsi Meet store listing", e)
                JitsiLaunchResult.Error
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Jitsi Meet store listing", e)
            JitsiLaunchResult.Error
        }
    }

    enum class JitsiLaunchResult(val webValue: String) {
        Opened("opened"),
        Store("store"),
        Error("error")
    }
}
