package com.nongxin.controller;

import com.nongxin.model.KnowledgeChunk;
import com.nongxin.model.KnowledgeDocument;
import com.nongxin.service.KnowledgeLibrary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class CatalogController {
    private final KnowledgeLibrary library;

    public CatalogController(KnowledgeLibrary library) {
        this.library = library;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "nongxin-api");
    }

    /** 来源登记清单：已核验原文在前，本地草稿仍标为未核验；附原文片段便于查看依据。 */
    @GetMapping("/api/knowledge")
    public List<Map<String, Object>> knowledge() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (KnowledgeDocument document : library.documents()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", document.id());
            item.put("title", document.title());
            item.put("institution", document.institution());
            item.put("url", document.url());
            item.put("publishedAt", document.publishedAt());
            item.put("fetchedAt", document.fetchedAt());
            item.put("region", document.region());
            item.put("crops", document.crops());
            item.put("topic", document.topic());
            item.put("version", document.version());
            item.put("license", document.license());
            item.put("reviewStatus", document.reviewStatus());
            item.put("reviewNote", document.reviewNote());
            item.put("chunks", chunksOf(document.id()));
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> chunksOf(String documentId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (KnowledgeChunk chunk : library.chunks()) {
            if (!chunk.documentId().equals(documentId)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", chunk.id());
            item.put("heading", chunk.heading());
            item.put("locator", chunk.locator());
            item.put("crop", chunk.crop());
            item.put("region", chunk.region());
            item.put("growthStage", chunk.growthStage());
            item.put("text", chunk.text());
            out.add(item);
        }
        return out;
    }
}
