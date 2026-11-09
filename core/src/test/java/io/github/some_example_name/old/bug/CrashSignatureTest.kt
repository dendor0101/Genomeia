package io.github.some_example_name.old.bug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CrashSignatureTest {
    @Test fun signatureComparesTheRootFailureAndIgnoresLineNumbers() {
        fun failure(method: String, line: Int) = IllegalStateException().apply {
            stackTrace = arrayOf(StackTraceElement("World", method, "World.kt", line))
        }
        val expected = BugReports.crashSignature(failure("save", 12))
        assertEquals(expected, BugReports.crashSignature(RuntimeException(failure("save", 30))))
        assertNotEquals(expected, BugReports.crashSignature(failure("load", 12)))
    }
}
