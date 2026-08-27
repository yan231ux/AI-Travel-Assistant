package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.client.EmbeddingClient;
import com.yuntu.tripplanner.model.GuideEmbedding;
import com.yuntu.tripplanner.repository.GuideEmbeddingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 向量持久化集成测试（需要 MySQL + 门控开启，默认不随 mvn test 运行）。
 *
 * <p>启动方式：{@code mvn test -Dtest=GuideEmbeddingPersistenceCheck -Drag.persist=true}。
 * 覆盖生产路径：loadOrBuildVectors 查库→补缺→回写→内容变化重算，验证 LONGBLOB byte[]
 * 与 float[] 的往返一致。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "rag.persist", matches = "true")
class GuideEmbeddingPersistenceCheck {

    private static final String TEST_MODEL = "text-embedding-v3";

    @Autowired
    private RagService ragService;

    @Autowired
    private GuideEmbeddingRepository repository;

    @MockBean
    private EmbeddingClient embeddingClient;

    /** 每个测试用独立 source，@AfterEach 统一清理 */
    private String testSource = "test_persist.md";

    @AfterEach
    void cleanUp() {
        repository.delete(new LambdaQueryWrapper<GuideEmbedding>()
                .eq(GuideEmbedding::getSource, testSource));
    }

    @Test
    void cacheBuildPersistReuseAndInvalidate() {
        when(embeddingClient.getModel()).thenReturn(TEST_MODEL);
        when(embeddingClient.embedAll(anyList(), isNull())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>();
            for (String ignored : texts) {
                out.add(new float[]{0.1f, 0.2f, 0.3f, 0.4f});
            }
            return out;
        });

        Map<String, String> chunk = chunk("测试小节", "这是一段测试正文");

        // 1. 首次构建：DB 无记录 → 调 embedding → 回写落库
        List<float[]> first = invokeLoadOrBuild(List.of(chunk));
        assertEquals(1, first.size());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, first.get(0), 1e-6f);
        verify(embeddingClient, times(1)).embedAll(anyList(), isNull());

        // 2. 落库校验：hash/dim/model/字节往返
        GuideEmbedding row = selectRow();
        assertNotNull(row, "向量应已持久化到 guide_embedding 表");
        assertEquals(sha256("测试小节\n这是一段测试正文"), row.getContentHash());
        assertEquals(4, row.getDim());
        assertEquals(TEST_MODEL, row.getModel());
        assertEquals(16, row.getVector().length);
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, bytesToFloats(row.getVector()), 1e-6f);

        // 3. 内容未变再次构建：命中缓存，零 embedding 调用
        List<float[]> second = invokeLoadOrBuild(List.of(chunk));
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, second.get(0), 1e-6f);
        verify(embeddingClient, times(1)).embedAll(anyList(), isNull());

        // 4. 攻略内容变化：hash 不匹配 → 触发重算并回写新 hash
        Map<String, String> changed = chunk("测试小节", "正文更新了，内容发生变化");
        List<float[]> third = invokeLoadOrBuild(List.of(changed));
        assertEquals(1, third.size());
        verify(embeddingClient, times(2)).embedAll(anyList(), isNull());
        GuideEmbedding updated = selectRow();
        assertNotNull(updated);
        assertEquals(sha256("测试小节\n正文更新了，内容发生变化"), updated.getContentHash());
    }

    @Test
    void vectorDimensionMismatchTreatsRowAsMissing() {
        when(embeddingClient.getModel()).thenReturn(TEST_MODEL);
        when(embeddingClient.embedAll(anyList(), isNull())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            List<float[]> out = new ArrayList<>();
            for (String ignored : texts) {
                out.add(new float[]{0.1f, 0.2f, 0.3f, 0.4f});
            }
            return out;
        });

        Map<String, String> chunk = chunk("维度校验小节", "旧模型 1024 维向量被误用会导致余弦恒为 0");
        invokeLoadOrBuild(List.of(chunk));

        // 伪造一条 dim 与 vector 长度不一致的记录（如换模型后遗留的脏数据）
        GuideEmbedding dirty = selectRow();
        dirty.setDim(1024);
        repository.updateById(dirty);

        // dim*4 != vector.length → 视为缺失，重新 embedding 并修复
        List<float[]> rebuilt = invokeLoadOrBuild(List.of(chunk));
        assertEquals(1, rebuilt.size());
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, rebuilt.get(0), 1e-6f);
        GuideEmbedding fixed = selectRow();
        assertEquals(4, fixed.getDim());
        assertEquals(16, fixed.getVector().length);
        verify(embeddingClient, times(2)).embedAll(anyList(), isNull());
    }

    /** 构造与生产 loadOrBuildVectors 同构的片段 map */
    private static Map<String, String> chunk(String title, String text) {
        Map<String, String> c = new HashMap<>();
        c.put("source", "test_persist.md");
        c.put("title", title);
        c.put("text", text);
        return c;
    }

    /** 反射调用生产私有方法 loadOrBuildVectors */
    @SuppressWarnings("unchecked")
    private List<float[]> invokeLoadOrBuild(List<Map<String, String>> chunks) {
        return (List<float[]>) ReflectionTestUtils.invokeMethod(ragService, "loadOrBuildVectors", chunks);
    }

    private GuideEmbedding selectRow() {
        return repository.selectOne(new LambdaQueryWrapper<GuideEmbedding>()
                .eq(GuideEmbedding::getSource, testSource));
    }

    private static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static float[] bytesToFloats(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        float[] vec = new float[bytes.length / 4];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = buf.getFloat();
        }
        return vec;
    }
}
