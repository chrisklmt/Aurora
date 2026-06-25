package gr.hua.aurora.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugInfoSectionCardTest {
    @Test
    fun planDebugInfoRowsPairsCompactItemsIntoTwoColumns() {
        val items = listOf(
            DebugInfoItem("Reachable", "1"),
            DebugInfoItem("Active", "peer-123"),
            DebugInfoItem("Pending", "0")
        )

        assertEquals(
            listOf(
                listOf(
                    DebugInfoItem("Reachable", "1"),
                    DebugInfoItem("Active", "peer-123")
                ),
                listOf(
                    DebugInfoItem("Pending", "0")
                )
            ),
            planDebugInfoRows(items)
        )
    }

    @Test
    fun planDebugInfoRowsKeepsFullWidthItemsOnTheirOwnRows() {
        val items = listOf(
            DebugInfoItem("Mode", "Full mesh"),
            DebugInfoItem("Last exchange", "Identity sent. Run on both devices.", preferFullWidth = true),
            DebugInfoItem("Sessions", "1"),
            DebugInfoItem("Handler", "ready")
        )

        assertEquals(
            listOf(
                listOf(DebugInfoItem("Mode", "Full mesh")),
                listOf(
                    DebugInfoItem(
                        "Last exchange",
                        "Identity sent. Run on both devices.",
                        preferFullWidth = true
                    )
                ),
                listOf(
                    DebugInfoItem("Sessions", "1"),
                    DebugInfoItem("Handler", "ready")
                )
            ),
            planDebugInfoRows(items)
        )
    }
}
