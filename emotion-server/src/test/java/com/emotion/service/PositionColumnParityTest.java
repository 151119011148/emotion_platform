package com.emotion.service;

import com.baomidou.mybatisplus.annotation.TableField;
import com.emotion.entity.Position;
import com.emotion.entity.PositionHistory;
import com.emotion.mapper.PositionHistoryMapper;
import com.emotion.mapper.PositionMapper;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手写 batch INSERT 的列清单，必须和实体上「该落库的字段」一一对上。
 *
 * <p>这两条 INSERT 是 {@code @Insert} 里手敲的列名清单，不是 MyBatis-Plus 按实体生成的：
 * 实体加一列、Store 里 set 了值，只要忘了往清单里加，那一列就会<b>静默不落库</b>——
 * 页面读回来永远是默认值，单测还全绿。t_position 的 status/纪律分、
 * t_position_history 的卖价/卖量都这么丢过一次。
 *
 * <p>所以这里不测行为，只测对齐：列数=占位数、每个持久化字段既在列清单里也在取值里。
 * 加列时漏了哪边，这条会直接点名。
 */
class PositionColumnParityTest {

    /** 由数据库自己给的列：不在手写 INSERT 里，属合法缺席。 */
    private static final Set<String> DB_MANAGED = new HashSet<>(Arrays.asList("id", "createdAt"));

    @Test
    void positionInsertCoversEveryPersistedField() throws Exception {
        assertAligned(PositionMapper.class.getMethod("insertBatch", java.util.List.class),
                Position.class, "t_position");
    }

    @Test
    void historyInsertCoversEveryPersistedField() throws Exception {
        assertAligned(PositionHistoryMapper.class.getMethod("insertBatch", java.util.List.class),
                PositionHistory.class, "t_position_history");
    }

    private void assertAligned(Method method, Class<?> entity, String table) {
        String sql = String.join("", method.getAnnotation(Insert.class).value());
        int from = sql.indexOf("(user_id");
        int to = sql.indexOf(") VALUES");
        assertTrue(from >= 0 && to > from, table + " 的 INSERT 列清单没解析出来，改写法了？");
        Set<String> columns = snakeToSet(sql.substring(from + 1, to));
        Set<String> holders = new LinkedHashSet<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#[{]r[.]([A-Za-z0-9_]+)[}]").matcher(sql);
        while (m.find()) {
            holders.add(m.group(1));
        }
        // 列数与占位数不等 = 某一行的值串到了隔壁列上，比丢列更糟
        assertTrue(columns.size() == holders.size(),
                table + " 列数 " + columns.size() + " 与取值数 " + holders.size() + " 不一致");

        for (Field f : entity.getDeclaredFields()) {
            if (f.isSynthetic() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            TableField tf = f.getAnnotation(TableField.class);
            if (tf != null && !tf.exist()) {
                continue; // 读时派生字段，本来就不落库
            }
            if (DB_MANAGED.contains(f.getName())) {
                continue;
            }
            assertTrue(holders.contains(f.getName()),
                    table + " 的 insertBatch 取值里没有 r." + f.getName()
                            + "}——这个字段设了值也存不进去");
            assertTrue(columns.contains(camelToSnake(f.getName())),
                    table + " 的 insertBatch 列清单里没有 " + camelToSnake(f.getName()));
        }
    }

    private Set<String> snakeToSet(String csv) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : csv.split(",")) {
            String col = s.trim();
            if (!col.isEmpty()) {
                out.add(col);
            }
        }
        return out;
    }

    private String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                sb.append('_').append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
