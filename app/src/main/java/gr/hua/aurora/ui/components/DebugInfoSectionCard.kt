package gr.hua.aurora.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class DebugInfoItem(
    val label: String,
    val value: String,
    val preferFullWidth: Boolean = false
)

data class DebugInfoSection(
    val title: String,
    val items: List<DebugInfoItem>
)

data class DebugInfoCardModel(
    val title: String,
    val sections: List<DebugInfoSection>
)

internal fun planDebugInfoRows(
    items: List<DebugInfoItem>
): List<List<DebugInfoItem>> {
    val rows = mutableListOf<List<DebugInfoItem>>()
    var index = 0

    while (index < items.size) {
        val currentItem = items[index]
        val nextItem = items.getOrNull(index + 1)

        if (
            !currentItem.preferFullWidth &&
            nextItem != null &&
            !nextItem.preferFullWidth
        ) {
            rows += listOf(currentItem, nextItem)
            index += 2
        } else {
            rows += listOf(currentItem)
            index += 1
        }
    }

    return rows
}

@Composable
fun DebugInfoCard(
    card: DebugInfoCardModel,
    modifier: Modifier = Modifier
) {
    val sections = card.sections.filter { it.items.isNotEmpty() }
    if (sections.isEmpty()) {
        return
    }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.labelLarge
            )
            sections.forEach { section ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (section.title.isNotBlank()) {
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    planDebugInfoRows(section.items).forEach { rowItems ->
                        if (rowItems.size == 1) {
                            DebugInfoRowText(
                                item = rowItems.single(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowItems.forEach { item ->
                                    DebugInfoRowText(
                                        item = item,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugInfoRowText(
    item: DebugInfoItem,
    modifier: Modifier = Modifier
) {
    Text(
        modifier = modifier,
        text = "${item.label}: ${item.value}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (item.preferFullWidth) 3 else 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun DebugInfoSectionCard(
    section: DebugInfoSection,
    modifier: Modifier = Modifier
) {
    DebugInfoCard(
        card = DebugInfoCardModel(
            title = section.title,
            sections = listOf(
                DebugInfoSection(
                    title = "",
                    items = section.items
                )
            )
        ),
        modifier = modifier
    )
}
