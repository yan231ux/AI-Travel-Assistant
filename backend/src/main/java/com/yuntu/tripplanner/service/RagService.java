package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.client.ChromaVectorStore;
import com.yuntu.tripplanner.client.EmbeddingClient;
import com.yuntu.tripplanner.model.GuideEmbedding;
import com.yuntu.tripplanner.model.TokenUsage;
import com.yuntu.tripplanner.repository.GuideEmbeddingRepository;
import com.yuntu.tripplanner.common.TagDictionary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * RAG 本地攻略检索服务
 *
 * 加载真实的攻略 Markdown 文件（resources/guides/），按标题切分成片段。
 * 检索优先走向量（embedding + 余弦相似度），向量不可用时自动降级为 BM25 关键词检索。
 * 数据全部来自真实攻略，不编造。
 */
@Slf4j
@Service
public class RagService {

    /** 已知城市列表兜底（application.yml 的 known-cities 未配置时使用） */
    private static final List<String> KNOWN_CITIES_FALLBACK = Arrays.asList(
            "北京", "大理", "成都", "三亚", "厦门", "西安"
    );

    private final CacheService cacheService;
    private final EmbeddingClient embeddingClient;

    /** 已知城市列表（来自配置 known-cities） */
    private final List<String> knownCities;

    /** RAG 结果缓存 TTL（秒），来自 redis.rag-ttl-seconds */
    private final long ragCacheTtl;

    /** 加载的攻略片段：每个片段带 source（文件名）、title（小节标题）、text（正文） */
    private final List<Map<String, String>> chunks = new ArrayList<>();

    /** 每个城市的向量索引，惰性构建（embedding 失败则缓存空列表 → 降级关键词） */
    private final Map<String, List<ChunkVec>> vectorsByCity = new ConcurrentHashMap<>();

    /**
     * 有攻略的城市集合（单一真相源）：启动时从 guides/ 各 .md 的 H1 标题自动提取，
     * 不再依赖 application.yml 的 known-cities 配置。新增城市攻略只需放一个 .md 文件。
     */
    private final Set<String> supportedCities = ConcurrentHashMap.newKeySet();

    /**
     * 城市名 → 攻略文件名 stem（去掉 .md），用于 Chroma 集合名（保证 ASCII 安全）。
     * 如 "成都" → "chengdu_guide"。
     */
    private final Map<String, String> cityToFileStem = new ConcurrentHashMap<>();

    /**
     * 持久化向量缓存（可选）：Spring 环境注入后落库，服务重启直接加载、免重算；
     * 单元测试直接 new RagService(...) 时为 null → 仅内存构建，行为与原来一致。
     */
    @Autowired(required = false)
    private GuideEmbeddingRepository embeddingRepository;

    /**
     * Chroma 向量库（可选加速层）：Spring 环境注入且配置开启时，rankVector 优先走
     * Chroma 检索；不可达/失败自动降级为内存余弦。直接 new RagService(...) 时为 null。
     */
    @Autowired(required = false)
    private ChromaVectorStore chromaVectorStore;

    /** 已完成 upsert 的城市集合（Chroma 健康时每城市只需写一次） */
    private final Set<String> chromaUpsertedCities = ConcurrentHashMap.newKeySet();

    /** 每城市最后一次 Chroma 告警时间（ms），用于节流刷屏 */
    private final Map<String, Long> lastChromaWarn = new ConcurrentHashMap<>();

    /**
     * 攻略片段 + 向量
     */
    private record ChunkVec(Map<String, String> chunk, float[] vector) {}

    /**
     * 带得分的攻略片段
     */
    private record ScoredChunk(Map<String, String> chunk, double score) {}

    /**
     * 检索策略：生产走 HYBRID（向量+关键词 RRF 融合）；VECTOR/KEYWORD 供评测对比
     */
    enum SearchMode {
        VECTOR, KEYWORD, HYBRID
    }

    /** RRF 融合参数（业界常用值 60） */
    private static final double RRF_K = 60.0;

    /** BM25 词频饱和参数（Lucene 常用默认 1.2~2.0） */
    private static final double BM25_K1 = 1.5;

    /** BM25 长度归一化强度（Lucene 常用默认 0.75） */
    private static final double BM25_B = 0.75;

    /** 关键词排序中标题命中的加权系数（保留"标题比正文更关键"的先验） */
    private static final double TITLE_WEIGHT = 3.0;

    /**
     * 查询改写表：口语化/复合表达 → 规范检索关键词（token 级，确定性、零成本）。
     * 仅覆盖明确映射的词组，不做通用改写，避免引入噪声。
     */
    private static final Map<String, String> QUERY_EXPANSIONS = Map.ofEntries(
            Map.entry("看熊猫", "大熊猫 熊猫"),
            Map.entry("大熊猫基地", "大熊猫 熊猫 基地"),
            Map.entry("熊猫", "大熊猫 熊猫"),
            Map.entry("看文物", "文物 博物馆"),
            Map.entry("吃烤鸭", "烤鸭"),
            Map.entry("必吃", "美食"),
            Map.entry("看海", "海滩"),
            Map.entry("爬山", "登山"),
            Map.entry("文博", "博物馆"),
            Map.entry("带娃", "亲子"),
            Map.entry("下雨", "雨天")
    );

    // 约束标签词典已收敛为全局唯一来源（com.yuntu.tripplanner.common.TagDictionary.TAG_KEYWORDS）。
    // 此处保留别名仅为兼容可能存在的检索评测引用；数据不再在此处单独维护。
    static final Map<String, List<String>> TAG_KEYWORDS = TagDictionary.TAG_KEYWORDS;

    public RagService(CacheService cacheService,
                      EmbeddingClient embeddingClient,
                      @Value("${known-cities:}") String knownCitiesConfig,
                      @Value("${redis.rag-ttl-seconds:21600}") long ragCacheTtl) {
        this.cacheService = cacheService;
        this.embeddingClient = embeddingClient;
        this.knownCities = parseKnownCities(knownCitiesConfig);
        this.ragCacheTtl = ragCacheTtl;
        loadGuides();
    }

    private List<String> parseKnownCities(String config) {
        if (config == null || config.isBlank()) {
            return new ArrayList<>(KNOWN_CITIES_FALLBACK);
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 启动时加载 resources/guides/ 下的攻略文件并切分
     */
    private void loadGuides() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            // classpath*: 扫描全部 classpath 根，避免测试 classpath 中同名目录遮蔽生产攻略
            Resource[] resources = resolver.getResources("classpath*:guides/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null && filename.startsWith("_")) {
                    continue; // 跳过模板/说明类文件（如 _TEMPLATE.md），不纳入攻略库
                }
                try (InputStream is = resource.getInputStream()) {
                    String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    String city = extractCity(content);
                    if (city != null) {
                        supportedCities.add(city);
                        if (filename != null) {
                            cityToFileStem.put(city, filename.replaceAll("\\.md$", ""));
                        }
                    } else {
                        log.warn("攻略文件 {} 缺少 H1 城市标题，无法自动纳入支持城市", filename);
                    }
                    splitMarkdown(filename, city, content);
                }
            }
            log.info("RAG攻略库加载完成，共 {} 个片段；支持城市 {} 个：{}",
                    chunks.size(), supportedCities.size(), supportedCities);
        } catch (IOException e) {
            log.error("加载攻略文件失败", e);
        }
    }

    /**
     * 从攻略 Markdown 的 H1 标题提取规范城市名。
     * 约定 H1 形如 "# 成都旅行攻略" / "# 北京旅游攻略"，去掉末尾的
     * "旅行攻略/旅游攻略/攻略/市/省" 得到城市名（"成都"/"北京"）。
     * 返回 null 表示该文件无合法 H1，不会被自动纳入支持城市。
     */
    private String extractCity(String content) {
        for (String line : content.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("# ") && !t.startsWith("## ")) {
                String title = t.substring(2).trim();
                String city = title.replaceAll("(旅行攻略|旅游攻略|攻略|市|省)$", "").trim();
                if (!city.isEmpty()) {
                    return city;
                }
            }
        }
        return null;
    }

    /**
     * 按 ## 和 ### 标题切分 Markdown
     */
    private void splitMarkdown(String sourceName, String city, String markdown) {
        String currentTitle = "文档开头";
        List<String> currentLines = new ArrayList<>();

        for (String line : markdown.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("## ") || trimmed.startsWith("### ")) {
                if (!currentLines.isEmpty()) {
                    addChunk(sourceName, city, currentTitle, currentLines);
                }
                currentTitle = trimmed.replaceAll("^#+\\s*", "").trim();
                currentLines = new ArrayList<>();
            } else if (!trimmed.isEmpty()) {
                currentLines.add(trimmed);
            }
        }
        if (!currentLines.isEmpty()) {
            addChunk(sourceName, city, currentTitle, currentLines);
        }
    }

    private void addChunk(String sourceName, String city, String title, List<String> lines) {
        Map<String, String> chunk = new HashMap<>();
        chunk.put("source", sourceName);
        chunk.put("city", city);
        chunk.put("title", title);
        chunk.put("text", String.join("\n", lines));
        chunk.put("tags", deriveTags(title, String.join("\n", lines)));
        chunks.add(chunk);
    }

    /**
     * 按标签词典从标题/正文推导维度标签（确定性、零成本，供元数据过滤）。
     * 统一使用 TagDictionary，命中任一关键词即打该标签；
     * 返回逗号分隔字符串便于存进 chunk map。
     */
    private String deriveTags(String title, String text) {
        return TagDictionary.deriveTags(title, text);
    }

    /**
     * 返回当前已加载攻略的城市集合（目录驱动，启动后只增不减）。
     */
    public Set<String> getSupportedCities() {
        return Set.copyOf(supportedCities);
    }

    /**
     * 判断目的地是否有本地攻略
     */
    public boolean isKnownCity(String destination) {
        if (destination == null) return false;
        String norm = destination.replaceAll("(市|省)$", "");
        if (supportedCities.contains(norm) || supportedCities.contains(destination)) {
            return true;
        }
        for (String c : supportedCities) {
            if (c.contains(norm) || norm.contains(c)) {
                return true;
            }
        }
        // 兜底：保留原 known-cities 配置门禁（KNOWN_CITIES_FALLBACK / application.yml）
        return knownCities.stream().anyMatch(c -> c.equals(destination) || destination.contains(c) || c.contains(destination));
    }

    /**
     * 在该城市的攻略中按景点名称查找"景点卡片"（### 条目且带 **位置** 字段的块）。
     * 命中返回结构化字段：location / ticket / duration / intro / note（可能缺字段）；
     * 未命中返回 null。
     *
     * <p>用途：生成后事实回写（事实锚定）——让景点的地址/简介/门票来自人工整理的攻略，
     * 而不是 LLM 凭记忆编造。匹配按规范化名称的相等/包含关系取最长（最具体）卡片。
     */
    public Map<String, String> findSpotCard(String destination, String spotName) {
        if (destination == null || spotName == null || spotName.isBlank()) {
            return null;
        }
        String norm = normalizeSpot(spotName);
        if (norm.length() < 2) {
            return null;
        }
        String city = resolveCity(destination);
        Map<String, String> best = null;
        int bestLen = -1;
        for (Map<String, String> chunk : chunks) {
            String chunkCity = chunk.get("city");
            if (chunkCity == null || !isSameCity(city, chunkCity, destination)) {
                continue;
            }
            String text = chunk.get("text");
            // 只认"景点卡片"块：必须有 **位置** 字段（餐饮/住宿/贴士块没有，自动排除）
            if (text == null || !text.contains("**位置**")) {
                continue;
            }
            String title = chunk.get("title");
            if (title == null) {
                continue;
            }
            String cardName = stripCardNumber(title);
            String cn = normalizeSpot(cardName);
            if (cn.length() < 2) {
                continue;
            }
            if (cn.equals(norm) || cn.contains(norm) || norm.contains(cn)) {
                if (cn.length() > bestLen) {
                    bestLen = cn.length();
                    best = parseSpotCard(chunk);
                }
            }
        }
        return best;
    }

    /** 城市归属判定：规范城市名互相包含即视为同一城市 */
    private boolean isSameCity(String resolvedCity, String chunkCity, String destination) {
        if (chunkCity.equals(destination)) {
            return true;
        }
        if (resolvedCity != null && (chunkCity.contains(resolvedCity) || resolvedCity.contains(chunkCity))) {
            return true;
        }
        return destination.contains(chunkCity) || chunkCity.contains(destination);
    }

    /** 去掉卡片标题的数字编号前缀："2.10 外滩" → "外滩" */
    private String stripCardNumber(String title) {
        return title.replaceFirst("^\\d+(\\.\\d+)*\\s*", "").trim();
    }

    /** 名称规范化（仅用于匹配）：去空白、全半角括号、行政区划后缀 */
    private String normalizeSpot(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\s\\u3000（）()]", "");
        return t.replaceAll("(风景区|景区|公园|古镇|老街|景点)$", "");
    }

    /**
     * 从用户特殊需求文本中反向匹配该城市攻略里的景点卡片名。
     * 匹配规则（严格版）：卡片名去掉括号注释、去掉城市名前缀后，用户文本完整包含核心名
     * 或核心名完整包含用户文本，即视为命中。
     * 例："我要去四行仓库" 命中卡片「上海四行仓库抗战纪念馆」（核心名包含用户文本）。
     * 反例（已修）：早期版本用"首/尾 4 字"宽匹配，会把"想去自然博物馆"误中所有含"自然博物馆"的
     * 卡片（玉见/森邻奇镜/华贝剧场…），导致 requestedSpots 膨胀、checkRequestedSpots 误报大量未安排。
     */
    public List<String> findSpotCardNames(String destination, String text) {
        if (text == null || text.isBlank() || destination == null) {
            return List.of();
        }
        String cleanText = text.replaceAll("[\\s，。、！？；：,.!?;:（）()\"'“”「」]", "");
        if (cleanText.length() < 2) {
            return List.of();
        }
        String city = resolveCity(destination);
        List<String> names = new ArrayList<>();
        for (Map<String, String> chunk : chunks) {
            String chunkCity = chunk.get("city");
            if (chunkCity == null || !isSameCity(city, chunkCity, destination)) {
                continue;
            }
            String chunkText = chunk.get("text");
            if (chunkText == null || !chunkText.contains("**位置**")) {
                continue;
            }
            String title = chunk.get("title");
            if (title == null) {
                continue;
            }
            String cardName = stripCardNumber(title);
            if (cardName.length() < 2) {
                continue;
            }
            // 去括号注释 + 去城市名前缀 + 去连接符，得到"景点核心名"
            String core = cardName.replaceAll("[（(][^）)]*[）)]", "").trim();
            core = core.replace(city, "").replace(destination, "")
                    .replace("市", "").replace("省", "")
                    .replace("&", "").replace("&amp;", "").replace("、", "").replace("/", "").trim();
            if (core.length() < 2) {
                continue;
            }
            // 严格匹配：用户文本完整包含核心名，或核心名完整包含用户文本（去停用词后仍整体包含）
            boolean hit = cleanText.contains(core) || core.contains(cleanText);
            if (hit && !names.contains(cardName)) {
                names.add(cardName);
            }
        }
        return names;
    }

    /** 把卡片块文本解析成结构化字段（位置/门票/游玩时长/简介/注意） */
    private Map<String, String> parseSpotCard(Map<String, String> chunk) {
        Map<String, String> card = new HashMap<>();
        card.put("name", stripCardNumber(chunk.get("title")));
        String text = chunk.get("text");
        if (text == null) {
            return card;
        }
        for (String line : text.split("\n")) {
            String t = line.trim();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^-?\\s*\\*\\*([^\\*]+)\\*\\*[:：]\\s*(.+)$").matcher(t);
            if (!m.matches()) {
                continue;
            }
            String key = m.group(1).trim();
            String value = m.group(2).trim();
            switch (key) {
                case "位置" -> card.put("location", value);
                case "门票" -> card.put("ticket", value);
                case "游玩时长" -> card.put("duration", value);
                case "简介" -> card.put("intro", value);
                case "注意", "贴士" -> card.put("note", value);
                default -> { /* 其他字段忽略 */ }
            }
        }
        return card;
    }

    /**
     * 将用户输入的目的地解析为 supportedCities 中的规范城市名。
     * 用于检索时定位该城市的攻略片段与 Chroma 集合；解析不到时返回去后缀后的原串兜底。
     */
    private String resolveCity(String destination) {
        if (destination == null) return null;
        String norm = destination.replaceAll("(市|省)$", "");
        if (supportedCities.contains(norm)) return norm;
        if (supportedCities.contains(destination)) return destination;
        for (String c : supportedCities) {
            if (c.contains(norm) || norm.contains(c)) return c;
        }
        return norm;
    }

    /**
     * 生成 Chroma 集合名前缀：优先用文件名 stem（ASCII 安全），无映射时退化清洗城市名。
     */
    private String collectionPrefix(String destination) {
        String city = resolveCity(destination);
        String stem = city == null ? null : cityToFileStem.get(city);
        if (stem != null) return stem;
        return city == null ? "guide" : city.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /**
     * 查询改写：把口语化表达扩展为规范检索关键词。
     * 保留原 token，追加扩展词并去重，帮助关键词检索命中规范表述的标题/正文。
     */
    public String expandQuery(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        List<String> tokens = Arrays.stream(query.split("\\s+"))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
        List<String> out = new ArrayList<>();
        for (String t : tokens) {
            String rep = QUERY_EXPANSIONS.get(t);
            List<String> words = rep == null ? List.of(t) : Arrays.asList(rep.split("\\s+"));
            for (String w : words) {
                if (!out.contains(w)) {
                    out.add(w);
                }
            }
        }
        return String.join(" ", out);
    }

    /**
     * 检索相关攻略片段（生产默认走混合检索 HYBRID）
     *
     * @param destination 目的地
     * @param query       检索关键词（目的地+偏好+节奏等）
     * @param topK        返回片段数
     * @param usageSink   非空时累加 embedding 调用的 token 消耗
     * @return 按相关度排序的片段文本列表
     */
    public List<String> search(String destination, String query, int topK, TokenUsage usageSink) {
        return search(destination, query, topK, usageSink, SearchMode.HYBRID, Collections.emptySet());
    }

    /**
     * 检索相关攻略片段（生产默认混合检索），并按约束标签先过滤再排序。
     * requiredTags 为空表示不过滤；多个标签为"或"关系（命中任一即可）。
     */
    public List<String> search(String destination, String query, int topK, TokenUsage usageSink, Set<String> requiredTags) {
        return search(destination, query, topK, usageSink, SearchMode.HYBRID, requiredTags);
    }

    /**
     * 按指定策略检索（VECTOR / KEYWORD 供评测对比，生产用 HYBRID）
     */
    List<String> search(String destination, String query, int topK, TokenUsage usageSink, SearchMode mode) {
        return search(destination, query, topK, usageSink, mode, Collections.emptySet());
    }

    /**
     * 按指定策略检索 + 可选约束标签过滤（VECTOR / KEYWORD 供评测对比，生产用 HYBRID）
     */
    List<String> search(String destination, String query, int topK, TokenUsage usageSink, SearchMode mode, Set<String> requiredTags) {
        if (!isKnownCity(destination)) {
            return Collections.emptyList();
        }

        String tagsPart = requiredTags == null || requiredTags.isEmpty()
                ? "" : ":" + String.join("+", new TreeSet<>(requiredTags));
        String cacheKey = String.format("rag:guide:%s:%s:%d%s", destination,
                query == null ? "" : query, topK, tagsPart);
        List<String> cached = cacheService.get(cacheKey, List.class);
        if (cached != null && !cached.isEmpty()) {
            log.info("RAG缓存命中: {}", cacheKey);
            return cached;
        }

        boolean withScore = true;
        List<ScoredChunk> ranked = switch (mode) {
            case KEYWORD -> {
                withScore = false;
                yield rankKeyword(destination, query);
            }
            case VECTOR -> {
                List<ScoredChunk> vr = rankVector(destination, query, usageSink);
                if (vr.isEmpty()) {
                    // 向量不可用时降级关键词（保持原有行为）
                    withScore = false;
                    yield rankKeyword(destination, query);
                }
                yield vr;
            }
            case HYBRID -> fuseRrf(destination, query, usageSink);
        };

        // 元数据过滤：仅保留命中任一必需标签的片段（约束维度优先），无匹配时降级不过滤
        List<ScoredChunk> filtered = applyTagFilter(ranked, requiredTags);

        List<String> results = formatResults(filtered, topK, withScore);
        if (!results.isEmpty()) {
            cacheService.set(cacheKey, results, ragCacheTtl);
        }
        return results;
    }

    /**
     * 混合排序：向量与关键词各出一份排名，用 RRF 融合（生产默认策略）。
     * RRF 只利用排名（1/(K+rank)），对余弦分与关键词整数的量纲差异不敏感。
     * 向量不可用时退化为纯关键词，无关键词命中时退化为纯向量。
     */
    private List<ScoredChunk> fuseRrf(String destination, String query, TokenUsage usageSink) {
        List<ScoredChunk> vectorRanks = rankVector(destination, query, usageSink);
        List<ScoredChunk> keywordRanks = rankKeyword(destination, query);

        if (vectorRanks.isEmpty()) {
            log.warn("向量排序不可用，混合检索退化为关键词");
            return keywordRanks;
        }
        if (keywordRanks.isEmpty()) {
            return vectorRanks;
        }

        Map<Map<String, String>, Double> fused = new HashMap<>();
        addRrf(fused, vectorRanks);
        addRrf(fused, keywordRanks);

        return fused.entrySet().stream()
                .map(e -> new ScoredChunk(e.getKey(), e.getValue()))
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .toList();
    }

    /**
     * 元数据过滤：仅保留命中任一必需标签的片段。
     * 没有任何片段命中时返回原列表（降级为不过滤），避免约束过严导致空结果。
     */
    private List<ScoredChunk> applyTagFilter(List<ScoredChunk> ranked, Set<String> requiredTags) {
        if (requiredTags == null || requiredTags.isEmpty()) {
            return ranked;
        }
        List<ScoredChunk> filtered = ranked.stream()
                .filter(sc -> hasAnyTag(sc.chunk(), requiredTags))
                .toList();
        if (filtered.isEmpty()) {
            log.info("约束标签 {} 无匹配片段，降级为不过滤", requiredTags);
            return ranked;
        }
        return filtered;
    }

    /** 片段是否命中任一必需标签（chunk 中 tags 存为逗号分隔字符串） */
    private boolean hasAnyTag(Map<String, String> chunk, Set<String> requiredTags) {
        String tags = chunk.get("tags");
        if (tags == null || tags.isBlank()) {
            return false;
        }
        for (String t : tags.split(",")) {
            if (requiredTags.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 惰性构建某城市的向量索引；失败时缓存空列表 → 该城市走关键词检索。
     * 优先加载持久化向量（guide_embedding 表），命中且内容未变则零 API 调用；
     * 只有缺失/变化的片段才调 embedding 补充并回写。
     */
    private List<ChunkVec> getVectors(String destination) {
        return vectorsByCity.computeIfAbsent(destination, city -> {
            String resolved = resolveCity(destination);
            List<Map<String, String>> cityChunks = chunks.stream()
                    .filter(c -> resolved.equals(c.get("city")) || destination.equals(c.get("city"))
                            || c.get("source").toLowerCase().contains(destinationToSource(destination)))
                    .toList();
            if (cityChunks.isEmpty()) {
                return List.of();
            }
            List<float[]> vectors = loadOrBuildVectors(cityChunks);
            if (vectors == null) {
                log.warn("embedding 失败，城市 {} 降级为关键词检索", city);
                return List.of();
            }
            List<ChunkVec> result = new ArrayList<>();
            for (int i = 0; i < cityChunks.size(); i++) {
                result.add(new ChunkVec(cityChunks.get(i), vectors.get(i)));
            }
            log.info("城市 {} 向量索引就绪，共 {} 个片段（持久化缓存：{}）", city, result.size(),
                    embeddingRepository != null ? "已启用" : "未启用");
            return result;
        });
    }

    /**
     * 加载或构建某城市全部片段的向量，与 cityChunks 顺序一一对应。
     * 优先读取持久化向量表，命中且内容未变（SHA-256 一致）直接复用；
     * 只有缺失/变化的片段才调 embedding API 补充并回写。
     * 返回 null 表示整体不可用（调用方降级关键词检索）。
     */
    private List<float[]> loadOrBuildVectors(List<Map<String, String>> cityChunks) {
        Map<String, GuideEmbedding> dbRows = loadEmbeddingsFromDb(cityChunks);

        List<Integer> missingIndexes = new ArrayList<>();
        List<String> missingTexts = new ArrayList<>();
        List<float[]> vectors = new ArrayList<>(cityChunks.size());
        for (int i = 0; i < cityChunks.size(); i++) {
            Map<String, String> chunk = cityChunks.get(i);
            GuideEmbedding row = dbRows.get(chunkKey(chunk));
            boolean valid = row != null && row.getDim() != null && row.getVector() != null
                    && row.getDim() * 4 == row.getVector().length
                    && chunkHash(chunk).equals(row.getContentHash());
            if (valid) {
                vectors.add(bytesToFloats(row.getVector()));
            } else {
                missingIndexes.add(i);
                missingTexts.add(chunk.get("title") + "\n" + chunk.get("text"));
                vectors.add(null);
            }
        }

        if (missingIndexes.isEmpty()) {
            return vectors; // 全部命中持久化向量，零 embedding API 调用
        }

        List<float[]> built = embeddingClient.embedAll(missingTexts, null);
        if (built == null || built.size() != missingIndexes.size()) {
            return null;
        }
        for (int j = 0; j < missingIndexes.size(); j++) {
            vectors.set(missingIndexes.get(j), built.get(j));
            persistEmbedding(cityChunks.get(missingIndexes.get(j)), built.get(j));
        }
        return vectors;
    }

    /** 读取某城市全部片段的持久化向量；DB 不可用时返回空 map（走重新构建） */
    private Map<String, GuideEmbedding> loadEmbeddingsFromDb(List<Map<String, String>> cityChunks) {
        if (embeddingRepository == null) {
            return Collections.emptyMap();
        }
        try {
            Set<String> sources = cityChunks.stream()
                    .map(c -> c.get("source"))
                    .collect(Collectors.toSet());
            List<GuideEmbedding> rows = embeddingRepository.selectList(
                    new LambdaQueryWrapper<GuideEmbedding>()
                            .in(GuideEmbedding::getSource, sources)
                            .eq(GuideEmbedding::getModel, embeddingClient.getModel()));
            Map<String, GuideEmbedding> map = new HashMap<>();
            for (GuideEmbedding row : rows) {
                map.put(row.getSource() + "|" + row.getChunkTitle(), row);
            }
            return map;
        } catch (Exception e) {
            log.warn("读取向量缓存失败（降级为重新构建）: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 将单个片段的向量 upsert 进持久化表；DB 不可用时静默跳过（仅内存可用） */
    private void persistEmbedding(Map<String, String> chunk, float[] vector) {
        if (embeddingRepository == null) {
            return;
        }
        try {
            GuideEmbedding row = new GuideEmbedding();
            row.setSource(chunk.get("source"));
            row.setChunkTitle(chunk.get("title"));
            row.setContentHash(chunkHash(chunk));
            row.setModel(embeddingClient.getModel());
            row.setDim(vector.length);
            row.setVector(floatsToBytes(vector));
            row.setUpdatedAt(LocalDateTime.now());

            GuideEmbedding exist = embeddingRepository.selectOne(
                    new LambdaQueryWrapper<GuideEmbedding>()
                            .eq(GuideEmbedding::getSource, row.getSource())
                            .eq(GuideEmbedding::getChunkTitle, row.getChunkTitle()));
            if (exist != null) {
                exist.setContentHash(row.getContentHash());
                exist.setModel(row.getModel());
                exist.setDim(row.getDim());
                exist.setVector(row.getVector());
                exist.setUpdatedAt(row.getUpdatedAt());
                embeddingRepository.updateById(exist);
            } else {
                embeddingRepository.insert(row);
            }
        } catch (Exception e) {
            log.warn("写入向量缓存失败（降级为仅内存）: {}", e.getMessage());
        }
    }

    /** 片段唯一键：来源文件 + 小节标题 */
    private static String chunkKey(Map<String, String> chunk) {
        return chunk.get("source") + "|" + chunk.get("title");
    }

    /** 片段内容 SHA-256（标题+正文），用于判断攻略内容是否变化、是否需要重算向量 */
    private static String chunkHash(Map<String, String> chunk) {
        String content = chunk.get("title") + "\n" + chunk.get("text");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** float[] → 字节（每个 float 4 字节，大端） */
    private static byte[] floatsToBytes(float[] vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4);
        for (float f : vec) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    /** 字节 → float[]（调用方保证 len 为 4 的倍数） */
    private static float[] bytesToFloats(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        float[] vec = new float[bytes.length / 4];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = buf.getFloat();
        }
        return vec;
    }

    /** RRF 累加：每个列表贡献 1/(K + rank)，rank 从 1 起 */
    private void addRrf(Map<Map<String, String>, Double> fused, List<ScoredChunk> ranks) {
        for (int i = 0; i < ranks.size(); i++) {
            fused.merge(ranks.get(i).chunk(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    /**
     * 向量排序：query 向量化 + 余弦相似度降序；embedding 不可用时返回空列表。
     * Chroma 健康时优先走 Chroma（向量检索交给向量库，查询文本由 store 内 EF 向量化），
     * 失败自动降级为内存余弦循环。
     */
    private List<ScoredChunk> rankVector(String destination, String query, TokenUsage usageSink) {
        List<ChunkVec> vectors = getVectors(destination);
        if (vectors.isEmpty()) {
            return List.of();
        }

        if (chromaVectorStore != null && chromaVectorStore.isEnabled()
                && "cosine".equals(chromaVectorStore.distanceFunc())) {
            List<ScoredChunk> chromaRanks = rankViaChroma(destination, query);
            if (chromaRanks != null) {
                return chromaRanks;
            }
        }

        float[] queryVec = embeddingClient.embed(destination + " " + query, usageSink);
        if (queryVec == null) {
            log.warn("query embedding 失败，向量排序不可用");
            return List.of();
        }

        List<ScoredChunk> scored = new ArrayList<>();
        for (ChunkVec cv : vectors) {
            scored.add(new ScoredChunk(cv.chunk(), cosine(queryVec, cv.vector())));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored;
    }

    /**
     * 走 Chroma 检索：首次把城市片段向量 upsert 进集合，然后查询。
     * 成功返回该城市全部片段的 Chroma 排名（得分=余弦相似度）；任何失败返回 null（调用方降级内存余弦）。
     */
    private List<ScoredChunk> rankViaChroma(String destination, String query) {
        String sourcePrefix = collectionPrefix(destination);
        List<ChunkVec> vectors = vectorsByCity.get(destination);
        if (vectors == null || vectors.isEmpty()) {
            return null;
        }
        String collectionName = chromaVectorStore.collectionName(sourcePrefix);
        try {
            if (!chromaUpsertedCities.contains(collectionName)) {
                List<ChromaVectorStore.ChunkRecord> records = vectors.stream()
                        .map(cv -> new ChromaVectorStore.ChunkRecord(
                                chunkKey(cv.chunk()),
                                toFloats(cv.vector()),
                                Map.of("source", cv.chunk().get("source"),
                                        "title", cv.chunk().get("title"),
                                        "tags", cv.chunk().getOrDefault("tags", "")),
                                cv.chunk().get("text")))
                        .toList();
                if (!chromaVectorStore.upsertCity(collectionName, records)) {
                    rateLimitedChromaWarn(destination, "upsert 失败");
                    return null;
                }
                chromaUpsertedCities.add(collectionName);
            }

            List<ChromaVectorStore.ChromaHit> hits = chromaVectorStore.query(
                    collectionName, destination + " " + query, vectors.size());
            if (hits.isEmpty()) {
                rateLimitedChromaWarn(destination, "query 无结果");
                return null;
            }

            // 按 id 还原原 chunk Map 实例，保证 RRF 键与标签过滤和内存路径同一对象
            Map<String, ChunkVec> byId = new HashMap<>();
            for (ChunkVec cv : vectors) {
                byId.put(chunkKey(cv.chunk()), cv);
            }
            List<ScoredChunk> scored = new ArrayList<>();
            for (ChromaVectorStore.ChromaHit hit : hits) {
                ChunkVec cv = byId.get(hit.id());
                if (cv != null) {
                    scored.add(new ScoredChunk(cv.chunk(), hit.score()));
                }
            }
            if (scored.isEmpty()) {
                rateLimitedChromaWarn(destination, "id 还原失败");
                return null;
            }
            log.info("城市 {} 向量排序由 Chroma 提供", destination);
            return scored;
        } catch (Exception e) {
            rateLimitedChromaWarn(destination, e.getMessage());
            return null;
        }
    }

    /** Chroma 失败告警节流：每城市 60s 最多 1 条，避免断连时刷屏 */
    private void rateLimitedChromaWarn(String destination, String reason) {
        long now = System.currentTimeMillis();
        Long last = lastChromaWarn.get(destination);
        if (last == null || now - last > 60_000) {
            lastChromaWarn.put(destination, now);
            log.warn("Chroma 不可用（城市 {}：{}），已降级为内存余弦检索", destination, reason);
        }
    }

    /** float[] → List<Float>（Chroma 客户端向量入参格式） */
    private static List<Float> toFloats(float[] vec) {
        List<Float> out = new ArrayList<>(vec.length);
        for (float f : vec) {
            out.add(f);
        }
        return out;
    }

    /**
     * 关键词排序：标准 BM25 稀疏检索（标题加权 + 正文），"文档开头"降权。
     *
     * <p>语料 = 该城市全部片段（N / df / 平均长度均基于城市内语料，避免跨城市污染）。
     * 中文无显式分词，term 频率用子串匹配频次近似（保留 contains 召回能力，仅改变打分）：
     * <ul>
     *   <li>idf(t) = ln(1 + (N − df(t) + 0.5) / (df(t) + 0.5))（BM25+ 平滑，恒正）；</li>
     *   <li>词频饱和与长度归一化：tf·(k1+1) / (tf + k1·(1 − b + b·len/avg))；</li>
     *   <li>标题命中额外加权 TITLE_WEIGHT，正文权重为 1。</li>
     * </ul>
     * 城市名（如"成都"）在城市内语料中 df≈N → idf≈0，自然弱化，聚焦查询实质词。
     */
    private List<ScoredChunk> rankKeyword(String destination, String query) {
        List<String> keywords = Arrays.stream(
                        (destination + " " + (query == null ? "" : query))
                                .split("[\\s,，。；;、]+"))
                .map(String::trim)
                .filter(k -> !k.isEmpty())
                .collect(Collectors.toList());
        if (keywords.isEmpty()) {
            return List.of();
        }

        List<Map<String, String>> cityChunks = chunks.stream()
                .filter(c -> resolveCity(destination).equals(c.get("city")) || destination.equals(c.get("city"))
                        || c.get("source").toLowerCase().contains(destinationToSource(destination)))
                .toList();
        if (cityChunks.isEmpty()) {
            return List.of();
        }

        // 语料统计：df（含任一字段即计 1）与平均字段长度（BM25 的 idf / 长度归一化依赖）
        Map<String, Integer> df = new HashMap<>();
        double sumTitle = 0, sumText = 0;
        for (Map<String, String> chunk : cityChunks) {
            String title = chunk.get("title");
            String text = chunk.get("text");
            sumTitle += title.length();
            sumText += text.length();
            for (String kw : keywords) {
                if (title.contains(kw) || text.contains(kw)) {
                    df.merge(kw, 1, Integer::sum);
                }
            }
        }
        int n = cityChunks.size();
        double avgTitle = sumTitle / n;
        double avgText = sumText / n;

        List<ScoredChunk> scored = new ArrayList<>();
        for (Map<String, String> chunk : cityChunks) {
            String title = chunk.get("title");
            String text = chunk.get("text");
            double score = 0;
            for (String kw : keywords) {
                int d = df.getOrDefault(kw, 0);
                if (d == 0) {
                    continue;
                }
                double idf = Math.log(1.0 + (n - d + 0.5) / (d + 0.5));
                int tfTitle = countOccurrences(title, kw);
                int tfText = countOccurrences(text, kw);
                score += TITLE_WEIGHT * bm25Term(tfTitle, title.length(), avgTitle, idf)
                        + bm25Term(tfText, text.length(), avgText, idf);
            }
            if ("文档开头".equals(title)) {
                score -= 8; // 降权噪声片段
            }
            if (score > 0) {
                scored.add(new ScoredChunk(chunk, score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored;
    }

    /** 单个 term 的 BM25 分量：idf · tf·(k1+1) / (tf + k1·(1 − b + b·len/avg)) */
    private static double bm25Term(int tf, int fieldLen, double avgLen, double idf) {
        if (tf <= 0 || avgLen <= 0) {
            return 0;
        }
        double norm = 1 - BM25_B + BM25_B * fieldLen / avgLen;
        return idf * (tf * (BM25_K1 + 1)) / (tf + BM25_K1 * norm);
    }

    /** 子串出现次数（中文场景的 term 频率近似，保留 contains 召回能力） */
    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        int idx;
        while ((idx = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from = idx + needle.length();
        }
        return count;
    }

    /**
     * 将排序片段格式化为带来源/标题（及分数）的文本列表
     */
    private List<String> formatResults(List<ScoredChunk> scored, int topK, boolean withScore) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            Map<String, String> chunk = scored.get(i).chunk();
            if (withScore) {
                results.add(String.format("[来源: %s | 标题: %s | 相关度: %.3f]\n%s",
                        chunk.get("source"), chunk.get("title"), scored.get(i).score(), chunk.get("text")));
            } else {
                results.add(String.format("[来源: %s | 标题: %s]\n%s",
                        chunk.get("source"), chunk.get("title"), chunk.get("text")));
            }
        }
        return results;
    }

    /**
     * 余弦相似度
     */
    private double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 目的地转文件名前缀（简单映射）
     */
    private String destinationToSource(String destination) {
        if (destination == null) return "";
        if (destination.contains("北京")) return "beijing";
        if (destination.contains("大理")) return "dali";
        if (destination.contains("成都")) return "chengdu";
        if (destination.contains("三亚")) return "sanya";
        if (destination.contains("厦门")) return "xiamen";
        if (destination.contains("西安")) return "xian";
        return destination;
    }
}
