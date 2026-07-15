package com.modeltest.monitor

import android.Manifest
import android.app.Activity
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
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
import java.net.URI
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
private const val HuaweiWatchSyncEnabledKey = "huawei_watch_sync_enabled"
private const val FinishedNotificationSignatureKey = "finished_notification_signature"
private const val PrivacyAcceptedKey = "privacy_accepted"
private const val UiStyleKey = "ui_style"
private const val PrivacyPolicyUrl = "https://github.com/ZephYer8/training-monitor/blob/main/PRIVACY.md"
private const val TrainingNotificationId = 4100
private const val TrainingFinishedNotificationId = 4101
private const val TrainingChannelId = "training_status_lock_screen_v2"
private const val TrainingFinishedChannelId = "training_finished_v2"
private const val AllGpuFilter = "all"


class TrainingNotificationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()
    private var loopJob: Job? = null
    private var lastStatus: String? = null
    private var lastTrainingStatus: TrainingStatus? = null
    private var nonTrainingPolls = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasNotificationPermission(this)) {
            removeActiveTrainingNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        val cached = loadCachedStatus(this)
        if (loadBaseUrl(this).isBlank() || cached == null || !shouldShowTrainingNotification(cached)) {
            removeActiveTrainingNotification()
            stopSelf()
            return START_NOT_STICKY
        }

        lastStatus = cached.status
        lastTrainingStatus = cached
        startForeground(
            TrainingNotificationId,
            buildTrainingNotification(
                context = this,
                status = cached,
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
            val delaySeconds = loadRefreshSeconds(this@TrainingNotificationService).coerceIn(5, 30)
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

                if (shouldShowTrainingNotification(status)) {
                    clearFinishedNotificationSignature(this@TrainingNotificationService)
                    lastTrainingStatus = status
                    nonTrainingPolls = 0
                    updateTrainingNotification(status)
                } else {
                    if (status.status == "finished") {
                        maybeShowFinishedTrainingNotification(this@TrainingNotificationService, status)
                    } else if (lastTrainingStatus != null && nonTrainingPolls < 24) {
                        nonTrainingPolls += 1
                        updateTrainingNotification(
                            lastTrainingStatus!!,
                            notificationRecoveringText(this@TrainingNotificationService, lastTrainingStatus!!),
                        )
                        lastStatus = status.status
                        delay(delaySeconds * 1_000L)
                        continue
                    }
                    lastStatus = status.status
                    removeActiveTrainingNotification()
                    stopSelf()
                    return
                }
                lastStatus = status.status
            } catch (exc: Exception) {
                val fallback = lastTrainingStatus
                    ?: loadCachedStatus(this@TrainingNotificationService)?.takeIf { shouldShowTrainingNotification(it) }

                if (fallback == null) {
                    lastStatus = "error"
                    removeActiveTrainingNotification()
                    stopSelf()
                    return
                }

                lastStatus = "training"
                updateTrainingNotification(
                    fallback,
                    notificationRetryText(this@TrainingNotificationService, fallback),
                )
            }

            delay(delaySeconds * 1_000L)
        }
    }

    private fun updateTrainingNotification(status: TrainingStatus, contentOverride: String? = null) {
        notificationManager(this).notify(
            TrainingNotificationId,
            buildTrainingNotification(this, status, contentOverride),
        )
    }

    private fun removeActiveTrainingNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        notificationManager(this).cancel(TrainingNotificationId)
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
    val runId: String? = null,
    val status: String = "idle",
    val gpuIds: List<String> = emptyList(),
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
    val runs: List<TrainingStatus> = emptyList(),
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

    fun latestMetricValue(metric: String): Double? {
        metrics[metric]?.let { return it }
        for (point in history.asReversed()) {
            point.metrics[metric]?.let { return it }
        }
        return null
    }

    fun selectableGpuIds(): List<String> {
        if (runs.size <= 1) return emptyList()
        return runs.flatMap { it.gpuIds }.filter { it.isNotBlank() }.distinct().sortedWith(gpuIdComparator())
    }

    fun statusForGpu(gpuId: String): TrainingStatus {
        if (gpuId == AllGpuFilter) return this
        return runs.firstOrNull { gpuId in it.gpuIds } ?: this
    }

    fun runDisplayName(): String {
        val name = runId?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
        return name ?: gpuLabel(gpuIds).takeIf { it.isNotBlank() } ?: "当前任务"
    }
}


private val BrandBlue = Color(0xFF155EEF)
private val BrandGreen = Color(0xFF12B76A)
private val Ink = Color(0xFF101828)
private val MutedInk = Color(0xFF667085)
private val AppBackground = Color(0xFFF7F9FC)
private val Hairline = Color(0xFFE4E7EC)
private val SoftBlue = Color(0xFFEFF4FF)

private val AppColors = lightColorScheme(
    primary = BrandBlue,
    secondary = BrandGreen,
    tertiary = Color(0xFFF79009),
    background = AppBackground,
    surface = Color.White,
    surfaceVariant = Color(0xFFF2F4F7),
    onSurface = Ink,
    onSurfaceVariant = MutedInk,
    outline = Color(0xFFD0D5DD),
    error = Color(0xFFD92D20),
)

private val ChartColors = listOf(
    BrandBlue,
    BrandGreen,
    Color(0xFFF79009),
    Color(0xFFF04438),
    Color(0xFF06AED4),
    Color(0xFF7A5AF8),
)


private fun glassPanelColor(glassStyle: Boolean): Color {
    return if (glassStyle) Color(0xE8FFFFFF) else Color.White
}


private fun glassFieldColor(glassStyle: Boolean): Color {
    return if (glassStyle) Color(0xA8FFFFFF) else Color.White
}


private fun Modifier.glassMaterial(glassStyle: Boolean, radius: Dp): Modifier {
    if (!glassStyle) return this
    return drawWithContent {
        val corner = CornerRadius(radius.toPx(), radius.toPx())
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.22f),
                    Color.White.copy(alpha = 0.06f),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = size.height * 0.75f,
            ),
            cornerRadius = corner,
        )
        drawContent()
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.76f),
                    Color.White.copy(alpha = 0.18f),
                    Color(0xFF93C5FD).copy(alpha = 0.18f),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}


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

    var savedUrl by rememberSaveable { mutableStateOf(loadBaseUrl(context)) }
    var savedToken by rememberSaveable { mutableStateOf(loadToken(context)) }
    var page by rememberSaveable {
        mutableStateOf(if (savedUrl.isBlank()) AppPage.Settings.name else AppPage.Dashboard.name)
    }
    var draftUrl by rememberSaveable { mutableStateOf(savedUrl) }
    var draftToken by rememberSaveable { mutableStateOf(savedToken) }
    var refreshSeconds by rememberSaveable { mutableIntStateOf(loadRefreshSeconds(context)) }
    var selectedMetricsText by rememberSaveable { mutableStateOf(loadSelectedMetricsText(context)) }
    var notificationEnabled by rememberSaveable { mutableStateOf(loadNotificationEnabled(context)) }
    var huaweiWatchSyncEnabled by rememberSaveable { mutableStateOf(loadHuaweiWatchSyncEnabled(context)) }
    var privacyAccepted by rememberSaveable { mutableStateOf(loadPrivacyAccepted(context)) }
    var uiStyle by rememberSaveable { mutableStateOf(loadUiStyle(context)) }
    var status by remember { mutableStateOf(loadCachedStatus(context) ?: TrainingStatus()) }
    var selectedGpuId by rememberSaveable { mutableStateOf(AllGpuFilter) }
    var hasFreshStatus by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var testMessage by rememberSaveable { mutableStateOf("") }
    var pendingHuaweiWatchEnable by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            notificationEnabled = true
            saveNotificationEnabled(context, true)
            if (pendingHuaweiWatchEnable) {
                huaweiWatchSyncEnabled = true
                saveHuaweiWatchSyncEnabled(context, true)
                testMessage = "华为手表同步已开启，请在华为运动健康里允许“模迹”通知同步到 FIT 4"
            } else {
                testMessage = "通知已开启，训练中才会显示到通知栏"
            }
            pendingHuaweiWatchEnable = false
            syncTrainingNotificationService(context, status, privacyAccepted)
        } else {
            notificationEnabled = false
            saveNotificationEnabled(context, false)
            if (pendingHuaweiWatchEnable) {
                huaweiWatchSyncEnabled = false
                saveHuaweiWatchSyncEnabled(context, false)
            }
            pendingHuaweiWatchEnable = false
            stopTrainingNotificationService(context)
            testMessage = "未授予通知权限，通知未开启"
        }
    }

    suspend fun refreshOnce(url: String = savedUrl, token: String = savedToken) {
        val normalizedUrl = normalizeBaseUrl(url)
        val validationError = serverUrlValidationError(normalizedUrl)
        if (validationError != null) {
            error = validationError
            hasFreshStatus = false
            stopTrainingNotificationService(context)
            return
        }

        isRefreshing = true
        try {
            val raw = fetchStatusJson(client, normalizedUrl, token)
            val nextStatus = parseTrainingStatus(JSONObject(raw))
            status = nextStatus
            hasFreshStatus = true
            saveCachedStatus(context, raw)
            if (nextStatus.status == "training") {
                clearFinishedNotificationSignature(context)
            }
            if (notificationEnabled && privacyAccepted && nextStatus.status == "finished") {
                maybeShowFinishedTrainingNotification(context, nextStatus)
            }
            syncTrainingNotificationService(context, nextStatus, notificationEnabled && privacyAccepted)
            error = null
        } catch (exc: Exception) {
            error = exc.message ?: "连接失败"
            hasFreshStatus = false
            val cachedTraining = loadCachedStatus(context)?.takeIf { shouldShowTrainingNotification(it) }
            if (notificationEnabled && privacyAccepted && cachedTraining != null) {
                startTrainingNotificationService(context)
            } else {
                stopTrainingNotificationService(context)
            }
        } finally {
            isRefreshing = false
        }
    }

    LaunchedEffect(savedUrl, savedToken, refreshSeconds, privacyAccepted) {
        if (!privacyAccepted) return@LaunchedEffect
        while (true) {
            refreshOnce()
            delay(refreshSeconds * 1_000L)
        }
    }

    LaunchedEffect(
        notificationEnabled,
        huaweiWatchSyncEnabled,
        savedUrl,
        savedToken,
        selectedMetricsText,
        refreshSeconds,
        privacyAccepted,
        status.status,
        status.epoch,
        status.totalEpochs,
        hasFreshStatus,
    ) {
        syncTrainingNotificationService(context, status, notificationEnabled && privacyAccepted)
    }

    val gpuOptions = status.selectableGpuIds()
    LaunchedEffect(status.runs, status.gpuIds) {
        if (selectedGpuId != AllGpuFilter && selectedGpuId !in gpuOptions) {
            selectedGpuId = AllGpuFilter
        }
    }
    val displayStatus = status.statusForGpu(selectedGpuId)
    val selectedMetrics = parseMetricList(selectedMetricsText)
    val visibleMetrics = chooseVisibleMetrics(displayStatus, selectedMetrics)
    val metricOptions = mergeMetricOptions(status.metricNames() + status.runs.flatMap { it.metricNames() }, selectedMetrics)
    val currentPage = AppPage.valueOf(page)
    val glassStyle = uiStyle == "glass"

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        AppBackdrop(glassStyle)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when (currentPage) {
                    AppPage.Dashboard -> DashboardScreen(
                        status = displayStatus,
                        rootStatus = status,
                        selectedGpuId = selectedGpuId,
                        gpuOptions = gpuOptions,
                        error = error,
                        isRefreshing = isRefreshing,
                        visibleMetrics = visibleMetrics,
                        glassStyle = glassStyle,
                        onGpuSelected = { selectedGpuId = it },
                        onRefresh = { scope.launch { refreshOnce() } },
                        onOpenSettings = { page = AppPage.Settings.name },
                    )

                AppPage.Charts -> ChartsScreen(
                    status = displayStatus,
                    rootStatus = status,
                    selectedGpuId = selectedGpuId,
                    gpuOptions = gpuOptions,
                    selectedMetrics = selectedMetrics,
                    glassStyle = glassStyle,
                    onGpuSelected = { selectedGpuId = it },
                )

                    AppPage.Settings -> SettingsScreen(
                        draftUrl = draftUrl,
                        draftToken = draftToken,
                        refreshSeconds = refreshSeconds,
                        metricOptions = metricOptions,
                        selectedMetrics = selectedMetrics,
                        notificationEnabled = notificationEnabled,
                        huaweiWatchSyncEnabled = huaweiWatchSyncEnabled,
                        uiStyle = uiStyle,
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
                                    syncTrainingNotificationService(context, status, privacyAccepted)
                                    testMessage = "通知已开启，训练中才会显示到通知栏"
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    pendingHuaweiWatchEnable = false
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            } else {
                                notificationEnabled = false
                                huaweiWatchSyncEnabled = false
                                saveNotificationEnabled(context, false)
                                saveHuaweiWatchSyncEnabled(context, false)
                                stopTrainingNotificationService(context)
                                testMessage = "通知已关闭"
                            }
                        },
                        onHuaweiWatchSyncChange = {
                            if (it) {
                                if (hasNotificationPermission(context)) {
                                    notificationEnabled = true
                                    huaweiWatchSyncEnabled = true
                                    saveNotificationEnabled(context, true)
                                    saveHuaweiWatchSyncEnabled(context, true)
                                    syncTrainingNotificationService(context, status, privacyAccepted)
                                    testMessage = "华为手表同步已开启，请在华为运动健康里允许“模迹”通知同步到 FIT 4"
                                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    pendingHuaweiWatchEnable = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            } else {
                                huaweiWatchSyncEnabled = false
                                saveHuaweiWatchSyncEnabled(context, false)
                                testMessage = "华为手表同步已关闭"
                            }
                        },
                        onUiStyleChange = {
                            uiStyle = it
                            saveUiStyle(context, it)
                        },
                        onClearCachedData = {
                            clearCachedStatus(context)
                            status = TrainingStatus()
                            hasFreshStatus = false
                            error = null
                            testMessage = "本地训练缓存已清除"
                        },
                        onClearToken = {
                            savedToken = ""
                            draftToken = ""
                            saveToken(context, "")
                            notificationEnabled = false
                            huaweiWatchSyncEnabled = false
                            saveNotificationEnabled(context, false)
                            saveHuaweiWatchSyncEnabled(context, false)
                            stopTrainingNotificationService(context)
                            testMessage = "Token 已清除，通知已停止"
                        },
                        onWithdrawPrivacyConsent = {
                            privacyAccepted = false
                            savePrivacyAccepted(context, false)
                            notificationEnabled = false
                            huaweiWatchSyncEnabled = false
                            saveNotificationEnabled(context, false)
                            saveHuaweiWatchSyncEnabled(context, false)
                            stopTrainingNotificationService(context)
                            testMessage = "已撤回同意，应用将停止刷新和通知"
                        },
                        onSave = save@{
                            val nextUrl = normalizeBaseUrl(draftUrl)
                            if (nextUrl.isBlank()) {
                                testMessage = "请先填写后端地址"
                                return@save
                            }
                            serverUrlValidationError(nextUrl)?.let {
                                testMessage = it
                                return@save
                            }
                            if (draftToken.isBlank()) {
                                testMessage = "请填写访问 Token"
                                return@save
                            }
                            savedUrl = nextUrl
                            draftUrl = savedUrl
                            savedToken = draftToken.trim()
                            saveBaseUrl(context, savedUrl)
                            saveToken(context, savedToken)
                            hasFreshStatus = false
                            stopTrainingNotificationService(context)
                            error = null
                            testMessage = "设置已保存，正在连接..."
                            page = AppPage.Dashboard.name
                        },
                        onTest = {
                            scope.launch {
                                val testUrl = normalizeBaseUrl(draftUrl)
                                if (testUrl.isBlank()) {
                                    testMessage = "请先填写后端地址"
                                    return@launch
                                }
                                serverUrlValidationError(testUrl)?.let {
                                    testMessage = it
                                    return@launch
                                }
                                if (draftToken.isBlank()) {
                                    testMessage = "请填写访问 Token"
                                    return@launch
                                }
                                testMessage = "正在测试连接..."
                                runCatching {
                                    val raw = fetchStatusJson(client, testUrl, draftToken.trim())
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
                glassStyle = glassStyle,
                onChange = { page = it.name },
            )
        }

        if (!privacyAccepted) {
            PrivacyConsentDialog(
                onAccept = {
                    privacyAccepted = true
                    savePrivacyAccepted(context, true)
                    testMessage = "已同意隐私与权限说明"
                },
                onDecline = {
                    savePrivacyAccepted(context, false)
                    stopTrainingNotificationService(context)
                    (context as? Activity)?.finish()
                },
            )
        }
    }
}


@Composable
private fun AppBackdrop(glassStyle: Boolean) {
    val background = if (glassStyle) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFFFBFCFE),
                Color(0xFFEEF4FF),
                Color(0xFFECFDF3),
            ),
            start = Offset.Zero,
            end = Offset(900f, 1600f),
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.background,
                Color(0xFFF2F4F7),
            ),
        )
    }

    Spacer(
        modifier = Modifier
            .fillMaxSize()
            .background(background),
    )
}


@Composable
private fun DashboardScreen(
    status: TrainingStatus,
    rootStatus: TrainingStatus,
    selectedGpuId: String,
    gpuOptions: List<String>,
    error: String?,
    isRefreshing: Boolean,
    visibleMetrics: List<String>,
    glassStyle: Boolean,
    onGpuSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val hasTrainingData = status.epoch > 0 || status.metrics.isNotEmpty() || status.history.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(
            status = status,
            error = error,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
        )
        if (!hasTrainingData) {
            SetupPromptCard(error, glassStyle, onOpenSettings)
        } else {
            GpuSelectorBar(
                rootStatus = rootStatus,
                selectedGpuId = selectedGpuId,
                gpuOptions = gpuOptions,
                glassStyle = glassStyle,
                onGpuSelected = onGpuSelected,
            )
            ProgressHeroCard(status, glassStyle)
            MiniChartCard(status, visibleMetrics, glassStyle)
            val primaryMetric = status.primaryMetric()
            val secondaryMetrics = visibleMetrics.filterNot { it == primaryMetric }
            if (secondaryMetrics.isNotEmpty()) {
                MetricGrid(status, secondaryMetrics, glassStyle)
            }
        }
    }
}


@Composable
private fun SetupPromptCard(error: String?, glassStyle: Boolean, onOpenSettings: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = glassPanelColor(glassStyle),
        border = BorderStroke(1.dp, if (glassStyle) Color(0xCCFFFFFF) else Hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (error == null) "连接训练服务器" else "暂时无法获取训练状态",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = error ?: "填写服务器地址和访问 Token 后，训练进度、指标与趋势会显示在这里。",
                color = if (error == null) MutedInk else Color(0xFFB42318),
            )
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(if (error == null) "开始配置" else "检查连接设置")
            }
        }
    }
}


@Composable
private fun GpuSelectorBar(
    rootStatus: TrainingStatus,
    selectedGpuId: String,
    gpuOptions: List<String>,
    glassStyle: Boolean,
    onGpuSelected: (String) -> Unit,
) {
    if (gpuOptions.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetricChip(
            text = "全部 ${rootStatus.runs.size}",
            selected = selectedGpuId == AllGpuFilter,
            onClick = { onGpuSelected(AllGpuFilter) },
            glassStyle = glassStyle,
        )
        gpuOptions.forEach { gpuId ->
            MetricChip(
                text = "GPU $gpuId",
                selected = selectedGpuId == gpuId,
                onClick = { onGpuSelected(gpuId) },
                glassStyle = glassStyle,
            )
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "模迹",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            StatusPill(status = if (error == null) status.status else "error")
        }
        Text(
            text = status.runDisplayName(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = syncText(status, error, isRefreshing),
                color = if (error == null) MutedInk else Color(0xFFB42318),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !isRefreshing) {
                Text(if (isRefreshing) "刷新中" else "刷新")
            }
        }
    }
}


@Composable
private fun ProgressHeroCard(status: TrainingStatus, glassStyle: Boolean) {
    val hasTotal = status.totalEpochs > 0
    val primaryMetric = status.primaryMetric()
    val current = primaryMetric?.let { status.latestMetricValue(it) }
    val best = primaryMetric?.let { status.bestMetrics[it] }
    val bestEpoch = primaryMetric?.let { status.bestEpochs[it] }
    val progressTrackColor = if (glassStyle) Color(0x78BFDBFE) else Color(0xFFE0E7FF)
    val progressText = if (hasTotal) {
        "${(status.progress * 100).toInt()}%"
    } else {
        "--"
    }
    val epochText = if (hasTotal) {
        "${status.epoch} / ${status.totalEpochs}"
    } else {
        "${status.epoch.takeIf { it > 0 } ?: "--"} / --"
    }
    val etaText = status.etaSeconds?.let { formatEta(it) } ?: "--"
    val metricTitle = primaryMetric?.let { metricDisplayName(it) } ?: "Metric"

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = glassPanelColor(glassStyle),
        border = BorderStroke(1.dp, if (glassStyle) Color(0xCCFFFFFF) else Hairline),
        shadowElevation = if (glassStyle) 3.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .glassMaterial(glassStyle, 12.dp),
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "训练进度",
                        color = MutedInk,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = epochText,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = progressText,
                        color = statusAccent(status.status),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(statusText(status.status), color = MutedInk)
                }
            }

            LinearProgressIndicator(
                progress = { status.progress },
                color = statusAccent(status.status),
                trackColor = progressTrackColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OverviewStat(
                    label = "预计剩余",
                    value = if (hasTotal) etaText else "待估算",
                    modifier = Modifier.weight(1f),
                )
                OverviewStat(
                    label = "当前 $metricTitle",
                    value = formatMetric(current),
                    modifier = Modifier.weight(1f),
                )
                OverviewStat(
                    label = "最佳 $metricTitle",
                    value = formatMetric(best),
                    hint = bestEpoch?.let { "第 $it 轮" },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}


@Composable
private fun OverviewStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            color = MutedInk,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hint != null) {
            Text(
                text = hint,
                color = MutedInk,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}


private fun statusAccent(status: String): Color {
    return when (status) {
        "training" -> BrandBlue
        "finished" -> BrandGreen
        "error" -> Color(0xFFD92D20)
        else -> MutedInk
    }
}


@Composable
private fun MetricGrid(
    status: TrainingStatus,
    visibleMetrics: List<String>,
    glassStyle: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "其他指标",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "显示指标可在设置中随时调整",
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        visibleMetrics.chunked(2).forEach { rowMetrics ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                rowMetrics.forEach { metric ->
                    MetricCard(
                        title = metricDisplayName(metric),
                        current = status.latestMetricValue(metric),
                        best = status.bestMetrics[metric],
                        bestEpoch = status.bestEpochs[metric],
                        glassStyle = glassStyle,
                        modifier = if (rowMetrics.size == 1) Modifier.fillMaxWidth() else Modifier.weight(1f),
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
    glassStyle: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = glassPanelColor(glassStyle),
        border = BorderStroke(1.dp, if (glassStyle) Color(0xCCFFFFFF) else Hairline),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = MutedInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = formatMetric(current),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "最佳 ${formatMetric(best)}${bestEpoch?.let { " · 第 $it 轮" } ?: ""}",
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


@Composable
private fun MiniChartCard(status: TrainingStatus, visibleMetrics: List<String>, glassStyle: Boolean) {
    val chartMetric = chartMetricsFor(status, visibleMetrics).firstOrNull { metric ->
        status.history.count { it.metrics[metric] != null } >= 2
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = glassPanelColor(glassStyle),
        border = BorderStroke(1.dp, if (glassStyle) Color(0xCCFFFFFF) else Hairline),
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
                Text("${status.history.size} 条记录", color = MutedInk)
            }

            if (chartMetric == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("至少同步 2 个 Epoch 后显示趋势", color = MutedInk)
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
    rootStatus: TrainingStatus,
    selectedGpuId: String,
    gpuOptions: List<String>,
    selectedMetrics: List<String>,
    glassStyle: Boolean,
    onGpuSelected: (String) -> Unit,
) {
    val chartMetrics = chooseChartMetrics(status, selectedMetrics).filter { metric ->
        status.history.count { it.metrics[metric] != null } >= 2
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "训练趋势",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${status.history.size} 个历史点 · ${chartMetrics.size} 个可分析指标",
                color = MutedInk,
            )
        }
        GpuSelectorBar(
            rootStatus = rootStatus,
            selectedGpuId = selectedGpuId,
            gpuOptions = gpuOptions,
            glassStyle = glassStyle,
            onGpuSelected = onGpuSelected,
        )

        if (chartMetrics.isEmpty()) {
            EmptyCard(
                title = "暂时没有可绘制的趋势",
                body = if (selectedMetrics.isEmpty()) {
                    "请到设置中选择指标。"
                } else {
                    "所选指标至少同步 2 个 Epoch 后会显示曲线。"
                },
                glassStyle = glassStyle,
            )
        } else {
            chartMetrics.forEach { metric ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = glassPanelColor(glassStyle),
                    border = BorderStroke(1.dp, if (glassStyle) Color(0xCCFFFFFF) else Hairline),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = metricDisplayName(metric),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "${status.history.count { it.metrics[metric] != null }} 点",
                                color = MutedInk,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            OverviewStat(
                                label = "当前",
                                value = formatMetric(status.latestMetricValue(metric)),
                                modifier = Modifier.weight(1f),
                            )
                            OverviewStat(
                                label = "最佳",
                                value = formatMetric(status.bestMetrics[metric]),
                                hint = status.bestEpochs[metric]?.let { "第 $it 轮" },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        MetricsChart(
                            history = status.history,
                            metrics = listOf(metric),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
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
                            color = MutedInk,
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
    huaweiWatchSyncEnabled: Boolean,
    uiStyle: String,
    testMessage: String,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onRefreshSecondsChange: (Int) -> Unit,
    onMetricToggle: (String) -> Unit,
    onNotificationEnabledChange: (Boolean) -> Unit,
    onHuaweiWatchSyncChange: (Boolean) -> Unit,
    onUiStyleChange: (String) -> Unit,
    onClearCachedData: () -> Unit,
    onClearToken: () -> Unit,
    onWithdrawPrivacyConsent: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
    val context = LocalContext.current
    val glassStyle = uiStyle == "glass"
    var showPrivacyPolicy by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text("连接、显示、提醒与本地数据。", color = MutedInk)

        SettingsCard(title = "训练服务器", glassStyle = glassStyle) {
            MonitorTextField(
                value = draftUrl,
                onValueChange = onUrlChange,
                label = { Text("后端地址") },
                placeholder = { Text("10.0.0.2:6006 或 https://example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                glassStyle = glassStyle,
            )
            Text(
                "支持局域网 IP、域名或 HTTPS；不写协议时默认使用 http://。",
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall,
            )
            val draftUrlError = draftUrl.takeIf { it.isNotBlank() }?.let(::serverUrlValidationError)
            if (draftUrlError != null) {
                Text(
                    draftUrlError,
                    color = Color(0xFFB42318),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (normalizeBaseUrl(draftUrl).startsWith("http://")) {
                Text(
                    "局域网 HTTP 已允许；公网地址必须使用 HTTPS。",
                    color = Color(0xFFB54708),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            MonitorTextField(
                value = draftToken,
                onValueChange = onTokenChange,
                label = { Text("访问 Token") },
                placeholder = { Text("从 training-monitor connection 获取") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                glassStyle = glassStyle,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                    Text("保存并连接")
                }
                OutlinedButton(onClick = onTest, modifier = Modifier.weight(1f)) {
                    Text("测试连接")
                }
            }
            if (testMessage.isNotBlank()) {
                ConnectionMessage(testMessage, glassStyle)
            }
        }

        SettingsCard(title = "显示指标", glassStyle = glassStyle) {
            Text("勾选后会同步用于总览、趋势和通知。", color = MutedInk)
            if (metricOptions.isEmpty()) {
                Text("连接训练服务器后，这里会出现检测到的指标。", color = MutedInk)
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

        SettingsCard(title = "显示与刷新", glassStyle = glassStyle) {
            Text("界面风格", color = MutedInk, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricChip(
                    text = "简洁",
                    selected = uiStyle != "glass",
                    onClick = { onUiStyleChange("normal") },
                    modifier = Modifier.weight(1f),
                    glassStyle = glassStyle,
                )
                MetricChip(
                    text = "柔光",
                    selected = uiStyle == "glass",
                    onClick = { onUiStyleChange("glass") },
                    modifier = Modifier.weight(1f),
                    glassStyle = glassStyle,
                )
            }
            Text("刷新间隔", color = MutedInk, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30).forEach { seconds ->
                    MetricChip(
                        text = "${seconds}秒",
                        selected = refreshSeconds == seconds,
                        onClick = { onRefreshSecondsChange(seconds) },
                        modifier = Modifier.weight(1f),
                        glassStyle = glassStyle,
                    )
                }
            }
            Text("间隔越短，状态更新越及时；后台耗电和服务器请求也会增加。", color = MutedInk, style = MaterialTheme.typography.bodySmall)
        }

        SettingsCard(title = "通知提醒", glassStyle = glassStyle) {
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
                        "持续显示 Epoch、最佳指标和 ETA，并在训练完成时提醒。",
                        color = MutedInk,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Checkbox(
                    checked = notificationEnabled,
                    onCheckedChange = onNotificationEnabledChange,
                )
            }
            Text(
                "通知优先显示已勾选的前 1-2 个指标。",
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onHuaweiWatchSyncChange(!huaweiWatchSyncEnabled) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("华为手表同步（FIT 4）", fontWeight = FontWeight.SemiBold)
                    Text(
                        "将通知压缩成适合手表阅读的训练摘要。",
                        color = MutedInk,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Checkbox(
                    checked = huaweiWatchSyncEnabled,
                    onCheckedChange = onHuaweiWatchSyncChange,
                )
            }
            Text(
                "需要在华为运动健康中允许“模迹”通知同步到 FIT 4。",
                color = MutedInk,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SettingsCard(title = "隐私与权限", glassStyle = glassStyle) {
            Text(
                "仅使用网络访问你配置的训练监控后端；开启通知后会使用通知和前台服务权限，用于通知栏、锁屏训练状态和训练完成提醒。",
                color = MutedInk,
            )
            Text(
                "本机保存内容包括服务器地址、加密 Token、刷新间隔、勾选指标和最后一次训练状态缓存；不读取通讯录、定位、相册、麦克风或摄像头。",
                color = MutedInk,
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
                onClick = onWithdrawPrivacyConsent,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("撤回同意并停止使用")
            }
            OutlinedButton(
                onClick = { showPrivacyPolicy = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("查看隐私政策摘要")
            }
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PrivacyPolicyUrl)))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开完整隐私政策")
            }
        }

        SettingsCard(title = "关于我们", glassStyle = glassStyle) {
            Text("模迹 ${appVersionText(context)}", fontWeight = FontWeight.SemiBold)
            Text("作者：Zephyer", color = MutedInk)
            Text("应用标识：${context.packageName}", color = MutedInk)
            Text("项目主页：github.com/ZephYer8/training-monitor", color = MutedInk)
            Text("反馈渠道：GitHub Issues 或应用市场反馈入口", color = MutedInk)
            Text(
                "本应用只连接你配置的训练监控后端，Token 加密保存在本机，不采集通讯录、定位、相册等个人信息。",
                color = MutedInk,
            )
            Text(
                "完整隐私政策可在本页上方打开；正式分发时以应用市场展示的同一政策链接为准。",
                color = MutedInk,
            )
        }
    }

    if (showPrivacyPolicy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacyPolicy = false })
    }
}


@Composable
private fun PrivacyConsentDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("隐私与权限提示") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("欢迎使用模迹。继续使用前，请先了解本应用如何处理数据。")
                Text("本应用只连接你配置的训练监控后端，用于显示训练进度、指标曲线、Best 指标、ETA 和训练完成提醒。")
                Text("本机保存服务器地址、加密 Token、刷新间隔、勾选指标和最后一次训练状态缓存。")
                Text("应用仅使用网络、通知和前台服务权限；不读取通讯录、定位、相册、麦克风或摄像头，不接入广告 SDK。")
                Text("你可以在设置页随时清除训练缓存和本机 Token。")
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("同意并继续")
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text("暂不同意")
            }
        },
    )
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
                Text("应用名称：模迹", fontWeight = FontWeight.SemiBold)
                Text("作者：Zephyer")
                Text("包名：com.modeltest.monitor")
                Text("项目主页：github.com/ZephYer8/training-monitor")
                Text("反馈渠道：GitHub Issues 或应用市场反馈入口")
                Text("完整政策：$PrivacyPolicyUrl")
                Text("功能用途：连接你配置的训练监控后端，展示训练进度、指标曲线、最佳指标、预计剩余时间和训练完成提醒。")
                Text("收集的信息：服务器地址、访问 Token、刷新间隔、勾选指标和最后一次训练状态缓存。")
                Text("权限使用：网络权限用于访问训练监控后端；通知权限和前台服务用于通知栏、锁屏训练状态和完成提醒。")
                Text("不收集的信息：不读取通讯录、定位、相册、麦克风、摄像头，不采集身份证号、银行卡号等敏感个人信息。")
                Text("存储方式：Token 通过 Android Keystore 加密保存；训练状态缓存只保存在本机。")
                Text("删除与撤回：可在设置页清除训练缓存、清除本机 Token，也可撤回同意并停止刷新和通知；服务端可执行 training-monitor rotate-token 重新生成 Token。")
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
private fun MonitorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    placeholder: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    glassStyle: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        supportingText = supportingText,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        singleLine = true,
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = glassFieldColor(glassStyle),
            unfocusedContainerColor = glassFieldColor(glassStyle),
            focusedBorderColor = BrandBlue,
            unfocusedBorderColor = if (glassStyle) Color(0x99FFFFFF) else Color(0xFFD0D5DD),
            focusedLabelColor = BrandBlue,
            cursorColor = BrandBlue,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    )
}


@Composable
private fun ConnectionMessage(message: String, glassStyle: Boolean) {
    val isError = message.startsWith("连接失败")
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isError) Color(0xFFFEF3F2) else if (glassStyle) Color(0xB8FFFFFF) else Color(0xFFF9FAFB),
        border = BorderStroke(1.dp, if (isError) Color(0xFFFDA29B) else if (glassStyle) Color(0xC6FFFFFF) else Hairline),
        modifier = Modifier.glassMaterial(glassStyle, 10.dp),
    ) {
        Text(
            message,
            color = if (isError) Color(0xFFB91C1C) else Color(0xFF475569),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}


@Composable
private fun SettingsCard(title: String, glassStyle: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = glassPanelColor(glassStyle),
        border = BorderStroke(1.dp, if (glassStyle) Color(0xCCFFFFFF) else Hairline),
        shadowElevation = if (glassStyle) 3.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .glassMaterial(glassStyle, 12.dp),
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
private fun MetricChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glassStyle: Boolean = false,
) {
    Surface(
        color = when {
            selected -> SoftBlue
            glassStyle -> Color(0xB8FFFFFF)
            else -> Color.Transparent
        },
        contentColor = if (selected) BrandBlue else Color(0xFF344054),
        shape = RoundedCornerShape(9.dp),
        modifier = modifier
            .clickable(onClick = onClick)
            .glassMaterial(glassStyle, 9.dp),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                selected -> Color(0xFF84ADFF)
                glassStyle -> Color(0xCCFFFFFF)
                else -> Hairline
            },
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
        "training" -> BrandBlue
        "finished" -> BrandGreen
        "error" -> Color(0xFFD92D20)
        else -> MutedInk
    }

    Surface(
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = RoundedCornerShape(50),
        modifier = Modifier.widthIn(min = 76.dp),
    ) {
        Text(
            text = statusText(status),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}


@Composable
private fun EmptyCard(title: String, body: String, glassStyle: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = glassPanelColor(glassStyle),
        border = BorderStroke(1.dp, if (glassStyle) Color(0xCCFFFFFF) else Hairline),
        modifier = Modifier
            .fillMaxWidth()
            .glassMaterial(glassStyle, 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = MutedInk)
        }
    }
}


@Composable
private fun BottomTabs(current: AppPage, glassStyle: Boolean, onChange: (AppPage) -> Unit) {
    Surface(
        color = if (glassStyle) Color(0xE8FFFFFF) else Color.White,
        border = BorderStroke(1.dp, if (glassStyle) Color(0xE6FFFFFF) else Hairline),
        shadowElevation = 4.dp,
        modifier = Modifier.glassMaterial(glassStyle, 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppPage.entries.forEach { page ->
                MetricChip(
                    text = page.title,
                    selected = current == page,
                    onClick = { onChange(page) },
                    modifier = Modifier.weight(1f),
                    glassStyle = glassStyle,
                )
            }
        }
    }
}


private suspend fun fetchStatusJson(client: OkHttpClient, baseUrl: String, token: String): String {
    serverUrlValidationError(baseUrl)?.let { error(it) }
    return withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url("${baseUrl.trim().trimEnd('/')}/api/status?history_limit=120")
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
    return parseTrainingStatus(json, includeRuns = true)
}


private fun parseTrainingStatus(json: JSONObject, includeRuns: Boolean): TrainingStatus {
    val metricName = json.optCleanString("metric_name", "IoU")
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
    val gpuIds = mergeMetricOptions(
        parseStringArray(json.optJSONArray("gpu_ids")),
        listOf(json.optCleanString("gpu_id")),
    )
    val runs = if (includeRuns) parseStatusRuns(json.optJSONArray("runs")) else emptyList()

    return TrainingStatus(
        runId = json.optCleanString("run_id").takeIf { it.isNotBlank() },
        status = json.optCleanString("status", "idle"),
        gpuIds = gpuIds,
        epoch = json.optInt("epoch", 0),
        totalEpochs = json.optInt("total_epochs", 0),
        metrics = metrics,
        bestMetrics = bestMetrics,
        bestEpochs = bestEpochs,
        metricName = metricName,
        etaSeconds = json.optNullableLong("eta_seconds"),
        updatedAt = json.optCleanString("updated_at"),
        history = history,
        availableMetrics = availableMetrics,
        runs = runs,
    )
}


private fun parseStatusRuns(array: JSONArray?): List<TrainingStatus> {
    if (array == null) return emptyList()
    val runs = mutableListOf<TrainingStatus>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        runs += parseTrainingStatus(item, includeRuns = false)
    }
    return runs
}


private fun parseHistory(array: JSONArray?): List<HistoryPoint> {
    if (array == null) return emptyList()
    val history = mutableListOf<HistoryPoint>()
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val metricName = item.optCleanString("metric_name", "IoU")
        val metrics = item.optJSONObject("metrics").toDoubleMap().toMutableMap()
        val legacyValue = item.optNullableDouble("iou")
        if (metrics.isEmpty() && legacyValue != null) {
            metrics[metricName] = legacyValue
        }
        history += HistoryPoint(
            epoch = item.optInt("epoch", 0),
            metrics = metrics,
            updatedAt = item.optCleanString("updated_at"),
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


private fun JSONObject.optCleanString(name: String, fallback: String = ""): String {
    if (!has(name) || isNull(name)) return fallback
    val value = optString(name, fallback).trim()
    return if (value.equals("null", ignoreCase = true)) fallback else value
}


private fun gpuLabel(gpuIds: List<String>): String {
    return gpuIds.filter { it.isNotBlank() }.joinToString(" + ") { "GPU $it" }
}


private fun gpuIdComparator(): Comparator<String> {
    return compareBy<String> { it.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it }
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


internal fun normalizeBaseUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    if (trimmed.isBlank()) return ""
    val lowered = trimmed.lowercase(Locale.US)
    return if (lowered.startsWith("http://") || lowered.startsWith("https://")) {
        trimmed
    } else if (trimmed.contains("://")) {
        trimmed
    } else {
        "http://$trimmed"
    }
}


internal fun serverUrlValidationError(value: String): String? {
    val normalized = normalizeBaseUrl(value)
    if (normalized.isBlank()) return "请先填写后端地址"
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return "后端地址格式不正确"
    val scheme = uri.scheme?.lowercase(Locale.US)
    if (scheme !in setOf("http", "https")) return "后端地址只支持 HTTP 或 HTTPS"
    if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.query != null) {
        return "后端地址格式不正确"
    }
    if (scheme == "http" && !isPrivateNetworkHost(uri.host)) {
        return "公网服务器必须使用 HTTPS，避免访问 Token 被截获"
    }
    return null
}


private fun isPrivateNetworkHost(host: String): Boolean {
    val normalized = host.trim('[', ']').lowercase(Locale.US)
    if (normalized == "localhost" || normalized == "::1") return true
    if (normalized.endsWith(".local") || normalized.endsWith(".lan") || !normalized.contains('.')) return true
    if (normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80:")) return true

    val parts = normalized.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.any { it !in 0..255 }) return false
    return octets[0] == 10 ||
        octets[0] == 127 ||
        (octets[0] == 169 && octets[1] == 254) ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168)
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
        .getString("base_url", "")
        ?: ""
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
    if (raw.toByteArray(Charsets.UTF_8).size > 750_000) return
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
        .getInt("refresh_seconds", 5)
        .coerceIn(5, 60)
}


private fun saveRefreshSeconds(context: Context, seconds: Int) {
    settingsPreferences(context)
        .edit()
        .putInt("refresh_seconds", seconds)
        .apply()
}


private fun loadUiStyle(context: Context): String {
    return settingsPreferences(context)
        .getString(UiStyleKey, "normal")
        ?.takeIf { it == "glass" }
        ?: "normal"
}


private fun saveUiStyle(context: Context, value: String) {
    settingsPreferences(context)
        .edit()
        .putString(UiStyleKey, if (value == "glass") "glass" else "normal")
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


private fun loadHuaweiWatchSyncEnabled(context: Context): Boolean {
    return settingsPreferences(context).getBoolean(HuaweiWatchSyncEnabledKey, false)
}


private fun saveHuaweiWatchSyncEnabled(context: Context, enabled: Boolean) {
    settingsPreferences(context)
        .edit()
        .putBoolean(HuaweiWatchSyncEnabledKey, enabled)
        .apply()
}


private fun loadPrivacyAccepted(context: Context): Boolean {
    return settingsPreferences(context).getBoolean(PrivacyAcceptedKey, false)
}


private fun savePrivacyAccepted(context: Context, accepted: Boolean) {
    settingsPreferences(context)
        .edit()
        .putBoolean(PrivacyAcceptedKey, accepted)
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
    if (loadBaseUrl(context).isBlank()) return
    val cached = loadCachedStatus(context) ?: return
    if (!shouldShowTrainingNotification(cached)) return
    val intent = Intent(context, TrainingNotificationService::class.java)
    context.startForegroundService(intent)
}


private fun stopTrainingNotificationService(context: Context) {
    context.stopService(Intent(context, TrainingNotificationService::class.java))
    notificationManager(context).cancel(TrainingNotificationId)
}


private fun syncTrainingNotificationService(context: Context, status: TrainingStatus, enabled: Boolean) {
    if (enabled && shouldShowTrainingNotification(status)) {
        startTrainingNotificationService(context)
    } else {
        stopTrainingNotificationService(context)
    }
}


private fun shouldShowTrainingNotification(status: TrainingStatus): Boolean {
    return status.status == "training"
}


private fun showFinishedTrainingNotification(context: Context, status: TrainingStatus) {
    if (!hasNotificationPermission(context)) return
    notificationManager(context).notify(
        TrainingFinishedNotificationId,
        buildFinishedNotification(context, status),
    )
}


private fun maybeShowFinishedTrainingNotification(context: Context, status: TrainingStatus) {
    if (!hasNotificationPermission(context)) return
    val signature = finishedNotificationSignature(status)
    if (signature == loadFinishedNotificationSignature(context)) return
    showFinishedTrainingNotification(context, status)
    saveFinishedNotificationSignature(context, signature)
}


private fun finishedNotificationSignature(status: TrainingStatus): String {
    return listOf(
        status.updatedAt.ifBlank { "-" },
        status.epoch.toString(),
        status.totalEpochs.toString(),
        status.bestMetrics.entries.sortedBy { it.key }.joinToString("|") { "${it.key}:${formatMetric(it.value)}" },
    ).joinToString("#")
}


private fun loadFinishedNotificationSignature(context: Context): String {
    return settingsPreferences(context).getString(FinishedNotificationSignatureKey, "") ?: ""
}


private fun saveFinishedNotificationSignature(context: Context, signature: String) {
    settingsPreferences(context)
        .edit()
        .putString(FinishedNotificationSignatureKey, signature)
        .apply()
}


private fun clearFinishedNotificationSignature(context: Context) {
    settingsPreferences(context)
        .edit()
        .remove(FinishedNotificationSignatureKey)
        .apply()
}


private fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}


private fun createNotificationChannels(context: Context) {
    notificationManager(context).createNotificationChannel(
        NotificationChannel(
            TrainingChannelId,
            "训练状态与锁屏",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "训练进行中的常驻状态、锁屏进度和系统通知展示"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        },
    )
    notificationManager(context).createNotificationChannel(
        NotificationChannel(
            TrainingFinishedChannelId,
            "训练完成提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "模型训练完成时提醒"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        },
    )
}


private fun buildTrainingNotification(
    context: Context,
    status: TrainingStatus,
    contentOverride: String? = null,
): Notification {
    val progressPercent = notificationProgressPercent(status)
    val watchSyncEnabled = loadHuaweiWatchSyncEnabled(context)
    val title = if (watchSyncEnabled) {
        watchNotificationTitle(status)
    } else {
        notificationShortTitle(status)
    }
    val compactText = contentOverride ?: if (watchSyncEnabled) {
        watchNotificationText(context, status)
    } else {
        notificationCompactText(context, status)
    }
    val expandedText = contentOverride ?: notificationExpandedText(context, status)
    val notification = Notification.Builder(context, TrainingChannelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFF155EEF.toInt())
        .setContentTitle(title)
        .setContentText(compactText)
        .setStyle(Notification.BigTextStyle().bigText(expandedText))
        .setContentIntent(mainActivityPendingIntent(context))
        .setSubText(progressPercent?.let { "$it%" })
        .setNumber(progressPercent ?: 0)
        .setOngoing(status.status == "training")
        .setOnlyAlertOnce(true)
        .setShowWhen(true)
        .setWhen(System.currentTimeMillis())
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_STATUS)
        .setTicker(compactText)
        .setLocalOnly(false)
        .setPublicVersion(buildPublicTrainingNotification(context, TrainingChannelId, title, compactText))

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
    val watchSyncEnabled = loadHuaweiWatchSyncEnabled(context)
    val title = if (watchSyncEnabled) {
        watchNotificationTitle(status)
    } else {
        notificationShortTitle(status)
    }
    val content = if (watchSyncEnabled) {
        watchNotificationText(context, status)
    } else {
        notificationCompactText(context, status)
    }
    val expandedText = notificationExpandedText(context, status)
    return Notification.Builder(context, TrainingFinishedChannelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFF12B76A.toInt())
        .setContentTitle(title)
        .setContentText(content)
        .setStyle(Notification.BigTextStyle().bigText(expandedText))
        .setContentIntent(mainActivityPendingIntent(context))
        .setAutoCancel(true)
        .setShowWhen(true)
        .setWhen(System.currentTimeMillis())
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_STATUS)
        .setTicker(content)
        .setLocalOnly(false)
        .setPublicVersion(buildPublicTrainingNotification(context, TrainingFinishedChannelId, title, content))
        .build()
}


private fun buildPublicTrainingNotification(
    context: Context,
    channelId: String,
    title: String,
    text: String,
): Notification {
    return Notification.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFF155EEF.toInt())
        .setContentTitle(title)
        .setContentText(text)
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


private fun notificationShortTitle(status: TrainingStatus): String {
    val epochText = if (status.totalEpochs > 0) {
        "${status.epoch}/${status.totalEpochs}"
    } else {
        status.epoch.takeIf { it > 0 }?.toString() ?: "--"
    }
    val progressText = notificationProgressPercent(status)?.let { " · ${it}%" } ?: ""
    return when (status.status) {
        "training" -> "训练中 · Epoch $epochText$progressText"
        "finished" -> "训练完成 · Epoch $epochText$progressText"
        "error" -> "训练异常 · Epoch $epochText"
        else -> "等待训练数据"
    }
}


private fun notificationCompactText(context: Context, status: TrainingStatus): String {
    val metrics = notificationMetrics(context, status)
    val bestMetric = metrics.firstOrNull { status.bestMetrics[it] != null }
    val currentMetric = metrics.firstOrNull { status.latestMetricValue(it) != null } ?: bestMetric
    val bestText = bestMetric?.let { metric ->
        val best = status.bestMetrics[metric]
        val epoch = status.bestEpochs[metric]
        "Best ${metricDisplayName(metric)} ${formatMetric(best)}${epoch?.let { " @ $it" } ?: ""}"
    }
    val currentText = currentMetric?.let { metric ->
        status.latestMetricValue(metric)?.let { value ->
            "${metricDisplayName(metric)} ${formatMetric(value)}"
        }
    }
    val etaText = status.etaSeconds?.let { "ETA ${formatEta(it)}" }
    val progressText = notificationProgressPercent(status)?.let { "Progress $it%" }
    return listOfNotNull(bestText, currentText, etaText, progressText)
        .distinct()
        .take(3)
        .joinToString(" · ")
        .ifBlank { "等待训练数据" }
}


private fun watchNotificationTitle(status: TrainingStatus): String {
    val progressText = notificationProgressPercent(status)?.let { " $it%" } ?: ""
    return when (status.status) {
        "training" -> "模迹 训练中$progressText"
        "finished" -> "模迹 训练完成$progressText"
        "error" -> "模迹 训练异常"
        else -> "模迹 等待数据"
    }
}


private fun watchNotificationText(context: Context, status: TrainingStatus): String {
    val metrics = notificationMetrics(context, status)
    val primaryMetric = metrics.firstOrNull()
    val currentText = primaryMetric
        ?.let { metric -> status.latestMetricValue(metric)?.let { "${shortMetricName(metric)} ${formatMetric(it)}" } }
    val bestText = primaryMetric
        ?.let { metric ->
            status.bestMetrics[metric]?.let { best ->
                val epoch = status.bestEpochs[metric]?.let { "@$it" } ?: ""
                "Best ${formatMetric(best)}$epoch"
            }
        }
    val epochText = if (status.totalEpochs > 0) {
        "E${status.epoch}/${status.totalEpochs}"
    } else {
        status.epoch.takeIf { it > 0 }?.let { "E$it" }
    }
    val etaText = status.etaSeconds?.let { "ETA ${formatEta(it)}" }
    return listOfNotNull(epochText, currentText, bestText, etaText)
        .distinct()
        .joinToString(" · ")
        .ifBlank { notificationCompactText(context, status) }
}


private fun shortMetricName(metric: String): String {
    return when (metricDisplayName(metric)) {
        "BBox mAP" -> "bbox"
        "Segm mAP" -> "segm"
        "Accuracy" -> "acc"
        "Top1 Acc" -> "top1"
        "Top5 Acc" -> "top5"
        else -> metricDisplayName(metric)
    }
}


private fun notificationExpandedText(context: Context, status: TrainingStatus): String {
    val metrics = notificationMetrics(context, status)
    val metricLines = metrics.mapNotNull { metric ->
        val current = status.latestMetricValue(metric)
        val best = status.bestMetrics[metric]
        val bestEpoch = status.bestEpochs[metric]
        when {
            best != null && current != null -> {
                "${metricDisplayName(metric)} ${formatMetric(current)} · Best ${formatMetric(best)}${bestEpoch?.let { " @ $it" } ?: ""}"
            }
            best != null -> {
                "Best ${metricDisplayName(metric)} ${formatMetric(best)}${bestEpoch?.let { " @ $it" } ?: ""}"
            }
            current != null -> {
                "${metricDisplayName(metric)} ${formatMetric(current)}"
            }
            else -> null
        }
    }
    val epochText = if (status.totalEpochs > 0) {
        "Epoch ${status.epoch}/${status.totalEpochs}"
    } else {
        status.epoch.takeIf { it > 0 }?.let { "Epoch $it" }
    }
    val etaText = status.etaSeconds?.let { "ETA ${formatEta(it)}" }
    return (listOfNotNull(epochText) + metricLines + listOfNotNull(etaText))
        .joinToString("\n")
        .ifBlank { notificationCompactText(context, status) }
}


private fun notificationRetryText(context: Context, status: TrainingStatus): String {
    return "连接中断 · ${wearableStatusText(context, status)}"
}


private fun notificationRecoveringText(context: Context, status: TrainingStatus): String {
    return "等待恢复 · ${wearableStatusText(context, status)}"
}


private fun wearableStatusText(context: Context, status: TrainingStatus): String {
    return if (loadHuaweiWatchSyncEnabled(context)) {
        watchNotificationText(context, status)
    } else {
        notificationCompactText(context, status)
    }
}


private fun notificationTitle(status: TrainingStatus): String {
    val epochText = if (status.totalEpochs > 0) {
        "${status.epoch}/${status.totalEpochs}"
    } else {
        status.epoch.takeIf { it > 0 }?.toString() ?: "--"
    }
    val progressText = notificationProgressPercent(status)?.let { " · ${it}%" } ?: ""
    return when (status.status) {
        "training" -> "训练中$progressText · Epoch $epochText"
        "finished" -> "训练完成$progressText · Epoch $epochText"
        "error" -> "训练异常 · Epoch $epochText"
        else -> "等待训练数据"
    }
}


private fun notificationSummary(context: Context, status: TrainingStatus): String {
    val metrics = notificationMetrics(context, status)
    val metricText = metrics.joinToString(" · ") { metric ->
        val current = status.latestMetricValue(metric)
        val best = status.bestMetrics[metric]
        val bestEpoch = status.bestEpochs[metric]
        when {
            current != null && best != null -> {
                "${metricDisplayName(metric)} ${formatMetric(current)} · Best ${formatMetric(best)}${bestEpoch?.let { " @ $it" } ?: ""}"
            }
            best != null -> {
                "Best ${metricDisplayName(metric)} ${formatMetric(best)}${bestEpoch?.let { " @ $it" } ?: ""}"
            }
            else -> {
                "${metricDisplayName(metric)} ${formatMetric(current)}"
            }
        }
    }
    val etaText = status.etaSeconds?.let { " · ETA ${formatEta(it)}" } ?: ""
    val progressText = notificationProgressPercent(status)?.let { "Progress $it%" }
    val summary = listOfNotNull(
        progressText,
        metricText.ifBlank { null },
        if (status.totalEpochs > 0) "Epoch ${status.epoch}/${status.totalEpochs}" else null,
    ).joinToString(" · ") + etaText
    return summary.ifBlank { "等待训练数据" }
}


private fun notificationProgressPercent(status: TrainingStatus): Int? {
    if (status.totalEpochs <= 0) return null
    return ((status.epoch.toDouble() / status.totalEpochs.toDouble()) * 100)
        .toInt()
        .coerceIn(0, 100)
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
