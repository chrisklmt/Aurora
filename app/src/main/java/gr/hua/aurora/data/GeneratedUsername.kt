package gr.hua.aurora.data

import java.security.SecureRandom
import java.util.Random

object GeneratedUsername {
    private const val usernameLength = 8
    private const val usernameAlphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val secureRandom = SecureRandom()

    // Το generated username μένει τοπικό και σταθεροποιείται μόνο αφού αποθηκευτεί στο υπάρχον profile shell.
    fun create(random: Random = secureRandom): String {
        return buildString(usernameLength) {
            repeat(usernameLength) {
                append(usernameAlphabet[random.nextInt(usernameAlphabet.length)])
            }
        }
    }
}
