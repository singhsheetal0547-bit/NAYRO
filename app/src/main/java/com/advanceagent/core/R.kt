package com.advanceagent.core

/**
 * Resource bridge allowing com.advanceagent.core classes to reference R.drawable.ic_launcher_foreground
 * while coexisting inside the com.example application namespace.
 */
object R {
    object drawable {
        val ic_launcher_foreground: Int = com.example.R.drawable.ic_launcher_foreground
    }
}
