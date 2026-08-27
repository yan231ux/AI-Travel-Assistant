package com.yuntu.tripplanner.service;

import com.yuntu.tripplanner.client.ChromaVectorStore;
import com.yuntu.tripplanner.client.EmbeddingClient;
import com.yuntu.tripplanner.config.ChromaConfig;
import com.yuntu.tripplanner.repository.GuideEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * Chroma 向量库集成测试（需要真实 Chroma server + MySQL，门控开启，不随 mvn test 运行）。
 *
 * <p>启动：{@code mvn test -Dtest=ChromaIntegrationCheck -Drag.chroma=true}
 * （先 {@code pip install chromadb && chroma run --host 127.0.0.1 --port 8000}）。
 *
 * <p>覆盖：store 往返（upsert→query→余弦排名与手算一致）、RagService 真实走 Chroma、
 * Chroma 不可达时降级为内存检索。用 @MockBean 隔离 EmbeddingClient / 缓存 / 向量落库，
 * 保证确定性且不污染 MySQL 向量缓存。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "rag.chroma", matches = "true")
@TestPropertySource(properties = {
        "chroma.enabled=true",
        "chroma.base-url=http://localhost:8000",
        "chroma.collection-prefix=guide_test"
})
class ChromaIntegrationCheck {

    /** 全部 mock 向量统一用这个查询向量，保证维度一致、可比 */
    private static final float[] Q = {1.0f, 0.0f, 0.0f, 0.0f};

    @Autowired
    private RagService ragService;

    @Autowired
    private ChromaVectorStore chromaVectorStore;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private CacheService cacheService;

    @MockBean
    private GuideEmbeddingRepository embeddingRepository;

    @BeforeEach
    void setUp() {
        when(embeddingClient.getModel()).thenReturn("text-embedding-v3");
        when(embeddingRepository.selectList(any())).thenReturn(List.of());
        when(embeddingClient.embedAll(anyList(), isNull())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>();
            for (String ignored : texts) {
                out.add(Q);
            }
            return out;
        });
    }

    @Test
    void roundTripUpsertAndQueryMatchesManualCosine() {
        List<ChromaVectorStore.ChunkRecord> records = List.of(
                record("a", new float[]{0.0f, 1.0f, 0.0f, 0.0f}),
                record("b", new float[]{1.0f, 0.0f, 0.0f, 0.0f}), // 与查询向量相同 → top-1
                record("c", new float[]{0.5f, 0.5f, 0.0f, 0.0f}));
        String collection = chromaVectorStore.collectionName("itest");

        assertTrue(chromaVectorStore.upsertCity(collection, records), "upsert 应成功");

        List<ChromaVectorStore.ChromaHit> hits = chromaVectorStore.query(collection, "任意查询文本", 3);
        assertFalse(hits.isEmpty(), "query 应有结果");
        assertEquals("b", hits.get(0).id(), "与查询向量最相似的片段应排第一");
        assertEquals(1.0, hits.get(0).score(), 1e-4, "相同向量余弦相似度应为 1");

        // 与手写余弦的排名一致性（Chroma 排名 == 内存余弦排名）
        String manualTop = manualCosineTopId(records);
        assertEquals(manualTop, hits.get(0).id(), "Chroma top-1 应与内存余弦一致");
    }

    @Test
    void healthyChromaIsUsedByRagService() {
        // 确保注入真实 store（测试执行顺序无关）
        ReflectionTestUtils.setField(ragService, "chromaVectorStore", chromaVectorStore);

        List<String> results = ragService.search("成都", "美食", 5, null);
        assertNotNull(results);
        assertFalse(results.isEmpty(), "Chroma 健康时检索应返回结果");

        // 证明真实走了 Chroma upsert 路径
        @SuppressWarnings("unchecked")
        Set<String> upserted = (Set<String>) ReflectionTestUtils.getField(ragService, "chromaUpsertedCities");
        assertNotNull(upserted);
        assertTrue(upserted.contains("guide_test_chengdu"),
                "成都应已完成 Chroma 集合 upsert");
    }

    @Test
    void unreachableChromaFallsBackToInMemory() {
        ChromaConfig deadConfig = new ChromaConfig();
        deadConfig.setEnabled(true);
        deadConfig.setBaseUrl("http://127.0.0.1:1");
        deadConfig.setTimeoutMs(2000);
        ReflectionTestUtils.setField(ragService, "chromaVectorStore",
                new ChromaVectorStore(deadConfig, null));

        List<String> results = ragService.search("成都", "美食", 5, null);
        assertNotNull(results);
        assertFalse(results.isEmpty(), "Chroma 不可达时降级内存余弦/关键词，仍应返回结果");
    }

    private static ChromaVectorStore.ChunkRecord record(String id, float[] vec) {
        return new ChromaVectorStore.ChunkRecord(id, toFloats(vec),
                Map.of("source", "itest.md", "title", id, "tags", ""), "测试正文 " + id);
    }

    /** 与内存余弦同款：算 Q 与各记录向量的余弦相似度，取最高者 id */
    private static String manualCosineTopId(List<ChromaVectorStore.ChunkRecord> records) {
        String best = null;
        double bestScore = -1;
        for (ChromaVectorStore.ChunkRecord r : records) {
            double dot = 0, normQ = 0, normV = 0;
            for (int i = 0; i < Q.length; i++) {
                dot += Q[i] * r.embedding().get(i);
                normQ += Q[i] * Q[i];
                normV += r.embedding().get(i) * r.embedding().get(i);
            }
            double score = dot / (Math.sqrt(normQ) * Math.sqrt(normV));
            if (score > bestScore) {
                bestScore = score;
                best = r.id();
            }
        }
        return best;
    }

    private static List<Float> toFloats(float[] vec) {
        List<Float> out = new ArrayList<>(vec.length);
        for (float f : vec) {
            out.add(f);
        }
        return out;
    }
}
