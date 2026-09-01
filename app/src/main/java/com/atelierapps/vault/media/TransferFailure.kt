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
         * A reason a person can act on, in words rather than in Java.
         *
         * This used to fall through to the exception's own message or, failing
         * that, its class name — so a run that went wrong reported things like
         * "blob length 41235 != expected 41251" or "AEADBadTagException" next to
         * a filename. That tells the one person who wrote the code something and
         * everybody else nothing, least of all whether to try again.
         *
         * Every branch below is a failure that actually happens on these two
         * paths. The last one keeps the class name deliberately: an unforeseen
         * error is worth being able to report, and a name is better than
         * "unknown".
         */
        fun describe(error: Throwable?): String {
            val name = error?.javaClass?.simpleName.orEmpty()
            val message = error?.message.orEmpty()
            return when {
                name.contains("UserNotAuthenticated") ->
                    "the vault locked partway through — unlock and run it again"
                name.contains("KeyPermanentlyInvalidated") ->
                    "this phone's vault key no longer works, so this file can't be decrypted"
                // CipherInputStream reports a bad GCM tag as end-of-stream, so a
                // wrong key surfaces here as a short read rather than as a tag
                // error. Both mean the same thing to the person reading it.
                name.contains("AEADBadTag") || message.contains("truncated") ->
                    "this file doesn't match this backup's passphrase — most likely it is " +
                        "left over from an earlier backup written to the same folder"
                message.startsWith("blob length") ->
                    "the encrypted copy came out the wrong size, so it was thrown away " +
                        "rather than trusted"
                message.startsWith("spool missing") || message.startsWith("blob missing") ->
                    "the file wasn't there when it was needed — it may have been removed " +
                        "while the run was going"
                error is OutOfMemoryError ->
                    "too large for this phone to process in one piece"
                error is SecurityException ->
                    "permission for that folder has lapsed — choose the folder again"
                message.contains("ENOSPC") || message.contains("No space left") ->
                    "the destination is full"
                error is java.io.FileNotFoundException ->
                    "the folder wouldn't let a file be created there"
                error is NullPointerException ->
                    "the folder wouldn't open a file for writing"
                error is java.io.IOException ->
                    "the storage refused it" + shortDetail(message)
                else -> "unexpected error" + shortDetail(name)
            }
        }

        /** A technical scrap, parenthesised, only when it is short enough to read. */
        private fun shortDetail(text: String): String =
            if (text.isBlank() || text.length > 60) "" else " ($text)"
    }
}
