package com.emotion.service;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.emotion.entity.Anchor;
import com.emotion.entity.Stock;
import com.emotion.mapper.AnchorMapper;
import com.emotion.mapper.StockMapper;

/**
 * 阵眼登记：增删改查，外加唯一一处字段校验。
 *
 * <p>这里刻意不打任何上游接口。保存一次阵眼要联网的话，腾讯一抽风用户就改不动自己的判断；
 * 跨度那些数全部在 {@link AnchorMetricsService} 读的时候现算，写侧只管把登记存住。
 */
@Service
public class AnchorService {

    /** 阵眼和总龙进分的方式一样，只是叫法不同——但只有这两种值能写进表。 */
    public static final String ROLE_CYCLE = "CYCLE";
    public static final String ROLE_LEADER = "LEADER";
    private static final Collection<String> ROLES = Arrays.asList(ROLE_CYCLE, ROLE_LEADER);

    private final AnchorMapper anchorMapper;
    private final StockMapper stockMapper;

    public AnchorService(AnchorMapper anchorMapper, StockMapper stockMapper) {
        this.anchorMapper = anchorMapper;
        this.stockMapper = stockMapper;
    }

    /** 某日在位 = 起点不晚于该日，且（没有终点 或 终点不早于该日）。打分和面板都读这一个判据。 */
    public List<Anchor> listInPosition(Long userId, LocalDate date) {
        return anchorMapper.selectList(new LambdaQueryWrapper<Anchor>()
                .eq(Anchor::getUserId, userId)
                .le(Anchor::getStartDate, date)
                .and(w -> w.isNull(Anchor::getEndDate).or().ge(Anchor::getEndDate, date))
                .orderByAsc(Anchor::getStartDate)
                .orderByAsc(Anchor::getId));
    }

    /** 跨度与 [from, to] 有交集的全部阵眼，含已经下位的：曲线画 markArea 用。 */
    public List<Anchor> listOverlapping(Long userId, LocalDate from, LocalDate to) {
        return anchorMapper.selectList(new LambdaQueryWrapper<Anchor>()
                .eq(Anchor::getUserId, userId)
                .le(Anchor::getStartDate, to)
                .and(w -> w.isNull(Anchor::getEndDate).or().ge(Anchor::getEndDate, from))
                .orderByAsc(Anchor::getStartDate)
                .orderByAsc(Anchor::getId));
    }

    public Anchor create(Long userId, Anchor anchor) {
        anchor.setUserId(userId);
        anchor.setId(null);
        validate(anchor);
        anchorMapper.insert(anchor);
        return anchor;
    }

    /**
     * 整条替换：前端弹框里改的就是这一条的全部字段。
     *
     * <p>不做"只改传上来的字段"那种半更新，因为 endDate=NULL 是有含义的（仍在位），
     * 一个 patch 分不清"要清空终点"和"这次没打算改终点"。必填项由 {@link #validate} 拦住，
     * 少传代码或起点会直接报错，不会静默把别的列清掉。
     */
    public Anchor update(Long userId, Long id, Anchor patch) {
        Anchor target = own(userId, id);
        patch.setId(target.getId());
        patch.setUserId(userId);
        patch.setCreatedAt(target.getCreatedAt());
        patch.setUpdatedAt(target.getUpdatedAt());
        validate(patch);
        anchorMapper.updateById(patch);
        return patch;
    }

    public void delete(Long userId, Long id) {
        own(userId, id);
        anchorMapper.deleteById(id);
    }

    private Anchor own(Long userId, Long id) {
        Anchor existing = anchorMapper.selectOne(new LambdaQueryWrapper<Anchor>()
                .eq(Anchor::getId, id)
                .eq(Anchor::getUserId, userId));
        if (existing == null) {
            throw new IllegalArgumentException("阵眼记录不存在：" + id);
        }
        return existing;
    }

    /**
     * 存在性校验加名称回填。
     *
     * <p>名字必须反查而不是采信前端传来的：一个错代码会把整只票的跨度算到别的票身上，
     * 而卡片上看不出任何异样。
     */
    private void validate(Anchor anchor) {
        String code = anchor.getStockCode() == null ? "" : anchor.getStockCode().trim();
        if (!code.matches("\\d{6}")) {
            throw new IllegalArgumentException("股票代码应为 6 位数字，收到：" + anchor.getStockCode());
        }
        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>().eq(Stock::getCode, code));
        if (stock == null) {
            throw new IllegalArgumentException("代码 " + code + " 不在 A股代码表里，请从搜索结果里选一只");
        }
        anchor.setStockCode(code);
        anchor.setStockName(stock.getName());
        if (anchor.getRole() == null || anchor.getRole().trim().isEmpty()) {
            anchor.setRole(ROLE_CYCLE);
        }
        if (!ROLES.contains(anchor.getRole())) {
            throw new IllegalArgumentException("未知的阵眼角色：" + anchor.getRole());
        }
        LocalDate start = anchor.getStartDate();
        if (start == null) {
            throw new IllegalArgumentException("跨度起点必填：这轮周期从哪天起爆是你定的，系统不猜");
        }
        if (start.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("跨度起点不能晚于今天：" + start);
        }
        if (anchor.getEndDate() != null) {
            if (anchor.getEndDate().isBefore(start)) {
                throw new IllegalArgumentException("跨度终点早于起点：" + start + " → " + anchor.getEndDate());
            }
            if (anchor.getEndDate().isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("跨度终点不能晚于今天：" + anchor.getEndDate());
            }
        }
        Long dup = anchorMapper.selectCount(new LambdaQueryWrapper<Anchor>()
                .eq(Anchor::getUserId, anchor.getUserId())
                .eq(Anchor::getStockCode, code)
                .eq(Anchor::getStartDate, start)
                .ne(anchor.getId() != null, Anchor::getId, anchor.getId()));
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException(code + " 在 " + start + " 已经登记过一次了");
        }
    }

    private static String cut(String raw, int max) {
        String trimmed = raw.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
