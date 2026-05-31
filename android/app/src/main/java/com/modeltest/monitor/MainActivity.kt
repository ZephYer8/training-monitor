package com.modeltest.monitor

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.Dispatchers
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

private val CommonMetrics = listOf(
    "loss",
    "mIoU",
    "IoU",
    "mAP",
    "mAP50",
    "accuracy",
    "precision",
    "recall",
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
    var status by remember { mutableStateOf(TrainingStatus()) }
    var error by remember { mutableStateOf<String?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var testMessage by rememberSaveable { mutableStateOf("") }

    suspend fun refreshOnce(url: String = savedUrl, token: String = savedToken) {
        if (url.isBlank()) {
            error = "请先在设置里填写后端地址"
            return
        }

        isRefreshing = true
        try {
            status = fetchStatus(client, url, token)
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

    val selectedMetrics = parseMetricList(selectedMetricsText)
    val visibleMetrics = chooseVisibleMetrics(status, selectedMetrics)
    val metricOptions = mergeMetricOptions(status.metricNames(), CommonMetrics)
    val currentPage = AppPage.valueOf(page)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                        visibleMetrics = visibleMetrics,
                        selectedMetrics = selectedMetrics,
                        metricOptions = metricOptions,
                        onMetricToggle = { metric ->
                            selectedMetricsText = toggleMetric(selectedMetrics, metric)
                            saveSelectedMetricsText(context, selectedMetricsText)
                        },
                    )

                    AppPage.Settings -> SettingsScreen(
                        draftUrl = draftUrl,
                        draftToken = draftToken,
                        refreshSeconds = refreshSeconds,
                        metricOptions = metricOptions,
                        selectedMetrics = selectedMetrics,
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
                        onSave = {
                            savedUrl = draftUrl.trim()
                            savedToken = draftToken.trim()
                            saveBaseUrl(context, savedUrl)
                            saveToken(context, savedToken)
                            testMessage = "设置已保存"
                        },
                        onTest = {
                            scope.launch {
                                testMessage = "正在测试连接..."
                                runCatching { fetchStatus(client, draftUrl.trim(), draftToken.trim()) }
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
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(
            status = status,
            error = error,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
        )
        ProgressHeroCard(status)
        MetricGrid(status, visibleMetrics)
        MiniChartCard(status, visibleMetrics)
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
                text = "训练监控",
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
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.training_buddy))
    val animationProgress by animateLottieCompositionAsState(
        composition = composition,
        iterations = LottieConstants.IterateForever,
        isPlaying = status == "training" || status == "idle",
    )
    val accent = when (status) {
        "training" -> Color(0xFF2563EB)
        "finished" -> Color(0xFF16A34A)
        "error" -> Color(0xFFDC2626)
        else -> Color(0xFF6B7280)
    }

    ElevatedCard(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LottieAnimation(
                composition = composition,
                progress = { animationProgress },
                modifier = Modifier
                    .weight(1f)
                    .height(112.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when (status) {
                        "training" -> "训练小助手正在盯曲线"
                        "finished" -> "训练完成，可以收工"
                        "error" -> "连接异常，先检查服务"
                        else -> "等待新的训练任务"
                    },
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                )
                Text(
                    text = "进度 ${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    color = Color(0xFF6B7280),
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
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowMetrics.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
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
                text = "最好 ${formatMetric(best)}${bestEpoch?.let { " · 第 $it 轮" } ?: ""}",
                color = Color(0xFF6B7280),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


@Composable
private fun MiniChartCard(status: TrainingStatus, visibleMetrics: List<String>) {
    val chartMetrics = visibleMetrics.filter { metric ->
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

            if (chartMetrics.isEmpty()) {
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
                    metrics = chartMetrics,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                )
                ChartLegend(chartMetrics)
            }
        }
    }
}


@Composable
private fun ChartsScreen(
    status: TrainingStatus,
    visibleMetrics: List<String>,
    selectedMetrics: List<String>,
    metricOptions: List<String>,
    onMetricToggle: (String) -> Unit,
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
        Text("选择你关心的指标，曲线会随训练实时刷新。", color = Color(0xFF6B7280))

        SettingsCard(title = "指标选择") {
            metricOptions.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { metric ->
                        MetricChip(
                            text = metricDisplayName(metric),
                            selected = selectedMetrics.contains(metric) || (selectedMetrics.isEmpty() && visibleMetrics.contains(metric)),
                            onClick = { onMetricToggle(metric) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        val chartMetrics = visibleMetrics.filter { metric ->
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
                Text("指标曲线", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (chartMetrics.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("至少需要两个 epoch 的数据才能画曲线", color = Color(0xFF6B7280))
                    }
                } else {
                    MetricsChart(
                        history = status.history,
                        metrics = chartMetrics,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                    )
                    ChartLegend(chartMetrics)
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
        val left = 36.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 12.dp.toPx()
        val bottom = size.height - 28.dp.toPx()
        val minEpoch = history.minOfOrNull { it.epoch } ?: 0
        val maxEpoch = history.maxOfOrNull { it.epoch } ?: max(1, minEpoch + 1)
        val epochSpan = max(1, maxEpoch - minEpoch)

        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            drawLine(
                color = Color(0xFFE5E7EB),
                start = Offset(left, y),
                end = Offset(right, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        drawLine(Color(0xFFD1D5DB), Offset(left, top), Offset(left, bottom), 1.dp.toPx())
        drawLine(Color(0xFFD1D5DB), Offset(left, bottom), Offset(right, bottom), 1.dp.toPx())

        metrics.forEachIndexed { index, metric ->
            val points = history.mapNotNull { point ->
                point.metrics[metric]?.let { point.epoch to it }
            }
            if (points.size < 2) return@forEachIndexed

            val minValue = points.minOf { it.second }
            val maxValue = points.maxOf { it.second }
            val valueSpan = max(abs(maxValue - minValue), 0.000001)
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
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
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
    testMessage: String,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onRefreshSecondsChange: (Int) -> Unit,
    onMetricToggle: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
) {
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
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draftToken,
                onValueChange = onTokenChange,
                label = { Text("访问 Token") },
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

        SettingsCard(title = "首页指标") {
            Text("勾选后，总览和曲线页都会优先显示这些指标。", color = Color(0xFF6B7280))
            metricOptions.forEach { metric ->
                MetricOptionRow(
                    metric = metric,
                    checked = selectedMetrics.contains(metric),
                    onToggle = { onMetricToggle(metric) },
                )
            }
        }
    }
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

            TrainingStatus(
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
    }
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
    val chosen = selected.filter { it in available }
    if (chosen.isNotEmpty()) return chosen.take(6)

    val defaults = listOf("loss", status.metricName, "mIoU", "IoU", "mAP", "accuracy")
    return (defaults + available).filter { it in available }.distinct().take(4)
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
        "loss" -> "损失 loss"
        "box_loss" -> "框损失"
        "cls_loss" -> "分类损失"
        "dfl_loss" -> "DFL 损失"
        "miou" -> "mIoU"
        "iou" -> "IoU"
        "map" -> "mAP"
        "map50" -> "mAP50"
        "accuracy" -> "准确率"
        "precision" -> "精确率"
        "recall" -> "召回率"
        else -> name
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


private fun loadRefreshSeconds(context: Context): Int {
    return context
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getInt("refresh_seconds", 2)
        .coerceIn(1, 60)
}


private fun saveRefreshSeconds(context: Context, seconds: Int) {
    context
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit()
        .putInt("refresh_seconds", seconds)
        .apply()
}


private fun loadSelectedMetricsText(context: Context): String {
    return context
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString("selected_metrics", "")
        ?: ""
}


private fun saveSelectedMetricsText(context: Context, value: String) {
    context
        .getSharedPreferences("settings", Context.MODE_PRIVATE)
        .edit()
        .putString("selected_metrics", value)
        .apply()
}
