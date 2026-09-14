package com.emotion.util;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public class PasswordHashGenTest {
    @Test
    public void gen() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode("123456");
        System.out.println(hash);
        // 自校验，确保贴进库的这串确实能被同一套 matches 认下来
        assert encoder.matches("123456", hash);
    }
}