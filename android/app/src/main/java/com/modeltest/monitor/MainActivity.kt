package com.modeltest.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrainingMonitorApp()
        }
    }
}


private const val SettingsPrefs = "settings"
private const val SecureSettingsPrefs = "secure_settings"
private const val TokenKey = "token"
private const val NotificationEnabledKey = "notification_enabled"
private const val TrainingNotificationId = 4100
private const val TrainingFinishedNotificationId = 4101
private const val TrainingChannelId = "training_status_lock_screen"
private const val TrainingFinishedChannelId = "training_finished"


class TrainingNotificationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
    private var loopJob: Job? = null
    private var lastStatus: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasNotificationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val cached = loadCachedStatus(this) ?: TrainingStatus()
        startForeground(
            TrainingNotificationId,
            buildTrainingNotification(
                context = this,
                status = cached,
                content = notificationSummary(this, cached),
            ),
        )
        loopJob?.cancel()
        loopJob = serviceScope.launch { pollTrainingStatus() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun pollTrainingStatus() {
        while (true) {
            val delaySeconds = loadRefreshSeconds(this@TrainingNotificationService).coerceIn(2, 60)
            val url = loadBaseUrl(this@TrainingNotificationService)
            val token = loadToken(this@TrainingNotificationService)

            try {
                val status = if (url.isBlank()) {
                    loadCachedStatus(this@TrainingNotificationService) ?: TrainingStatus()
                } else {
                    val raw = fetchStatusJson(client, url, token)
                    saveCachedStatus(this@TrainingNotificationService, raw)
                    parseTrainingStatus(JSONObject(raw))
                }

                updateTrainingNotification(status, notificationSummary(this@TrainingNotificationService, status))
                if (status.status == "finished" && lastStatus != "finished") {
                    showTrainingFinishedNotification(status)
                }
                lastStatus = status.status
            } catch (exc: Exception) {
                val cached = loadCachedStatus(this@TrainingNotificationService) ?: TrainingStatus(status = "error")
                updateTrainingNotification(cached, "连接失败，显示最后一次同步数据")
                lastStatus = "error"
            }

            delay(delaySeconds * 1_000L)
        }
    }

    private fun updateTrainingNotification(status: TrainingStatus, content: String) {
        notificationManager(this).notify(
            TrainingNotificationId,
            buildTrainingNotification(this, status, content),
        )
    }

    private fun showTrainingFinishedNotification(status: TrainingStatus) {
        notificationManager(this).notify(
            TrainingFinishedNotificationId,
            buildFinishedNotification(this, status),
        )
    }
}


private enum class AppPage(val title: String) {
    Dashboard("总览"),
    Charts("曲线"),
    Settings("设置"),
}


data class HistoryPoint(
    val epoch: Int,
    val metrics: Map<String, Double>,
    val updatedAt: String = "",
)


data class TrainingStatus(
    val status: String = "idle",
    val epoch: Int = 0,
    val totalEpochs: Int = 0,
    val metrics: Map<String, Double> = emptyMap(),
    val bestMetrics: Map<String, Double> = emptyMap(),
    val bestEpochs: Map<String, Int> = emptyMap(),
    val metricName: String = "IoU",
    val etaSeconds: Long? = null,
    val updatedAt: String = "",
    val history: List<HistoryPoint> = emptyList(),
    val availableMetrics: List<String> = emptyList(),
) {
    val progress: Float
        get() = if (totalEpochs > 0) {
            (epoch.toFloat() / totalEpochs).coerceIn(0f, 1f)
        } else {
            0f
        }

    fun metricNames(): List<String> {
        val names = mutableListOf<String>()
        names.addAll(availableMetrics)
        names.addAll(metrics.keys)
        names.addAll(bestMetrics.keys)
        history.forEach { names.addAll(it.metrics.keys) }
        return names.filter { it.isNotBlank() }.distinct()
    }

    fun primaryMetric(): String? {
        return when {
            metricName in metrics -> metricName
            metricName in bestMetrics -> metricName
            metrics.isNotEmpty() -> metrics.keys.first()
            bestMetrics.isNotEmpty() -> bestMetrics.keys.first()
            else -> null
        }
    }
}


private val AppColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    background = Color(0xFFF5F7FB),
    surface = Color.White,
    onSurface = Color(0xFF111827),
)

private val ChartColors = listOf(
    Color(0xFF2563EB),
    Color(0xFF16A34A),
    Color(0xFFDC2626),
    Color(0xFF9333EA),
    Color(0xFFF59E0B),
    Color(0xFF0891B2),
)

@Composable
fun TrainingMonitorApp() {
    MaterialTheme(colorScheme = AppColors) {
        MonitorRoot()
    }
}


@Composable
private fun MonitorRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    var page by rememberSaveable { mutableStateOf(AppPage.Dashboard.name) }
    var savedUrl by rememberSaveable { mutableStateOf(loadBaseUrl(context)) }
    var savedToken by rememberSaveable { mutableStateOf(loadToken(context)) }
    var draftUrl by rememberSaveable { mutableStateOf(savedUrl) }
    var draftToken by rememberSaveable { mutableStateOf(savedToken) }
    var refreshSeconds by rememberSaveable { mutableStateOf(loadRefreshSeconds(context)) }
    var selectedMetricsText by rememberSaveable { mutableStateOf(loadSelectedMetricsText(context)) }
    var notificationEnabled by rememberSaveable { mutableStateOf(loadNotificationEnabled(context)) }
    var status by remember { mutableStateOf(loadCachedStatus(context) ?: TrainingStatus()) }
    var error by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var testMessage by rememberSaveable { mutableStateOf("") }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            notificationEnabled = true
            saveNotificationEnabled(context, true)
            startTrainingNotificationService(context)
            testMessage = "通知已开启"
        } else {
            notificationEnabled = false
            saveNotificationEnabled(context, false)
            stopTrainingNotificationService(context)
            testMessage = "未授予通知权限，通知未开启"
        }
    }

    suspend fun refreshOnce(url: String = savedUrl, token: String = savedToken) {
        val normalizedUrl = normalizeBaseUrl(url)
        if (normalizedUrl.isBlank()) {
            error = "请先在设置里填写后端地址"
            return
        }

        isRefreshing = true
        try {
            val raw = fetchStatusJson(client, normalizedUrl, token)
            status = parseTrainingStatus(JSONObject(raw))
            saveCachedStatus(context, raw)
            error = null
        } catch (exc: Exception) {
            error = exc.message ?: "连接失败"
        } finally {
            isRefreshing = false
        }
    }

    LaunchedEffect(savedUrl, savedToken, refreshSeconds) {
        while (true) {
            refreshOnce()
            delay(refreshSeconds * 1_000L)
        }
    }

    LaunchedEffect(notificationEnabled, savedUrl, savedToken, selectedMetricsText, refreshSeconds) {
        if (notificationEnabled) {
            startTrainingNotificationService(context)
        } else {
            stopTrainingNotificationService(context)
        }
    }

    val selectedMetrics = parseMetricList(selectedMetricsText)
    val visibleMetrics = chooseVisibleMetrics(status, selectedMetrics)
    val metricOptions = mergeMetricOptions(status.metricNames(), selectedMetrics)
    val currentPage = AppPage.valueOf(page)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when (currentPage) {
                    AppPage.Dashboard -> DashboardScreen(
                        status = status,
                        error = error,
                        isRefreshing = isRefreshing,
                        visibleMetrics = visibleMetrics,
                        onRefresh = { scope.launch { refreshOnce() } },
                    )

                    AppPage.Charts -> ChartsScreen(
                        status = status,
                        selectedMetrics = selectedMetrics,
                    )

                    AppPage.Settings -> SettingsScreen(
                        draftUrl = draftUrl,
                        draftToken = draftToken,
                        refreshSeconds = refreshSeconds,
                        metricOptions = metricOptions,
                        selectedMetrics = selectedMetrics,
                        notificationEnabled = notificationEnabled,
                        testMessage = testMessage,
                        onUrlChange = { draftUrl = it },
                        onTokenChange = { draftToken = it },
                        onRefreshSecondsChange = {
                            refreshSeconds = it
                            saveRefreshSeconds(context, it)
                        },
                        onMetricToggle = { metric ->
                            selectedMetricsText = toggleMetric(selectedMetrics, metric)
                            saveSelectedMetricsText(context, selectedMetricsText)
                        },
                        onNotificationEnabledChange = {
                            if (it) {
                                if (hasNotificationPermission(context)) {
                                    notificationEnabled = true
                                    saveNotificationEnabled(context, true)
                                    startTrainingNotificationService(context)
                                    testMessage = "通知已开启"
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            } else {
                                notificationEnabled = false
                                saveNotificationEnabled(context, false)
                                stopTrainingNotificationService(context)
                                testMessage = "通知已关闭"
                            }
                        },
                        onClearCachedData = {
                            clearCachedStatus(context)
                            status = TrainingStatus()
                            error = null
                            testMessage = "本地训练缓存已清除"
                        },
                        onClearToken = {
                            savedToken = ""
                            draftToken = ""
                            saveToken(context, "")
                            notificationEnabled = false
                            saveNotificationEnabled(context, false)
                            stopTrainingNotificationService(context)
                            testMessage = "Token 已清除，通知已停止"
                        },
                        onSave = {
                            savedUrl = normalizeBaseUrl(draftUrl)
                            draftUrl = savedUrl
                            savedToken = draftToken.trim()
                            saveBaseUrl(context, savedUrl)
                            saveToken(context, savedToken)
                            testMessage = "设置已保存"
                        },
                        onTest = {
                            scope.launch {
                                testMessage = "正在测试连接..."
                                runCatching {
                                    val raw = fetchStatusJson(client, normalizeBaseUrl(draftUrl), draftToken.trim())
                                    saveCachedStatus(context, raw)
                                    parseTrainingStatus(JSONObject(raw))
                                }
                                    .onSuccess {
                                        status = it
                                        error = null
                                        testMessage = "连接正常"
                                    }
                                    .onFailure {
                                        testMessage = "连接失败：${it.message ?: "未知错误"}"
                                    }
                            }
                        },
                    )
                }
            }
            BottomTabs(
                current = currentPage,
                onChange = { page = it.name },
            )
        }
    }
}


@Composable
private fun DashboardScreen(
    status: TrainingStatus,
    error: String?,
    isRefreshing: Boolean,
    visibleMetrics: List<String>,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(
            status = status,
            error = error,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
        )
        ProgressHeroCard(status)
        BestSummaryCard(status)
        MetricGrid(status, visibleMetrics)
        MiniChartCard(status, visibleMetrics)
    }
}


@Composable
private fun BestSummaryCard(status: TrainingStatus) {
    val metric = status.primaryMetric()
    val best = metric?.let { status.bestMetrics[it] }
    val bestEpoch = metric?.let { status.bestEpochs[it] }
    val current = metric?.let { status.metrics[it] }

    ElevatedCard(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF111827)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1.2f)) {
                Text(
                    text = "Best ${metricDisplayName(metric ?: status.metricName)}",
                    color = Color(0xFFCBD5E1),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = formatMetric(best),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Best Epoch", color = Color(0xFFCBD5E1), fontWeight = FontWeight.SemiBold)
                Text(
                    text = bestEpoch?.let { "$it" } ?: "--",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Current", color = Color(0xFFCBD5E1), fontWeight = FontWeight.SemiBold)
                Text(
                    text = formatMetric(current),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


@Composable
private fun Header(
    status: TrainingStatus,
    error: String?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "训迹",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = syncText(status, error, isRefreshing),
                color = if (error == null) Color(0xFF6B7280) else Color(0xFFB91C1C),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusPill(status = if (error == null) status.status else "error")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefresh) {
            Text("立即刷新")
        }
    }
}


@Composable
private fun ProgressHeroCard(status: TrainingStatus) {
    ElevatedCard(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text("训练进度", color = Color(0xFF6B7280))
                    Text(
                        text = "${status.epoch} / ${status.totalEpochs}",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${(status.progress * 100).toInt()}%",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("预计剩余 ${formatEta(status.etaSeconds)}", color = Color(0xFF6B7280))
                }
            }

            TrainingBuddy(
                status = status.status,
                progress = status.progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(126.dp),
            )

            LinearProgressIndicator(
                progress = { status.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
            )
        }
    }
}


@Composable
private fun TrainingBuddy(status: String, progress: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "training_buddy")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "buddy_pulse",
    )
    val loop by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Restart),
        label = "buddy_loop",
    )
    val accent = when (status) {
        "training" -> Color(0xFF2563EB)
        "finished" -> Color(0xFF16A34A)
        "error" -> Color(0xFFDC2626)
        else -> Color(0xFF6B7280)
    }
    val title = when (status) {
        "training" -> "训练助手正在巡航"
        "finished" -> "训练完成，结果已记录"
        "error" -> "连接异常，等待恢复"
        else -> "等待新的训练任务"
    }
    val subtitle = when (status) {
        "training" -> "Progress ${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
        "finished" -> "可以查看 Best 指标和曲线"
        "error" -> "保留最后一次同步数据"
        else -> "连接服务器后自动开始"
    }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF8FBFF)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BuddyCanvas(
                status = status,
                accent = accent,
                pulse = pulse,
                loop = loop,
                modifier = Modifier
                    .weight(1f)
                    .height(116.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    color = Color(0xFF6B7280),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    color = accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                )
            }
        }
    }
}


@Composable
private fun BuddyCanvas(
    status: String,
    accent: Color,
    pulse: Float,
    loop: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val groundY = h * 0.82f
        val bodyX = w * 0.28f
        val bodyY = h * 0.58f + if (status == "training") (pulse - 0.5f) * 8.dp.toPx() else 0f
        val skin = Color(0xFFFFD7A8)
        val ink = Color(0xFF111827)
        val muted = Color(0xFFE5E7EB)

        drawRoundRect(
            color = Color(0xFFEAF2FF),
            topLeft = Offset(w * 0.47f, h * 0.22f),
            size = Size(w * 0.42f, h * 0.42f),
            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
        )
        drawRoundRect(
            color = Color(0xFFBFD7FF),
            topLeft = Offset(w * 0.47f, h * 0.22f),
            size = Size(w * 0.42f, h * 0.42f),
            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        repeat(3) { index ->
            val y = h * (0.32f + index * 0.1f)
            drawLine(muted, Offset(w * 0.52f, y), Offset(w * 0.84f, y), 1.dp.toPx())
        }
        val chart = Path().apply {
            moveTo(w * 0.53f, h * 0.55f)
            lineTo(w * 0.61f, h * 0.45f)
            lineTo(w * 0.69f, h * 0.49f)
            lineTo(w * 0.78f, h * 0.33f)
        }
        drawPath(
            path = chart,
            color = if (status == "error") Color(0xFFEF4444) else Color(0xFF2563EB),
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        if (status == "training") {
            val dotX = w * (0.53f + 0.25f * loop)
            val dotY = h * (0.55f - 0.2f * loop + 0.04f * pulse)
            drawCircle(Color(0xFFF59E0B), 5.dp.toPx(), Offset(dotX, dotY))
        }

        drawRoundRect(
            color = Color(0x33000000),
            topLeft = Offset(bodyX - 36.dp.toPx(), groundY),
            size = Size(72.dp.toPx(), 10.dp.toPx()),
            cornerRadius = CornerRadius(50f, 50f),
        )

        val armLift = when (status) {
            "finished" -> -32.dp.toPx()
            "training" -> -8.dp.toPx() - pulse * 12.dp.toPx()
            "error" -> 10.dp.toPx()
            else -> 4.dp.toPx()
        }
        val legStep = if (status == "training") (loop - 0.5f) * 18.dp.toPx() else 0f

        drawLine(accent, Offset(bodyX - 16.dp.toPx(), bodyY + 18.dp.toPx()), Offset(bodyX - 36.dp.toPx(), bodyY + armLift), 8.dp.toPx(), StrokeCap.Round)
        drawLine(accent, Offset(bodyX + 16.dp.toPx(), bodyY + 18.dp.toPx()), Offset(bodyX + 36.dp.toPx(), bodyY + armLift * 0.7f), 8.dp.toPx(), StrokeCap.Round)
        drawLine(ink, Offset(bodyX - 12.dp.toPx(), bodyY + 56.dp.toPx()), Offset(bodyX - 22.dp.toPx() - legStep, groundY), 7.dp.toPx(), StrokeCap.Round)
        drawLine(ink, Offset(bodyX + 12.dp.toPx(), bodyY + 56.dp.toPx()), Offset(bodyX + 22.dp.toPx() + legStep, groundY), 7.dp.toPx(), StrokeCap.Round)
        drawRoundRect(
            color = accent,
            topLeft = Offset(bodyX - 28.dp.toPx(), bodyY + 8.dp.toPx()),
            size = Size(56.dp.toPx(), 58.dp.toPx()),
            cornerRadius = CornerRadius(20.dp.toPx(), 20.dp.toPx()),
        )
        drawCircle(skin, 24.dp.toPx(), Offset(bodyX, bodyY - 18.dp.toPx()))
        drawRoundRect(
            color = ink,
            topLeft = Offset(bodyX - 22.dp.toPx(), bodyY - 45.dp.toPx()),
            size = Size(44.dp.toPx(), 14.dp.toPx()),
            cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
        )
        drawCircle(ink, 2.8.dp.toPx(), Offset(bodyX - 8.dp.toPx(), bodyY - 18.dp.toPx()))
        drawCircle(ink, 2.8.dp.toPx(), Offset(bodyX + 9.dp.toPx(), bodyY - 18.dp.toPx()))

        val mouth = Path().apply {
            moveTo(bodyX - 9.dp.toPx(), bodyY - 6.dp.toPx())
            if (status == "error") {
                lineTo(bodyX + 9.dp.toPx(), bodyY - 6.dp.toPx())
            } else {
                quadraticBezierTo(bodyX, bodyY + 3.dp.toPx(), bodyX + 10.dp.toPx(), bodyY - 6.dp.toPx())
            }
        }
        drawPath(mouth, ink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        if (status == "finished") {
            listOf(
                Offset(w * 0.16f, h * 0.14f),
                Offset(w * 0.38f, h * 0.16f),
                Offset(w * 0.43f, h * 0.34f),
            ).forEachIndexed { index, offset ->
                drawCircle(ChartColors[(index + 1) % ChartColors.size], (4 + index).dp.toPx(), offset)
            }
        }
        if (status == "error") {
            val warning = Path().apply {
                moveTo(w * 0.84f, h * 0.22f)
                lineTo(w * 0.92f, h * 0.42f)
                lineTo(w * 0.76f, h * 0.42f)
                close()
            }
            drawPath(warning, Color(0xFFFFEDD5))
            drawPath(warning, Color(0xFFF97316), style = Stroke(width = 2.dp.toPx()))
            drawLine(Color(0xFFF97316), Offset(w * 0.84f, h * 0.29f), Offset(w * 0.84f, h * 0.36f), 2.dp.toPx())
            drawCircle(Color(0xFFF97316), 2.dp.toPx(), Offset(w * 0.84f, h * 0.39f))
        }
    }
}


@Composable
private fun MetricGrid(status: TrainingStatus, visibleMetrics: List<String>) {
    if (visibleMetrics.isEmpty()) {
        EmptyCard("还没有指标", "等待训练脚本同步指标后，这里会自动显示。")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        visibleMetrics.chunked(2).forEach { rowMetrics ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowMetrics.forEach { metric ->
                    MetricCard(
                        title = metricDisplayName(metric),
                        current = status.metrics[metric],
                        best = status.bestMetrics[metric],
                        bestEpoch = status.bestEpochs[metric],
                        modifier = if (rowMetrics.size == 1) {
                            Modifier.fillMaxWidth()
                        } else {
                            Modifier.weight(1f)
                        },
                    )
                }
            }
        }
    }
}


@Composable
private fun MetricCard(
    title: String,
    current: Double?,
    best: Double?,
    bestEpoch: Int?,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = Color(0xFF6B7280), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = formatMetric(current),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Best ${formatMetric(best)}${bestEpoch?.let { " @ epoch $it" } ?: ""}",
                color = Color(0xFF6B7280),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


@Composable
private fun MiniChartCard(status: TrainingStatus, visibleMetrics: List<String>) {
    val chartMetric = chartMetricsFor(status, visibleMetrics).firstOrNull { metric ->
        status.history.count { it.metrics[metric] != null } >= 2
    }

    ElevatedCard(
        shape = RoundedCornerShape(10.dp),
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("实时曲线", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("${status.history.size} 条记录", color = Color(0xFF6B7280))
            }

            if (chartMetric == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("训练数据积累后会显示曲线", color = Color(0xFF6B7280))
                }
            } else {
                MetricsChart(
                    history = status.history,
                    metrics = listOf(chartMetric),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                )
                ChartLegend(listOf(chartMetric))
            }
        }
    }
}


@Composable
private fun ChartsScreen(
    status: TrainingStatus,
    selectedMetrics: List<String>,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "曲线分析",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text("曲线只显示你在设置中勾选、并且服务器实际检测到的指标。", color = Color(0xFF6B7280))

        val chartMetrics = chooseChartMetrics(status, selectedMetrics).filter { metric ->
            status.history.count { it.metrics[metric] != null } >= 2
        }
        val selectedAvailable = resolveSelectedMetrics(status, selectedMetrics)

        if (chartMetrics.isEmpty()) {
            ElevatedCard(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (selectedMetrics.isEmpty()) {
                            "请先到设置中选择要显示的指标"
                        } else {
                            "当前选择的指标还没有足够历史数据"
                        },
                        color = Color(0xFF6B7280),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            chartMetrics.forEach { metric ->
                ElevatedCard(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "${metricDisplayName(metric)} Trend",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (selectedAvailable.size > 1) {
                            Text(
                                text = "已选择：${selectedAvailable.joinToString(" / ") { metricDisplayName(it) }}",
                                color = Color(0xFF6B7280),
                            )
                        }
                        MetricsChart(
                            history = status.history,
                            metrics = listOf(metric),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                        )
                        ChartLegend(listOf(metric))
                    }
                }
            }
        }
    }
}


@Composable
private fun MetricsChart(
    history: List<HistoryPoint>,
    metrics: List<String>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(107, 114, 128)
            textSize = 10.dp.toPx()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val pointsByMetric = metrics.associateWith { metric ->
            history.mapNotNull { point ->
                point.metrics[metric]?.let { point.epoch to it }
            }
        }
        val allEpochs = pointsByMetric.values.flatten().map { it.first }
        val allValues = pointsByMetric.values.flatten().map { it.second }
        if (allValues.isEmpty()) return@Canvas

        val left = 52.dp.toPx()
        val right = size.width - 16.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = size.height - 34.dp.toPx()
        val minEpoch = allEpochs.minOrNull() ?: 0
        val maxEpoch = allEpochs.maxOrNull() ?: max(1, minEpoch + 1)
        val epochSpan = max(1, maxEpoch - minEpoch)
        val rawMinValue = allValues.minOrNull() ?: 0.0
        val rawMaxValue = allValues.maxOrNull() ?: 1.0
        val valuePadding = max(abs(rawMaxValue - rawMinValue) * 0.08, 0.000001)
        val minValue = rawMinValue - valuePadding
        val maxValue = rawMaxValue + valuePadding
        val valueSpan = max(abs(maxValue - minValue), 0.000001)

        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            val value = maxValue - (maxValue - minValue) * index / 3.0
            drawLine(
                color = Color(0xFFE5E7EB),
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 1.dp.toPx(),
            )
            drawContext.canvas.nativeCanvas.drawText(
                compactNumber(value),
                4.dp.toPx(),
                y + 4.dp.toPx(),
                textPaint,
            )
        }

        drawLine(Color(0xFFD1D5DB), Offset(left, top), Offset(left, bottom), 1.dp.toPx())
        drawLine(Color(0xFFD1D5DB), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())
        val midEpoch = minEpoch + epochSpan / 2
        listOf(minEpoch, midEpoch, maxEpoch).distinct().forEach { epoch ->
            val x = left + (right - left) * ((epoch - minEpoch).toFloat() / epochSpan.toFloat())
            drawContext.canvas.nativeCanvas.drawText(
                epoch.toString(),
                x - 8.dp.toPx(),
                size.height - 8.dp.toPx(),
                textPaint,
            )
        }

        metrics.forEachIndexed { index, metric ->
            val points = pointsByMetric[metric].orEmpty()
            if (points.size < 2) return@forEachIndexed

            val path = Path()

            points.forEachIndexed { pointIndex, (epoch, value) ->
                val x = left + (right - left) * ((epoch - minEpoch).toFloat() / epochSpan.toFloat())
                val normalized = ((value - minValue) / valueSpan).toFloat().coerceIn(0f, 1f)
                val y = bottom - (bottom - top) * normalized
                if (pointIndex == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = ChartColors[index % ChartColors.size],
                style = Stroke(
                    width = if (points.size > 120) 1.6.dp.toPx() else 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            if (points.size <= 240) {
                val pointRadius = if (points.size > 120) 1.1.dp.toPx() else 1.8.dp.toPx()
                points.forEach { (epoch, value) ->
                    val x = left + (right - left) * ((epoch - minEpoch).toFloat() / epochSpan.toFloat())
                    val normalized = ((value - minValue) / valueSpan).toFloat().coerceIn(0f, 1f)
                    val y = bottom - (bottom - top) * normalized
                    drawCircle(
                        color = ChartColors[index % ChartColors.size],
                        radius = pointRadius,
                        center = Offset(x, y),
                    )
                }
            }
        }
    }
}


@Composable
private fun ChartLegend(metrics: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { index, metric ->
                    val realIndex = metrics.indexOf(metric).coerceAtLeast(index)
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = ChartColors[realIndex % ChartColors.size],
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.size(10.dp),
                            content = {},
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = metricDisplayName(metric),
                            color = Color(0xFF6B7280),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}


@Composable
private fun SettingsScreen(
    draftUrl: String,
    draftToken: String,
    refreshSeconds: Int,
    metricOptions: List<String>,
    selectedMetrics: List<String>,
    notificationEnabled: Boolean,
    testMessage: String,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onRefreshSecondsChange: (Int) -> Unit,
    onMetricToggle: (String) -> Unit,
    onNotificationEnabledChange: (Boolean) -> Unit,
    onClearCachedData: () -> Unit,
    onClearToken: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    val context = LocalContext.current
    var showPrivacyPolicy by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text("配置服务器连接、刷新频率和首页指标。", color = Color(0xFF6B7280))

        SettingsCard(title = "服务器连接") {
            OutlinedTextField(
                value = draftUrl,
                onValueChange = onUrlChange,
                label = { Text("后端地址") },
                placeholder = { Text("例如 10.0.0.2:6006 或 https://example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (normalizeBaseUrl(draftUrl).startsWith("http://")) {
                Text(
                    "当前使用 HTTP，适合内网或临时测试；公网长期使用建议配置 HTTPS，避免 Token 在网络中被截获。",
                    color = Color(0xFFB45309),
                )
            }
            OutlinedTextField(
                value = draftToken,
                onValueChange = onTokenChange,
                label = { Text("访问 Token") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onSave) {
                    Text("保存")
                }
                OutlinedButton(onClick = onTest) {
                    Text("测试连接")
                }
            }
            if (testMessage.isNotBlank()) {
                Text(testMessage, color = Color(0xFF6B7280))
            }
        }

        SettingsCard(title = "实时刷新") {
            Text("刷新间隔", color = Color(0xFF6B7280))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 5, 10).forEach { seconds ->
                    MetricChip(
                        text = "${seconds}秒",
                        selected = refreshSeconds == seconds,
                        onClick = { onRefreshSecondsChange(seconds) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        SettingsCard(title = "通知提醒") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNotificationEnabledChange(!notificationEnabled) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("通知栏训练状态", fontWeight = FontWeight.SemiBold)
                    Text(
                        "开启后会用前台进度通知显示 epoch、Best 指标和 ETA；训练完成时提醒。锁屏和荣耀灵动胶囊是否展示由系统通知设置决定。",
                        color = Color(0xFF6B7280),
                    )
                }
                Checkbox(
                    checked = notificationEnabled,
                    onCheckedChange = onNotificationEnabledChange,
                )
            }
            Text(
                "通知中显示的指标来自“显示指标”的前 1-2 个；不选择时会自动使用主要指标。",
                color = Color(0xFF6B7280),
            )
        }

        SettingsCard(title = "隐私与权限") {
            Text("作者：Zephyer", fontWeight = FontWeight.SemiBold)
            Text(
                "仅使用网络访问你配置的训练监控后端；开启通知后会使用通知和前台服务权限，用于通知栏、锁屏训练状态和训练完成提醒。",
                color = Color(0xFF6B7280),
            )
            Text(
                "本机保存内容包括服务器地址、加密 Token、刷新间隔、勾选指标和最后一次训练状态缓存；不读取通讯录、定位、相册、麦克风或摄像头。",
                color = Color(0xFF6B7280),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onClearCachedData, modifier = Modifier.weight(1f)) {
                    Text("清除训练缓存")
                }
                OutlinedButton(onClick = onClearToken, modifier = Modifier.weight(1f)) {
                    Text("清除 Token")
                }
            }
            OutlinedButton(
                onClick = { showPrivacyPolicy = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("查看隐私政策要点")
            }
        }

        SettingsCard(title = "显示指标") {
            Text("这里只显示服务器已经检测到的指标；勾选后会出现在总览、曲线和通知中。", color = Color(0xFF6B7280))
            if (metricOptions.isEmpty()) {
                Text("还没有检测到指标。训练脚本同步数据后，这里会自动出现可选项。", color = Color(0xFF6B7280))
            } else {
                metricOptions.forEach { metric ->
                    MetricOptionRow(
                        metric = metric,
                        checked = selectedMetrics.contains(metric),
                        onToggle = { onMetricToggle(metric) },
                    )
                }
            }
        }

        SettingsCard(title = "关于应用") {
            Text("训迹 ${appVersionText(context)}", fontWeight = FontWeight.SemiBold)
            Text("作者：Zephyer", color = Color(0xFF6B7280))
            Text("包名：${context.packageName}", color = Color(0xFF6B7280))
            Text(
                "本应用只连接你配置的训练监控后端，Token 加密保存在本机，不采集通讯录、定位、相册等个人信息。",
                color = Color(0xFF6B7280),
            )
            Text(
                "后期申请软著或上架时，可使用名称“训迹”与包名 ${context.packageName} 作为基础信息。",
                color = Color(0xFF6B7280),
            )
        }
    }

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
    }
}


@Composable
private fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("隐私政策要点") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("应用名称：训迹", fontWeight = FontWeight.SemiBold)
                Text("作者：Zephyer")
                Text("包名：com.modeltest.monitor")
                Text("功能用途：连接你配置的训练监控后端，展示训练进度、指标曲线、最佳指标、预计剩余时间和训练完成提醒。")
                Text("收集的信息：服务器地址、访问 Token、刷新间隔、勾选指标和最后一次训练状态缓存。")
                Text("权限使用：网络权限用于访问训练监控后端；通知权限和前台服务用于通知栏、锁屏训练状态和完成提醒。")
                Text("不收集的信息：不读取通讯录、定位、相册、麦克风、摄像头，不采集身份证号、银行卡号等敏感个人信息。")
                Text("存储方式：Token 通过 Android Keystore 加密保存；训练状态缓存只保存在本机。")
                Text("删除方式：可在设置页清除训练缓存、清除本机 Token；服务端可执行 training-monitor rotate-token 重新生成 Token。")
                Text("第三方共享：当前 App 不接入广告 SDK，不向第三方共享个人信息。")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("我知道了")
            }
        },
    )
}


@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}


@Composable
private fun MetricChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = if (selected) Color(0xFFEFF6FF) else Color.White,
        contentColor = if (selected) Color(0xFF2563EB) else Color(0xFF374151),
        shape = RoundedCornerShape(50),
        modifier = modifier.clickable(onClick = onClick),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) Color(0xFF2563EB) else Color(0xFFE5E7EB),
        ),
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


@Composable
private fun MetricOptionRow(metric: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(metricDisplayName(metric), fontWeight = FontWeight.SemiBold)
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
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
private fun EmptyCard(title: String, body: String) {
    ElevatedCard(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color.White),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFF6B7280))
        }
    }
}


@Composable
private fun BottomTabs(current: AppPage, onChange: (AppPage) -> Unit) {
    Surface(color = Color.White, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppPage.entries.forEach { page ->
                MetricChip(
                    text = page.title,
                    selected = current == page,
                    onClick = { onChange(page) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}


private suspend fun fetchStatusJson(client: OkHttpClient, baseUrl: String, token: String): String {
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

            response.body?.string().orEmpty()
        }
    }
}


private fun parseTrainingStatus(json: JSONObject): TrainingStatus {
    val metricName = json.optString("metric_name", "IoU")
    val metrics = json.optJSONObject("metrics").toDoubleMap().toMutableMap()
    val currentMetric = json.optNullableDouble("current_iou")
    if (metrics.isEmpty() && currentMetric != null) {
        metrics[metricName] = currentMetric
    }

    val bestMetrics = json.optJSONObject("best_metrics").toDoubleMap().toMutableMap()
    val bestMetric = json.optNullableDouble("best_iou")
    if (bestMetrics.isEmpty() && bestMetric != null) {
        bestMetrics[metricName] = bestMetric
    }

    val bestEpochs = json.optJSONObject("best_epochs").toIntMap().toMutableMap()
    val bestEpoch = json.optNullableInt("best_epoch")
    if (bestEpochs.isEmpty() && bestEpoch != null) {
        bestEpochs[metricName] = bestEpoch
    }

    val history = parseHistory(json.optJSONArray("history"))
    val availableMetrics = mergeMetricOptions(
        parseStringArray(json.optJSONArray("available_metrics")),
        metrics.keys.toList() + bestMetrics.keys.toList() + history.flatMap { it.metrics.keys },
    )

    return TrainingStatus(
        status = json.optString("status", "idle"),
        epoch = json.optInt("epoch", 0),
        totalEpochs = json.optInt("total_epochs", 0),
        metrics = metrics,
        bestMetrics = bestMetrics,
        bestEpochs = bestEpochs,
        metricName = metricName,
        etaSeconds = json.optNullableLong("eta_seconds"),
        updatedAt = json.optString("updated_at", ""),
        history = history,
        availableMetrics = availableMetrics,
    )
}


private fun parseHistory(array: JSONArray?): List<HistoryPoint> {
    if (array == null) return emptyList()
    val history = mutableListOf<HistoryPoint>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val metricName = item.optString("metric_name", "IoU")
        val metrics = item.optJSONObject("metrics").toDoubleMap().toMutableMap()
        val legacyValue = item.optNullableDouble("iou")
        if (metrics.isEmpty() && legacyValue != null) {
            metrics[metricName] = legacyValue
        }
        history += HistoryPoint(
            epoch = item.optInt("epoch", 0),
            metrics = metrics,
            updatedAt = item.optString("updated_at", ""),
        )
    }
    return history
}


private fun JSONObject?.toDoubleMap(): Map<String, Double> {
    if (this == null) return emptyMap()
    val result = mutableMapOf<String, Double>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (!isNull(key)) {
            result[key] = optDouble(key)
        }
    }
    return result
}


private fun JSONObject?.toIntMap(): Map<String, Int> {
    if (this == null) return emptyMap()
    val result = mutableMapOf<String, Int>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (!isNull(key)) {
            result[key] = optInt(key)
        }
    }
    return result
}


private fun parseStringArray(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (value.isNotBlank()) add(value)
        }
    }
}


private fun JSONObject.optNullableDouble(name: String): Double? {
    return if (has(name) && !isNull(name)) optDouble(name) else null
}


private fun JSONObject.optNullableInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}


private fun JSONObject.optNullableLong(name: String): Long? {
    return if (has(name) && !isNull(name)) optLong(name) else null
}


private fun chooseVisibleMetrics(status: TrainingStatus, selected: List<String>): List<String> {
    val available = status.metricNames()
    val chosen = resolveSelectedMetrics(status, selected)
    if (chosen.isNotEmpty()) return chosen.take(6)

    val defaults = listOf("loss", status.metricName, "mIoU", "IoU", "mAP", "BBox mAP", "Accuracy", "accuracy", "Top1 Acc")
    return (defaults + available).filter { it in available }.distinct().take(4)
}


private fun chooseChartMetrics(status: TrainingStatus, selected: List<String>): List<String> {
    return resolveSelectedMetrics(status, selected).take(6)
}


private fun resolveSelectedMetrics(status: TrainingStatus, selected: List<String>): List<String> {
    val available = status.metricNames()
    return selected.mapNotNull { canonicalMetric(it, available) }.distinct()
}


private fun canonicalMetric(metric: String, available: List<String>): String? {
    if (metric in available) return metric
    val lowered = metric.lowercase(Locale.US)
    return when {
        lowered == "iou" -> available.firstOrNull { it.equals("mIoU", ignoreCase = true) }
        lowered == "miou" -> available.firstOrNull { it.equals("IoU", ignoreCase = true) }
        lowered == "map" -> available.firstOrNull { it.equals("mAP50", ignoreCase = true) }
        else -> null
    }
}


private fun chartMetricsFor(status: TrainingStatus, visibleMetrics: List<String>): List<String> {
    val candidates = visibleMetrics.ifEmpty {
        status.primaryMetric()?.let { listOf(it) } ?: emptyList()
    }
    return candidates.take(1)
}


private fun mergeMetricOptions(first: List<String>, second: List<String> = emptyList()): List<String> {
    return (first + second).filter { it.isNotBlank() }.distinct()
}


private fun parseMetricList(text: String): List<String> {
    return text.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
}


private fun toggleMetric(selected: List<String>, metric: String): String {
    val next = selected.toMutableList()
    if (metric in next) {
        next.remove(metric)
    } else {
        next.add(metric)
    }
    return next.joinToString(",")
}


private fun metricDisplayName(name: String): String {
    return when (name.lowercase(Locale.US)) {
        "loss" -> "Loss"
        "decode.loss_ce" -> "Decode Loss"
        "aux.loss_ce" -> "Aux Loss"
        "bbox_loss" -> "BBox Loss"
        "box_loss" -> "Box Loss"
        "cls_loss" -> "Cls Loss"
        "dfl_loss" -> "DFL Loss"
        "miou" -> "mIoU"
        "iou" -> "IoU"
        "mdice" -> "mDice"
        "macc" -> "mAcc"
        "aacc" -> "aAcc"
        "mfscore" -> "mFscore"
        "map" -> "mAP"
        "map50" -> "mAP50"
        "bbox map" -> "BBox mAP"
        "bbox map50" -> "BBox mAP50"
        "segm map" -> "Segm mAP"
        "segm map50" -> "Segm mAP50"
        "ap" -> "AP"
        "ar" -> "AR"
        "accuracy" -> "Accuracy"
        "top1 acc", "top1_acc", "top1" -> "Top1 Acc"
        "top5 acc", "top5_acc", "top5" -> "Top5 Acc"
        "precision" -> "Precision"
        "recall" -> "Recall"
        "pck" -> "PCK"
        "auc" -> "AUC"
        "hmean" -> "Hmean"
        "psnr" -> "PSNR"
        "ssim" -> "SSIM"
        "fid" -> "FID"
        "is" -> "IS"
        "mota" -> "MOTA"
        "motp" -> "MOTP"
        "idf1" -> "IDF1"
        "idp" -> "IDP"
        "idr" -> "IDR"
        "word acc", "word_acc" -> "Word Acc"
        "mean class accuracy", "mean_class_accuracy" -> "Mean Class Acc"
        "mae" -> "MAE"
        "rmse" -> "RMSE"
        "nme" -> "NME"
        "epe" -> "EPE"
        else -> name
    }
}


private fun compactNumber(value: Double): String {
    val absValue = abs(value)
    return when {
        absValue >= 100 -> String.format(Locale.US, "%.0f", value)
        absValue >= 10 -> String.format(Locale.US, "%.1f", value)
        absValue >= 1 -> String.format(Locale.US, "%.2f", value)
        else -> String.format(Locale.US, "%.3f", value)
    }
}


private fun formatMetric(value: Double?): String {
    if (value == null) return "--"
    val absValue = abs(value)
    return when {
        absValue >= 100 -> String.format(Locale.US, "%.1f", value)
        absValue >= 10 -> String.format(Locale.US, "%.2f", value)
        else -> String.format(Locale.US, "%.4f", value)
    }
}


private fun formatEta(seconds: Long?): String {
    if (seconds == null) return "--"
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${secs}秒"
        else -> "${secs}秒"
    }
}


private fun statusText(status: String): String {
    return when (status) {
        "training" -> "训练中"
        "finished" -> "已完成"
        "error" -> "异常"
        else -> "空闲"
    }
}


private fun syncText(status: TrainingStatus, error: String?, isRefreshing: Boolean): String {
    if (error != null) return "连接失败：$error"
    if (isRefreshing) return "正在同步最新数据..."
    if (status.updatedAt.isBlank()) return "等待训练数据"
    return "实时同步中 · 上次更新 ${status.updatedAt.replace("T", " ")}"
}


private fun normalizeBaseUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    val lowered = trimmed.lowercase(Locale.US)
    return if (lowered.startsWith("http://") || lowered.startsWith("https://")) {
        trimmed
    } else {
        "http://$trimmed"
    }
}


private fun appVersionText(context: Context): String {
    return runCatching {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        val versionName = packageInfo.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
        "v$versionName ($versionCode)"
    }.getOrDefault("")
}


private fun loadBaseUrl(context: Context): String {
    return settingsPreferences(context)
        .getString("base_url", "http://10.0.2.2:6006")
        ?: "http://10.0.2.2:6006"
}


private fun saveBaseUrl(context: Context, baseUrl: String) {
    settingsPreferences(context)
        .edit()
        .putString("base_url", baseUrl)
        .apply()
}


private fun loadToken(context: Context): String {
    val secureToken = secureSettingsPreferences(context)
        .getString(TokenKey, "")
        ?: ""
    if (secureToken.isNotBlank()) return secureToken

    val legacyToken = settingsPreferences(context).getString(TokenKey, "") ?: ""
    if (legacyToken.isNotBlank()) {
        saveToken(context, legacyToken)
    }
    return legacyToken
}


private fun saveToken(context: Context, token: String) {
    secureSettingsPreferences(context)
        .edit()
        .putString(TokenKey, token)
        .apply()

    settingsPreferences(context)
        .edit()
        .remove(TokenKey)
        .apply()
}


private fun loadCachedStatus(context: Context): TrainingStatus? {
    val raw = settingsPreferences(context)
        .getString("cached_status_json", null)
        ?: return null
    return runCatching { parseTrainingStatus(JSONObject(raw)) }.getOrNull()
}


private fun saveCachedStatus(context: Context, raw: String) {
    settingsPreferences(context)
        .edit()
        .putString("cached_status_json", raw)
        .apply()
}


private fun clearCachedStatus(context: Context) {
    settingsPreferences(context)
        .edit()
        .remove("cached_status_json")
        .apply()
}


private fun loadRefreshSeconds(context: Context): Int {
    return settingsPreferences(context)
        .getInt("refresh_seconds", 2)
        .coerceIn(1, 60)
}


private fun saveRefreshSeconds(context: Context, seconds: Int) {
    settingsPreferences(context)
        .edit()
        .putInt("refresh_seconds", seconds)
        .apply()
}


private fun loadSelectedMetricsText(context: Context): String {
    return settingsPreferences(context)
        .getString("selected_metrics", "")
        ?: ""
}


private fun saveSelectedMetricsText(context: Context, value: String) {
    settingsPreferences(context)
        .edit()
        .putString("selected_metrics", value)
        .apply()
}


private fun loadNotificationEnabled(context: Context): Boolean {
    return settingsPreferences(context).getBoolean(NotificationEnabledKey, false)
}


private fun saveNotificationEnabled(context: Context, enabled: Boolean) {
    settingsPreferences(context)
        .edit()
        .putBoolean(NotificationEnabledKey, enabled)
        .apply()
}


private fun settingsPreferences(context: Context): SharedPreferences {
    return context.getSharedPreferences(SettingsPrefs, Context.MODE_PRIVATE)
}


@Suppress("DEPRECATION")
private fun secureSettingsPreferences(context: Context): SharedPreferences {
    return runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SecureSettingsPrefs,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        settingsPreferences(context)
    }
}


private fun startTrainingNotificationService(context: Context) {
    if (!hasNotificationPermission(context)) return
    val intent = Intent(context, TrainingNotificationService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
}


private fun stopTrainingNotificationService(context: Context) {
    context.stopService(Intent(context, TrainingNotificationService::class.java))
}


private fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}


private fun createNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    notificationManager(context).createNotificationChannel(
        NotificationChannel(
            TrainingChannelId,
            "训练状态与锁屏",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "训练进行中的常驻状态、锁屏进度和系统胶囊候选通知"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        },
    )
    notificationManager(context).createNotificationChannel(
        NotificationChannel(
            TrainingFinishedChannelId,
            "训练完成提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "模型训练完成时提醒"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        },
    )
}


private fun buildTrainingNotification(
    context: Context,
    status: TrainingStatus,
    content: String,
): Notification {
    val notification = Notification.Builder(context, TrainingChannelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFF2563EB.toInt())
        .setContentTitle(notificationTitle(status))
        .setContentText(content)
        .setStyle(Notification.BigTextStyle().bigText(content))
        .setContentIntent(mainActivityPendingIntent(context))
        .setOngoing(status.status == "training")
        .setOnlyAlertOnce(true)
        .setShowWhen(true)
        .setWhen(System.currentTimeMillis())
        .setPriority(Notification.PRIORITY_HIGH)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_PROGRESS)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
    }

    if (status.totalEpochs > 0) {
        notification.setProgress(status.totalEpochs, status.epoch.coerceAtMost(status.totalEpochs), false)
    } else {
        notification.setProgress(0, 0, true)
    }

    return notification.build()
}


private fun buildFinishedNotification(context: Context, status: TrainingStatus): Notification {
    val content = notificationSummary(context, status)
    return Notification.Builder(context, TrainingFinishedChannelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFF16A34A.toInt())
        .setContentTitle("训练完成")
        .setContentText(content)
        .setStyle(Notification.BigTextStyle().bigText(content))
        .setContentIntent(mainActivityPendingIntent(context))
        .setAutoCancel(true)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_STATUS)
        .build()
}


private fun mainActivityPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}


private fun notificationManager(context: Context): NotificationManager {
    return context.getSystemService(NotificationManager::class.java)
}


private fun notificationTitle(status: TrainingStatus): String {
    val epochText = if (status.totalEpochs > 0) {
        "${status.epoch}/${status.totalEpochs}"
    } else {
        status.epoch.takeIf { it > 0 }?.toString() ?: "--"
    }
    return when (status.status) {
        "training" -> "训练中 · Epoch $epochText"
        "finished" -> "训练完成 · Epoch $epochText"
        "error" -> "训练异常 · Epoch $epochText"
        else -> "等待训练数据"
    }
}


private fun notificationSummary(context: Context, status: TrainingStatus): String {
    val metrics = notificationMetrics(context, status)
    val metricText = metrics.joinToString(" · ") { metric ->
        val best = status.bestMetrics[metric]
        val bestEpoch = status.bestEpochs[metric]
        if (best != null) {
            "Best ${metricDisplayName(metric)} ${formatMetric(best)}${bestEpoch?.let { " @ $it" } ?: ""}"
        } else {
            "${metricDisplayName(metric)} ${formatMetric(status.metrics[metric])}"
        }
    }
    val etaText = status.etaSeconds?.let { " · ETA ${formatEta(it)}" } ?: ""
    val summary = listOfNotNull(
        metricText.ifBlank { null },
        if (status.totalEpochs > 0) "Epoch ${status.epoch}/${status.totalEpochs}" else null,
    ).joinToString(" · ") + etaText
    return summary.ifBlank { "等待训练数据" }
}


private fun notificationMetrics(context: Context, status: TrainingStatus): List<String> {
    val selected = parseMetricList(loadSelectedMetricsText(context))
    val available = status.metricNames()
    val preferredBest = listOf("mIoU", "IoU", "mAP", "BBox mAP", "Segm mAP", "mAP50", "Accuracy", "accuracy", "Top1 Acc")
        .firstNotNullOfOrNull { canonicalMetric(it, available) }
        ?: status.primaryMetric()
    val selectedMetrics = resolveSelectedMetrics(status, selected)
    val visible = chooseVisibleMetrics(status, selected)
    val metrics = (listOfNotNull(preferredBest) + selectedMetrics + visible).distinct()
    return metrics.take(2)
}
