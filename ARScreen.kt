package com.example.visionfurnish10

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMainLightNode
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.delay

// ── Scale constants ────────────────────────────────────────────────────────────
private const val SCALE_MIN        = 0.1f
private const val SCALE_MAX        = 3.0f
private const val SCALE_STEP       = 0.15f  // per button tap — large enough to be clearly visible
private const val SCALE_BASE       = 0.5f   // placement default → displayed as 100%

// ── Rotation constants ─────────────────────────────────────────────────────────
private const val ROTATE_STEP_DEG  = 15f    // single tap / double-tap
private const val ROTATE_LONG_MS   = 40L    // frame interval for continuous rotation
private const val ROTATE_LONG_DEG  = 3f     // degrees per frame during long-press

@Composable
fun ARScreen(selectedModelFile: String = "chair.glb") {
    val engine        = rememberEngine()
    val modelLoader   = rememberModelLoader(engine)
    val mainLightNode = rememberMainLightNode(engine)
    val anchors       = remember { mutableStateListOf<Anchor>() }
    val context       = LocalContext.current

    var modelFileExists  by remember { mutableStateOf(true) }
    var currentFrame     by remember { mutableStateOf<Frame?>(null) }
    var currentSession   by remember { mutableStateOf<com.google.ar.core.Session?>(null) }
    var isModelLoading   by remember { mutableStateOf(false) }

    // ── Gesture / control state ───────────────────────────────────────────────
    var modelScale      by remember { mutableStateOf(SCALE_BASE) }
    var modelRotationY  by remember { mutableStateOf(0f) }

    // Animated scale — drives ModelNode so every resize step is smoothly interpolated
    val animatedScale   by animateFloatAsState(
        targetValue    = modelScale,
        animationSpec  = tween(durationMillis = 180),
        label          = "modelScale"
    )

    // Scale as a human-readable percentage relative to the placement baseline
    val scalePercent = ((animatedScale / SCALE_BASE) * 100f).roundToInt()

    // Long-press continuous rotation state
    // direction: +1 = clockwise, -1 = anticlockwise
    var isLongPressing  by remember { mutableStateOf(false) }
    var longPressDir    by remember { mutableStateOf(1) }   // starts clockwise

    // ── Model Selection State — initialized from product tapped on HomeScreen ─
    var selectedModel by remember { mutableStateOf(selectedModelFile) }

    // ── Continuous rotation loop ──────────────────────────────────────────────
    LaunchedEffect(isLongPressing, longPressDir) {
        while (isLongPressing) {
            modelRotationY += ROTATE_LONG_DEG * longPressDir
            delay(ROTATE_LONG_MS)
        }
    }

    LaunchedEffect(selectedModel) {
        // Clear any previously placed model so only one model exists in the scene at a time.
        anchors.forEach { it.detach() }
        anchors.clear()
        // Reset state so the new model starts at default scale/rotation.
        modelScale     = SCALE_BASE
        modelRotationY = 0f
        isLongPressing = false
        longPressDir   = 1

        try {
            context.assets.open(selectedModel).close()
            modelFileExists = true
            Log.d("ARPlacement", "Model file found in assets: $selectedModel")
        } catch (e: Exception) {
            modelFileExists = false
            Log.e("ARPlacement", "Model file NOT found in assets: $selectedModel")
        }
    }

    LaunchedEffect(mainLightNode) {
        // Boost light intensity so even untextured or dark materials are clearly visible
        mainLightNode?.intensity = 150_000f
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(anchors.isNotEmpty()) {
                if (anchors.isNotEmpty()) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        // Smooth pinch-to-scale with tight limits
                        val targetScale = (modelScale * zoom).coerceIn(SCALE_MIN, SCALE_MAX)
                        modelScale += (targetScale - modelScale) * 0.35f
                        // Smooth horizontal drag → Y-axis rotation
                        modelRotationY += pan.x * 0.3f
                    }
                }
            }
    ) {
        // ── AR Scene ─────────────────────────────────────────────────────────
        ARSceneView(
            modifier     = Modifier.fillMaxSize(),
            engine       = engine,
            modelLoader  = modelLoader,
            mainLightNode = mainLightNode,
            sessionConfiguration = { session, config ->
                // Enable HDR light estimation for realistic IBL reflections and shadows
                config.lightEstimationMode =
                    com.google.ar.core.Config.LightEstimationMode.ENVIRONMENTAL_HDR
            },
            onSessionUpdated = { session, frame ->
                currentSession = session
                currentFrame   = frame
            }
        ) {
            anchors.forEach { anchor ->
                AnchorNode(anchor = anchor) {
                    val modelInstance = rememberModelInstance(
                        modelLoader  = modelLoader,
                        fileLocation = selectedModel
                    )

                    LaunchedEffect(modelInstance) {
                        isModelLoading = modelInstance == null
                        if (modelInstance != null) {
                            Log.d("ARPlacement", "Model loaded and attached successfully!")
                        } else {
                            Log.d("ARPlacement", "Model is loading...")
                        }
                    }

                    if (modelInstance != null) {
                        ModelNode(
                            modelInstance = modelInstance,
                            scaleToUnits  = animatedScale,   // animated for smooth resize
                            rotation      = io.github.sceneview.math.Rotation(0f, modelRotationY, 0f),
                            centerOrigin  = io.github.sceneview.math.Position(x = 0f, y = -1f, z = 0f)
                        )
                    } else if (!isModelLoading) {
                        Log.e("ARPlacement", "Model failed to load, cannot show fallback primitive natively.")
                    }
                }
            }
        }

        // ── Loading Indicator Overlay ─────────────────────────────────────────
        if (isModelLoading) {
            Box(
                modifier        = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        // ── Status Overlay (Error / Scan hint) ───────────────────────────────
        if (!modelFileExists || anchors.isEmpty()) {
            AnimatedVisibility(
                visible  = true,
                enter    = fadeIn(),
                exit     = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 24.dp, end = 24.dp)
            ) {
                Surface(
                    shape          = RoundedCornerShape(24.dp),
                    color          = if (!modelFileExists)
                                         MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
                                     else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier             = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment    = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector     = if (!modelFileExists) Icons.Filled.Warning else Icons.Filled.Check,
                            contentDescription = null,
                            tint            = if (!modelFileExists)
                                                  MaterialTheme.colorScheme.onErrorContainer
                                              else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text      = if (!modelFileExists)
                                            "$selectedModel not found in assets!"
                                        else "Scan floor, then press Place",
                            style     = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color     = if (!modelFileExists)
                                            MaterialTheme.colorScheme.onErrorContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize  = 15.sp
                        )
                    }
                }
            }
        }

        // ── Bottom Controls ───────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp, start = 20.dp, end = 20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Floating control panel (visible only when model is placed) ───
            if (anchors.isNotEmpty()) {
                Surface(
                    shape          = RoundedCornerShape(28.dp),
                    color          = Color.Black.copy(alpha = 0.55f),
                    shadowElevation = 12.dp,
                    modifier       = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier             = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment    = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {

                        // ── Size controls ─────────────────────────────────────
                        Column(
                            horizontalAlignment  = Alignment.CenterHorizontally,
                            verticalArrangement  = Arrangement.spacedBy(6.dp)
                        ) {
                            // Scale % badge
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.80f)
                            ) {
                                Text(
                                    text       = "$scalePercent%",
                                    fontSize   = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color      = Color.White,
                                    modifier   = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                )
                            }

                            Row(
                                verticalAlignment    = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Decrease size
                                FilledIconButton(
                                    onClick  = {
                                        modelScale = (modelScale - SCALE_STEP).coerceAtLeast(SCALE_MIN)
                                    },
                                    modifier = Modifier.size(48.dp),
                                    shape    = CircleShape,
                                    colors   = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = if (modelScale <= SCALE_MIN)
                                                             MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                         else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                        contentColor   = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Icon(
                                        imageVector        = Icons.Filled.Remove,
                                        contentDescription = "Decrease size",
                                        modifier           = Modifier.size(22.dp)
                                    )
                                }

                                // Increase size
                                FilledIconButton(
                                    onClick  = {
                                        modelScale = (modelScale + SCALE_STEP).coerceAtMost(SCALE_MAX)
                                    },
                                    modifier = Modifier.size(48.dp),
                                    shape    = CircleShape,
                                    colors   = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = if (modelScale >= SCALE_MAX)
                                                             MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                         else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                        contentColor   = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                ) {
                                    Icon(
                                        imageVector        = Icons.Filled.Add,
                                        contentDescription = "Increase size",
                                        modifier           = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Text(
                                text       = "Size",
                                fontSize   = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color      = Color.White.copy(alpha = 0.6f)
                            )
                        }

                        // ── Divider ────────────────────────────────────────────
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(36.dp)
                                .background(Color.White.copy(alpha = 0.2f))
                        )

                        // ── Smart Rotate button ───────────────────────────────
                        // Tap         → CW  +15°
                        // Double-tap  → CCW -15°
                        // Long-press  → continuous CW; second long-press → reverse
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilledIconButton(
                                onClick = {
                                    // Single tap = clockwise 15°
                                    if (!isLongPressing) {
                                        modelRotationY += ROTATE_STEP_DEG
                                    }
                                },
                                modifier = Modifier.size(56.dp),
                                shape    = CircleShape,
                                colors   = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (isLongPressing)
                                                         MaterialTheme.colorScheme.primary
                                                     else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                    contentColor   = if (isLongPressing)
                                                         MaterialTheme.colorScheme.onPrimary
                                                     else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector        = Icons.Filled.RotateRight,
                                    contentDescription = "Rotate",
                                    modifier           = Modifier.size(26.dp)
                                )
                            }
                            Text(
                                text = when {
                                    isLongPressing && longPressDir > 0 -> "↻ Rotating"
                                    isLongPressing && longPressDir < 0 -> "↺ Rotating"
                                    else -> "Rotate"
                                },
                                fontSize  = 10.sp,
                                color     = if (isLongPressing)
                                                MaterialTheme.colorScheme.primary
                                            else Color.White.copy(alpha = 0.75f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // ── Long-press trigger button ─────────────────────────
                        // Separate smaller button to start / reverse / stop continuous rotation
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            FilledIconButton(
                                onClick = {
                                    if (!isLongPressing) {
                                        // Start continuous CW rotation
                                        isLongPressing = true
                                        longPressDir   = 1
                                    } else {
                                        // Already rotating → reverse direction
                                        longPressDir = -longPressDir
                                    }
                                },
                                modifier = Modifier.size(44.dp),
                                shape    = CircleShape,
                                colors   = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (isLongPressing)
                                                         MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                                                     else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    contentColor   = if (isLongPressing)
                                                         MaterialTheme.colorScheme.onPrimaryContainer
                                                     else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Text(
                                    text       = if (isLongPressing) "⇄" else "▶▶",
                                    fontSize   = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color      = if (isLongPressing)
                                                     MaterialTheme.colorScheme.onPrimaryContainer
                                                 else Color.White.copy(alpha = 0.8f)
                                )
                            }
                            // Stop button (only visible while auto-rotating)
                            if (isLongPressing) {
                                Text(
                                    text      = "Stop / Rev",
                                    fontSize  = 9.sp,
                                    color     = Color.White.copy(alpha = 0.65f),
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Text(
                                    text      = "Auto",
                                    fontSize  = 10.sp,
                                    color     = Color.White.copy(alpha = 0.65f),
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            // Dedicated Stop button when auto-rotating
                            if (isLongPressing) {
                                FilledIconButton(
                                    onClick  = { isLongPressing = false },
                                    modifier = Modifier.size(36.dp),
                                    shape    = CircleShape,
                                    colors   = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                                        contentColor   = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Text(
                                        text       = "■",
                                        fontSize   = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color      = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Place button (always visible, untouched) ─────────────────────
            Button(
                onClick = {
                    if (!modelFileExists) {
                        Toast.makeText(context, "Error: $selectedModel missing", Toast.LENGTH_SHORT).show()
                        return@Button
                    }

                    val metrics       = context.resources.displayMetrics
                    val screenCenterX = metrics.widthPixels  / 2f
                    val screenCenterY = metrics.heightPixels / 2f

                    val hitResults = currentFrame?.hitTest(screenCenterX, screenCenterY)
                    val planeHit   = hitResults?.firstOrNull { it.trackable is Plane }

                    val cameraPose = currentFrame?.camera?.pose

                    if (planeHit != null) {
                        anchors.forEach { it.detach() }
                        anchors.clear()
                        anchors.add(planeHit.createAnchor())
                        modelScale     = SCALE_BASE
                        modelRotationY = 0f
                        isLongPressing = false
                        Log.d("ARPlacement", "Placement success: Aligned to floor plane.")
                        Toast.makeText(
                            context,
                            "${selectedModel.replace(".glb", "").replaceFirstChar { it.uppercase() }} placed on floor!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else if (cameraPose != null && currentSession != null) {
                        // Fallback: 1.5 metres ahead, drop to floor if needed.
                        val forwardPose    = Pose.makeTranslation(0f, -0.5f, -1.5f)
                        val placementPose  = cameraPose.compose(forwardPose)
                        val anchor         = currentSession?.createAnchor(placementPose)
                        if (anchor != null) {
                            anchors.forEach { it.detach() }
                            anchors.clear()
                            anchors.add(anchor)
                            modelScale     = SCALE_BASE
                            modelRotationY = 0f
                            isLongPressing = false
                            Log.d("ARPlacement", "Placement fallback success: 1.5m ahead of camera.")
                            Toast.makeText(
                                context,
                                "${selectedModel.replace(".glb", "").replaceFirstChar { it.uppercase() }} placed 1.5m ahead!",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Log.e("ARPlacement", "Placement failure: Could not create fallback anchor.")
                        }
                    } else {
                        Log.e("ARPlacement", "Placement failure: Camera or Session not ready.")
                        Toast.makeText(context, "Camera not ready.", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier  = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape     = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                enabled   = !isModelLoading
            ) {
                Icon(
                    imageVector        = Icons.Filled.Add,
                    contentDescription = "Place",
                    modifier           = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text       = "Place ${selectedModel.replace(".glb", "").replaceFirstChar { it.uppercase() }}",
                    fontSize   = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
