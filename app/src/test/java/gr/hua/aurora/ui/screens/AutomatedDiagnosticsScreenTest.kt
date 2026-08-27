package gr.hua.aurora.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomatedDiagnosticsScreenTest {
    @Test
    fun reportCardsKeepCopyActionsWithoutRenderingReportBodyText() {
        val source = automatedDiagnosticsScreenSource()

        assertTrue(source.contains("Text(\"Copy report\")"))
        assertTrue(source.contains("Text(\"Copy full report\")"))
        assertTrue(source.contains("clipboardManager.setText(AnnotatedString(phaseSection.reportText))"))
        assertFalse(source.contains("SelectionContainer"))
        assertFalse(source.contains("text = reportText"))
    }

    private fun automatedDiagnosticsScreenSource(): String {
        val candidates = listOf(
            File("app/src/main/java/gr/hua/aurora/ui/screens/AutomatedDiagnosticsScreen.kt"),
            File("src/main/java/gr/hua/aurora/ui/screens/AutomatedDiagnosticsScreen.kt")
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error(
                "AutomatedDiagnosticsScreen.kt source file not found for source-level diagnostics assertions."
            )
    }
}
