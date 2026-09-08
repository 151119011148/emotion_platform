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

    public List<NodeEvent> listByUser(Long userId) {
        return nodeEventMapper.selectList(
                new LambdaQueryWrapper<NodeEvent>()
                        .eq(NodeEvent::getUserId, userId)
                        .orderByDesc(NodeEvent::getCreatedAt));
    }

    public NodeEvent getCurrent(Long userId) {
        return nodeEventMapper.selectOne(
                new LambdaQueryWrapper<NodeEvent>()
                        .eq(NodeEvent::getUserId, userId)
                        .eq(NodeEvent::getStatus, "待验证")
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
