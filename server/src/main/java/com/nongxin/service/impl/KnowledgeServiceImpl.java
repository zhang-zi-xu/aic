package com.nongxin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.model.KbData;
import com.nongxin.model.KbEntry;
import com.nongxin.service.KnowledgeService;
import com.nongxin.service.KnowledgeService.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 农业知识库实现：kb.json 条目加载 + 关键词加权检索（2-gram 召回 + 同义词扩展）。
 * 打分权重：标题 10 > 关键词 8 > 作物 5 > 正文 3（与 TS 版一致）。
 */
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeServiceImpl.class);

    private static final List<String[]> SYNONYMS = List.of(
            new String[]{"稻瘟病", "稻瘟", "叶瘟", "穗颈瘟"},
            new String[]{"纹枯病", "纹枯", "云纹"},
            new String[]{"飞虱", "稻飞虱", "褐飞虱", "白背飞虱"},
            new String[]{"螟虫", "二化螟", "三化螟", "钻心虫"},
            new String[]{"赤霉病", "赤霉", "穗腐"},
            new String[]{"锈病", "条锈", "叶锈"},
            new String[]{"蚜虫", "麦蚜", "穗蚜"},
            new String[]{"玉米螟", "钻心虫"},
            new String[]{"大斑病", "叶斑"},
            new String[]{"干热风", "高温逼熟"},
            new String[]{"倒伏", "抗倒", "控旺"},
            new String[]{"涝", "积水", "洪涝", "渍"},
            new String[]{"晒田", "烤田", "控蘖"},
            new String[]{"药害", "烧叶", "畸形"},
            new String[]{"化肥", "施肥", "追肥", "氮肥"});

    private static final List<String[]> CROP_ALIASES = List.of(
            new String[]{"水稻", "水稻", "稻子", "稻", "稻田", "秧"},
            new String[]{"小麦", "小麦", "麦子", "麦田", "麦"},
            new String[]{"玉米", "玉米", "苞谷", "棒子"});

    private final ObjectMapper objectMapper;
    private List<KbEntry> entries = new ArrayList<>();

    public KnowledgeServiceImpl(@Value("${nongxin.kb-resource}") Resource kbResource, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try {
            KbData data = objectMapper.readValue(kbResource.getInputStream(), KbData.class);
            entries = data.entries();
            log.info("知识库加载完成：{} 条", entries.size());
        } catch (Exception e) {
            log.error("知识库加载失败", e);
        }
    }

    @Override
    public int entryCount() {
        return entries.size();
    }

    @Override
    public List<KbEntry> list() {
        return List.copyOf(entries);
    }

    @Override
    public List<Hit> search(String query, String cropFilter, int topK) {
        if (query == null || query.isBlank()) return List.of();
        Set<String> terms = expandTerms(ngrams(query));
        if (terms.isEmpty()) return List.of();

        String crop = cropFilter;
        if (crop == null) {
            for (String[] aliases : CROP_ALIASES) {
                for (int i = 1; i < aliases.length; i++) {
                    if (query.contains(aliases[i])) { crop = aliases[0]; break; }
                }
                if (crop != null) break;
            }
        }
        final String cropFinal = crop;

        return entries.stream()
                .filter(e -> cropFinal == null || e.crop().equals(cropFinal) || "通用".equals(e.crop()))
                .map(e -> new Hit(e, scoreEntry(e, terms)))
                .filter(h -> h.score() > 0)
                .sorted((a, b) -> b.score() != a.score()
                        ? Integer.compare(b.score(), a.score())
                        : a.entry().id().compareTo(b.entry().id()))
                .limit(topK)
                .toList();
    }

    @Override
    public String formatHits(List<Hit> hits) {
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            KbEntry e = hits.get(i).entry();
            sb.append('【').append(i + 1).append("】").append(e.crop()).append('·').append(e.topic())
                    .append("｜").append(e.title()).append('\n')
                    .append("症状：").append(e.symptom()).append('\n')
                    .append("判断：").append(e.diagnosis()).append('\n')
                    .append("处置：\n");
            for (int j = 0; j < e.advice().size(); j++) {
                sb.append(j + 1).append(". ").append(e.advice().get(j)).append('\n');
            }
            sb.append("复查：").append(e.review()).append('\n')
                    .append("依据：").append(e.source()).append('\n');
            if (i < hits.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private int scoreEntry(KbEntry e, Set<String> terms) {
        int score = 0;
        String title = e.title().toLowerCase();
        String body = (e.title() + " " + e.symptom() + " " + e.diagnosis() + " "
                + String.join(" ", e.advice()) + " " + e.review()).toLowerCase();
        String cropLower = e.crop().toLowerCase();
        for (String term : terms) {
            String t = term.toLowerCase();
            if (title.contains(t)) score += 10;
            else if (e.keywords().stream().anyMatch(k -> k.toLowerCase().contains(t) || t.contains(k.toLowerCase()))) score += 8;
            else if (cropLower.contains(t) || t.contains(cropLower)) score += 5;
            else if (body.contains(t)) score += 3;
        }
        return score;
    }

    /** 2-gram 切词（中文按双字滑窗 + 原始片段） */
    private Set<String> ngrams(String query) {
        Set<String> out = new LinkedHashSet<>();
        String cleaned = query.replaceAll("[\\s，。？！、；：\"'“”（）【】\\-—…·%0-9a-zA-Z]", "");
        if (cleaned.length() >= 2) out.add(cleaned);
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            out.add(cleaned.substring(i, i + 2));
        }
        return out;
    }

    private Set<String> expandTerms(Set<String> terms) {
        Set<String> expanded = new LinkedHashSet<>(terms);
        for (String[] group : SYNONYMS) {
            boolean hit = false;
            for (String t : terms) {
                for (String alias : group) {
                    if (t.contains(alias) || alias.contains(t)) { hit = true; break; }
                }
                if (hit) break;
            }
            if (hit) expanded.addAll(List.of(group));
        }
        return expanded;
    }
}
