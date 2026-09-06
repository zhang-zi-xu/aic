package com.nongxin.service.impl;

import com.nongxin.service.RiskService;

import com.nongxin.model.RiskItem;
import com.nongxin.model.RiskReport;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 农情数据风险规则引擎（R0-R5）：解析上传数据 → 阈值规则 → 分级报告。
 * 本地启发式阈值规则；未经区域与作物校准，不替代实测诊断或气象预警。
 */
@Service
public class RiskServiceImpl implements RiskService {

    private static final String VALUE_PATTERN = "\\s*[:：=]?\\s*(?:大约|约|为|是|达|有)?\\s*([+-]?\\d+(?:\\.\\d+)?)";

    private static final Map<String, Pattern> METRIC_PATTERNS = Map.of(
            "温度", Pattern.compile("(?:气温|温度|air temp(?:erature)?)" + VALUE_PATTERN, Pattern.CASE_INSENSITIVE),
            "湿度", Pattern.compile("(?:空气湿度|相对湿度|湿度|humidity)" + VALUE_PATTERN, Pattern.CASE_INSENSITIVE),
            "降水量", Pattern.compile("(?:降水量?|降雨量?|雨量|下了|下过)" + VALUE_PATTERN, Pattern.CASE_INSENSITIVE),
            "风速", Pattern.compile("(?:风速|wind(?: speed)?)" + VALUE_PATTERN, Pattern.CASE_INSENSITIVE),
            "土壤湿度", Pattern.compile("(?:土壤\\s*湿度|地墒|soil moisture)" + VALUE_PATTERN, Pattern.CASE_INSENSITIVE));

    private static final List<String> METRIC_NAMES = List.of("温度", "湿度", "降水量", "风速", "土壤湿度");

    /** 从自由文本提取指标（温度/湿度/降水/风速/土壤湿度） */
    public Map<String, Double> extractMetrics(String text) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return out;
        for (String name : METRIC_NAMES) {
            // Remove soil readings before matching generic 湿度, so the same reading is not air humidity.
            String source = "湿度".equals(name) ? METRIC_PATTERNS.get("土壤湿度").matcher(text).replaceAll("") : text;
            Matcher m = METRIC_PATTERNS.get(name).matcher(source);
            if (m.find()) {
                try {
                    double value = Double.parseDouble(m.group(1));
                    if ("风速".equals(name) && source.substring(m.end()).stripLeading().matches("(?is)^(?:m/s|米/秒).*")) {
                        value *= 3.6;
                    }
                    if (validMetric(name, value)) out.put(name, value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return out;
    }

    /** 解析「时间 指标 值」文本行（支持逗号/空白分隔） */
    public List<Map<String, Object>> parseFarmData(String text) {
        List<Map<String, Object>> series = new ArrayList<>();
        if (text == null) return series;
        for (String line : text.split("\\r?\\n")) {
            String[] cols = line.trim().split("[,，\\t;；\\s]+");
            List<String> parts = new ArrayList<>();
            for (String c : cols) if (!c.isBlank()) parts.add(c);
            if (parts.size() < 2) continue;
            // 时间 + 指标 + 值
            if (parts.size() == 3 && isNumeric(parts.get(2))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", parts.get(0));
                row.put(parts.get(1), parseNumber(parts.get(2)));
                series.add(row);
            } else if (parts.size() == 2 && isNumeric(parts.get(1))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", parts.get(0));
                row.put("value", parseNumber(parts.get(1)));
                series.add(row);
            }
        }
        return series;
    }

    public RiskReport assess(Map<String, Double> metrics, List<Map<String, Object>> series, String phaseText) {
        Map<String, Double> valid = new LinkedHashMap<>();
        if (metrics != null) {
            metrics.forEach((name, value) -> {
                if (value != null && validMetric(name, value)) valid.put(name, value);
            });
        }
        series = series == null ? List.of() : series;
        for (String name : METRIC_NAMES) {
            List<Double> values = recentValues(series, name);
            if (!valid.containsKey(name) && !values.isEmpty()) valid.put(name, values.get(values.size() - 1));
        }
        metrics = valid;
        List<RiskItem> items = new ArrayList<>();
        Double humidity = metrics.get("湿度");
        Double rain = metrics.get("降水量");
        Double wind = metrics.get("风速");
        Double soil = metrics.get("土壤湿度");
        Double temp = metrics.get("温度");

        // R1：高湿 → 病害
        if (humidity != null) {
            double maxHumidity = Math.max(humidity, recentValues(series, "湿度").stream().mapToDouble(Double::doubleValue).max().orElse(humidity));
            if (maxHumidity >= 85) {
                items.add(new RiskItem("中", "病害高发条件",
                        String.format("输入空气湿度最高为%.0f%%（≥85%%），触发本地高湿提示；观测持续时间与叶面湿润情况尚未核实，不能据此确诊病害。", maxHumidity),
                        "核实空气湿度与持续时间，加强田间巡查；出现症状时结合现场观察咨询农技员。", "R1"));
            }
        }
        // R2：强降水 → 涝渍
        if (rain != null) {
            double maxRain = Math.max(rain, recentValues(series, "降水量").stream().mapToDouble(Double::doubleValue).max().orElse(0));
            if (maxRain >= 50) {
                items.add(new RiskItem("高", "涝渍风险",
                        String.format("输入降水量最高为%.1fmm，触发本地50mm阈值提示；统计时段尚未核实，不能直接作为24小时累计量或暴雨判定。", maxRain),
                        "先确认降水统计时段、当地预警和田间积水情况；检查沟渠，实际发生积水时及时排涝。", "R2"));
            }
        }
        // R3：大风 → 倒伏
        if (wind != null && wind >= 50) {
            items.add(new RiskItem("中", "大风倒伏风险",
                    String.format("输入风速按%.0fkm/h参与本地阈值比较（≥50km/h）；需核实原始单位、平均风或阵风及作物实际状态。", wind),
                    "核实风速单位与当地预警，结合实际生育期检查倒伏风险。", "R3"));
        }
        // R4：土壤偏干
        if (soil != null && soil < 40) {
            items.add(new RiskItem("中", "土壤偏干，旱情风险",
                    String.format("输入土壤湿度%.0f%%（<40%%），触发本地偏干提示；含水率定义、土壤类型和传感器标定尚未核实。", soil),
                    "核实测量口径并查看根层墒情，结合实际作物需水和预报再决定是否灌溉。", "R4"));
        }
        // R5：敏感生育期低温
        if (temp != null && temp <= 0 && phaseText != null && phaseText.matches(".*(抽穗|扬花|花期|孕穗).*")) {
            items.add(new RiskItem("高", "冷害风险（敏感生育期）",
                    String.format("输入气温%.0f°C；提供的生育期背景为%s，触发低温条件提示。估算生育期需通过田间观察核实。", temp, phaseText),
                    "核实最低温度和实际生育期，关注当地预警，向农技员确认适合作物的防寒措施。", "R5"));
        }
        // R0：无有效指标
        if (items.isEmpty() && humidity == null && rain == null && temp == null && wind == null && soil == null) {
            items.add(new RiskItem("未知", "暂未识别到有效风险指标",
                    "上传内容中未能识别温度/湿度/降水/风速/土壤湿度等指标，当前无法进行风险判断。",
                    "提供包含「时间、指标、数值」的数据（如：6月1日 湿度 92%），或直接用自然语言描述田里情况。", "R0"));
        }

        String overall = valid.isEmpty() ? "未知" : items.stream().anyMatch(i -> "高".equals(i.level())) ? "高"
                : items.stream().anyMatch(i -> "中".equals(i.level())) ? "中" : "低";
        String summary = switch (overall) {
            case "高" -> "已提供指标触发 " + items.stream().filter(i -> "高".equals(i.level())).count() + " 项本地高风险阈值，请核实数据口径与现场情况并咨询农技员。";
            case "中" -> "已提供指标触发本地中风险阈值，请核实数据并加强巡查。";
            case "未知" -> "未识别到有效指标，暂时无法评估风险；这不代表低风险或没有风险。";
            default -> "已提供指标未触发当前本地规则；未提供的指标和规则未覆盖的风险仍未知，不能据此排除风险。";
        };
        return new RiskReport(overall, summary, items);
    }

    /** 报告转文字（供 Agent 上下文与前端渲染） */
    public String reportText(RiskReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("风险判定：总体").append(levelText(report.overall())).append("。").append(report.summary());
        for (RiskItem item : report.items()) {
            sb.append('\n').append("【").append(levelText(item.level())).append("】").append(item.title())
                    .append("\n依据：").append(item.reason())
                    .append("\n建议：").append(item.suggestion())
                    .append("\n规则：").append(item.rule());
        }
        return sb.toString();
    }

    private String levelText(String level) {
        return switch (level) {
            case "高" -> "高风险";
            case "中" -> "中风险";
            case "未知" -> "未知（无法评估）";
            default -> "低风险";
        };
    }

    private static boolean isNumeric(String s) {
        try {
            return Double.isFinite(parseNumber(s));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double parseNumber(String text) {
        return Double.parseDouble(text.replaceFirst("(?i)(?:%|℃|°C|mm|毫米|km/h)$", ""));
    }

    private static boolean validMetric(String name, double value) {
        if (!METRIC_NAMES.contains(name) || !Double.isFinite(value)) return false;
        if ("湿度".equals(name) || "土壤湿度".equals(name)) return value >= 0 && value <= 100;
        if ("风速".equals(name) || "降水量".equals(name)) return value >= 0;
        return true;
    }

    private static List<Double> recentValues(List<Map<String, Object>> series, String key) {
        List<Double> out = new ArrayList<>();
        for (Map<String, Object> row : series) {
            if (row == null) continue;
            Object v = row.get(key);
            if (v instanceof Number n && validMetric(key, n.doubleValue())) out.add(n.doubleValue());
        }
        int from = Math.max(0, out.size() - 10);
        return out.subList(from, out.size());
    }
}
