package gr.hua.aurora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsResolutionTest {
    @Test
    fun singleSavedUsernameStaysCustomWhenItLooksUserChosen() {
        val resolved = resolveStoredProfileSettings(
            snapshot = StoredProfileSnapshot(
                generatedUsername = null,
                customUsername = null,
                legacyUsername = "JOHN2024",
                useCustomUsernameInGlobalChat = null
            ),
            createGeneratedUsername = { "GEN12345" }
        )

        assertEquals("GEN12345", resolved.settings.generatedUsername)
        assertEquals("JOHN2024", resolved.settings.customUsername)
        assertTrue(resolved.settings.useCustomUsernameInGlobalChat)
        assertEquals("GEN12345", resolved.generatedUsernameToPersist)
        assertEquals("JOHN2024", resolved.customUsernameToPersist)
        assertTrue(resolved.clearLegacyUsername)
    }

    @Test
    fun singleSavedUsernameStaysCustomWhenItMatchesGeneratedShape() {
        val resolved = resolveStoredProfileSettings(
            snapshot = StoredProfileSnapshot(
                generatedUsername = null,
                customUsername = null,
                legacyUsername = "PIAIUFN1",
                useCustomUsernameInGlobalChat = false
            ),
            createGeneratedUsername = { "GEN12345" }
        )

        assertEquals("GEN12345", resolved.settings.generatedUsername)
        assertEquals("PIAIUFN1", resolved.settings.customUsername)
        assertEquals(false, resolved.settings.useCustomUsernameInGlobalChat)
        assertEquals("GEN12345", resolved.generatedUsernameToPersist)
        assertEquals("PIAIUFN1", resolved.customUsernameToPersist)
        assertTrue(resolved.clearLegacyUsername)
    }

    @Test
    fun missingUsernamesKeepEmptyCustomAndDefaultToggleTrue() {
        val resolved = resolveStoredProfileSettings(
            snapshot = StoredProfileSnapshot(
                generatedUsername = null,
                customUsername = null,
                legacyUsername = null,
                useCustomUsernameInGlobalChat = null
            ),
            createGeneratedUsername = { "GEN12345" }
        )

        assertNull(resolved.settings.generatedUsername)
        assertNull(resolved.settings.customUsername)
        assertTrue(resolved.settings.useCustomUsernameInGlobalChat)
        assertNull(resolved.generatedUsernameToPersist)
        assertNull(resolved.customUsernameToPersist)
        assertEquals(false, resolved.clearLegacyUsername)
    }

    @Test
    fun existingSplitValuesArePreserved() {
        val resolved = resolveStoredProfileSettings(
            snapshot = StoredProfileSnapshot(
                generatedUsername = "GEN12345",
                customUsername = "Alice",
                legacyUsername = "OLDVALUE",
                useCustomUsernameInGlobalChat = null
            ),
            createGeneratedUsername = { "OTHER999" }
        )

        assertEquals("GEN12345", resolved.settings.generatedUsername)
        assertEquals("Alice", resolved.settings.customUsername)
        assertTrue(resolved.settings.useCustomUsernameInGlobalChat)
        assertNull(resolved.generatedUsernameToPersist)
        assertNull(resolved.customUsernameToPersist)
        assertEquals(false, resolved.clearLegacyUsername)
    }

    @Test
    fun absentToggleDefaultsToTrueForExistingSplitValues() {
        val resolved = resolveStoredProfileSettings(
            snapshot = StoredProfileSnapshot(
                generatedUsername = "GEN12345",
                customUsername = null,
                legacyUsername = null,
                useCustomUsernameInGlobalChat = null
            ),
            createGeneratedUsername = { "OTHER999" }
        )

        assertTrue(resolved.settings.useCustomUsernameInGlobalChat)
    }
}
