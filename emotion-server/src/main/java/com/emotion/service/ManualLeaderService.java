package com.emotion.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.ManualLeader;
import com.emotion.entity.Stock;
import com.emotion.mapper.ManualLeaderMapper;
import com.emotion.mapper.StockMapper;

/**
 * 天梯人工总龙头的登记：每账号每交易日一行，读给 {@link TiantiService} 标"总龙头"标签。
 *
 * <p>写侧只落库（名称对着 {@code t_stock} 反查回填），读侧由天梯装配；删除即回归无人工总龙头。
 */
@Service
public class ManualLeaderService {

    private final ManualLeaderMapper mapper;
    private final StockMapper stockMapper;

    public ManualLeaderService(ManualLeaderMapper mapper, StockMapper stockMapper) {
        this.mapper = mapper;
        this.stockMapper = stockMapper;
    }

    /** 某日人工总龙头代码；没登记返回 null。 */
    public String codeOf(Long userId, LocalDate date) {
        if (userId == null || date == null) {
            return null;
        }
        ManualLeader row = mapper.selectOne(new LambdaQueryWrapper<ManualLeader>()
                .eq(ManualLeader::getUserId, userId)
                .eq(ManualLeader::getTradeDate, date));
        return row == null ? null : row.getCode();
    }

    /** 保存/覆盖某日总龙头。名称反查回填，不采信前端。 */
    public ManualLeader save(Long userId, LocalDate date, String code) {
        String c = code == null ? "" : code.trim();
        if (!c.matches("\\d{6}")) {
            throw new IllegalArgumentException("股票代码应为 6 位数字，收到：" + code);
        }
        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>().eq(Stock::getCode, c));
        if (stock == null) {
            throw new IllegalArgumentException("代码 " + c + " 不在 A股代码表里，请从搜索里选一只");
        }
        ManualLeader row = mapper.selectOne(new LambdaQueryWrapper<ManualLeader>()
                .eq(ManualLeader::getUserId, userId)
                .eq(ManualLeader::getTradeDate, date));
        if (row == null) {
            row = new ManualLeader();
        }
        row.setUserId(userId);
        row.setTradeDate(date);
        row.setCode(c);
        row.setName(stock.getName());
        if (row.getUpdatedAt() == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        return row;
    }

    /** 清除某日人工总龙头。 */
    public void clear(Long userId, LocalDate date) {
        if (userId == null || date == null) {
            return;
        }
        mapper.delete(new LambdaQueryWrapper<ManualLeader>()
                .eq(ManualLeader::getUserId, userId)
                .eq(ManualLeader::getTradeDate, date));
    }
}