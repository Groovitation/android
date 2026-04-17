package io.blaha.groovitation

import android.net.Uri
import android.os.Looper
import android.view.View
import android.webkit.ValueCallback
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainActivityImageChooserTest {

    @Test
    fun chooserActionKeepsPendingCallbackUntilPickerReturns() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val callback = RecordingFileChooserCallback()

        assertTrue(activity.launchImageChooser(callback, null))
        assertTrue(activity.hasPendingFileChooserCallbackForTest())

        val dialog = activity.activeImageIntakeDialogForTest()
        assertNotNull("Expected image intake sheet to be visible after launching chooser", dialog)

        val choosePhotos = dialog!!.findViewById<View>(R.id.image_intake_choose_photos)
        assertNotNull("Expected choose-photos action in the intake sheet", choosePhotos)

        choosePhotos!!.performClick()
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "Tapping a chooser action must not clear the pending callback before the picker result returns",
            activity.hasPendingFileChooserCallbackForTest()
        )
        assertEquals(
            "The file chooser callback should not receive a null cancellation when an action was selected",
            0,
            callback.invocationCount
        )
    }

    private class RecordingFileChooserCallback : ValueCallback<Array<Uri>> {
        var invocationCount: Int = 0
            private set

        override fun onReceiveValue(value: Array<Uri>?) {
            invocationCount += 1
        }
    }
}
