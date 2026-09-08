package com.emotion.market;

import lombok.Data;

/**
 * 一天盘面明细按池聚合出来的三个家数，第 4 维三个子项的分母全部来自这里。
 *
 * <p>数的是 {@code t_market_stock} 的明细行，不是上游的 tc。理由是重算必须能离线复现：
 * 用 tc 就只有在拉取那一刻拿得到，隔天重算就只能退回明细，两条路径会给出两个封板率。
 * 14 天实测逐日 明细 ZT 家数 == limit_up_count（上游 pagesize 配 200，一天最多 88 家，截不到），
 * 所以这里和上游同源；哪天真被截断了，池子的截断警告会先响。
 */
@Data
public class PoolCounts {

    /** 涨停池家数。 */
    private int ztCount;
    /** 炸板池家数。 */
    private int zbCount;
    /** 涨停池里 zbc&gt;0 的家数：封住前打开过、尾盘又封回去了。 */
    private int resealCount;

    /** 一行明细都没有 = 那天没回补过明细，两个家数口径都该是"未评"，不是 0%。 */
    public boolean isEmpty() {
        return ztCount + zbCount == 0;
    }
}
