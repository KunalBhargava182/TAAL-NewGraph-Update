package com.musediagnostics.taal.app.ui.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.musediagnostics.taal.app.R

class PlayerSaveDiscardDialog(
    private val onResult: (Action) -> Unit
) : DialogFragment() {

    enum class Action { SAVE, DISCARD }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_player_save_discard, null)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<Button>(R.id.saveButton).setOnClickListener {
            dismiss()
            onResult(Action.SAVE)
        }

        view.findViewById<Button>(R.id.discardButton).setOnClickListener {
            dismiss()
            onResult(Action.DISCARD)
        }

        return dialog
    }
}
