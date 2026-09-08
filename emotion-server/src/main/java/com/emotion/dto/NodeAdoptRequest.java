package com.emotion.dto;

import lombok.Data;

/**
 * {@code POST /api/nodes/{id}/adopt} 的 body。
 *
 * <p>只有指纹这一个字段，是因为要落的八个值<b>不由前端提供</b>：服务端重算一遍，跟他在界面上看到的
 * 那份逐字段比对，一致才写。把八个值回传上来等于让他替平台做决定，中间任何一次格式化（50.00→50）
 * 都会变成一个查不出来的偏差。
 */
@Data
public class NodeAdoptRequest {
    /** {@code GET /api/nodes/{id}/suggest} 返回的那串规范 JSON，原样带回来即可。 */
    private String fingerprint;
}
