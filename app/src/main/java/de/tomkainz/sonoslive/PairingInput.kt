package de.tomkainz.sonoslive

/**
 * What the user typed into the pairing notification: the 6-digit code and,
 * if the app could not find the dialog itself, the port shown next to it.
 */
data class PairingInput(val code: String, val port: Int?) {

    companion object {
        private const val CODE_LENGTH = 6
        private val NUMBERS = Regex("\\d+")

        /**
         * Accepts "123456", "123 456", "123456 37123" and also the whole address
         * from the dialog, "192.168.1.5:37123 123456". Returns null if there is no
         * 6-digit code in it.
         */
        fun parse(text: String): PairingInput? {
            val numbers = NUMBERS.findAll(text).map { it.value }.toList()
            val code = numbers.firstOrNull { it.length == CODE_LENGTH }
            if (code == null) {
                // A code typed with spaces arrives in pieces.
                val joined = numbers.joinToString("")
                return if (joined.length == CODE_LENGTH) PairingInput(joined, null) else null
            }
            val port = numbers.lastOrNull { it !== code && it.length < CODE_LENGTH }
                ?.toIntOrNull()?.takeIf { it in 1..65535 }
            return PairingInput(code, port)
        }
    }
}
