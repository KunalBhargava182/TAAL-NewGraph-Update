package com.musediagnostics.taal.stemz.uikit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment

/**
 * Shared base of [TaalRecorderActivity], [TaalPlayerActivity] and [TaalSavedRecordingsActivity].
 * Not meant to be used or subclassed directly — launch one of those three instead.
 *
 * All screens live in one navigation graph; each entry activity only chooses which screen opens
 * first and with which arguments. Once a recording has been saved, the activity's result becomes
 * RESULT_OK + [TaalRecorderActivity.RESULT_FILE_PATH] (delivered when the activity finishes).
 */
abstract class StemzHostActivity internal constructor() : AppCompatActivity() {

    /** Navigation destination id of the first screen. */
    internal abstract val startDestinationId: Int

    /** Arguments for the first screen, or null. */
    internal open fun startDestinationArgs(): Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.tsuk_activity_taal_stemz_host)

        if (savedInstanceState == null) {
            val navHost = NavHostFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.stemzNavHost, navHost)
                .setPrimaryNavigationFragment(navHost)
                .commitNow()
            val controller = navHost.navController
            val graph = controller.navInflater.inflate(R.navigation.tsuk_nav_taal_stemz)
            graph.setStartDestination(startDestinationId)
            controller.setGraph(graph, startDestinationArgs())
        }
    }

    /** Called by the save screen once both files are in filesDir/saved/. */
    internal fun onRecordingSaved(filteredWavPath: String) {
        setResult(RESULT_OK, Intent().putExtra(TaalRecorderActivity.RESULT_FILE_PATH, filteredWavPath))
    }
}
