package io.blaha.groovitation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton

class UpdateDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_CURRENT_VERSION = "current_version"
        private const val ARG_LATEST_VERSION = "latest_version"
        private const val ARG_DOWNLOAD_URL = "download_url"

        fun newInstance(currentVersion: String, latestVersion: String, downloadUrl: String): UpdateDialogFragment {
            return UpdateDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_CURRENT_VERSION, currentVersion)
                    putString(ARG_LATEST_VERSION, latestVersion)
                    putString(ARG_DOWNLOAD_URL, downloadUrl)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_FRAME, android.R.style.Theme_Translucent_NoTitleBar)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_update_available, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentVersion = arguments?.getString(ARG_CURRENT_VERSION) ?: ""
        val latestVersion = arguments?.getString(ARG_LATEST_VERSION) ?: ""
        val downloadUrl = arguments?.getString(ARG_DOWNLOAD_URL) ?: ""

        view.findViewById<TextView>(R.id.update_message).text =
            "A new version of Groovitation is available.\n\nInstalled: v$currentVersion\nLatest: v$latestVersion"

        view.findViewById<MaterialButton>(R.id.btn_download).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.btn_dismiss).setOnClickListener {
            dismiss()
        }
    }
}
