package com.example.wakeworddisplayimage

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wakeworddisplayimage.ui.theme.WakeWordDisplayImageTheme

class MainActivity : ComponentActivity() {
    private lateinit var openWakeWord: OpenWakeWord

    private val requestPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openWakeWord.startListeningForKeyword()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel: MainViewModel by viewModels()
        openWakeWord = OpenWakeWord(this, viewModel)

        enableEdgeToEdge()
        setContent {
            WakeWordDisplayImageTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AlexaDashboard(viewModel)
                }
            }
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            openWakeWord.startListeningForKeyword()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        openWakeWord.release()
        super.onDestroy()
    }
}

@Composable
fun AlexaDashboard(viewModel: MainViewModel) {
    var score by remember { mutableStateOf(0f) }
    var count by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf(true) }

    viewModel.predictionScores.observeAsStateCompat()?.let { scores ->
        if (scores.isNotEmpty()) score = scores[0].coerceIn(0f, 1f)
    }
    viewModel.wakewordCount.observeAsStateCompat()?.let {
        count = it
        active = true
    }

    val animatedScore by animateFloatAsState(score, label = "score")

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Alexa", fontSize = 42.sp, fontWeight = FontWeight.Bold)
        Text(
            "Hands-free voice control",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        Box(
            modifier = Modifier
                .size(104.dp)
                .scale(if (active) 1f else .96f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text("MIC", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(18.dp))
        Text(
            if (active) "Listening for “Alexa”" else "Microphone inactive",
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Say Alexa to open Amazon Alexa",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Wake-word confidence", fontWeight = FontWeight.Medium)
                    Text("${(animatedScore * 100).toInt()}%", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Gray.copy(alpha = .25f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(animatedScore)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Card(shape = RoundedCornerShape(20.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
