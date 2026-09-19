package com.nongxin.agent;

import com.nongxin.model.FarmTask;
import com.nongxin.model.FieldProfile;
import com.nongxin.model.PhenologyResult;
import com.nongxin.model.RiskReport;
import com.nongxin.model.TaskRecord;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.PhenologyService;
import com.nongxin.service.RiskService;
import com.nongxin.service.TaskService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 农心农业工具集：知识检索 / 田块档案 / 天气预报 / 风险判定 / 处方单 / 确认清单。
 * submit_* 为产出型工具，结果结构化采集供前端渲染卡片。
 */
@Component
public class AgriTools {

    /** 本次请求检索命中的片段 ID；处方依据只能从这个集合里取。 */
    public static final String RETRIEVED_CHUNKS = "retrievedChunks";

    private final KnowledgeLibrary knowledge;
    private final PhenologyService phenology;
    private final RiskService risk;
    private final TaskService tasks;
    private final com.nongxin.service.UploadService uploads;

    public AgriTools(KnowledgeLibrary knowledge, PhenologyService phenology, RiskService risk, TaskService tasks,
                     com.nongxin.service.UploadService uploads) {
        this.knowledge = knowledge;
        this.phenology = phenology;
        this.risk = risk;
        this.tasks = tasks;
        this.uploads = uploads;
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
                    sb.append(taskContext(field));
                    sb.append(photoContext(field));
                    return sb.toString();
                }));

        // ---- 知识检索 ----
        registry.register(ToolDefinition.of("search_agri_knowledge",
                "检索已登记来源的农技资料（含原文链接、机构、发布日期与适用地区/作物/生育期）。" +
                        "**凡涉及以下内容都必须先调用本工具**：病害/虫害识别与防治、农药使用（含安全间隔期、剂量、残留、混配）、" +
                        "施肥浇水、农时操作、天气与风险应对、以及任何会给出具体动作的问题。" +
                        "**唯一可以跳过的情形是与农业无关的问题。**" +
                        "特别提醒：即使问法听起来像常识（「打药要注意什么」「安全间隔期是怎么回事」「这个虫怎么治」），" +
                        "只要涉及农业投入品或田间操作，就必须先检索再回答——农户的口语提问正是最需要依据的场景。" +
                        "返回的每条资料都带「来源ID」，回答与处方只能引用这些 ID；" +
                        "没有命中就如实说明依据不足，不得凭记忆补充剂量或登记信息。",
                Map.of("type", "object",
                        "properties", Map.of("query", Map.of("type", "string", "description", "检索关键词，如：水稻稻瘟病、小麦赤霉病、高温热害、连阴雨")),
                        "required", List.of("query")),
                (args, ctx) -> prefetch(ctx, args.get("query") instanceof String s ? s : "")));

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
                        "且已有事实和适用资料足以支持所请求的行动时使用，不要求先建档或先发确认卡。" +
                        "仍缺影响行动安全性的关键信息时不得生成该行动方案，应先通过确认卡补充。" +
                        "每个动作需给出日期、时间窗口（window）、适用条件、所需物料（materials）、方法、风险与复查点，" +
                        "并在 evidence 中填写本次 search_agri_knowledge 返回的来源ID（如 chunk-pest-rice-blast）。" +
                        "非关键安排细节可写「待确认」，但未知诊断、用药前提或安全条件不能用占位词绕过；不得补造用量。" +
                        "按需获取田块、天气和资料依据；一般观察或整理记录不要求所有田块与天气字段齐全。" +
                        "没有适用资料支持具体防治行动时，不得提交该行动的处方；解释缺口并给核查步骤。" +
                        "方案只供用户审阅，不代表用户已确认或执行。" +
                        "若用户已提交执行/复查记录，方案要明确写出相较上次建议的变化点与原因。",
                planSchema(),
                (args, ctx) -> {
                    if (!(args.get("title") instanceof String title) || title.isBlank()
                            || !(args.get("items") instanceof List<?> items) || items.isEmpty()) {
                        throw new IllegalArgumentException("处方单需要标题和农事动作");
                    }
                    // 依据只能来自本次检索命中的来源：伪造、非列表、嵌套的 evidence 一律清空，不写进用户可见的处方。
                    Set<String> allowed = ctx.extra(RETRIEVED_CHUNKS) instanceof Set<?> set
                            ? (Set<String>) set : Set.of();
                    int[] removed = {0};
                    List<Object> sanitized = new ArrayList<>();
                    int index = 0;
                    for (Object item : items) {
                        Object cleaned = sanitizeEvidenceContainers(item, allowed, removed);
                        if (cleaned instanceof Map<?, ?> map) {
                            // 方案项稳定 ID：用户点「加入任务」时用它做幂等键（同一方案项只登记一次）
                            Map<String, Object> copy = new LinkedHashMap<>();
                            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
                            copy.put("itemId", "p" + (++index));
                            cleaned = copy;
                        }
                        sanitized.add(cleaned);
                    }
                    args.put("items", sanitized);
                    String note = removed[0] > 0
                            ? "其中 " + removed[0] + " 个依据不在本次检索结果中，已剔除，不要声称有资料支持。" : "";
                    return "已登记处方单「" + title + "」：共 " + sanitized.size() + " 项动作。" + note
                            + "请用自然语言向用户简短说明方案要点，并提醒以当地登记标签为准。"
                            + "方案里的时间窗口、所需物料若用户资料里没有，保持「待确认」字样，不要在对话里补造。";
                }));

        // ---- 待确认清单（产出型）----
        registry.register(ToolDefinition.of("submit_clarify",
                "提交结构化「待确认清单」，用于向用户追问缺失的关键信息（最多 3 项）。" +
                        "只有信息不足影响判断结果时才调用；每个问题尽量给出可点选的选项。" +
                        "信息不足时明确说明未知，解释需要补充的原因及核查步骤，不强迫先给诊断。" +
                        "不重复追问已回答的问题，「暂不确定」仍为未知，不能按追问轮数强制生成处方。" +
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
                    // 这个返回值决定模型下一轮的开场白：必须要求它先给实质内容，否则它只会写"确认卡已经发您了"。
                    return "已登记确认清单（" + count + " 项）。请简短解释缺什么、为什么需要以及核查步骤；"
                            + "信息不足时明确说明未知，不为了给结论而猜诊断或作业时间。"
                            + "用户可逐题作答后统一提交，「暂不确定」仍为未知，不得当成已经补齐。"
                            + "因为本卡用于补充影响判断的关键信息，这一轮不要提交处方单；"
                            + "待已有事实和适用资料足以支持行动后再提交方案，不以回答过几轮为依据。"
                            + "不要只写「确认卡已经发您了，点一下就行」这类没有实质内容的话。";
                }));

        return registry;
    }

    /**
     * 田块影像档案的近日记录：只报"什么时候拍过、备注写了什么"，让模型知道有影像可依据；
     * 图片本身只有用户要求带图时才会一起发过来（省钱，也避免把旧照片当现状）。
     */
    private String photoContext(FieldProfile field) {
        List<com.nongxin.service.UploadService.Stored> photos = uploads.latestForField(field.id(), 5);
        if (photos.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n【田间照片档案】最近拍摄：");
        sb.append(photos.stream()
                .map(photo -> (photo.observedAt() == null || photo.observedAt().isBlank() ? "日期未知" : photo.observedAt())
                        + (photo.note() == null || photo.note().isBlank() ? "" : "（" + photo.note() + "）"))
                .collect(java.util.stream.Collectors.joining("、")));
        sb.append("\n（这些是用户自己拍的照片；本轮有没有随附原图要看这次请求。");
        sb.append("需要看图时提示用户点「让农心看这块地最近的状况」，不要假装看过没发来的照片）");
        return sb.toString();
    }

    /**
     * 检索已登记资料。既是 search_agri_knowledge 工具的实现，也供服务端**预检索**调用。
     *
     * <p>为什么要预检索：检索原本完全依赖模型自愿调用工具，实测"打农药要注意什么？安全间隔期？"
     * 这类听起来像常识的问题，模型会跳过检索、直接凭记忆回答（无引用）。服务端在提问后按农业关键词
     * 自动检索一次并把结果注入提示词，可保证这类问题也有依据可引。
     */
    public String prefetch(AgentContext ctx, String query) {
        if (query == null || query.isBlank()) return "请提供检索关键词。";
        FieldProfile field = ctx.extra("field") instanceof FieldProfile f ? f : null;
        String fieldCrop = field == null || field.crop() == null || field.crop().isBlank() ? null : field.crop().trim();
        // 用户在田块（例如水稻）下问另一个作物（例如小麦）时，按问题里的作物检索，否则会被田块作物硬过滤挡掉。
        String askedCrop = knowledge.detectCrop(query);
        String cropFilter = askedCrop != null ? askedCrop : fieldCrop;
        boolean crossCrop = askedCrop != null && fieldCrop != null && !askedCrop.equals(fieldCrop);
        String region = KnowledgeLibrary.regionOf(locationLabel(ctx));
        List<KnowledgeLibrary.SourcedHit> hits = knowledge.search(query, cropFilter, region, 3);
        if (hits.isEmpty()) {
            return "没有检索到与本问题相关的已登记资料。请明确说明依据不足，给出下一步需要现场核实的信息，不要凭记忆补充药剂剂量或登记信息。";
        }
        Set<String> retrieved = ctx.extra(RETRIEVED_CHUNKS) instanceof Set<?> existing
                ? new LinkedHashSet<>((Set<String>) existing) : new LinkedHashSet<>();
        for (KnowledgeLibrary.SourcedHit hit : hits) retrieved.add(hit.chunk().id());
        ctx.put(RETRIEVED_CHUNKS, retrieved);
        String external = knowledge.formatForModel(hits);
        if (crossCrop) {
            external = "注意：本次按问题中提到的「" + askedCrop + "」检索，与当前田块作物（" + fieldCrop + "）不同。"
                    + "这些资料只能用于回答用户关于" + askedCrop + "的问题，不得用于当前田块的处方；回答时先说明这一点。\n" + external;
        }
        // 田块档案参与检索：从本田块历史记录中筛出与本次问题相关的条目（本地档案，不计入外部来源）
        String archive = matchFieldRecords(field, query);
        return archive.isBlank() ? external : archive + "\n\n" + external;
    }

    private String locationLabel(AgentContext ctx) {
        if (ctx.extra("location") instanceof Map<?, ?> location
                && location.get("label") instanceof String label && !label.isBlank()) {
            return label;
        }
        return "未填";
    }

    /**
     * 田块的任务与用户记录：让对话知道"哪些安排真的做了、复查看到什么"。
     * 这里输出的是用户提交的事实，模型不得把待执行的任务当成已经执行。
     */
    private String taskContext(FieldProfile field) {
        List<FarmTask> fieldTasks = tasks.forField(field.id(), 8);
        if (fieldTasks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n农事任务与用户记录（用户提交的事实，不是模型推断）：\n");
        for (FarmTask task : fieldTasks) {
            sb.append("- [").append(task.statusLabel()).append("] ")
                    .append(task.date() == null || task.date().isBlank() ? "日期待定" : task.date())
                    .append(' ').append(task.title());
            if (task.condition() != null && !task.condition().isBlank()) sb.append("（条件：").append(task.condition()).append("）");
            sb.append('\n');
            for (TaskRecord record : task.records()) {
                sb.append("    · ").append(TaskRecord.kindLabel(record.kind())).append(' ').append(record.date())
                        .append("：").append(record.note());
                String outcome = TaskRecord.outcomeLabel(record.outcome());
                if (!outcome.isBlank()) sb.append("（复查结论：").append(outcome).append("）");
                sb.append('\n');
            }
        }
        sb.append("（以上记录由用户提交：标「待确认/待执行」的任务还没做，不得说成已完成；");
        sb.append("复查后调整建议时必须说明哪里变了、为什么变）");
        return sb.toString();
    }

    /**
     * 递归清洗任意层级里的 evidence 字段：非列表形态一律清空，列表里只保留本次检索命中的来源ID。
     * 返回新的容器，不修改调用方传入的对象。
     */
    @SuppressWarnings("unchecked")
    public static Object sanitizeEvidenceContainers(Object value, Set<String> allowed, int[] removed) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                copy.put(key, "evidence".equalsIgnoreCase(key)
                        ? cleanEvidence(entry.getValue(), allowed, removed)
                        : sanitizeEvidenceContainers(entry.getValue(), allowed, removed));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) copy.add(sanitizeEvidenceContainers(item, allowed, removed));
            return copy;
        }
        return value;
    }

    private static List<String> cleanEvidence(Object value, Set<String> allowed, int[] removed) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object id : list) {
                if (id instanceof String sourceId && allowed.contains(sourceId.trim())) out.add(sourceId.trim());
                else removed[0]++;
            }
        } else if (value != null) {
            // 非列表形态（字符串、对象等）无法校验，一律清空。
            removed[0]++;
        }
        return out;
    }

    /**
     * 田块档案检索：把本田块历史记录中与当前问题相关的条目挑出来（本地档案，非外部资料）。
     * 说明：记录是运行时数据、随时新增，故采用查询期匹配而非预先向量化；
     * 无明确匹配时回退最近 3 条，保证"田块连续档案"始终参与上下文。
     */
    private String matchFieldRecords(FieldProfile field, String query) {
        if (field == null || field.records() == null || field.records().isEmpty()) return "";
        List<com.nongxin.model.FieldRecord> records = field.records();
        Set<String> queryGrams = new LinkedHashSet<>();
        String cleaned = query.replaceAll("[\\s，。？！、；：\"'“”（）【】\\-—…·%]", "");
        for (int i = 0; i < cleaned.length(); i++) {
            queryGrams.add(String.valueOf(cleaned.charAt(i)));
            if (i + 1 < cleaned.length()) queryGrams.add(cleaned.substring(i, i + 2));
        }
        List<com.nongxin.model.FieldRecord> matched = new ArrayList<>();
        for (com.nongxin.model.FieldRecord record : records) {
            String note = record.note() == null ? "" : record.note();
            for (String gram : queryGrams) {
                if (gram.length() >= 2 && note.contains(gram)) { matched.add(record); break; }
            }
        }
        List<com.nongxin.model.FieldRecord> use = matched.isEmpty()
                ? records.subList(Math.max(0, records.size() - 3), records.size())
                : matched.subList(Math.max(0, matched.size() - 5), matched.size());
        StringBuilder sb = new StringBuilder("【本田块档案】").append(field.name());
        if (!matched.isEmpty()) sb.append("（含与本次问题相关的历史记录）");
        sb.append('\n');
        for (com.nongxin.model.FieldRecord record : use) {
            sb.append("- ").append(record.date()).append(' ').append(record.note()).append('\n');
        }
        sb.append("（以上为田块本地记录，可作为判断背景，但不得当作外部资料引用）");
        return sb.toString();
    }
    private Map<String, Object> planSchema() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("date", Map.of("type", "string", "description", "执行日期 YYYY-MM-DD；若天气相关，注明条件（如：雨前）"));
        itemProps.put("window", Map.of("type", "string", "description", "建议时间窗口/物候窗口，如：破口前 3—5 天、齐穗期；资料或用户信息里没有就写「待确认」"));
        itemProps.put("task", Map.of("type", "string", "description", "动作名称，如：晒田、喷施防治药"));
        itemProps.put("dosage", Map.of("type", "string", "description", "用量/配比（参考区间，注明以登记标签为准）"));
        itemProps.put("materials", Map.of("type", "string", "description", "完成该动作需要的物料/器械；不清楚就写「待确认」，不要编造品牌或规格"));
        itemProps.put("method", Map.of("type", "string", "description", "操作方法"));
        itemProps.put("condition", Map.of("type", "string", "description", "执行条件（天气/生育期/观察前提）"));
        itemProps.put("warning", Map.of("type", "string", "description", "风险提示与避开事项"));
        itemProps.put("review", Map.of("type", "string", "description", "复查动作与时间（或触发条件）"));
        itemProps.put("evidence", Map.of("type", "array", "items", Map.of("type", "string"), "description", "依据来源ID列表，必须取自本次 search_agri_knowledge 返回的来源ID，如 [\"chunk-pest-rice-blast\"]"));

        Map<String, Object> itemSchema = Map.of(
                "type", "object",
                "properties", itemProps,
                "required", List.of("date", "window", "task", "condition", "materials", "review", "evidence"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string", "description", "方案标题，如：水稻分蘖期一周农事方案"));
        props.put("crop", Map.of("type", "string", "description", "作物名称"));
        props.put("fieldName", Map.of("type", "string", "description", "田块名称"));
        props.put("summary", Map.of("type", "string", "description", "方案要点总结（2-3 句话）"));
        props.put("items", Map.of("type", "array", "description", "按时间顺序的农事动作清单（每日 1-3 件）", "items", itemSchema));

        return Map.of("type", "object", "properties", props, "required", List.of("title", "crop", "fieldName", "summary", "items"));
    }
}
