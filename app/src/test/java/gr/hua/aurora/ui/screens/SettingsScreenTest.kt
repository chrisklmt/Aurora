package gr.hua.aurora.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScreenTest {
    @Test
    fun appVersionLabelFormatsCurrentVersionNameWithExpectedPrefix() {
        assertEquals("v. 2.25", appVersionLabel("2.25"))
    }

    @Test
    fun appVersionLabelUsesDynamicVersionNameInsteadOfHardcodedValue() {
        assertEquals("v. 7.3.1-beta", appVersionLabel(" 7.3.1-beta "))
    }

    @Test
    fun settingsScreenSourceUsesHeaderVersionLabelWithoutBottomSectionOrTapAction() {
        val source = settingsScreenSource()

        assertTrue(source.contains("username = appVersionDisplayLabel"))
        assertFalse(source.contains("\"2.25\""))
        assertFalse(source.contains("text = \"App version\""))
        assertFalse(source.contains("onUsernameTripleTap ="))
    }

    private fun settingsScreenSource(): String {
        val candidates = listOf(
            File("app/src/main/java/gr/hua/aurora/ui/screens/SettingsScreen.kt"),
            File("src/main/java/gr/hua/aurora/ui/screens/SettingsScreen.kt")
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("SettingsScreen.kt source file not found for source-level Settings assertions.")
    }
}
