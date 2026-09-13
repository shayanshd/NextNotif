package com.nextnotif.app

import org.junit.Assert.*
import org.junit.Test

class CallRequestDispatchTest {
    @Test fun successfulStartIsNotRejected() {
        var starts = 0
        var rejections = 0
        CallRequestDispatch.attempt({ starts++ }, { rejections++ })
        assertEquals(1, starts)
        assertEquals(0, rejections)
    }

    @Test fun permissionAndBackgroundStartRejectionsAreHandledOnce() {
        for (failure in listOf(SecurityException(), IllegalStateException())) {
            var rejections = 0
            CallRequestDispatch.attempt({ throw failure }, { rejections++ })
            assertEquals(1, rejections)
        }
    }

    @Test fun unrelatedBugsAreNotSilentlySwallowed() {
        val failure = IllegalArgumentException("unexpected")
        try {
            CallRequestDispatch.attempt({ throw failure }, { fail("must not reject unrelated bug") })
            fail("must propagate")
        } catch (caught: IllegalArgumentException) {
            assertSame(failure, caught)
        }
    }
}
