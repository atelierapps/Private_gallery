package com.atelierapps.vault.share

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity

/**
 * Transparent share target (spec §5) — the primary daily flow.
 *
 * Step 3 builds this out: render the save bottom sheet, capture source
 * attribution (§6), and — critically — read the incoming `InputStream` while
 * this Activity is still alive (the `ACTION_SEND` grant dies with it, §5.1),
 * spooling to an app-private `tmp/` file that an expedited WorkManager job
 * encrypts. No biometric prompt: public-key wrapping means saving needs no auth.
 *
 * FLAG_SECURE is set so the incoming preview can't be screenshot.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // TODO(step 3): bottom sheet + source capture + spool-then-encrypt.
        finish()
    }
}
