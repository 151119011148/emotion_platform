package com.emotion.ai;

/**
 * 大模型客户端的唯一出口。整个工程只有这一层碰网络，业务侧对着它写单测（给个假实现就行），
 * 于是「跑测试」永远不需要一个 key，也不需要联网。
 */
public interface LlmClient {

    /**
     * 一轮系统 + 用户的补全，返回模型的<b>原始</b>文本——不做任何加工：
     * 数字门卫要看的正是加工前的原文。
     *
     * @throws LlmException 没配 key、上游非 {@code 2xx}、超时、返回体读不出内容。
     *                      消息里绝不带 key，也不带整份响应体。
     */
    String complete(String system, String user);

    /** 用在草稿上的模型名，进接口返回值让他看清是哪一款写的。 */
    String modelName();
}
