package com.emotion.task;

import com.emotion.scheduler.ManagedTask;
import com.emotion.scheduler.TaskResult;
import com.emotion.service.StockDictService;
import org.springframework.stereotype.Component;

/**
 * 每周同步一次 A股代码名称字典（t_stock）。
 *
 * <p>为什么需要它：选股下拉（高位生态的阵眼登记、持仓台账）是远程搜索，只查 t_stock；
 * 这张表不写就一直是空的，表现是「输股票名称搜不出来」。字典变化很慢（新股上市、ST 改名），
 * 一周一次足够，不需要挂在每日拉取里跟着每次都打一遍全市场名单。
 *
 * <p>失败按 FAILED 记：字典空着只是不方便，但"同步失败"和"本周没有变化"必须分得开，
 * 留痕里看得到才不会把长年空白当成正常状态。
 */
@Component("stockDictSyncTask")
public class StockDictSyncTask implements ManagedTask {

    private final StockDictService stockDictService;

    public StockDictSyncTask(StockDictService stockDictService) {
        this.stockDictService = stockDictService;
    }

    @Override
    public TaskResult run() {
        try {
            StockDictService.SyncResult result = stockDictService.sync();
            if (!result.isOk()) {
                return TaskResult.fail(result.getMessage());
            }
            return TaskResult.ok(result.getMessage());
        } catch (RuntimeException e) {
            return TaskResult.fail("股票字典同步异常：" + e.getMessage());
        }
    }
}
