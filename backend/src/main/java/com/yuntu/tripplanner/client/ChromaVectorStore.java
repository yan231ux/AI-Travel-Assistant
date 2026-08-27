package com.yuntu.tripplanner.client;

import com.yuntu.tripplanner.config.ChromaConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.amikos.chromadb.Collection;
import tech.amikos.chromadb.EmbeddingFunction;
import tech.amikos.chromadb.model.QueryEmbedding.IncludeEnum;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chroma 向量库客户端封装（可选加速层，失败软降级）
 *
 * <p>仅负责两件事：把某城市的攻略片段向量 upsert 进对应集合，以及用查询文本做向量检索。
 * 任何调用失败都返回空/ false，由调用方（RagService）降级为内存余弦检索——本类绝不抛异常外泄。
 *
 * <p>0.1.5 API 要点（已用 javap 验证）：
 * <ul>
 *   <li>{@code Collection.upsert(embeddings, metadatas, ids, documents)} 传入非空向量时
 *       不会调用 EmbeddingFunction（字节码 ifnonnull 守卫），直接用显式向量；</li>
 *   <li>{@code Collection.query(queryTexts, nResults, ...)} 会调用集合绑定的
 *       EmbeddingFunction 对查询文本向量化——这里委托给系统既有的 {@link EmbeddingClient}（DashScope），
 *       保证查询向量与内存余弦路径完全一致、评测可对比。</li>
 * </ul>
 */
@Slf4j
@Component
public class ChromaVectorStore {

    /** 待写入 Chroma 的一条片段向量记录 */
    public record ChunkRecord(String id, List<Float> embedding,
                              Map<String, String> metadata, String document) {}

    /** Chroma 命中结果：id 用于还原原片段 Map，score 为余弦相似度 */
    public record ChromaHit(String id, double score) {}

    private final ChromaConfig config;
    private final EmbeddingClient embeddingClient;

    /** 底层客户端；构造失败（服务不可达/配置错误）时为 null → 整体停用 */
    private final tech.amikos.chromadb.Client client;

    /** 已解析的集合缓存：集合名 → Collection */
    private final Map<String, Collection> collections = new ConcurrentHashMap<>();

    public ChromaVectorStore(ChromaConfig config,
                             @Autowired(required = false) EmbeddingClient embeddingClient) {
        this.config = config;
        this.embeddingClient = embeddingClient;
        this.client = config.isEnabled() ? buildClient(config) : null;
    }

    /** 构建底层客户端；失败返回 null → 整体停用（Spring 启动永不因 Chroma 失败） */
    private static tech.amikos.chromadb.Client buildClient(ChromaConfig config) {
        try {
            tech.amikos.chromadb.Client c = new tech.amikos.chromadb.Client(config.getBaseUrl());
            c.setTimeout(Math.max(1, config.getTimeoutMs() / 1000));
            return c;
        } catch (Exception e) {
            log.warn("Chroma 客户端初始化失败，将降级为内存余弦检索: {}", e.getMessage());
            return null;
        }
    }

    /** 是否可用（配置开启且客户端构建成功） */
    public boolean isEnabled() {
        return config.isEnabled() && client != null;
    }

    /** 距离函数（仅 cosine 时调用方才启用 Chroma，保证排名与内存一致） */
    public String distanceFunc() {
        return config.getDistanceFunc();
    }

    /** 城市集合名：{prefix}_{sourcePrefix}，如 guide_chengdu */
    public String collectionName(String sourcePrefix) {
        return config.getCollectionPrefix() + "_" + sourcePrefix;
    }

    /**
     * 把城市全部片段向量写入集合（get-or-create + upsert）。
     *
     * @return 是否成功；失败返回 false（调用方降级内存余弦）
     */
    public boolean upsertCity(String collectionName, List<ChunkRecord> records) {
        if (client == null || records == null || records.isEmpty()) {
            return false;
        }
        Collection collection = getOrCreateCollection(collectionName);
        if (collection == null) {
            return false;
        }
        try {
            List<List<Float>> embeddings = new ArrayList<>(records.size());
            List<Map<String, String>> metadatas = new ArrayList<>(records.size());
            List<String> ids = new ArrayList<>(records.size());
            List<String> documents = new ArrayList<>(records.size());
            for (ChunkRecord r : records) {
                embeddings.add(r.embedding());
                metadatas.add(r.metadata());
                ids.add(r.id());
                documents.add(r.document());
            }
            // 注意 0.1.5 的 upsert 签名为 (embeddings, metadatas, documents, ids)——第 3 参是
            // documents、第 4 参是 ids（javap 字节码验证，EF 空 embedding 时也用第 4 参生成向量）
            collection.upsert(embeddings, metadatas, documents, ids);
            return true;
        } catch (Exception e) {
            log.debug("Chroma upsert 失败 ({}): {}", collectionName, e.getMessage());
            return false;
        }
    }

    /**
     * 用查询文本做向量检索（文本由集合绑定的 DashScope EF 向量化，与内存路径同向量）。
     * 返回按相似度降序的命中；失败/空返回空列表（调用方降级）。
     */
    public List<ChromaHit> query(String collectionName, String queryText, int topK) {
        if (client == null) {
            return List.of();
        }
        Collection collection = getOrCreateCollection(collectionName);
        if (collection == null) {
            return List.of();
        }
        try {
            Collection.QueryResponse resp = collection.query(
                    List.of(queryText), topK, null, null,
                    List.of(IncludeEnum.DISTANCES));
            if (resp == null || resp.getIds() == null || resp.getIds().isEmpty()) {
                return List.of();
            }
            List<String> ids = resp.getIds().get(0);
            List<Float> distances = (resp.getDistances() == null || resp.getDistances().isEmpty())
                    ? null : resp.getDistances().get(0);
            List<ChromaHit> hits = new ArrayList<>(ids.size());
            for (int i = 0; i < ids.size(); i++) {
                // cosine 空间下 distance = 1 - cosine → score = 1 - distance
                double score = (distances != null && i < distances.size())
                        ? 1.0 - distances.get(i) : 0.0;
                hits.add(new ChromaHit(ids.get(i), score));
            }
            hits.sort((a, b) -> Double.compare(b.score(), a.score()));
            return hits;
        } catch (Exception e) {
            log.debug("Chroma query 失败 ({}): {}", collectionName, e.getMessage());
            return List.of();
        }
    }

    /** 取或创建集合（惰性，首次使用）；失败返回 null */
    private Collection getOrCreateCollection(String name) {
        Collection existing = collections.get(name);
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            Collection again = collections.get(name);
            if (again != null) {
                return again;
            }
            try {
                // hnsw:space=cosine 让集合用余弦距离，排名与内存余弦一致
                Map<String, String> meta = Map.of("hnsw:space", config.getDistanceFunc());
                Collection created = client.createCollection(name, meta, Boolean.TRUE, embeddingFunction());
                collections.put(name, created);
                return created;
            } catch (Exception e) {
                log.debug("Chroma createCollection 失败，尝试取已有集合: {}", e.getMessage());
                try {
                    Collection got = client.getCollection(name, embeddingFunction());
                    collections.put(name, got);
                    return got;
                } catch (Exception e2) {
                    log.debug("Chroma getCollection 失败 ({}): {}", name, e2.getMessage());
                    return null;
                }
            }
        }
    }

    /** 集合绑定的 EmbeddingFunction：查询文本委托给 DashScope EmbeddingClient（失败返回空列表） */
    private EmbeddingFunction embeddingFunction() {
        return new EmbeddingFunction() {
            @Override
            public List<List<Float>> createEmbedding(List<String> texts) {
                if (embeddingClient == null) {
                    return List.of();
                }
                List<float[]> vecs = embeddingClient.embedAll(texts, null);
                if (vecs == null) {
                    return List.of();
                }
                List<List<Float>> out = new ArrayList<>(vecs.size());
                for (float[] v : vecs) {
                    out.add(toFloats(v));
                }
                return out;
            }

            @Override
            public List<List<Float>> createEmbedding(List<String> texts, String collectionId) {
                return createEmbedding(texts);
            }
        };
    }

    private static List<Float> toFloats(float[] vec) {
        List<Float> out = new ArrayList<>(vec.length);
        for (float f : vec) {
            out.add(f);
        }
        return out;
    }
}
