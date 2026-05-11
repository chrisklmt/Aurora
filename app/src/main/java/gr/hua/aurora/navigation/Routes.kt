package gr.hua.aurora.navigation

object Routes {
    const val GLOBAL = "global"
    const val PRIVATE = "private"
    const val PRIVATE_ARG = "peerId"
    const val PRIVATE_ROUTE = "$PRIVATE/{$PRIVATE_ARG}"
    const val NEARBY = "nearby"
    const val SETTINGS = "settings"

    fun privateChat(peerId: String): String = "$PRIVATE/$peerId"
}
