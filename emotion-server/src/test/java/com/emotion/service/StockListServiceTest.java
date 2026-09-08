package com.emotion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 板块归类只认代码前缀，一个都判错不了：这张表是给人搜股票用的，
 * 把创业板标成北交所会直接把人带错方向。
 */
class StockListServiceTest {

    @Test
    void classifiesEveryBoardByCodePrefix() {
        assertEquals("沪主板", StockListService.boardOf("605577"));
        assertEquals("科创板", StockListService.boardOf("688601"));
        assertEquals("深主板", StockListService.boardOf("002403"));
        assertEquals("创业板", StockListService.boardOf("300059"));
        assertEquals("北交所", StockListService.boardOf("920066"));
        assertEquals("北交所", StockListService.boardOf("430047"));
        assertEquals("北交所", StockListService.boardOf("830799"));
        assertEquals("北交所", StockListService.boardOf("870199"));
        assertEquals("北交所", StockListService.boardOf("880000"));
    }

    /** 定向可转债进不了这张表，但万一上游改了口径，也要落在"其他"而不是冒充主板。 */
    @Test
    void unknownPrefixesLandInOtherRatherThanGuessing() {
        assertEquals("其他", StockListService.boardOf("810014"));
        assertEquals("其他", StockListService.boardOf("900901"));
        assertEquals("其他", StockListService.boardOf("600"));
        assertEquals("其他", StockListService.boardOf(null));
    }
}
