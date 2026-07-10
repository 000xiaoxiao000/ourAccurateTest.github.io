package com.oAT.relay.control;

import com.oAT.relay.config.RelayProperties;
import com.oAT.relay.model.RelayStatus;
import com.oAT.relay.service.RelayMetrics;
import com.oAT.relay.service.RelayQueueService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/relay")
public class RelayStatusController {
    private final RelayProperties properties;
    private final RelayQueueService queueService;
    private final RelayMetrics metrics;

    public RelayStatusController(RelayProperties properties, RelayQueueService queueService, RelayMetrics metrics) {
        this.properties = properties;
        this.queueService = queueService;
        this.metrics = metrics;
    }

    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    public String dashboard() {
        RelayStatus current = status();
        StringBuilder html = new StringBuilder(8192);
        html.append("<!doctype html>")
                .append("<html lang=\"zh-CN\">")
                .append("<head>")
                .append("<meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("<title>oAT Relay 控制台</title>")
                .append("<style>")
                .append(":root{color-scheme:light;--bg:#f5f7fb;--card:#ffffff;--text:#1f2937;--muted:#6b7280;--primary:#2563eb;--ok:#16a34a;--border:#e5e7eb;}")
                .append("*{box-sizing:border-box;}body{margin:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:radial-gradient(circle at top left,#dbeafe,transparent 32rem),var(--bg);color:var(--text);}")
                .append("header{padding:32px 40px 16px;}h1{margin:0 0 8px;font-size:28px;}.subtitle{color:var(--muted);font-size:14px;}main{padding:16px 40px 40px;}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:16px;}.card{background:rgba(255,255,255,.9);border:1px solid var(--border);border-radius:16px;padding:20px;box-shadow:0 10px 30px rgba(15,23,42,.06);}")
                .append(".label{color:var(--muted);font-size:13px;margin-bottom:8px;}.value{font-size:26px;font-weight:700;overflow-wrap:anywhere;}.value.small{font-size:16px;line-height:1.5;}")
                .append(".status{display:inline-flex;align-items:center;gap:8px;padding:6px 10px;border-radius:999px;background:#dcfce7;color:var(--ok);font-weight:700;}.dot{width:8px;height:8px;border-radius:50%;background:var(--ok);}")
                .append(".section-title{margin:28px 0 12px;font-size:18px;}.actions{display:flex;gap:12px;margin-top:20px;flex-wrap:wrap;}a.button,button{border:0;border-radius:10px;padding:10px 14px;background:var(--primary);color:#fff;text-decoration:none;font-weight:600;cursor:pointer;}a.button.secondary{background:#111827;}footer{padding:0 40px 28px;color:var(--muted);font-size:12px;}")
                .append("</style>")
                .append("</head>")
                .append("<body>")
                .append("<header><h1>oAT Relay 控制台</h1><div class=\"subtitle\">流量采集器中继状态总览，页面刷新即可获取最新数据。</div></header>")
                .append("<main>")
                .append("<div class=\"grid\">")
                .append("<div class=\"card\"><div class=\"label\">服务状态</div><div class=\"status\"><span class=\"dot\"></span>UP</div></div>")
                .append("<div class=\"card\"><div class=\"label\">目标平台</div><div class=\"value small\">").append(escapeHtml(current.getTargetBaseUrl())).append("</div></div>")
                .append("<div class=\"card\"><div class=\"label\">转发模式</div><div class=\"value\">").append(escapeHtml(current.getForwardMode())).append("</div></div>")
                .append("<div class=\"card\"><div class=\"label\">队列深度</div><div class=\"value\">").append(current.getQueueDepth()).append("</div></div>")
                .append("</div>")
                .append("<h2 class=\"section-title\">核心指标</h2>")
                .append("<div class=\"grid\">")
                .append(metricCard("接收总数", current.getReceivedCount()))
                .append(metricCard("入队成功", current.getEnqueueSuccessCount()))
                .append(metricCard("入队失败", current.getEnqueueFailureCount()))
                .append(metricCard("转发成功", current.getForwardSuccessCount()))
                .append(metricCard("转发失败", current.getForwardFailureCount()))
                .append(metricCard("死信数量", current.getDeadLetterCount()))
                .append("</div>")
                .append("<div class=\"actions\">")
                .append("<button onclick=\"location.reload()\">刷新页面</button>")
                .append("<a class=\"button secondary\" href=\"/relay/status\" target=\"_blank\">查看 JSON 状态</a>")
                .append("<a class=\"button secondary\" href=\"/relay/metrics\" target=\"_blank\">查看 Metrics</a>")
                .append("<a class=\"button secondary\" href=\"/relay/health\" target=\"_blank\">健康检查</a>")
                .append("</div>")
                .append("</main>")
                .append("<footer>oAT Relay Dashboard</footer>")
                .append("</body></html>");
        return html.toString();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("targetBaseUrl", properties.getTargetBaseUrl());
        return result;
    }

    @GetMapping("/status")
    public RelayStatus status() {
        return new RelayStatus(
                properties.getTargetBaseUrl(),
                properties.getForwardMode().name().toLowerCase(),
                queueService.depth(),
                metrics.getReceivedCount(),
                metrics.getEnqueueSuccessCount(),
                metrics.getEnqueueFailureCount(),
                metrics.getForwardSuccessCount(),
                metrics.getForwardFailureCount(),
                metrics.getDeadLetterCount()
        );
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("oat_relay_queue_depth", queueService.depth());
        result.put("oat_relay_received_total", metrics.getReceivedCount());
        result.put("oat_relay_enqueue_success_total", metrics.getEnqueueSuccessCount());
        result.put("oat_relay_enqueue_failure_total", metrics.getEnqueueFailureCount());
        result.put("oat_relay_forward_success_total", metrics.getForwardSuccessCount());
        result.put("oat_relay_forward_failure_total", metrics.getForwardFailureCount());
        result.put("oat_relay_dead_letter_total", metrics.getDeadLetterCount());
        return result;
    }

    private String metricCard(String label, long value) {
        return "<div class=\"card\"><div class=\"label\">" + escapeHtml(label)
                + "</div><div class=\"value\">" + value + "</div></div>";
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
