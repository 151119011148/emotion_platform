package com.emotion.dto;

import java.time.LocalDate;
import java.util.List;

/** 人工归类请求：把若干代码设进某题材某日（is_primary 决定主/辅题材）。 */
public class ThemeBindRequest {

    private Long themeId;
    private LocalDate tradeDate;
    private List<String> codes;
    /** true=设为主题材（参与统计）false=设为辅题材（仅关联）。 */
    private boolean primary = true;

    public Long getThemeId() { return themeId; }
    public void setThemeId(Long themeId) { this.themeId = themeId; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public List<String> getCodes() { return codes; }
    public void setCodes(List<String> codes) { this.codes = codes; }
    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }
}