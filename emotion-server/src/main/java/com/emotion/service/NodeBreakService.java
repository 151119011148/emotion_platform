package com.emotion.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.NodeEvent;
import com.emotion.mapper.NodeEventMapper;
import com.emotion.vo.BreakDetailVO;
import com.emotion.vo.BreakNodeCreateVO;
import com.emotion.vo.NodeVO;
import com.emotion.vo.TiantiVO;

/**
 * 把曲线上的那颗 ☆/★ 立成节点事件。
 *
 * <p><b>一次点击落两行</b>（按他定的口径）：试探破壁日落一行「试探破壁 · 观察」，
 * 破壁成功日再落一行「破壁成功 · 出手」；点在成功日上时，前一天那次试探也一并补齐，
 * 免得成功行孤零零挂在表里、前面那次追平查无实据。
 *
 * <p>判定还是那一份：哪天是试探、哪天成功，读 {@link TiantiService#breakDay}，
 * 与详情面板和曲线上那颗标记同一个来源。这里只负责把它翻译成 {@code t_node_event} 的行。
 *
 * <p>幂等只在服务里挡：{@code t_node_event} 上没有唯一键（只有三个普通索引），
 * 同 {@code user + d0Date + nodeType} 已存在就把既有行交回去，不重复插。
 */
@Service
public class NodeBreakService {

    /** 类型码·试探破壁：有票首次追平这条钉住的线，成不成要看它次日续不续板。 */
    static final String TYPE_PROBE = "SPACE_BREAK";
    /** 类型码·破壁成功：昨天那次试探当天续板加高，旧周期那面壁被破掉了。 */
    static final String TYPE_BREAK = "SPACE_BREAK_NEXT";
    /** {@code repair_status} 沿用 V34 的三个值；试探行落地就是「待判定」，成没成由复算面板算、他采纳才落。 */
    static final String REPAIR_PENDING = "PENDING";

    private final TiantiService tiantiService;
    private final NodeService nodeService;
    private final NodeEventMapper nodeEventMapper;
    private final BreakDetailService breakDetailService;

    public NodeBreakService(TiantiService tiantiService,
                            NodeService nodeService,
                            NodeEventMapper nodeEventMapper,
                            BreakDetailService breakDetailService) {
        this.tiantiService = tiantiService;
        this.nodeService = nodeService;
        this.nodeEventMapper = nodeEventMapper;
        this.breakDetailService = breakDetailService;
    }

    /**
     * 立节点：试探日一行，成功日两行（成功行 + 前一天那次试探）。
     * 已立过的行按幂等交回来，不重复插；一行都没新插时 {@code alreadyExists=true}，
     * 免得界面把"早就立过了"报成"又立了一条"。
     *
     * @throws IllegalArgumentException 这天曲线上没有 ☆/★，没有破壁事件可立
     */
    @Transactional
    public BreakNodeCreateVO createNodes(Long userId, LocalDate date) {
        TiantiService.BreakDay day = tiantiService.breakDay(date);
        List<Line> lines = linesOf(day.getPoint(), day.getPrev());
        if (lines.isEmpty()) {
            throw new IllegalArgumentException(date + " 这天曲线上没有破壁事件：既没有票追平破壁线，"
                    + "也没有破壁成功，没有节点可立");
        }
        List<NodeVO> rows = new ArrayList<>();
        int inserted = 0;
        for (Line line : lines) {
            NodeEvent held = findExisting(userId, line.d0Date, line.nodeType);
            if (held == null) {
                rows.add(nodeService.create(userId, toEntity(line)));
                inserted++;
            } else {
                rows.add(nodeService.detail(userId, held.getId()));
            }
        }
        BreakNodeCreateVO out = new BreakNodeCreateVO();
        out.setRows(rows);
        out.setAlreadyExists(inserted == 0);
        Line head = lines.get(0);
        out.setScoreImpact(breakDetailService.scoreImpact(userId, head.d0Date,
                head.stockCode, head.nodeType));
        return out;
    }

    /** 面板/接口的事件码 → 节点类型码。 */
    static String typeOfEvent(String event) {
        if (BreakDetailVO.EVENT_PROBE.equals(event)) {
            return TYPE_PROBE;
        }
        return BreakDetailVO.EVENT_BREAK.equals(event) ? TYPE_BREAK : null;
    }

    /**
     * 这一行是不是破壁节点：复算服务按它分流，破壁两行走 {@code decideBreak}，
     * 高低切那五个字（接位/补位/转切…）一个字都不碰。
     */
    static boolean isBreakType(String nodeType) {
        return TYPE_PROBE.equals(nodeType) || TYPE_BREAK.equals(nodeType);
    }

    // ---------- 中间结构：一行节点该带的数 ----------

    /** 一条待落库的破壁行；单测直接搓 {@link TiantiVO.HeightPoint} 数组断言这几个字段。 */
    static final class Line {
        final LocalDate d0Date;
        final String nodeType;
        final String stockCode;
        final String stockName;
        final Integer stockBoard;
        /** 它追的那条线高：试探日读当天挂着的线，成功日读被追平的那条线。 */
        final Integer chasedLine;
        /** 试探行的续板判定初始值（PENDING）；成功行没有这一项，留 null。 */
        final String repairStatus;

        Line(LocalDate d0Date, String nodeType, TiantiVO.HeightStock stock, Integer stockBoard,
             Integer chasedLine, String repairStatus) {
            this.d0Date = d0Date;
            this.nodeType = nodeType;
            this.stockCode = stock == null ? null : stock.getCode();
            this.stockName = stock == null ? null : stock.getName();
            this.stockBoard = stockBoard;
            this.chasedLine = chasedLine;
            this.repairStatus = repairStatus;
        }
    }

    /**
     * 这个点要落哪几行：成功日两行（成功行 + 前一天那次试探），试探日一行。
     * 两行的主角都是<b>追线的这只票</b>，不是挂着线的那只——挂线的老龙从来不是这次试探的对象。
     */
    static List<Line> linesOf(TiantiVO.HeightPoint p, TiantiVO.HeightPoint prev) {
        List<Line> out = new ArrayList<>();
        if (p == null) {
            return out;
        }
        if (Boolean.TRUE.equals(p.getIsBreak())) {
            String code = code(p.getBreakStock());
            out.add(new Line(p.getTradeDate(), TYPE_BREAK, p.getBreakStock(),
                    TiantiService.boardOf(p, code), p.getPrevHigh(), null));
            if (prev != null && Boolean.TRUE.equals(prev.getIsProbe()) && code != null
                    && code.equals(code(prev.getProbeStock()))) {
                // 补出来的那次试探追的是哪条线，成功日的 prevHigh 说得很准；
                // 试探日当天的 ceiling 已经跟着它抬上去了，用不得。
                out.add(new Line(prev.getTradeDate(), TYPE_PROBE, prev.getProbeStock(),
                        TiantiService.boardOf(prev, code),
                        p.getPrevHigh() == null ? prev.getCeiling() : p.getPrevHigh(), REPAIR_PENDING));
            }
        } else if (Boolean.TRUE.equals(p.getIsProbe())) {
            String code = code(p.getProbeStock());
            out.add(new Line(p.getTradeDate(), TYPE_PROBE, p.getProbeStock(),
                    TiantiService.boardOf(p, code), TiantiService.chasedLine(prev, p), REPAIR_PENDING));
        }
        return out;
    }

    /** 破壁行落进 {@code t_node_event}。 */
    static NodeEvent toEntity(Line line) {
        NodeEvent e = new NodeEvent();
        e.setD0Date(line.d0Date);
        e.setNodeType(line.nodeType);
        e.setNodeStock(displayOf(line.stockName, line.stockCode));
        e.setNodeStockMaxBoard(line.stockBoard);
        e.setBreakStockCode(line.stockCode);
        e.setBreakBoard(line.chasedLine);
        e.setRepairStatus(line.repairStatus);
        e.setStatus("待验证");
        // system_type 是高低切那套「系统A/B」的标签，破壁节点不属于任何一个：留 DDL 的默认空串，
        // 别硬套「系统A」——那等于在节点表里凭空造出一个从来没被这样定义过的分类。
        e.setSystemType("");
        return e;
    }

    /** {@code node_stock} 的写法：{@code 深中华Ａ(000012)}，与 {@link NodeService#stockCodeOf} 的解析对得上。 */
    static String displayOf(String name, String code) {
        if (code == null) {
            return name;
        }
        return (name == null || name.trim().isEmpty() ? "" : name.trim()) + "(" + code + ")";
    }

    private NodeEvent findExisting(Long userId, LocalDate d0Date, String nodeType) {
        return nodeEventMapper.selectOne(new LambdaQueryWrapper<NodeEvent>()
                .eq(NodeEvent::getUserId, userId)
                .eq(NodeEvent::getD0Date, d0Date)
                .eq(NodeEvent::getNodeType, nodeType)
                .last("LIMIT 1"));
    }

    private static String code(TiantiVO.HeightStock stock) {
        return stock == null ? null : stock.getCode();
    }
}
