package com.nongxin.agent;

import com.nongxin.model.FieldProfile;
import com.nongxin.model.PhenologyResult;
import com.nongxin.model.RiskReport;
import com.nongxin.service.KnowledgeService;
import com.nongxin.service.PhenologyService;
import com.nongxin.service.RiskService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 农心农业工具集：知识检索 / 田块档案 / 天气预报 / 风险判定 / 处方单 / 确认清单。
 * submit_* 为产出型工具，结果结构化采集供前端渲染卡片。
 */
@Component
public class AgriTools {

    private final KnowledgeService knowledge;
    private final PhenologyService phenology;
    private final RiskService risk;

    public AgriTools(KnowledgeService knowledge, PhenologyService phenology, RiskService risk) {
        this.knowledge = knowledge;
        this.phenology = phenology;
        this.risk = risk;
    }

    public ToolRegistry buildRegistry() {
        ToolRegistry registry = new ToolRegistry();

        // ---- 田块档案 ----
        registry.register(ToolDefinition.of("get_field_context",
                "获取当前田块的档案信息（作物、品种、播期、生育期、位置、历史记录）。" +
                        "当用户的问题与具体田块有关时应调用本工具获取田块事实，不要凭记忆编造。",
                Map.of("type", "object", "properties", Map.of()),
                (args, ctx) -> {
                    FieldProfile field = (FieldProfile) ctx.extra("field");
                    if (field == null) return "本次未提供田块档案。可继续回答一般问题；具体建议只需补充影响判断的作物、播期或实际生育期等信息。";
                    PhenologyResult phen = phenology.getPhenology(field.crop(), field.sowDate());
                    StringBuilder sb = new StringBuilder();
                    sb.append("田块：").append(field.name()).append('\n')
                            .append("作物：").append(field.crop())
                            .append(field.variety() != null && !field.variety().isBlank() ? "（" + field.variety() + "）" : "").append('\n')
                            .append("播期：").append(field.sowDate()).append('\n')
                            .append("估算生育期：").append(phen.note() == null || phen.note().isBlank() ? "暂无可用估算，请以田间观察为准" : phen.note()).append('\n')
                            .append("面积：").append(field.areaMu() != null ? field.areaMu() + " 亩" : "未填").append('\n')
                            .append("位置：").append(locationLabel(ctx)).append('\n');
                    if (field.records() != null && !field.records().isEmpty()) {
                        sb.append("历史记录：");
                        for (int i = Math.max(0, field.records().size() - 5); i < field.records().size(); i++) {
                            var r = field.records().get(i);
                            sb.append(r.date()).append(' ').append(r.note()).append("；");
                        }
                        sb.append('\n');
                    }
                    if (field.notes() != null && !field.notes().isBlank()) {
                        sb.append("备注：").append(field.notes()).append('\n');
                    }
                    return sb.toString();
                }));

        // ---- 知识检索 ----
        registry.register(ToolDefinition.of("search_agri_knowledge",
                "在本地农业知识库检索病虫害防治、水肥调控、农时操作等整理条目（来源待核验）。" +
                        "涉及病害/虫害识别与防治、施肥浇水、农时操作时必须调用本工具获取专业依据；" +
                        "常识性问题不需要调用。引用须标为本地知识库，不得将来源名称说成已经核验的官方原文或登记标签。",
                Map.of("type", "object",
                        "properties", Map.of("query", Map.of("type", "string", "description", "检索关键词，如：水稻稻瘟病、小麦赤霉病、玉米倒伏、连阴雨后高温")),
                        "required", List.of("query")),
                (args, ctx) -> {
                    String query = args.get("query") instanceof String s ? s : "";
                    if (query.isBlank()) return "请提供检索关键词。";
                    List<KnowledgeService.Hit> hits = knowledge.search(query, null, 3);
                    if (hits.isEmpty()) return "知识库暂无高度相关的条目，请根据常识审慎回答，明确说明依据不足。";
                    return "【本地知识库，来源待核验】以下为本地整理资料，来源名称未逐条核验原文，不能据此声称官方已确认、现行登记或最新测报；用药剂量需另外核对有效登记标签。\n"
                            + knowledge.formatHits(hits);
                }));

        // ---- 7 日天气 ----
        registry.register(ToolDefinition.of("get_weather_forecast",
                "获取田块所在地未来 7 天天气预报（温度/降水概率/风速）。" +
                        "当需要制定农事时间安排、判断施药窗口、评估暴雨大风影响时调用。",
                Map.of("type", "object", "properties", Map.of()),
                (args, ctx) -> {
                    String forecast = ctx.extraString("forecastText");
                    if (forecast.isBlank()) return "未获取到 7 日天气预报：请确保已设置田块位置或所在城市。";
                    return forecast;
                }));

        // ---- 风险判定（产出型）----
        registry.register(ToolDefinition.of("submit_risk_report",
                "对用户提供的农情数据（温度/湿度/降水量/风速/土壤湿度等时序或快照数据）" +
                        "进行规则化风险判定，输出结构化风险报告（分级+依据+建议）。" +
                        "用户上传数据文件或给出数值时调用；调用后向用户引用风险等级并给出建议。",
                Map.of("type", "object",
                        "properties", Map.of("data", Map.of("type", "string", "description", "用户提供的农情数据原文（CSV 或自然语言描述，需保留数值）")),
                        "required", List.of("data")),
                (args, ctx) -> {
                    String data = args.get("data") instanceof String s ? s : "";
                    if (data.isBlank()) throw new IllegalArgumentException("未提供农情数据");
                    List<Map<String, Object>> series = risk.parseFarmData(data);
                    Map<String, Double> metrics = risk.extractMetrics(data);
                    if (metrics.isEmpty()) {
                        // 从序列中汇总最后值
                        Map<String, Double> agg = new LinkedHashMap<>();
                        for (Map<String, Object> row : series) {
                            for (Map.Entry<String, Object> e : row.entrySet()) {
                                if (e.getValue() instanceof Number n) agg.put(e.getKey(), n.doubleValue());
                            }
                        }
                        metrics = agg;
                    }
                    FieldProfile field = (FieldProfile) ctx.extra("field");
                    String phaseText = "";
                    if (field != null) {
                        PhenologyResult phen = phenology.getPhenology(field.crop(), field.sowDate());
                        phaseText = phen.note();
                    }
                    RiskReport report = risk.assess(metrics, series, phaseText);
                    // Keep the rule engine's computed report separate from model-provided tool arguments.
                    ctx.put(AgentRunner.SUBMISSION_RESULT_KEY, report);
                    return risk.reportText(report);
                }));

        // ---- 处方单（产出型）----
        registry.register(ToolDefinition.of("submit_farm_plan",
                "提交一份结构化农事处方单（方案）。当用户要求「方案/计划/处方/怎么办/安排」" +
                        "且你已掌握足够信息（作物、生育期、天气、知识库依据）时，必须调用本工具提交最终方案。" +
                        "每个动作需给出时间（具体日期）、用量（无则写明条件）、方法、风险与复查点，" +
                        "并在 evidence 中引用知识库条目 id（如 rice-blast）。生成前先调用 get_field_context、" +
                        "get_weather_forecast、search_agri_knowledge 获取依据。",
                planSchema(),
                (args, ctx) -> {
                    if (!(args.get("title") instanceof String title) || title.isBlank()
                            || !(args.get("items") instanceof List<?> items) || items.isEmpty()) {
                        throw new IllegalArgumentException("处方单需要标题和农事动作");
                    }
                    int count = items.size();
                    return "已登记处方单「" + title + "」：共 " + count + " 项动作。请用自然语言向用户简短说明方案要点，并提醒以当地登记标签为准。";
                }));

        // ---- 待确认清单（产出型）----
        registry.register(ToolDefinition.of("submit_clarify",
                "提交结构化「待确认清单」，用于向用户追问缺失的关键信息（最多 3 项）。" +
                        "只有信息不足影响判断结果时才调用；每个问题尽量给出可点选的选项。" +
                        "调用前必须已在回答中给出能给的结论或可执行部分，不要把追问当成回答的主体。" +
                        "注意：本工具是追问的唯一通道——只有调用它，用户界面才会出现确认卡。禁止只用文字描述清单代替工具调用。",
                Map.of("type", "object",
                        "properties", Map.of(
                                "intro", Map.of("type", "string", "description", "给用户的一句话引导，先共情一句，再说明确认后能做什么"),
                                "items", Map.of("type", "array", "description", "需要用户确认的信息项，最多 3 项",
                                        "items", Map.of("type", "object",
                                                "properties", Map.of(
                                                        "question", Map.of("type", "string", "description", "问题本身，口语化"),
                                                        "options", Map.of("type", "array", "items", Map.of("type", "string"), "description", "可点选的选项（2-4 个）；无法提供选项时传空数组"),
                                                        "hint", Map.of("type", "string", "description", "可选补充说明，如：拍照发来更好")),
                                                "required", List.of("question")))),
                        "required", List.of("intro", "items")),
                (args, ctx) -> {
                    if (!(args.get("intro") instanceof String intro) || intro.isBlank()
                            || !(args.get("items") instanceof List<?> items) || items.isEmpty() || items.size() > 3
                            || items.stream().anyMatch(item -> !(item instanceof Map<?, ?> question)
                                    || !(question.get("question") instanceof String text) || text.isBlank())) {
                        throw new IllegalArgumentException("确认清单需要引导语和 1 至 3 个相关问题");
                    }
                    int count = items.size();
                    return "已登记确认清单（" + count + " 项）。请用 1-2 句话告知用户信息已记录，等用户点选后继续。";
                }));

        return registry;
    }

    private String locationLabel(AgentContext ctx) {
        if (ctx.extra("location") instanceof Map<?, ?> location
                && location.get("label") instanceof String label && !label.isBlank()) {
            return label;
        }
        return "未填";
    }

    private Map<String, Object> planSchema() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("date", Map.of("type", "string", "description", "执行日期 YYYY-MM-DD；若天气相关，注明条件（如：雨前）"));
        itemProps.put("task", Map.of("type", "string", "description", "动作名称，如：晒田、喷施防治药"));
        itemProps.put("dosage", Map.of("type", "string", "description", "用量/配比（参考区间，注明以登记标签为准）"));
        itemProps.put("method", Map.of("type", "string", "description", "操作方法"));
        itemProps.put("condition", Map.of("type", "string", "description", "执行条件（天气/生育期/观察前提）"));
        itemProps.put("warning", Map.of("type", "string", "description", "风险提示与避开事项"));
        itemProps.put("review", Map.of("type", "string", "description", "复查动作与时间"));
        itemProps.put("evidence", Map.of("type", "array", "items", Map.of("type", "string"), "description", "依据的知识库条目 id 列表，如 [\"rice-blast\"]"));

        Map<String, Object> itemSchema = Map.of(
                "type", "object",
                "properties", itemProps,
                "required", List.of("date", "task", "condition", "review", "evidence"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string", "description", "方案标题，如：水稻分蘖期一周农事方案"));
        props.put("crop", Map.of("type", "string", "description", "作物名称"));
        props.put("fieldName", Map.of("type", "string", "description", "田块名称"));
        props.put("summary", Map.of("type", "string", "description", "方案要点总结（2-3 句话）"));
        props.put("items", Map.of("type", "array", "description", "按时间顺序的农事动作清单（每日 1-3 件）", "items", itemSchema));

        return Map.of("type", "object", "properties", props, "required", List.of("title", "crop", "fieldName", "summary", "items"));
    }
}
