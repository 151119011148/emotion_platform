package com.emotion.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.ReviewFetch;
import com.emotion.mapper.ReviewFetchMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * {@code t_review_fetch} 的唯一写口/读口。整行读改写（先读后写），不留并发主键冲突的余地。
 */
@Service
public class ReviewFetchStore {

    private final ReviewFetchMapper mapper;

    public ReviewFetchStore(ReviewFetchMapper mapper) {
        this.mapper = mapper;
    }

    public ReviewFetch getByDate(LocalDate date) {
        if (date == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<ReviewFetch>()
                .eq(ReviewFetch::getTradeDate, date)
                .last("LIMIT 1"));
    }

    /**
     * @param tasksJson T1-T8 逐任务进度 JSON（报告输出给前端，落库只作状态回显）
     */
    public void save(LocalDate date, String overall, String tasksJson, String warnings) {
        ReviewFetch row = getByDate(date);
        boolean isNew = row == null;
        if (isNew) {
            row = new ReviewFetch();
            row.setTradeDate(date);
            row.setCreatedAt(LocalDateTime.now());
        }
        row.setOverall(overall);
        row.setTasksJson(tasksJson);
        row.setWarnings(warnings == null ? "" : warnings);
        if (isNew) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }
}