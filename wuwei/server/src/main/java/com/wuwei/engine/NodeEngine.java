package com.wuwei.engine;

import com.wuwei.entity.NodeDaily;
import org.springframework.stereotype.Service;

/**
 * 节点判定引擎：六态状态机（冰点→启动→发酵→高潮→分歧→退潮）（PRD §4.2）。
 * 硬判定（冰点/高潮/强制退潮）优先，其余按 prev_node + 题材生命周期阶段软判定。
 */
@Service
public class NodeEngine {

    public NodeResult determine(SentimentResult r, NodeDaily prevRow) {
        String prev = prevRow == null ? null : prevRow.getNode();
        double total = r.getTotal();
        double prevTotal = r.getPrevTotal();
        int h = r.getMaxBoard();
        int zt = r.getZtCount();
        int noodle = r.getBigNoodleCount();

        String node = null;
        String trigger = null;

        // ---------- 硬判定 ----------
        if (r.isForceExit()) {
            node = "退潮";
            trigger = "强制退潮：" + r.getForceReason();
        } else if (h <= 2 && zt <= 15 && total < 30) {
            node = "冰点";
            trigger = "连板高度≤2 且涨停≤15家，情绪冰封（硬判定）";
        } else if ("高潮".equals(prev) || "分歧".equals(prev)) {
            if (noodle >= 2 && prevTotal > 0 && total < prevTotal * 0.85) {
                node = "分歧";
                trigger = "大面≥2家且总分回落（" + fmt(prevTotal) + "→" + fmt(total) + "），高潮后分歧";
            } else if (total < 40) {
                node = "退潮";
                trigger = "总分跌破40，情绪退潮确认";
            } else if (total >= 85 && h >= 5) {
                node = "高潮";
                trigger = "总分≥85且高度≥5板，分歧转一致再高潮";
            } else if (total >= 70) {
                node = "高潮";
                trigger = "承接充分，高位延续";
            } else {
                node = "分歧";
                trigger = "高位震荡，多空拉锯";
            }
        } else if ("冰点".equals(prev) || "退潮".equals(prev)) {
            if (total >= 55 && r.getShouban() >= 45) {
                node = "启动";
                trigger = "首板生态回暖（" + fmt(r.getShouban()) + "）+ 总分回升至" + fmt(total) + "，情绪启动";
            } else if (total >= 70 && r.getConcept() >= 60) {
                node = "发酵";
                trigger = "主线确立且总分V形反转至" + fmt(total);
            } else if (total < 30) {
                node = "冰点";
                trigger = "情绪继续冰封";
            } else {
                node = "退潮";
                trigger = "退潮余波，仍在磨底";
            }
        } else if ("启动".equals(prev)) {
            boolean mainConfirmed = "确认".equals(r.getMainStage()) || "扩散".equals(r.getMainStage());
            if (mainConfirmed && r.getMidPromoteRate() >= 0.30 && total >= 55) {
                node = "发酵";
                trigger = "主线「" + r.getMainConceptName() + "」" + r.getMainStage()
                        + "，中位晋级率 " + pct(r.getMidPromoteRate()) + "≥30%，发酵确认";
            } else if (total < 35) {
                node = "退潮";
                trigger = "假启动，总分再度走弱至" + fmt(total);
            } else {
                node = "启动";
                trigger = "启动进行中，等待主线确认与中位晋级";
            }
        } else if ("发酵".equals(prev)) {
            if (total >= 80 && h >= 5) {
                node = "高潮";
                trigger = "高度（" + h + "板）与总分（" + fmt(total) + "）共振，进入高潮";
            } else if (total < 45) {
                node = "分歧";
                trigger = "发酵中断，总分回落至" + fmt(total);
            } else {
                node = "发酵";
                trigger = "发酵延续，总分 " + fmt(total);
            }
        } else if ("分歧".equals(prev)) {
            if (total >= 70) {
                node = "高潮";
                trigger = "分歧转一致，总分修复至" + fmt(total);
            } else if (total < 50) {
                node = "退潮";
                trigger = "分歧未修复，总分走弱至" + fmt(total);
            } else {
                node = "分歧";
                trigger = "分歧延续，等待方向选择";
            }
        }

        // ---------- 兜底 ----------
        if (node == null) {
            if (total >= 85 && h >= 5) {
                node = "高潮";
                trigger = "总分≥85且高度≥5板（硬判定）";
            } else if (noodle >= 5) {
                node = "退潮";
                trigger = "大面/核按钮≥5家，亏钱效应爆表";
            } else if (prev == null) {
                if (total < 30) node = "冰点";
                else if (total < 50) node = "启动";
                else if (total < 70) node = "发酵";
                else node = "分歧";
                trigger = "首日无前值，按总分分段定位（" + fmt(total) + "）";
            } else {
                node = "启动";
                trigger = "按总分区间默认定位（" + fmt(total) + "）";
            }
        }

        NodeResult nr = new NodeResult();
        nr.setNode(node);
        nr.setPrevNode(prev == null ? "—" : prev);
        nr.setTransition(prev == null ? "—" : prev + "→" + node);
        nr.setTriggerReason(trigger);
        nr.setForecast(forecastOf(node, r));
        nr.setMainConcept(r.getMainConceptName() == null ? "—" : r.getMainConceptName());
        nr.setMainStage(r.getMainStage() == null ? "—" : r.getMainStage());

        // 观察点：动态生成
        if (r.getLeaderName() != null && ("PROMOTE".equals(r.getLeaderAction()) || "HOLD".equals(r.getLeaderAction()))) {
            nr.getWatchPoints().add("总龙头 " + r.getLeaderName() + " 能否晋级 " + (r.getLeaderBoard() + 1) + " 板");
        } else if (r.getLeaderName() != null) {
            nr.getWatchPoints().add("原总龙头 " + r.getLeaderName() + " 断板后能否反包");
        }
        if (noodle > 0) {
            nr.getWatchPoints().add("大面/核按钮家数（今日 " + noodle + " 家）");
        }
        if (r.getMidPromoteRate() > 0) {
            nr.getWatchPoints().add("中位晋级率（今日 " + pct(r.getMidPromoteRate()) + "）");
        }
        nr.getWatchPoints().add("主线「" + nr.getMainConcept() + "」阶段与首板聚集度");
        return nr;
    }

    private String forecastOf(String node, SentimentResult r) {
        String leader = r.getLeaderName();
        switch (node) {
            case "冰点":
                return "等待首板批量修复与连板高度重建；若有资金试错首板，次日看晋级率能否跟上";
            case "启动":
                return "主线若确认可加仓至5成；中位晋级率是发酵的发令枪";
            case "发酵":
                return "持股为主，主线中军低吸、总龙头打板；远离杂毛跟风";
            case "高潮":
                return "只做总龙头，冲高兑现不恋战；警惕大面信号（≥2家即减仓）";
            case "分歧":
                return "3-5成仓，等分歧转一致或退潮确认；中军承接力度是关键";
            default:
                return "空仓或轻仓试错新题材首板；严禁任何高位接力";
        }
    }

    private String fmt(double v) {
        return String.format("%.1f", v);
    }

    private String pct(double v) {
        return String.format("%.0f%%", v * 100);
    }
}
