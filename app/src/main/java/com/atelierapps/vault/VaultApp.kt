package com.atelierapps.vault

import android.app.Application

/**
 * Application entry point. Later steps hook in here: publishing the long-lived
 * share shortcut (spec §5), the ProcessLifecycleOwner auto-lock timer (§9), and
 * sweeping orphaned `tmp/` plaintext on launch (§5.1).
 */
class VaultApp : Application()
