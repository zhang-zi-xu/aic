package com.nongxin.controller;

import com.nongxin.model.KbEntry;
import com.nongxin.service.KnowledgeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class CatalogController {
    private final KnowledgeService knowledge;

    public CatalogController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "nongxin-api");
    }

    @GetMapping("/api/knowledge")
    public List<KbEntry> knowledge() {
        return knowledge.list();
    }
}
