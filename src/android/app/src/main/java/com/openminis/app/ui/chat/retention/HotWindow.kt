package com.openminis.app.ui.chat.retention

/** Resident window. Room and [com.openminis.app.data.ContextOffload] stay the cold store. */
internal object HotWindow {
    const val RESIDENT_BYTES = 8L * 1024 * 1024
    const val TOOL_SPILL_CHARS = 64 * 1024
}
