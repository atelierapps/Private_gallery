package com.atelierapps.vault.media

/**
 * One item that didn't make it, and why.
 *
 * An export that reports "3 failed" and nothing else is worse than useless:
 * you can't tell whether three thumbnails or three irreplaceable videos are
 * missing from the backup you are about to rely on, and you have no way to find
 * out. The name and the reason are the whole point.
 */
data class TransferFailure(val name: String, val reason: String) {

    companion object {
        /**
         * A reason a person can act on. Exception messages here are mostly
         * either absent or internal, so the classes that actually happen get
         * named; anything else falls back to the class name, which at least
         * says what kind of thing went wrong.
         */
        fun describe(error: Throwable?): String {
            val name = error?.javaClass?.simpleName.orEmpty()
            val message = error?.message?.takeIf { it.isNotBlank() }
            return when {
                name.contains("UserNotAuthenticated") ->
                    "vault locked mid-run — unlock and run it again"
                name.contains("KeyPermanentlyInvalidated") ->
                    "the vault key is no longer usable on this device"
                error is java.io.FileNotFoundException -> "the destination refused the file"
                error is java.io.IOException ->
                    "storage error" + (message?.let { ": " + it } ?: "")
                message != null -> message
                name.isNotEmpty() -> name
                else -> "unknown error"
            }
        }
    }
}
