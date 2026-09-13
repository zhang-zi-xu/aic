package com.nongxin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.model.KnowledgeChunk;
import com.nongxin.model.KnowledgeDocument;
import com.nongxin.service.impl.KnowledgeLibraryImpl;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeLibraryTest {
    private final KnowledgeLibrary library = new KnowledgeLibraryImpl(
            new ClassPathResource("kb.json"), new ClassPathResource("knowledge/sources.json"), new ObjectMapper());

    private List<KnowledgeLibrary.SourcedHit> search(String query, String crop, String region) {
        return library.search(query, crop, region, 3);
    }

    @Test
    void loadsVerifiedSourcesAndKeepsLocalDraftsUnverified() {
        List<KnowledgeDocument> documents = library.documents();
        assertThat(documents).filteredOn(KnowledgeDocument::verified).hasSize(5);
        assertThat(documents).filteredOn(d -> !d.verified()).hasSize(17);
        assertThat(documents).filteredOn(KnowledgeDocument::verified)
                .allSatisfy(d -> {
                    assertThat(d.url()).startsWith("https://");
                    assertThat(d.institution()).isNotBlank();
                    assertThat(d.publishedAt()).isNotBlank();
                    assertThat(d.version()).isNotBlank();
                    assertThat(d.license()).isNotBlank();
                    assertThat(d.fetchedAt()).isEqualTo("2026-09-08");
                });
        assertThat(documents).filteredOn(d -> !d.verified())
                .allSatisfy(d -> {
                    assertThat(d.url()).isNull();
                    assertThat(d.reviewStatus()).isEqualTo("unverified");
                });
        assertThat(library.chunks()).hasSize(44);
        assertThat(library.chunks()).allSatisfy(chunk -> {
            assertThat(chunk.documentId()).isNotBlank();
            assertThat(chunk.text()).isNotBlank();
        });
    }

    @Test
    void cropFilterNeverReturnsAnotherCropsMaterial() {
        assertThat(search("赤霉病 见花打药", "水稻", null))
                .noneMatch(hit -> "小麦".equals(hit.chunk().crop()));
        assertThat(search("稻瘟病 防治", "小麦", null))
                .noneMatch(hit -> "水稻".equals(hit.chunk().crop()));
        assertThat(search("稻瘟病 防治", "水稻", null)).isNotEmpty();
    }

    @Test
    void regionFilterKeepsLocalAndNationalMaterialOnly() {
        List<KnowledgeLibrary.SourcedHit> zhejiang = search("梅涝 排水", "水稻", "浙江");
        assertThat(zhejiang).isNotEmpty();
        assertThat(zhejiang).allMatch(hit -> hit.chunk().region() == null
                || hit.chunk().region().contains("浙江") || hit.chunk().region().contains("长江中下游")
                || hit.chunk().region().contains("全国"));

        List<KnowledgeLibrary.SourcedHit> henan = search("赤霉病 打药", "小麦", "河南");
        assertThat(henan).isNotEmpty();
        assertThat(henan).allMatch(hit -> hit.chunk().region() == null
                || hit.chunk().region().contains("河南") || hit.chunk().region().contains("黄淮")
                || hit.chunk().region().contains("全国"));
    }

    @Test
    void regionHierarchyLetsProvinceRequestsUseTheirAgroRegion() {
        assertThat(KnowledgeLibrary.regionUsable("浙江", "长江中下游")).isTrue();
        assertThat(KnowledgeLibrary.regionUsable("河南", "黄淮")).isTrue();
        assertThat(KnowledgeLibrary.regionUsable("浙江", "全国")).isTrue();
        assertThat(KnowledgeLibrary.regionUsable("浙江", null)).isTrue();
        assertThat(KnowledgeLibrary.regionUsable("浙江", "河南")).isFalse();
        assertThat(KnowledgeLibrary.regionUsable(null, "河南")).isTrue();
    }

    @Test
    void locationLabelsMapToRegisteredRegionsOnly() {
        assertThat(KnowledgeLibrary.regionOf("杭州市")).isEqualTo("浙江");
        assertThat(KnowledgeLibrary.regionOf("河南省南阳市")).isEqualTo("河南");
        assertThat(KnowledgeLibrary.regionOf("广州市")).isNull();
        assertThat(KnowledgeLibrary.regionOf(null)).isNull();
    }

    @Test
    void formattingAlwaysCarriesSourceIdInstitutionDateAndApplicability() {
        String text = library.formatForModel(search("稻瘟病 破口前", "水稻", null));
        assertThat(text).contains("来源ID：", "机构：", "发布日期：", "适用作物：", "适用地区：", "原文摘录：")
                .contains("只能引用下面列出的来源ID");
    }

    @Test
    void citationsResolveOnlyForIdsThatExistInTheLibrary() {
        List<KnowledgeLibrary.SourcedHit> resolved = library.resolve(Set.of("chunk-pest-rice-blast", "chunk-invented-by-model"));
        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().chunk().id()).isEqualTo("chunk-pest-rice-blast");
        assertThat(library.resolve(List.of())).isEmpty();
        assertThat(library.resolve(null)).isEmpty();
    }

    /**
     * 人工核验的检索用例：每条 query 都用真实资料核对过期望来源。
     * 准确率按真实结果计算，不放宽断言。
     */
    @Test
    void humanVerifiedRetrievalCasesMeetTheExpectedSource() {
        record Case(String query, String crop, String region, List<String> acceptable) {}
        List<Case> cases = List.of(
                new Case("稻瘟病 怎么防", "水稻", "浙江", List.of("chunk-pest-rice-blast")),
                new Case("稻飞虱 防治指标", "水稻", "浙江", List.of("chunk-pest-rice-planthopper")),
                new Case("高温热害 灌水", "水稻", "浙江", List.of("chunk-heat-water")),
                new Case("稻曲病 什么时候打药", "水稻", null, List.of("chunk-pest-rice-false-smut", "chunk-heat-pest")),
                new Case("纹枯病 病丛率", "水稻", null, List.of("chunk-pest-rice-sheath")),
                new Case("梅涝 排水 补肥", "水稻", "浙江", List.of("chunk-zjagri-drain", "chunk-zjagri-fertilize")),
                new Case("二化螟 枯鞘", "水稻", null, List.of("chunk-pest-rice-borer")),
                new Case("施药 风速 气温", "水稻", null, List.of("chunk-yipen-spray")),
                new Case("小麦赤霉病 见花打药", "小麦", "河南", List.of("chunk-henan-scab", "chunk-pest-wheat-scab")),
                new Case("条锈病 打点保面", "小麦", "河南", List.of("chunk-henan-scab", "chunk-pest-wheat-stripe-rust")),
                new Case("小麦蚜虫 百穗蚜量", "小麦", "河南", List.of("chunk-pest-wheat-aphid")),
                new Case("干热风 叶面肥", "小麦", "河南", List.of("chunk-henan-foliar")),
                new Case("灌浆水 什么时候浇", "小麦", "河南", List.of("chunk-henan-irrigation")),
                new Case("稻飞虱 抗药性", "水稻", null, List.of("chunk-pest-rice-planthopper")),
                new Case("小麦 茎基腐病 拌种", "小麦", "河南", List.of("chunk-pest-wheat-sheath")));

        long passed = 0;
        for (Case testCase : cases) {
            List<KnowledgeLibrary.SourcedHit> hits = library.search(testCase.query(), testCase.crop(), testCase.region(), 3);
            boolean hit = hits.stream().anyMatch(h -> testCase.acceptable().contains(h.chunk().id()));
            if (hit) passed++;
            else System.out.println("[retrieval-miss] '" + testCase.query() + "' crop=" + testCase.crop()
                    + " region=" + testCase.region() + " → " + hits.stream().map(h -> h.chunk().id()).toList());
        }
        double accuracy = (double) passed / cases.size();
        System.out.printf("[retrieval-accuracy] %d/%d = %.1f%%%n", passed, cases.size(), accuracy * 100);
        assertThat(accuracy).as("人工核验检索用例准确率").isEqualTo(1.0);
    }

    @Test
    void verifiedMaterialOutranksUnverifiedDraftsForTheSameTopic() {
        List<KnowledgeLibrary.SourcedHit> hits = search("稻瘟病 破口前", "水稻", null);
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().document().verified())
                .as("同主题下已核验原文应排在本地草稿之前：%s", hits.stream().map(h -> h.chunk().id()).toList())
                .isTrue();
    }

    @Test
    void sourceCardsAreBuiltFromTheLibraryAndIgnoreUnknownIds() {
        List<Map<String, Object>> cards = library.cards(List.of("chunk-pest-rice-blast", "chunk-forged"));
        assertThat(cards).hasSize(1);
        assertThat(cards.getFirst()).containsEntry("id", "chunk-pest-rice-blast")
                .containsEntry("status", "verified")
                .containsEntry("institution", "全国农技推广服务中心");
        assertThat((String) cards.getFirst().get("url")).startsWith("https://");
        assertThat(library.cards(List.of())).isEmpty();
        assertThat(library.cards(null)).isEmpty();
    }

    @Test
    void unrelatedQueriesReturnNothing() {
        assertThat(search("柑橘黄龙病 防治", null, null)).isEmpty();
        assertThat(search("", null, null)).isEmpty();
    }

    @Test
    void chunksKeepLocatorInformationForTraceability() {
        List<KnowledgeChunk> chunks = library.chunks();
        assertThat(chunks).filteredOn(chunk -> chunk.id().startsWith("chunk-") && !chunk.id().startsWith("chunk-draft-"))
                .allSatisfy(chunk -> {
                    assertThat(chunk.heading()).isNotBlank();
                    assertThat(chunk.locator()).isNotBlank();
                });
    }
}
