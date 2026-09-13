package com.nextnotif.app

/** Android can reject service startup after an otherwise valid Answer tap. */
internal object CallRequestDispatch {
    fun attempt(start: () -> Unit, rejected: () -> Unit) {
        try {
            start()
        } catch (_: SecurityException) {
            rejected()
        } catch (_: IllegalStateException) {
            rejected()
        }
    }
}
