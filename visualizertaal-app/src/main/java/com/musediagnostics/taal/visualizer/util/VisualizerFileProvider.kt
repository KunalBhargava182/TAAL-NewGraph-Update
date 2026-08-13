package com.musediagnostics.taal.visualizer.util

import androidx.core.content.FileProvider

/**
 * A distinct FileProvider subclass (not androidx.core.content.FileProvider directly).
 * taal-ui-kit already declares its own FileProvider with android:name
 * "androidx.core.content.FileProvider"; the manifest merger treats <provider> nodes with
 * that same android:name as one logical node across modules, which collided with our
 * authorities/paths. Subclassing gives this module's provider a distinct android:name
 * so both providers coexist independently.
 */
class VisualizerFileProvider : FileProvider()
