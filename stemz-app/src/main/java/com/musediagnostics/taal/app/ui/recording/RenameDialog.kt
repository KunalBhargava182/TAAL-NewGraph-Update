package com.musediagnostics.taal.app.ui.recording

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.musediagnostics.taal.app.R

class RenameDialog(
    private val currentName: String,
    private val onRenamed: (String) -> Unit
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_rename, null)
        val input = view.findViewById<EditText>(R.id.renameInput)
        input.setText(currentName)
        input.selectAll()

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(true)
            .create()

        view.findViewById<Button>(R.id.discardButton).setOnClickListener {
            dialog.dismiss()
        }

        view.findViewById<Button>(R.id.saveButton).setOnClickListener {
            val newName = input.text.toString().trim()
            if (newName.isNotEmpty()) {
                onRenamed(newName)
                dialog.dismiss()
            }
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }
}
