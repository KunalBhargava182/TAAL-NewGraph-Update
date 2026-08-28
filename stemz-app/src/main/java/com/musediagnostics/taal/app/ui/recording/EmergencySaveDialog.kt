package com.musediagnostics.taal.app.ui.recording

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.musediagnostics.taal.app.R

class EmergencySaveDialog(
    private val onResult: (String?) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_save_emergency, null)
        val input = view.findViewById<EditText>(R.id.fileNameInput)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(true)
            .create()

        view.findViewById<Button>(R.id.saveButton).setOnClickListener {
            val fileName = input.text.toString().trim()
            dismiss()
            onResult(fileName.ifEmpty { null })
        }

        view.findViewById<Button>(R.id.discardButton).setOnClickListener {
            dismiss()
            onResult(null)
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }
}
