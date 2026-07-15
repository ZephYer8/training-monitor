const mockStatus = {
  type: 'training_status',
  version: 1,
  title: '模迹 训练中 43%',
  summary: 'E13/30 · mIoU 78.42 · Best 79.10@12 · ETA 1h20m',
  status: 'training',
  epoch: 13,
  total_epochs: 30,
  progress_percent: 43,
  metric_name: 'mIoU',
  current_metric: 78.42,
  best_metric: 79.1,
  best_epoch: 12,
  eta_seconds: 4800,
  updated_at: '2026-07-07T00:00:00',
  metrics: []
};

export default {
  data: {
    statusClass: 'training',
    statusLabel: '训练中',
    progressText: '43%',
    progressWidth: '43%',
    summary: 'E13/30 · mIoU 78.42 · Best 79.10@12 · ETA 1h20m',
    currentMetricText: 'mIoU 78.42',
    bestMetricText: '79.10 @12',
    etaText: 'ETA 1h20m',
    updatedText: '00:00'
  },

  onInit() {
    this.applyStatus(mockStatus);
    this.registerWearEngineReceiver();
  },

  registerWearEngineReceiver() {
    // Placeholder for the wearable-side Huawei Wear Engine receiver.
    // The receiver should call this.updateFromMessage(rawPayload) whenever
    // the phone sends WatchStatusPayload JSON over P2P.
  },

  updateFromMessage(rawPayload) {
    try {
      const payload = typeof rawPayload === 'string' ? JSON.parse(rawPayload) : rawPayload;
      if (!payload || payload.type !== 'training_status' || payload.version !== 1) {
        return;
      }
      this.applyStatus(payload);
    } catch (err) {
      this.statusClass = 'error';
      this.statusLabel = '解析失败';
      this.summary = '手机同步数据格式异常';
    }
  },

  applyStatus(payload) {
    const progress = normalizeProgress(payload.progress_percent);
    const metricName = payload.metric_name || 'metric';
    const current = formatMetric(payload.current_metric);
    const best = formatMetric(payload.best_metric);
    const bestEpoch = payload.best_epoch ? ` @${payload.best_epoch}` : '';

    this.statusClass = statusClass(payload.status);
    this.statusLabel = statusLabel(payload.status);
    this.progressText = progress === null ? '--%' : `${progress}%`;
    this.progressWidth = progress === null ? '0%' : `${progress}%`;
    this.summary = payload.summary || buildSummary(payload);
    this.currentMetricText = current ? `${metricName} ${current}` : '--';
    this.bestMetricText = best ? `${best}${bestEpoch}` : '--';
    this.etaText = payload.eta_seconds === null || payload.eta_seconds === undefined
      ? 'ETA --'
      : `ETA ${formatEta(payload.eta_seconds)}`;
    this.updatedText = formatUpdatedAt(payload.updated_at);
  }
};

function normalizeProgress(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return null;
  }
  return Math.max(0, Math.min(100, Math.floor(Number(value))));
}

function statusClass(status) {
  if (status === 'finished') return 'finished';
  if (status === 'error') return 'error';
  if (status === 'training') return 'training';
  return 'idle';
}

function statusLabel(status) {
  if (status === 'finished') return '完成';
  if (status === 'error') return '异常';
  if (status === 'training') return '训练中';
  return '等待';
}

function formatMetric(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return '';
  }
  const parsed = Number(value);
  if (Math.abs(parsed) >= 100) return parsed.toFixed(1);
  if (Math.abs(parsed) >= 10) return parsed.toFixed(2);
  return parsed.toFixed(4).replace(/0+$/, '').replace(/\.$/, '');
}

function formatEta(seconds) {
  const safe = Math.max(0, Number(seconds) || 0);
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  return hours > 0 ? `${hours}h${minutes}m` : `${minutes}m`;
}

function formatUpdatedAt(value) {
  if (!value) return '未同步';
  const text = String(value).replace('T', ' ');
  return text.length >= 16 ? text.substring(11, 16) : text;
}

function buildSummary(payload) {
  const epoch = payload.total_epochs > 0
    ? `E${payload.epoch}/${payload.total_epochs}`
    : `E${payload.epoch || '--'}`;
  const metric = payload.metric_name && payload.current_metric !== null && payload.current_metric !== undefined
    ? `${payload.metric_name} ${formatMetric(payload.current_metric)}`
    : '';
  return [epoch, metric].filter(Boolean).join(' · ');
}
