package com.emotion.market;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** 离线 fixture 读取：单测一律不打网络，喂的是 2026-09-04 真实响应存档。 */
final class Fixtures {

    static final String TRADE_DATE = "2026-09-04";
    static final String PREV_TRADE_DATE = "2026-09-03";

    private Fixtures() {
    }

    static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }

    /** 腾讯返回的是 GBK 字节，测试里按同样的编码解，才能复现线上路径。 */
    static String gbkText(String name) {
        return new String(bytes(name), Charset.forName("GBK"));
    }

    private static byte[] bytes(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/market/" + name)) {
            if (in == null) {
                throw new IllegalStateException("缺少测试 fixture: /market/" + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("读取 fixture 失败: " + name, e);
        }
    }
}
