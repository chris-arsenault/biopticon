package io.ahara.biopticon.probe

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class PermissionRationaleActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply {
            text = "Biopticon Probe tests which attributes and interfaces are accessible. Only selected read permissions are requested. Measurements stay on this phone; S3 receives capability-test status events. You can revoke permissions in Android settings."
            setPadding(32, 32, 32, 32)
        })
    }
}
