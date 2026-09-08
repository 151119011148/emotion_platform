package com.emotion.dto;

import lombok.Data;

/**
 * 复盘 md 导入请求。预览和确认走<b>同一个端点</b>，靠 {@code confirm} 分岔，
 * 所以页面天然就是两次调用：先 false 看一眼，再 true 落库。
 *
 * <p>默认 false 是有意的：「看一眼会改成什么样」这一步不能有副作用。
 * 它照样会联网取阵眼和监管日 K（为了把导入后的温度算给你看），但绝不碰你的记录。
 */
@Data
public class ImportRequest {

    /** 整份 md 文本，含 ```meta 围栏块和围栏外的正文。 */
    private String content;

    /** true 才落库。null 当 false 处理：宁可让他多点一次，也不替他猜。 */
    private Boolean confirm;

    public boolean isConfirm() {
        return Boolean.TRUE.equals(confirm);
    }
}
