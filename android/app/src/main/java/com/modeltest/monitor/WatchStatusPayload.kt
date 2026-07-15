package com.modeltest.monitor

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale


object WatchStatusPayload {
    const val Type = "training_status"
    const val Version = 1

    fun build(status: TrainingStatus, selectedMetricsText: String = ""): JSONObject {
        val metric = chooseMetric(status, selectedMetricsText)
        val current = metric?.let { status.latestMetricValue(it) }
        val best = metric?.let { status.bestMetrics[it] }
        val bestEpoch = metric?.let { status.bestEpochs[it] }
        val progressPercent = progressPercent(status)

        return JSONObject()
            .put("type", Type)
            .put("version", Version)
            .put("title", title(status, progressPercent))
            .put("summary", summary(status, metric, current, best, bestEpoch))
            .put("run_id", status.runId ?: JSONObject.NULL)
            .put("run_name", status.runDisplayName())
            .put("status", status.status)
            .put("epoch", status.epoch)
            .put("total_epochs", status.totalEpochs)
            .put("progress_percent", progressPercent ?: JSONObject.NULL)
            .put("metric_name", metric ?: JSONObject.NULL)
            .put("current_metric", current ?: JSONObject.NULL)
            .put("best_metric", best ?: JSONObject.NULL)
            .put("best_epoch", bestEpoch ?: JSONObject.NULL)
            .put("eta_seconds", status.etaSeconds ?: JSONObject.NULL)
            .put("updated_at", status.updatedAt)
            .put("gpu_ids", JSONArray(status.gpuIds))
            .put("metrics", metricArray(status))
    }

    fun buildString(status: TrainingStatus, selectedMetricsText: String = ""): String {
        return build(status, selectedMetricsText).toString()
    }

    private fun chooseMetric(status: TrainingStatus, selectedMetricsText: String): String? {
        val selected = selectedMetricsText
            .split(",", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val available = status.metricNames()
        return selected.firstOrNull { it in available }
            ?: preferredMetric(available)
            ?: status.primaryMetric()
    }

    private fun preferredMetric(available: List<String>): String? {
        val preferred = listOf(
            "mIoU",
            "IoU",
            "mAP",
            "BBox mAP",
            "Segm mAP",
            "mAP50",
            "Accuracy",
            "accuracy",
            "Top1 Acc",
        )
        return preferred.firstNotNullOfOrNull { wanted ->
            available.firstOrNull { it.equals(wanted, ignoreCase = true) }
        }
    }

    private fun metricArray(status: TrainingStatus): JSONArray {
        val result = JSONArray()
        status.metricNames().take(8).forEach { name ->
            result.put(
                JSONObject()
                    .put("name", name)
                    .put("display_name", shortMetricName(name))
                    .put("current", status.latestMetricValue(name) ?: JSONObject.NULL)
                    .put("best", status.bestMetrics[name] ?: JSONObject.NULL)
                    .put("best_epoch", status.bestEpochs[name] ?: JSONObject.NULL),
            )
        }
        return result
    }

    private fun progressPercent(status: TrainingStatus): Int? {
        if (status.totalEpochs <= 0) return null
        return ((status.epoch.toDouble() / status.totalEpochs.toDouble()) * 100)
            .toInt()
            .coerceIn(0, 100)
    }

    private fun title(status: TrainingStatus, progressPercent: Int?): String {
        val progress = progressPercent?.let { " $it%" } ?: ""
        return when (status.status) {
            "training" -> "模迹 训练中$progress"
            "finished" -> "模迹 训练完成$progress"
            "error" -> "模迹 训练异常"
            else -> "模迹 等待数据"
        }
    }

    private fun summary(
        status: TrainingStatus,
        metric: String?,
        current: Double?,
        best: Double?,
        bestEpoch: Int?,
    ): String {
        val epochText = if (status.totalEpochs > 0) {
            "E${status.epoch}/${status.totalEpochs}"
        } else {
            status.epoch.takeIf { it > 0 }?.let { "E$it" }
        }
        val currentText = if (metric != null && current != null) {
            "${shortMetricName(metric)} ${formatValue(current)}"
        } else {
            null
        }
        val bestText = best?.let {
            val epoch = bestEpoch?.let { value -> "@$value" } ?: ""
            "Best ${formatValue(it)}$epoch"
        }
        val etaText = status.etaSeconds?.let { "ETA ${formatEta(it)}" }
        return listOfNotNull(epochText, currentText, bestText, etaText)
            .distinct()
            .joinToString(" · ")
            .ifBlank { "等待训练数据" }
    }

    private fun shortMetricName(metric: String): String {
        return when (metric) {
            "BBox mAP" -> "bbox"
            "Segm mAP" -> "segm"
            "Accuracy" -> "acc"
            "Top1 Acc" -> "top1"
            "Top5 Acc" -> "top5"
            else -> metric
        }
    }

    private fun formatValue(value: Double): String {
        val abs = kotlin.math.abs(value)
        return when {
            abs >= 100 -> String.format(Locale.US, "%.1f", value)
            abs >= 10 -> String.format(Locale.US, "%.2f", value)
            else -> String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
        }
    }

    private fun formatEta(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        return if (hours > 0) {
            "${hours}h${minutes}m"
        } else {
            "${minutes}m"
        }
    }
}
