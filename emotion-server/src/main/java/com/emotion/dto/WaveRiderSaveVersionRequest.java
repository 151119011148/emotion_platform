package com.emotion.dto;

import lombok.Data;

/**
 * 保存新版本的请求体。
 *
 * <p>{@code config} 收的是<strong>对象</strong>而不是字符串：前端直接把手上的配置对象丢过来即可，
 * 不必自己 stringify 再让服务端 parse 一遍（那样只会多一层转义出错的入口）。
 * 服务端会把它规范化后算哈希，内容没变就不产生新版本。
 */
@Data
public class WaveRiderSaveVersionRequest {
    private Object config;
    private String changeNote;
}
