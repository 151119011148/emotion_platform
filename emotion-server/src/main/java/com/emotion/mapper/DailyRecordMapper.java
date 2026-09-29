package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.DailyRecord;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface DailyRecordMapper extends BaseMapper<DailyRecord> {

    /**
     * 写过复盘的账号（升序去重）。
     *
     * <p>定时任务补客观行时按这份名单走：只给<b>已经在用</b>复盘的人建当天记录，
     * 绝不给一个从没复盘过的账号凭空造一行——那行在页面上会读成"这人这天什么都没发生"。
     */
    @Select("SELECT DISTINCT user_id FROM t_daily_record ORDER BY user_id")
    List<Long> listReviewerUserIds();
}
