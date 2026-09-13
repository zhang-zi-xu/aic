package com.nongxin.controller;

import com.nongxin.service.EmbeddingService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索效果评估（口径修正版）。
 *
 * 三条诚实原则（上一版评估的偏差已修正）：
 * 1. **同深度对照**：基线与混合检索使用相同召回深度（默认 Top5），避免"基线被卡死、混合路靠通道多取胜"；
 * 2. **用例分型**：literal（含字面线索，字符级向量即可命中）与 semantic（零字面重合，只有真实语义模型才该命中）
 *    分开统计——本地哈希向量在 semantic 类上的命中**不计入有效成绩**；
 * 3. **如实标注当前向量类型**：本地哈希（离线兜底）或远程语义模型，报告里明确区分，避免把兜底能力当语义成绩。
 */
@RestController
@RequestMapping("/api/kb")
public class RetrievalEvalController {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvalController.class);

    /** 用例类型 */
    private enum Kind {
        /** 含字面线索：字符级相似即可召回（本地哈希向量也能命中，属"管线有效"证据） */
        LITERAL,
        /** 零字面重合：需要真正理解语义（只有真实 embedding 模型命中才算数） */
        SEMANTIC
    }

    private record EvalCase(String crop, String query, List<String> expect, Kind kind) {}

    /** 评估集：口语化提问 + 期望命中的资料关键词 + 类型标注 */
    private static final List<EvalCase> CASES = List.of(
            // —— 含字面线索（"叶""白""虫""积水"等与资料用词重合）——
            new EvalCase("水稻", "叶子像被开水烫过一样发白枯萎", List.of("稻瘟", "叶瘟"), Kind.LITERAL),
            new EvalCase("水稻", "稻丛基部一层小白虫，一碰就飞起来", List.of("飞虱"), Kind.LITERAL),
            new EvalCase("水稻", "稻叶上有一圈圈像云彩的花纹", List.of("纹枯", "云纹"), Kind.LITERAL),
            new EvalCase("小麦", "麦叶上一道道黄粉，抹一下手上全是粉", List.of("锈"), Kind.LITERAL),
            new EvalCase("小麦", "麦子还没熟就干枯发白了", List.of("干热风", "逼熟"), Kind.LITERAL),
            new EvalCase("玉米", "玉米叶子被虫咬出一排排小孔", List.of("玉米螟", "钻蛀"), Kind.LITERAL),
            new EvalCase("通用", "打完药叶子反而焦了", List.of("药害", "烧叶"), Kind.LITERAL),
            new EvalCase("通用", "下大雨田里积水排不出去", List.of("涝", "积水", "排水"), Kind.LITERAL),
            // —— 零字面重合（口语现象与资料术语无共用词，考验真实语义能力）——
            new EvalCase("水稻", "禾苗蔫头耷脑，叶尖先黄后枯", List.of("稻瘟", "飞虱", "螟", "涝", "药害"), Kind.SEMANTIC),
            new EvalCase("小麦", "麦子抽穗后穗子发白不结实", List.of("赤霉", "穗腐", "锈", "干热风"), Kind.SEMANTIC),
            new EvalCase("水稻", "田里一片一片地瘫倒，茎基发黑", List.of("倒伏", "纹枯"), Kind.SEMANTIC),
            new EvalCase("玉米", "玉米棵子长得又高又细，风一来就折", List.of("倒伏", "控旺"), Kind.SEMANTIC));

    private final KnowledgeLibrary library;
    private final EmbeddingService embedding;
    private final VectorIndexService vectorIndex;

    public RetrievalEvalController(KnowledgeLibrary library, EmbeddingService embedding, VectorIndexService vectorIndex) {
        this.library = library;
        this.embedding = embedding;
        this.vectorIndex = vectorIndex;
    }

    @PostMapping("/eval")
    public Map<String, Object> evaluate(@RequestParam(name = "depth", defaultValue = "5") int depth) {
        int topK = Math.max(3, Math.min(depth, 10));
        boolean vectorReady = vectorIndex.ready();
        String vectorKind = vectorReady ? classifyVector() : "none";

        int[] keywordHit = new int[2];  // [literal, semantic]
        int[] hybridHit = new int[2];
        int[] total = new int[2];
        int keywordRecallSum = 0;
        int hybridRecallSum = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        for (EvalCase evalCase : CASES) {
            int idx = evalCase.kind() == Kind.LITERAL ? 0 : 1;
            total[idx]++;

            // 同深度对照：两条路径都用 topK
            List<KnowledgeLibrary.SourcedHit> keywordOnly = library.searchKeywordOnly(evalCase.query(), evalCase.crop(), topK);
            List<KnowledgeLibrary.SourcedHit> hybrid = library.search(evalCase.query(), evalCase.crop(), null, topK);
            boolean kHit = matches(keywordOnly, evalCase.expect());
            boolean hHit = matches(hybrid, evalCase.expect());

            if (kHit) keywordHit[idx]++;
            // 本地哈希向量在 semantic 类上的命中不计入有效成绩（它不是语义能力）
            boolean hCounted = hHit && !(evalCase.kind() == Kind.SEMANTIC && "local-hash".equals(vectorKind));
            if (hCounted) hybridHit[idx]++;

            keywordRecallSum += keywordOnly.size();
            hybridRecallSum += hybrid.size();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", evalCase.kind().name().toLowerCase());
            row.put("crop", evalCase.crop());
            row.put("query", evalCase.query());
            row.put("expect", evalCase.expect());
            row.put("keywordTop", titles(keywordOnly));
            row.put("hybridTop", titles(hybrid));
            row.put("keywordHit", kHit);
            row.put("hybridHit", hHit);
            if (evalCase.kind() == Kind.SEMANTIC && "local-hash".equals(vectorKind)) {
                row.put("note", "本地哈希向量命中不计入语义成绩：该类型需要真实语义模型复测");
            }
            details.add(row);
        }

        int literalTotal = total[0];
        int semanticTotal = total[1];
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("depth", topK);
        out.put("cases", CASES.size());
        out.put("vectorKind", vectorKind);
        out.put("vectorReady", vectorReady);
        out.put("note", buildNote(vectorKind));
        out.put("literalHitRate", rate(keywordHit[0], literalTotal) + " -> " + rate(hybridHit[0], literalTotal));
        out.put("literal", section(keywordHit[0], hybridHit[0], literalTotal));
        out.put("semanticHitRate", rate(keywordHit[1], semanticTotal) + " -> " + rate(hybridHit[1], semanticTotal));
        out.put("semantic", section(keywordHit[1], hybridHit[1], semanticTotal));
        out.put("avgRecallCount", Map.of(
                "keyword", String.format("%.1f", (double) keywordRecallSum / CASES.size()),
                "hybrid", String.format("%.1f", (double) hybridRecallSum / CASES.size())));
        out.put("details", details);
        log.info("检索评估(depth={}, vector={}): literal {}/{} -> {}/{}; semantic {}/{} -> {}/{}",
                topK, vectorKind,
                keywordHit[0], literalTotal, hybridHit[0], literalTotal,
                keywordHit[1], semanticTotal, hybridHit[1], semanticTotal);
        return out;
    }

    private Map<String, Object> section(int keywordHit, int hybridHit, int total) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cases", total);
        out.put("keywordHits", keywordHit);
        out.put("hybridHits", hybridHit);
        return out;
    }

    private String buildNote(String vectorKind) {
        return switch (vectorKind) {
            case "local-hash" -> "当前为本地哈希向量（离线兜底）：只能证明检索管线有效，不能作为语义检索成绩；"
                    + "semantic 类用例必须配置真实 embedding 模型（NONGXIN_EMBEDDING_KEY）后复测。";
            case "remote-model" -> "当前为远程语义模型：结果可用于说明语义检索效果，建议同时报告无关条目占比与人工相关性判定。";
            default -> "当前未启用向量路：hybrid 列与 keyword 列相同（纯关键词检索）。";
        };
    }

    /** 判断当前向量实现类型：远程语义模型 or 本地哈希兜底 */
    private String classifyVector() {
        String model = embedding.modelName() == null ? "" : embedding.modelName();
        return model.startsWith("local-hash") ? "local-hash" : "remote-model";
    }

    private boolean matches(List<KnowledgeLibrary.SourcedHit> hits, List<String> expect) {
        for (KnowledgeLibrary.SourcedHit hit : hits) {
            String haystack = (hit.chunk().heading() == null ? "" : hit.chunk().heading())
                    + " " + (hit.chunk().text() == null ? "" : hit.chunk().text());
            for (String keyword : expect) {
                if (haystack.contains(keyword)) return true;
            }
        }
        return false;
    }

    private List<String> titles(List<KnowledgeLibrary.SourcedHit> hits) {
        List<String> out = new ArrayList<>();
        for (KnowledgeLibrary.SourcedHit hit : hits) {
            String heading = hit.chunk().heading();
            out.add(heading == null || heading.isBlank() ? hit.chunk().id() : heading);
        }
        return out;
    }

    private String rate(int hit, int total) {
        return total == 0 ? "0%" : String.format("%.1f%% (%d/%d)", 100.0 * hit / total, hit, total);
    }
}
