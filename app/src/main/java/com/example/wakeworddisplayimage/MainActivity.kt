package com.example.wakeworddisplayimage

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.wakeworddisplayimage.ui.theme.WakeWordDisplayImageTheme

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startWakeWordService()
        }

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WakeWordService.ACTION_SCORE -> {
                    val score = intent.getFloatExtra(WakeWordService.EXTRA_SCORE, 0f)
                    dashboardViewModel?.updatePredictionScore(floatArrayOf(score))
                }
                WakeWordService.ACTION_COUNT -> {
                    dashboardViewModel?.addCount()
                }
            }
        }
    }

    private var dashboardViewModel: MainViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel: MainViewModel by viewModels()
        dashboardViewModel = viewModel
        enableEdgeToEdge()
        setContent {
            WakeWordDisplayImageTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AlexaDashboard(viewModel)
                }
            }
        }
        ensureWakeWordService()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(WakeWordService.ACTION_SCORE)
            addAction(WakeWordService.ACTION_COUNT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(serviceStateReceiver, filter)
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(serviceStateReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (hasMicPermission()) {
            startWakeWordService(WakeWordService.ACTION_RESUME)
        }
    }

    override fun onDestroy() {
        dashboardViewModel = null
        super.onDestroy()
    }

    private fun ensureWakeWordService() {
        if (hasMicPermission()) {
            startWakeWordService()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun startWakeWordService(action: String? = null) {
        if (!hasMicPermission()) return
        val intent = Intent(this, WakeWordService::class.java).apply {
            if (action != null) this.action = action
        }
        ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
fun AlexaDashboard(viewModel: MainViewModel) {
    var score by remember { mutableStateOf(0f) }
    var count by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf(true) }
    viewModel.predictionScores.observeAsStateCompat()?.let { if (it.isNotEmpty()) score = it[0].coerceIn(0f, 1f) }
    viewModel.wakewordCount.observeAsStateCompat()?.let { count = it; active = true }
    val animatedScore by animateFloatAsState(score, label = "score")
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 30.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text("Alexa", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Text("Hands-free voice control", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Box(Modifier.size(104.dp).scale(if (active) 1f else .96f).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
            Text("MIC", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(18.dp))
        Text(if (active) "Listening for “Alexa”" else "Microphone inactive", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text("Works from the home screen via foreground service", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Card(Modifier.fillMaxWidth(), RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Wake-word confidence", fontWeight = FontWeight.Medium)
                    Text("${(animatedScore * 100).toInt()}%", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color.Gray.copy(alpha = .25f))) {
                    Box(Modifier.fillMaxWidth(animatedScore).fillMaxHeight().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(20.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Wake words detected", fontWeight = FontWeight.Medium)
                Text("$count", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun <T> androidx.lifecycle.LiveData<T>.observeAsStateCompat(): T? {
    var value by remember { mutableStateOf<T?>(this.value) }
    androidx.compose.runtime.DisposableEffect(this) {
        val observer = androidx.lifecycle.Observer<T> { value = it }
        observeForever(observer)
        onDispose { removeObserver(observer) }
    }
    return value
}
