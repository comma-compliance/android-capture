package com.commacompliance.archiver.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.commacompliance.archiver.R
import com.commacompliance.archiver.util.applySystemWindowInsetsAsPadding
import com.google.android.material.button.MaterialButton

/**
 * Plain-language explanation of WHAT the app captures and WHY, shown BEFORE the
 * READ_SMS permission is requested. The user reads the rationale, taps "Allow
 * message access", and only then does the system permission dialog appear. This
 * keeps the request honest and informed rather than springing a bare system
 * prompt with no context.
 *
 * On a fully-managed device the permission may already be granted by policy; in
 * that case this screen forwards straight to onboarding.
 */
class PermissionRationaleActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { proceed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasSmsPermission()) {
            proceed()
            return
        }

        setContentView(R.layout.activity_rationale)
        findViewById<View>(android.R.id.content).applySystemWindowInsetsAsPadding()
        findViewById<MaterialButton>(R.id.grantButton).setOnClickListener {
            permissionLauncher.launch(Manifest.permission.READ_SMS)
        }
    }

    private fun proceed() {
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
}
