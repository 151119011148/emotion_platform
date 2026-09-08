package com.emotion;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.emotion.mapper")
public class EmotionApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmotionApplication.class, args);
    }
}
