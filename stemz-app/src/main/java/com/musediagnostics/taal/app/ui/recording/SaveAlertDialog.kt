package com.musediagnostics.taal.app.ui.recording

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.musediagnostics.taal.app.R

class SaveAlertDialog(
    private val onResult: (SaveType) -> Unit
) : DialogFragment() {

    enum class SaveType {
        SAVE, EMERGENCY, DISCARD
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_save_alert, null)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(true)
            .create()

        view.findViewById<Button>(R.id.saveButton).setOnClickListener {
            dismiss()
            onResult(SaveType.SAVE)
        }

        view.findViewById<Button>(R.id.discardButton).setOnClickListener {
            dismiss()
            onResult(SaveType.DISCARD)
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        return dialog
    }
}
