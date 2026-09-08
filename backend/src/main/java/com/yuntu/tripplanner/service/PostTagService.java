package com.yuntu.tripplanner.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuntu.tripplanner.common.PostTagResolver;
import com.yuntu.tripplanner.model.PostSpot;
import com.yuntu.tripplanner.model.PostTag;
import com.yuntu.tripplanner.model.Spot;
import com.yuntu.tripplanner.model.TravelPost;
import com.yuntu.tripplanner.repository.PostSpotRepository;
import com.yuntu.tripplanner.repository.PostTagRepository;
import com.yuntu.tripplanner.repository.SpotRepository;
import com.yuntu.tripplanner.repository.TravelPostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 帖子标签服务（产品化阶段三：内容个性化闭环的标签底座持久化）。
 *
 * <p>职责边界：只回答「这篇帖子的画像标签是什么、怎么落库」，不承担排序/日志/审核。
 * 标签由 {@link PostTagResolver} 纯词典映射（零 LLM），帖子一旦可进公开流
 * （PUBLISHED 或被用户浏览/反馈）即可惰性补齐；(post_id, category, tag) 唯一幂等。
 * 关联景点的业态从 spot 表读（spot_id 优先，缺失时名称兜底，防名称串联）。
 */
@Slf4j
@Service
public class PostTagService {

    private final PostTagRepository postTagRepository;
    private final PostSpotRepository postSpotRepository;
    private final SpotRepository spotRepository;
    private final TravelPostRepository travelPostRepository;

    public PostTagService(PostTagRepository postTagRepository,
                          PostSpotRepository postSpotRepository,
                          SpotRepository spotRepository,
                          TravelPostRepository travelPostRepository) {
        this.postTagRepository = postTagRepository;
        this.postSpotRepository = postSpotRepository;
        this.spotRepository = spotRepository;
        this.travelPostRepository = travelPostRepository;
    }

    /** 删除并重算某帖子的标签（帖子内容/关联景点变更后调用，幂等） */
    @Transactional
    public List<PostTag> refresh(TravelPost post) {
        if (post == null || post.getId() == null) {
            return List.of();
        }
        postTagRepository.delete(new LambdaQueryWrapper<PostTag>()
                .eq(PostTag::getPostId, post.getId()));
        List<PostTag> tags = computeTags(post);
        for (PostTag tag : tags) {
            try {
                postTagRepository.insert(tag);
            } catch (DuplicateKeyException e) {
                log.debug("帖子标签重复跳过: postId={} {}={}", post.getId(), tag.getCategory(), tag.getTag());
            }
        }
        if (!tags.isEmpty()) {
            log.info("帖子标签刷新：postId={} tags={}", post.getId(),
                    tags.stream().map(t -> t.getCategory() + ":" + t.getTag()).toList());
        }
        return tags;
    }

    /** 惰性补齐：已有标签直接用；没有才按当前帖子内容算一次（存量历史帖平滑升级） */
    @Transactional
    public List<PostTag> ensure(Long postId) {
        if (postId == null) {
            return List.of();
        }
        List<PostTag> rows = list(postId);
        if (!rows.isEmpty()) {
            return rows;
        }
        TravelPost post = travelPostRepository.selectById(postId);
        if (post == null || TravelPost.STATUS_DELETED.equals(post.getStatus())) {
            return List.of();
        }
        return refresh(post);
    }

    /** 单帖标签（不触发计算，纯查询） */
    public List<PostTag> list(Long postId) {
        if (postId == null) {
            return List.of();
        }
        return postTagRepository.selectList(new LambdaQueryWrapper<PostTag>()
                .eq(PostTag::getPostId, postId)
                .orderByAsc(PostTag::getId));
    }

    /** 批量惰性补齐并返回 postId → 标签列表（推荐流整页一次取，避免 N+1） */
    public Map<Long, List<PostTag>> ensureBatch(Collection<TravelPost> posts) {
        Map<Long, List<PostTag>> result = new HashMap<>();
        if (posts == null || posts.isEmpty()) {
            return result;
        }
        List<TravelPost> rows = posts.stream()
                .filter(p -> p != null && p.getId() != null)
                .collect(Collectors.toList());
        if (rows.isEmpty()) {
            return result;
        }
        List<Long> ids = rows.stream().map(TravelPost::getId).collect(Collectors.toList());
        List<PostTag> all = postTagRepository.selectList(new LambdaQueryWrapper<PostTag>()
                .in(PostTag::getPostId, ids)
                .orderByAsc(PostTag::getId));
        Map<Long, List<PostTag>> byPost = all.stream().collect(Collectors.groupingBy(PostTag::getPostId));
        for (TravelPost post : rows) {
            List<PostTag> existing = byPost.get(post.getId());
            if (existing != null && !existing.isEmpty()) {
                result.put(post.getId(), existing);
            } else {
                result.put(post.getId(), refresh(post));
            }
        }
        return result;
    }

    /* ================= 内部计算 ================= */

    /** 计算帖子标签：读关联景点（spot_id → spot 业态），交给纯函数映射后落实体 */
    private List<PostTag> computeTags(TravelPost post) {
        List<PostTagResolver.SpotRef> refs = loadSpotRefs(post.getId());
        List<PostTagResolver.Tag> resolved = PostTagResolver.resolve(
                post.getTitle(), post.getSummary(), post.getContent(),
                post.getCity(), post.getPace(), refs);
        List<PostTag> tags = new ArrayList<>();
        for (PostTagResolver.Tag t : resolved) {
            PostTag tag = new PostTag();
            tag.setPostId(post.getId());
            tag.setCategory(t.category());
            tag.setTag(t.tag());
            tag.setSource(t.source());
            tags.add(tag);
        }
        return tags;
    }

    /** post_spot → (高德业态, 名称)；spot 行缺失时名称兜底（名称映射优先级低于业态） */
    private List<PostTagResolver.SpotRef> loadSpotRefs(Long postId) {
        List<PostSpot> links = postSpotRepository.selectList(
                new LambdaQueryWrapper<PostSpot>().eq(PostSpot::getPostId, postId));
        if (links.isEmpty()) {
            return List.of();
        }
        List<String> spotIds = links.stream()
                .map(PostSpot::getSpotId)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
        Map<String, Spot> spotById = new HashMap<>();
        if (!spotIds.isEmpty()) {
            try {
                spotRepository.selectList(new LambdaQueryWrapper<Spot>().in(Spot::getSpotId, spotIds))
                        .forEach(s -> spotById.put(s.getSpotId(), s));
            } catch (Exception e) {
                log.debug("帖子关联景点业态读取失败（名称兜底）: {}", e.getMessage());
            }
        }
        List<PostTagResolver.SpotRef> refs = new ArrayList<>();
        for (PostSpot link : links) {
            Spot spot = link.getSpotId() == null ? null : spotById.get(link.getSpotId());
            String category = spot == null ? null : spot.getCategory();
            String name = spot != null && spot.getName() != null ? spot.getName() : link.getSpotName();
            refs.add(new PostTagResolver.SpotRef(category, name));
        }
        return refs;
    }
}
