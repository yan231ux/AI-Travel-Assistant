package com.yuntu.tripplanner.client;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bing搜索客户端
 */
@Slf4j
@Component
public class BingSearchClient {
    
    private static final String BING_SEARCH_URL = "https://www.bing.com/search";
    
    /**
     * 执行搜索
     *
     * @param query 搜索关键词
     * @return 搜索结果列表
     */
    public List<Map<String, String>> search(String query) {
        List<Map<String, String>> results = new ArrayList<>();
        
        try {
            String url = UriComponentsBuilder.fromHttpUrl(BING_SEARCH_URL)
                    .queryParam("q", query)
                    .queryParam("count", "10")
                    .queryParam("setlang", "zh-cn")
                    .toUriString();
            
            log.info("Bing搜索: {}", url);
            
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(10000)
                    .get();
            
            // 解析搜索结果
            Elements searchResults = doc.select(".b_algo");
            
            for (Element result : searchResults) {
                try {
                    Map<String, String> item = new HashMap<>();
                    
                    // 标题和链接
                    Element titleElement = result.selectFirst("h2 a");
                    if (titleElement != null) {
                        item.put("title", titleElement.text());
                        item.put("link", titleElement.attr("href"));
                    }
                    
                    // 摘要
                    Element snippetElement = result.selectFirst(".b_caption p");
                    if (snippetElement != null) {
                        item.put("snippet", snippetElement.text());
                    } else {
                        // 尝试其他选择器
                        Element p = result.selectFirst("p");
                        if (p != null) {
                            item.put("snippet", p.text());
                        }
                    }
                    
                    if (item.containsKey("title") && item.containsKey("link")) {
                        results.add(item);
                    }
                } catch (Exception e) {
                    log.warn("解析单个搜索结果失败: {}", e.getMessage());
                }
            }
            
            log.info("Bing搜索返回 {} 条结果", results.size());
            
        } catch (Exception e) {
            log.error("Bing搜索失败: {}", e.getMessage());
        }
        
        return results;
    }
    
    /**
     * 搜索并返回格式化文本
     *
     * @param query 搜索关键词
     * @return 格式化的搜索结果文本
     */
    public String searchAsText(String query) {
        List<Map<String, String>> results = search(query);
        
        if (results.isEmpty()) {
            return "未找到相关搜索结果";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("搜索关键词: ").append(query).append("\n\n");
        
        for (int i = 0; i < results.size(); i++) {
            Map<String, String> result = results.get(i);
            sb.append(i + 1).append(". ").append(result.get("title")).append("\n");
            sb.append("   链接: ").append(result.get("link")).append("\n");
            if (result.containsKey("snippet")) {
                sb.append("   摘要: ").append(result.get("snippet")).append("\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
}