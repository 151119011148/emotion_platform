package com.emotion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.emotion.entity.User;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserMapper extends BaseMapper<User> {

    /**
     * 令牌版本号自增，一条 UPDATE 在库里做完。
     *
     * <p>不能用「SELECT 出来 +1 再 updateById 写回去」替代：同一账号两台设备几乎同时登录时，
     * 两个事务会读到同一起点、写回同一个数字，两个 token 就对得上同一个版本号——互踢当场形同虚设。
     * 这条 UPDATE 拿到行锁就持到事务提交，后来者只能排在后面再加一号。
     * 判据与读回方式见 {@code SingleSessionService.issue}。
     */
    @Update("UPDATE t_user SET token_version = token_version + 1 WHERE id = #{userId}")
    int bumpTokenVersion(@Param("userId") Long userId);
}
