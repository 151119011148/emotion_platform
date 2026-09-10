package com.wuwei;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.wuwei.mapper")
public class WuweiApplication {
    public static void main(String[] args) {
        SpringApplication.run(WuweiApplication.class, args);
    }
}
