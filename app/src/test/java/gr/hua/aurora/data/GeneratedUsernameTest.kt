package gr.hua.aurora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class GeneratedUsernameTest {
    @Test
    fun generatedUsernameUsesExpectedLengthAndAlphabet() {
        val username = GeneratedUsername.create(Random(1234L))

        assertEquals(8, username.length)
        assertTrue(username.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789" })
    }

    @Test
    fun generatedUsernameIsDeterministicForProvidedRandom() {
        val first = GeneratedUsername.create(Random(99L))
        val second = GeneratedUsername.create(Random(99L))

        assertEquals(first, second)
    }

    @Test
    fun matchesFormatAcceptsGeneratedPatternOnly() {
        assertTrue(GeneratedUsername.matchesFormat("PIAIUFN1"))
        assertTrue(!GeneratedUsername.matchesFormat("Aurora-1234"))
        assertTrue(!GeneratedUsername.matchesFormat("john1234"))
        assertTrue(!GeneratedUsername.matchesFormat("ABC123"))
    }
}
