package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.NodeEvent;
import com.emotion.mapper.NodeEventMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NodeService {

    private final NodeEventMapper nodeEventMapper;

    public NodeService(NodeEventMapper nodeEventMapper) {
        this.nodeEventMapper = nodeEventMapper;
    }

    /**
     * 历史节点：<b>按 D0 倒排</b>，最新那个节点在最上面。
     *
     * <p>他记一个节点记的是"哪一天起的"，按 {@code created_at} 排会把几个月前补录的最老节点顶到第一行。
     * D0 相同的（同一天建了好几个）再按创建时间倒排；D0 还没填的空壳在 MySQL 的 DESC 下自然落最后。
     */
    public List<NodeEvent> listByUser(Long userId) {
        return nodeEventMapper.selectList(
                new LambdaQueryWrapper<NodeEvent>()
                        .eq(NodeEvent::getUserId, userId)
                        .orderByDesc(NodeEvent::getD0Date)
                        .orderByDesc(NodeEvent::getCreatedAt));
    }

    /** 待验证的那一个：同样以 D0 为准挑最近，不能挑到一条 D0 更老的。 */
    public NodeEvent getCurrent(Long userId) {
        return nodeEventMapper.selectOne(
                new LambdaQueryWrapper<NodeEvent>()
                        .eq(NodeEvent::getUserId, userId)
                        .eq(NodeEvent::getStatus, "待验证")
                        .orderByDesc(NodeEvent::getD0Date)
                        .orderByDesc(NodeEvent::getCreatedAt)
                        .last("LIMIT 1"));
    }

    public NodeEvent create(Long userId, NodeEvent event) {
        event.setUserId(userId);
        nodeEventMapper.insert(event);
        return event;
    }

    public NodeEvent update(Long userId, Long id, NodeEvent event) {
        NodeEvent existing = nodeEventMapper.selectOne(
                new LambdaQueryWrapper<NodeEvent>()
                        .eq(NodeEvent::getId, id)
                        .eq(NodeEvent::getUserId, userId));
        if (existing == null) throw new RuntimeException("节点事件不存在");

        if (event.getT1Date() != null) existing.setT1Date(event.getT1Date());
        if (event.getT1AnchorRepack() != null) existing.setT1AnchorRepack(event.getT1AnchorRepack());
        if (event.getT1PromotionCount() != null) existing.setT1PromotionCount(event.getT1PromotionCount());
        if (event.getT1PromotionRate() != null) existing.setT1PromotionRate(event.getT1PromotionRate());
        if (event.getNodeValid() != null) existing.setNodeValid(event.getNodeValid());
        if (event.getNodeStock() != null) existing.setNodeStock(event.getNodeStock());
        if (event.getNodeStockMaxBoard() != null) existing.setNodeStockMaxBoard(event.getNodeStockMaxBoard());
        if (event.getStatus() != null) existing.setStatus(event.getStatus());
        if (event.getNote() != null) existing.setNote(event.getNote());
        if (event.getD0Candidates() != null) existing.setD0Candidates(event.getD0Candidates());

        nodeEventMapper.updateById(existing);
        return existing;
    }
}
