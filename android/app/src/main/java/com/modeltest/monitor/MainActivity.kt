package com.modeltest.monitor

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrainingMonitorApp()
        }
    }
}


data class TrainingStatus(
    val status: String = "idle",
    val epoch: Int = 0,
    val totalEpochs: Int = 0,
    val currentIou: Double? = null,
    val bestIou: Double? = null,
    val metricName: String = "IoU",
    val bestEpoch: Int? = null,
    val etaSeconds: Long? = null,
    val updatedAt: String? = null,
)


private val AppColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    background = Color(0xFFF7F8FA),
    surface = Color.White,
    onSurface = Color(0xFF111827),
)


@Composable
fun TrainingMonitorApp() {
    MaterialTheme(colorScheme = AppColors) {
        MonitorScreen()
    }
}


@Composable
private fun MonitorScreen() {
    val context = LocalContext.current
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    var savedUrl by rememberSaveable { mutableStateOf(loadBaseUrl(context)) }
    var savedToken by rememberSaveable { mutableStateOf(loadToken(context)) }
    var draftUrl by rememberSaveable { mutableStateOf(savedUrl) }
    var draftToken by rememberSaveable { mutableStateOf(savedToken) }
    var status by remember { mutableStateOf(TrainingStatus()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(savedUrl, savedToken) {
        while (true) {
            if (savedUrl.isNotBlank()) {
                runCatching { fetchStatus(client, savedUrl, savedToken) }
                    .onSuccess {
                        status = it
                        error = null
                    }
                    .onFailure {
                        error = it.message ?: "Connection failed"
                    }
            }
            delay(2_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(status = status, error = error)
        SettingsCard(
            draftUrl = draftUrl,
            draftToken = draftToken,
            onUrlChange = { draftUrl = it },
            onTokenChange = { draftToken = it },
            onSave = {
                savedUrl = draftUrl.trim()
                savedToken = draftToken.trim()
                saveBaseUrl(context, savedUrl)
                saveToken(context, savedToken)
            },
        )
        ProgressCard(status)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard("Current ${status.metricName}", formatMetric(status.currentIou), Modifier.weight(1f))
            MetricCard("Best ${status.metricName}", formatMetric(status.bestIou), Modifier.weight(1f))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                title = "Best Epoch",
                value = status.bestEpoch?.let { "Epoch $it" } ?: "--",
                modifier = Modifier.weight(1f),
            )
            MetricCard("ETA", formatEta(status.etaSeconds), Modifier.weight(1f))
        }
    }
}


@Composable
private fun SettingsCard(
    draftUrl: String,
    draftToken: String,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Backend URL", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = draftUrl,
                onValueChange = onUrlChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Access Token", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = draftToken,
                onValueChange = onTokenChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSave,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Save")
            }
        }
    }
}


@Composable
private fun Header(status: TrainingStatus, error: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Training Monitor",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (status.updatedAt.isNullOrBlank()) {
                    "Waiting for training data"
                } else {
                    "Updated at ${status.updatedAt}"
                },
                color = Color(0xFF6B7280),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusPill(status = if (error == null) status.status else "error")
    }
    if (error != null) {
        Text(
            text = "Connection failed: $error",
            color = Color(0xFFB91C1C),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}


@Composable
private fun StatusPill(status: String) {
    val color = when (status) {
        "training" -> Color(0xFF2563EB)
        "finished" -> Color(0xFF16A34A)
        "error" -> Color(0xFFDC2626)
        else -> Color(0xFF6B7280)
    }

    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = statusText(status),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}


@Composable
private fun ProgressCard(status: TrainingStatus) {
    val progress = if (status.totalEpochs > 0) {
        (status.epoch.toFloat() / status.totalEpochs).coerceIn(0f, 1f)
    } else {
        0f
    }

    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text("Training Progress", color = Color(0xFF6B7280))
                    Text(
                        text = "${status.epoch} / ${status.totalEpochs}",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
        }
    }
}


@Composable
private fun MetricCard(title: String, value: String, modifier: Modifier = Modifier) {
    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = Color(0xFF6B7280))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.size(2.dp))
        }
    }
}


private suspend fun fetchStatus(client: OkHttpClient, baseUrl: String, token: String): TrainingStatus {
    return withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url("${baseUrl.trim().trimEnd('/')}/api/status")
            .get()

        if (token.isNotBlank()) {
            requestBuilder.header("X-Monitor-Token", token)
        }

        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }

            val json = JSONObject(response.body?.string().orEmpty())
            TrainingStatus(
                status = json.optString("status", "idle"),
                epoch = json.optInt("epoch", 0),
                totalEpochs = json.optInt("total_epochs", 0),
                currentIou = json.optNullableDouble("current_iou"),
                bestIou = json.optNullableDouble("best_iou"),
                metricName = json.optString("metric_name", "IoU"),
                bestEpoch = json.optNullableInt("best_epoch"),
                etaSeconds = json.optNullableLong("eta_seconds"),
                updatedAt = json.optString("updated_at", ""),
            )
        }
    }
}


private fun JSONObject.optNullableDouble(name: String): Double? {
    return if (isNull(name)) null else optDouble(name)
}


private fun JSONObject.optNullableInt(name: String): Int? {
    return if (isNull(name)) null else optInt(name)
}


private fun JSONObject.optNullableLong(name: String): Long? {
    return if (isNull(name)) null else optLong(name)
}


private fun formatMetric(value: Double?): String {
    return value?.let { String.format(Locale.US, "%.4f", it) } ?: "--"
}


private fun formatEta(seconds: Long?): String {
    if (seconds == null) return "--"
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}


private fun statusText(status: String): String {
    return when (status) {
        "training" -> "Training"
        "finished" -> "Finished"
        "error" -> "Error"
        else -> "Idle"
    }
}


private fun loadBaseUrl(context: Context): String {
    return context
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("base_url", "http://10.0.2.2:6006")
        ?: "http://10.0.2.2:6006"
}


private fun saveBaseUrl(context: Context, baseUrl: String) {
    context
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit()
        .putString("base_url", baseUrl)
        .apply()
}


private fun loadToken(context: Context): String {
    return context
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("token", "")
        ?: ""
}


private fun saveToken(context: Context, token: String) {
    context
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit()
        .putString("token", token)
        .apply()
}
