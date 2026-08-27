package com.yuntu.tripplanner.client;

import com.yuntu.tripplanner.config.ChromaConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ChromaVectorStore 单元测试（不需要 Chroma server）：
 * 验证失败软降级——服务不可达时 upsert 返回 false、query 返回空、绝不抛异常。
 */
class ChromaVectorStoreTest {

    /** 指向必然拒绝连接的端口，模拟 Chroma server 未启动 */
    private ChromaVectorStore deadPortStore() {
        ChromaConfig config = new ChromaConfig();
        config.setEnabled(true);
        config.setBaseUrl("http://127.0.0.1:1");
        config.setTimeoutMs(2000);
        return new ChromaVectorStore(config, null);
    }

    @Test
    void unreachableServerDegradesGracefully() {
        ChromaVectorStore store = deadPortStore();
        assertTrue(store.isEnabled(), "客户端构建成功即可用（连接是惰性的）");
        assertEquals("cosine", store.distanceFunc());
        assertEquals("guide_chengdu", store.collectionName("chengdu"));

        boolean upserted = store.upsertCity("guide_chengdu", List.of(
                new ChromaVectorStore.ChunkRecord("chengdu_guide.md|美食",
                        List.of(0.1f, 0.2f),
                        Map.of("source", "chengdu_guide.md", "title", "美食", "tags", ""),
                        "成都火锅必吃")));
        assertFalse(upserted, "连接拒绝 → upsert 失败返回 false");

        List<ChromaVectorStore.ChromaHit> hits = store.query("guide_chengdu", "成都 美食", 5);
        assertTrue(hits.isEmpty(), "连接拒绝 → query 返回空");
    }

    @Test
    void disabledConfigNeverAttemptsConnection() {
        ChromaConfig config = new ChromaConfig(); // enabled 默认 false
        ChromaVectorStore store = new ChromaVectorStore(config, null);
        assertFalse(store.isEnabled());
        assertTrue(store.query("guide_chengdu", "成都 美食", 5).isEmpty());
        assertFalse(store.upsertCity("guide_chengdu", List.of()));
    }

    @Test
    void nullRecordsAreRejected() {
        ChromaVectorStore store = deadPortStore();
        assertFalse(store.upsertCity("guide_chengdu", null));
        assertFalse(store.upsertCity("guide_chengdu", List.of()));
    }
}
