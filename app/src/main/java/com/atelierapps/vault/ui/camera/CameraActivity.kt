package com.atelierapps.vault.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.atelierapps.vault.VaultGraph
import com.atelierapps.vault.data.entity.SourceType
import com.atelierapps.vault.media.SaveRequest
import com.atelierapps.vault.media.SourceInfo
import com.atelierapps.vault.share.SaveMediaWorker
import java.io.File
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import com.atelierapps.vault.ui.theme.Bg
import com.atelierapps.vault.ui.theme.Brass
import com.atelierapps.vault.ui.theme.Muted
import com.atelierapps.vault.session.AppDisguise

/**
 * In-app camera: captures photos and videos **straight into the vault** via the
 * existing encrypt pipeline ([SaveMediaWorker]), so the media never touches
 * MediaStore / the device gallery. Source is tagged CAMERA. FLAG_SECURE.
 */
class CameraActivity : ComponentActivity() {

    private var hasCamera by mutableStateOf(false)
    private var hasAudio by mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            hasCamera = result[Manifest.permission.CAMERA] == true || granted(Manifest.permission.CAMERA)
            hasAudio = result[Manifest.permission.RECORD_AUDIO] == true || granted(Manifest.permission.RECORD_AUDIO)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        hasCamera = granted(Manifest.permission.CAMERA)
        hasAudio = granted(Manifest.permission.RECORD_AUDIO)
        if (!hasCamera) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }

        setContent {
            if (hasCamera) {
                CameraScreen(lifecycleOwner = this, audioEnabled = hasAudio, onClose = { finish() })
            } else {
                PermissionPrompt(
                    onGrant = {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                        )
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun CameraScreen(lifecycleOwner: LifecycleOwner, audioEnabled: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val storage = remember { VaultGraph.storage(context) }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE)
            bindToLifecycle(lifecycleOwner)
        }
    }
    var videoMode by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var frontFacing by remember { mutableStateOf(false) }

    fun enqueue(temp: File, mime: String, name: String) {
        SaveMediaWorker.enqueue(
            context,
            SaveRequest(
                tempPath = temp.absolutePath,
                mimeType = mime,
                originalName = name,
                dateTakenMillis = System.currentTimeMillis(),
                tagNames = emptyList(),
                source = SourceInfo(SourceType.CAMERA, null, "Camera", null),
            ),
        )
        Toast.makeText(context, "Saved to ${AppDisguise.currentLabel(context)}", Toast.LENGTH_SHORT).show()
    }

    fun takePhoto() {
        val temp = storage.newTempFile()
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(temp).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    enqueue(temp, "image/jpeg", "IMG_${System.currentTimeMillis()}.jpg")
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(context, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    fun toggleRecording() {
        val active = recording
        if (active != null) {
            active.stop()
            recording = null
            return
        }
        val temp = storage.newTempFile()
        val audioConfig = if (audioEnabled) AudioConfig.create(true) else AudioConfig.AUDIO_DISABLED
        recording = controller.startRecording(
            FileOutputOptions.Builder(temp).build(),
            audioConfig,
            ContextCompat.getMainExecutor(context),
        ) { event ->
            if (event is VideoRecordEvent.Finalize) {
                recording = null
                if (!event.hasError()) enqueue(temp, "video/mp4", "VID_${System.currentTimeMillis()}.mp4")
                else Toast.makeText(context, "Recording failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // TextureView-backed so the preview renders under FLAG_SECURE.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    this.controller = controller
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Icon(
            Icons.Outlined.Close,
            contentDescription = "Close camera",
            tint = Color.White,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp)
                .size(26.dp).clickable { onClose() },
        )
        Text(
            "Flip", color = Color.White, fontSize = 14.sp,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp).clickable {
                frontFacing = !frontFacing
                controller.cameraSelector =
                    if (frontFacing) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            },
        )

        // Bottom controls: mode toggle + shutter.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp),
        ) {
            Row(
                Modifier.align(Alignment.TopCenter).padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                ModeLabel("Photo", !videoMode) { if (recording == null) videoMode = false }
                ModeLabel("Video", videoMode) { if (recording == null) videoMode = true }
            }

            // Shutter / record button.
            val recordingActive = recording != null
            Box(
                Modifier.align(Alignment.Center).padding(top = 44.dp).size(72.dp).clip(CircleShape)
                    .border(3.dp, Color.White, CircleShape)
                    .padding(6.dp).clip(CircleShape)
                    .background(if (videoMode) Color(0xFFE05656) else Color.White)
                    .clickable { if (videoMode) toggleRecording() else takePhoto() },
                contentAlignment = Alignment.Center,
            ) {
                if (recordingActive) Text("■", color = Color.White, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun ModeLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Brass else Color.White,
        fontSize = 14.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Bg).padding(32.dp), contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Grant camera", color = Brass, fontSize = 16.sp, modifier = Modifier.clickable { onGrant() })
            Text("Cancel", color = Muted, fontSize = 16.sp, modifier = Modifier.clickable { onClose() })
        }
    }
}
