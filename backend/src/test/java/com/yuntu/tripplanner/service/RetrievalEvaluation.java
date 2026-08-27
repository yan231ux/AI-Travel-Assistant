package com.yuntu.tripplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntu.tripplanner.client.EmbeddingClient;
import com.yuntu.tripplanner.config.LLMConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * RAG 检索质量评测（毕设实验用，非默认单元测试）
 *
 * 对比三种检索策略的 recall@k 与 MRR：
 *   1. 向量：真实 embedding + 余弦相似度
 *   2. 关键词：BM25 稀疏检索（标题加权，语料=城市内片段）
 *   3. 混合 RRF：两路排序融合（生产默认策略）
 *
 * 需要真实 embedding（联网、计费），默认不参与 mvn test：
 *   mvn test -Dtest=RetrievalEvaluation -Drag.eval=true
 * 且环境需配置 LLM_API_KEY，否则直接报错而非给出失真数据。
 *
 * 评测集：6 城市 × 3 查询 = 18 条，相关文档由攻略小节标题的精确子串标注。
 */
@EnabledIfSystemProperty(named = "rag.eval", matches = "true")
@TestMethodOrder(MethodOrderer.MethodName.class) // 固定方法执行顺序，保证报告先主评测后过滤评测
class RetrievalEvaluation {

    /** 覆盖城市全部片段（向量策略），保证 MRR 精确 */
    private static final int TOP_K = 100;

    /** 查询集：目的地 + 偏好/细节描述，标注相关的攻略小节标题（精确子串） */
    record EvalQuery(String destination, String query, List<String> relevant) {}

    static final List<EvalQuery> QUERIES = List.of(
            // 北京
            new EvalQuery("北京", "故宫 开放时间 预约", List.of("2.1 故宫博物院")),
            new EvalQuery("北京", "烤鸭 老字号 推荐", List.of("北京烤鸭")),
            new EvalQuery("北京", "皇家宫殿 历史遗迹 游览", List.of("2.1 故宫博物院", "2.2 天安门广场")),
            // 成都
            new EvalQuery("成都", "大熊猫基地 看熊猫", List.of("2.1 大熊猫繁育研究基地")),
            new EvalQuery("成都", "火锅 必吃 推荐", List.of("火锅")),
            new EvalQuery("成都", "三国文化 武侯祠 祠堂", List.of("2.3 武侯祠")),
            // 大理
            new EvalQuery("大理", "洱海 环湖 骑行 生态廊道", List.of("2.2 洱海生态廊道")),
            new EvalQuery("大理", "野生菌火锅 当季 必吃", List.of("野生菌火锅（7-9月当季）")),
            new EvalQuery("大理", "白族 特色美食 酸辣", List.of("白族菜（酸辣口味为主）")),
            // 三亚
            new EvalQuery("三亚", "蜈支洲岛 潜水 玩水", List.of("2.1 蜈支洲岛")),
            new EvalQuery("三亚", "海鲜 大排档 必吃", List.of("海鲜")),
            new EvalQuery("三亚", "免税 购物 国际免税城", List.of("2.5 三亚国际免税城")),
            // 厦门
            new EvalQuery("厦门", "鼓浪屿 轮渡 上岛", List.of("2.1 鼓浪屿")),
            new EvalQuery("厦门", "厦门大学 参观 预约", List.of("2.2 厦门大学")),
            new EvalQuery("厦门", "福建土楼 一日游 周边", List.of("2.6 福建土楼（周边一日游）")),
            // 西安
            new EvalQuery("西安", "兵马俑 参观 攻略", List.of("2.1 秦始皇帝陵博物院（兵马俑）")),
            new EvalQuery("西安", "回民街 美食 小吃", List.of("2.5 回民街/洒金桥", "小吃")),
            new EvalQuery("西安", "历史博物馆 展品 文物", List.of("2.4 陕西历史博物馆")),
            // 口语化查询（验证查询改写的价值）
            new EvalQuery("成都", "看熊猫", List.of("2.1 大熊猫繁育研究基地")),
            new EvalQuery("西安", "看文物", List.of("2.4 陕西历史博物馆")),
            new EvalQuery("北京", "吃烤鸭", List.of("北京烤鸭")),
            // 补维度后新增查询（验证雨天/亲子/预算/交通新小节可被检索到）
            new EvalQuery("北京", "交通 出行 地铁 建议", List.of("交通出行")),
            new EvalQuery("成都", "亲子 带娃 景点 安排", List.of("亲子出行提示")),
            new EvalQuery("西安", "下雨 室内 备选 方案", List.of("雨天备选方案")),
            new EvalQuery("大理", "省钱 预算 性价比 玩法", List.of("省钱贴士"))
    );

    /** 评测策略：展示标签 + 检索模式 + 是否查询改写 */
    record Strategy(String label, RagService.SearchMode mode, boolean rewrite) {}

    static final List<Strategy> STRATEGIES = List.of(
            new Strategy("向量(原始)", RagService.SearchMode.VECTOR, false),
            new Strategy("关键词(原始)", RagService.SearchMode.KEYWORD, false),
            new Strategy("关键词(改写)", RagService.SearchMode.KEYWORD, true),
            new Strategy("混合(改写)", RagService.SearchMode.HYBRID, true)
    );

    private static RagService rag;

    /** 校验标注集用的检索方式：关键词、不改写 */
    private static final Strategy KEYWORD_RAW = new Strategy("", RagService.SearchMode.KEYWORD, false);

    private static final Pattern TITLE_PATTERN = Pattern.compile("标题:\\s*([^|\\]]+)\\s*");

    @BeforeAll
    static void setUp() {
        // 清掉上一次评测报告，避免多次运行叠写
        try {
            Files.deleteIfExists(Path.of("target", "retrieval-eval.md"));
        } catch (IOException ignored) {
            // 首次运行无旧报告，忽略
        }
        String apiKey = System.getenv("LLM_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("检索评测需要真实 embedding，请先配置环境变量 LLM_API_KEY");
        }
        LLMConfig cfg = new LLMConfig();
        cfg.setApiKey(apiKey); // base-url / model / embedding-model 用默认值

        CacheService noCache = mock(CacheService.class);

        EmbeddingClient realEmbedding = new EmbeddingClient(cfg, new ObjectMapper());
        // 预检：确认 embedding 可用，否则向量/混合会静默降级成关键词，数据失真
        if (realEmbedding.embed("评测预检文本", null) == null) {
            throw new IllegalStateException("embedding 调用失败，无法运行向量评测（请检查 LLM_API_KEY）");
        }

        rag = new RagService(noCache, realEmbedding, "北京,大理,成都,三亚,厦门,西安", 3600);
    }

    @Test
    void runEvaluation() {
        validateGroundTruth();

        int sCount = STRATEGIES.size();
        int n = QUERIES.size();
        double[][] sums = new double[sCount][4]; // 各策略 R@1/R@3/R@5/MRR 累加
        List<double[][]> rows = new ArrayList<>(); // 每查询 [策略][指标]

        for (EvalQuery q : QUERIES) {
            double[][] row = new double[sCount][4];
            for (int s = 0; s < sCount; s++) {
                List<String> titles = retrieveTitles(q.destination(), q.query(), STRATEGIES.get(s));
                double[] m = metrics(titles, q.relevant());
                row[s] = m;
                for (int i = 0; i < 4; i++) {
                    sums[s][i] += m[i];
                }
            }
            rows.add(row);
        }

        // 控制台文本表（每策略一块）
        StringBuilder out = new StringBuilder();
        for (int s = 0; s < sCount; s++) {
            out.append(String.format("== %s ==%n", STRATEGIES.get(s).label()));
            out.append(String.format("%-6s %-28s %5s %5s %5s %6s%n", "城市", "查询", "R@1", "R@3", "R@5", "MRR"));
            out.append("--------------------------------------------------\n");
            for (int qi = 0; qi < n; qi++) {
                EvalQuery q = QUERIES.get(qi);
                double[] m = rows.get(qi)[s];
                out.append(String.format("%-6s %-28s %5.2f %5.2f %5.2f %6.3f%n",
                        q.destination(), truncate(q.query(), 26), m[0], m[1], m[2], m[3]));
            }
            out.append("--------------------------------------------------\n");
            out.append(String.format("%-6s %-28s %5.2f %5.2f %5.2f %6.3f%n",
                    "均值", "", sums[s][0] / n, sums[s][1] / n, sums[s][2] / n, sums[s][3] / n));
            out.append("\n");
        }

        String report = buildMarkdownReport(rows, sums, n);

        System.out.println("========== RAG 检索评测（向量 / 关键词 / 混合RRF） ==========");
        System.out.print(out);
        System.out.println("==============================================================");
        writeReport(report);

        // 轻量 sanity：任一策略 recall@5 显著低于 0.5 说明评测集或检索实现存在根本问题
        for (int s = 0; s < sCount; s++) {
            assertTrue(sums[s][2] / n >= 0.5, STRATEGIES.get(s).label() + " 策略 recall@5 异常偏低");
        }
    }

    /**
     * 元数据过滤评测（毕设实验用）：用户带明确约束（雨天/亲子/预算）时，
     * 先按标签过滤再排序，验证 top 结果是否保证落在约束维度内。
     * 对比：不过滤 = 混合检索直接用查询词；过滤 = 同查询 + 约束标签前置过滤。
     * 纯度 = top5 中命中任一约束标签的占比。
     */
    @Test
    void runMetadataFilterEvaluation() {
        record TagQuery(String destination, String query, Set<String> tags, List<String> relevant) {}

        List<TagQuery> tagQueries = List.of(
                new TagQuery("成都", "下雨天带孩子去哪玩", Set.of("雨天", "亲子"), List.of("雨天备选方案", "亲子出行提示")),
                new TagQuery("北京", "阴雨天 室内 景点", Set.of("雨天"), List.of("雨天备选方案")),
                new TagQuery("大理", "预算有限 怎么玩", Set.of("预算"), List.of("省钱贴士")),
                new TagQuery("三亚", "台风天 怎么安排", Set.of("雨天"), List.of("雨天备选方案")),
                new TagQuery("西安", "带孩子 亲子 玩法", Set.of("亲子"), List.of("亲子出行提示"))
        );

        StringBuilder md = new StringBuilder();
        md.append("\n## 元数据过滤评测（约束查询）\n\n");
        md.append("> 目的：用户带明确约束（雨天/亲子/预算）时，先按标签过滤再排序，保证 top 结果落在约束维度内。\n");
        md.append("> 对比：**不过滤** = 混合检索直接用查询词；**过滤** = 同查询 + 约束标签前置过滤。**纯度** = top5 中命中任一约束标签的占比。\n\n");
        md.append("| 城市 | 约束查询 | 约束标签 | 不过滤R@3 | 过滤R@3 | 不过滤纯度 | 过滤纯度 |\n");
        md.append("|------|----------|----------|-----------|---------|-----------|----------|\n");

        StringBuilder out = new StringBuilder("== 元数据过滤：约束查询 混合检索 不过滤 vs 过滤 ==\n");
        out.append(String.format("%-4s %-22s %-12s %8s %8s %12s %10s%n",
                "城市", "查询", "标签", "不过滤R@3", "过滤R@3", "纯度(不过滤)", "纯度(过滤)"));
        out.append("--------------------------------------------------------------------------------\n");
        for (TagQuery tq : tagQueries) {
            List<String> noFilter = filteredResults(tq.destination(), tq.query(), Collections.emptySet());
            List<String> filtered = filteredResults(tq.destination(), tq.query(), tq.tags());
            double noR3 = recallAt(titlesOf(noFilter), tq.relevant(), 3);
            double fR3 = recallAt(titlesOf(filtered), tq.relevant(), 3);
            double noPur = purity(noFilter, tq.tags());
            double fPur = purity(filtered, tq.tags());
            out.append(String.format("%-4s %-22s %-12s %8.2f %8.2f %12.2f %10.2f%n",
                    tq.destination(), truncate(tq.query(), 20), tq.tags(), noR3, fR3, noPur, fPur));
            md.append(String.format("| %s | %s | %s | %.2f | %.2f | %.2f | %.2f |%n",
                    tq.destination(), tq.query(), tq.tags(), noR3, fR3, noPur, fPur));
        }
        System.out.println("========== 元数据过滤评测 ==========");
        System.out.print(out);

        // 过滤后的保证：纯度必须为 1.0（返回结果全部落在约束维度内），且召回不劣于不过滤
        for (TagQuery tq : tagQueries) {
            List<String> filtered = filteredResults(tq.destination(), tq.query(), tq.tags());
            assertEquals(1.0, purity(filtered, tq.tags()), 1e-9,
                    tq.destination() + " 过滤后纯度应=1.0（结果应全部落在约束维度内）");
            List<String> noFilter = filteredResults(tq.destination(), tq.query(), Collections.emptySet());
            assertTrue(recallAt(titlesOf(filtered), tq.relevant(), 3)
                            >= recallAt(titlesOf(noFilter), tq.relevant(), 3) - 1e-9,
                    tq.destination() + " 过滤后召回不应劣于不过滤");
        }
        writeReport(md.toString());
    }

    /** 带约束标签过滤的检索结果（混合策略 + 查询改写），保留完整片段文本供纯度计算 */
    private List<String> filteredResults(String destination, String query, Set<String> tags) {
        return rag.search(destination, rag.expandQuery(query), TOP_K, null,
                RagService.SearchMode.HYBRID, tags);
    }

    /** 从检索结果中抽取标题列表（用于 recallAt） */
    private List<String> titlesOf(List<String> results) {
        List<String> titles = new ArrayList<>();
        for (String result : results) {
            Matcher m = TITLE_PATTERN.matcher(result.split("\n", 2)[0]);
            if (m.find()) {
                titles.add(m.group(1).trim());
            }
        }
        return titles;
    }

    /** 纯度：top 结果中命中任一约束标签关键词的占比（与 RagService 标签推导逻辑一致） */
    private double purity(List<String> results, Set<String> tags) {
        if (results.isEmpty()) {
            return 0.0;
        }
        int onTag = 0;
        for (String r : results) {
            if (matchesAnyTagKeyword(r, tags)) {
                onTag++;
            }
        }
        return (double) onTag / results.size();
    }

    private boolean matchesAnyTagKeyword(String resultText, Set<String> tags) {
        for (String tag : tags) {
            for (String kw : RagService.TAG_KEYWORDS.get(tag)) {
                if (resultText.contains(kw)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Markdown 报告：汇总表 + 每查询明细表 */
    private String buildMarkdownReport(List<double[][]> rows, double[][] sums, int n) {
        int sCount = STRATEGIES.size();
        StringBuilder md = new StringBuilder();
        md.append("# RAG 检索质量评测报告（混合检索对比）\n\n");
        md.append(String.format("> 评测集：%d 条查询（6 城市×3 基础 + 3 口语 + 4 补维度）；相关文档用攻略小节标题精确子串标注。\n", n));
        md.append("> 策略：**向量** = embedding + 余弦；**关键词** = BM25（标题加权）；**混合RRF** = 两路排名融合（生产默认）。\n\n");

        md.append(String.format("## 汇总（%d 条查询均值）\n\n", n));
        md.append("| 策略 | recall@1 | recall@3 | recall@5 | MRR |\n");
        md.append("|------|----------|----------|----------|-----|\n");
        for (int s = 0; s < sCount; s++) {
            md.append(String.format("| %s | %.2f | %.2f | %.2f | %.3f |%n",
                    STRATEGIES.get(s).label(),
                    sums[s][0] / n, sums[s][1] / n, sums[s][2] / n, sums[s][3] / n));
        }

        md.append("\n## 每查询明细\n\n");
        md.append("| 城市 | 查询 |");
        for (Strategy st : STRATEGIES) {
            md.append(String.format(" %sR@1 | %sR@3 | %sR@5 | %sMRR |", st.label(), st.label(), st.label(), st.label()));
        }
        md.append("\n|------|------|");
        for (int s = 0; s < sCount; s++) {
            md.append("--------|--------|--------|--------|");
        }
        md.append("\n");
        for (int qi = 0; qi < n; qi++) {
            EvalQuery q = QUERIES.get(qi);
            md.append(String.format("| %s | %s |", q.destination(), q.query()));
            for (int s = 0; s < sCount; s++) {
                double[] m = rows.get(qi)[s];
                md.append(String.format(" %.2f | %.2f | %.2f | %.3f |", m[0], m[1], m[2], m[3]));
            }
            md.append("\n");
        }
        return md.toString();
    }

    /** 校验标注集：每个相关标题子串都能在攻略库中定位（防止标注错误导致评测失真） */
    private void validateGroundTruth() {
        List<String> missing = new ArrayList<>();
        for (EvalQuery q : QUERIES) {
            for (String rel : q.relevant()) {
                // 用标题本身作为查询走关键词检索：标题命中 +3，应能命中对应片段
                List<String> found = retrieveTitles(q.destination(), rel, KEYWORD_RAW);
                if (found.stream().noneMatch(t -> t.contains(rel))) {
                    missing.add(q.destination() + " → " + rel);
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "以下相关标题未在攻略库定位到，请核对评测标注: " + missing);
    }

    /** 按策略检索并抽取排序后的标题列表（与 score 无关，仅保留顺序） */
    private List<String> retrieveTitles(String destination, String query, Strategy strategy) {
        String q = strategy.rewrite() ? rag.expandQuery(query) : query;
        List<String> titles = new ArrayList<>();
        for (String result : rag.search(destination, q, TOP_K, null, strategy.mode())) {
            Matcher m = TITLE_PATTERN.matcher(result.split("\n", 2)[0]);
            if (m.find()) {
                titles.add(m.group(1).trim());
            }
        }
        return titles;
    }

    /** 计算某查询的 [recall@1, recall@3, recall@5, MRR] */
    private double[] metrics(List<String> titles, List<String> relevant) {
        return new double[]{
                recallAt(titles, relevant, 1),
                recallAt(titles, relevant, 3),
                recallAt(titles, relevant, 5),
                mrr(titles, relevant)
        };
    }

    private double recallAt(List<String> titles, List<String> relevant, int k) {
        Set<String> hit = new HashSet<>();
        for (int i = 0; i < Math.min(k, titles.size()); i++) {
            for (String rel : relevant) {
                if (titles.get(i).contains(rel)) {
                    hit.add(rel);
                }
            }
        }
        return (double) hit.size() / relevant.size();
    }

    private double mrr(List<String> titles, List<String> relevant) {
        for (int i = 0; i < titles.size(); i++) {
            for (String rel : relevant) {
                if (titles.get(i).contains(rel)) {
                    return 1.0 / (i + 1);
                }
            }
        }
        return 0.0;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 报告追加落盘为 UTF-8 Markdown（控制台中文可能乱码，文件最可靠） */
    private static void writeReport(String report) {
        try {
            Path path = Path.of("target", "retrieval-eval.md");
            Files.writeString(path, report, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("评测报告已写入: " + path.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("报告写入失败: " + e.getMessage());
        }
    }
}
