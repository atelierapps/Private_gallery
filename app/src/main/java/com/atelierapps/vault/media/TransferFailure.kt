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
                // Not a lock: the Keystore lets the media key be used for five
                // minutes after an unlock, and this is that running out. It
                // used to say "the app locked", which sent people looking for
                // a lock that never happened.
                name.contains("UserNotAuthenticated") ->
                    "the unlock had timed out — lock, unlock and retry"
                name.contains("KeyPermanentlyInvalidated") ->
                    "encryption key no longer valid — can't decrypt"
                // CipherInputStream reports a bad GCM tag as end-of-stream, so a
                // wrong key surfaces here as a short read rather than as a tag
                // error. Both mean the same thing to the person reading it.
                name.contains("AEADBadTag") || message.contains("truncated") ->
                    "doesn't match this passphrase — likely left from an older backup in this folder"
                message.startsWith("blob length") ->
                    "encrypted copy was the wrong size — discarded"
                message.startsWith("spool missing") || message.startsWith("blob missing") ->
                    "file went missing during the run"
                error is OutOfMemoryError ->
                    "too large to process on this phone"
                error is SecurityException ->
                    "folder access lapsed — choose it again"
                message.contains("ENOSPC") || message.contains("No space left") ->
                    "the destination is full"
                error is java.io.FileNotFoundException ->
                    "folder refused to create the file"
                error is NullPointerException ->
                    "folder refused to open the file for writing"
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
