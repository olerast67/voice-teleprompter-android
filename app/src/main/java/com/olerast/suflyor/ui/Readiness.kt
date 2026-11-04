package com.olerast.suflyor.ui

import com.olerast.suflyor.speech.SpeechPacks
import com.olerast.suflyor.BuildConfig
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.olerast.suflyor.overlay.PrompterAccessibilityService

/** What the "over the camera" mode needs, checked on every resume. */
data class Readiness(
    val mic: Boolean,
    val notifications: Boolean,
    val a11yEnabled: Boolean,
    val a11yRunning: Boolean,
    val drawOverlays: Boolean,
    /** A speech model is there: always in the full APK; in the lite one, once a speech pack is installed. */
    val speechModel: Boolean = true,
) {
    /** Hearing the voice while another app records needs the microphone and our accessibility service. */
    val overlayVoice: Boolean get() = mic && a11yRunning && speechModel
    val doneCount: Int get() = listOf(mic, a11yRunning).count { it } + if (!BuildConfig.BUNDLED_MODELS && speechModel) 1 else 0
    val total: Int get() = if (BuildConfig.BUNDLED_MODELS) 2 else 3

    companion object {
        fun check(context: Context) = Readiness(
            mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notifications = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            a11yEnabled = PrompterAccessibilityService.isEnabledInSettings(context),
            a11yRunning = PrompterAccessibilityService.instance != null,
            drawOverlays = Settings.canDrawOverlays(context),
            speechModel = SpeechPacks.hasAnyModel(context),
        )
    }
}
