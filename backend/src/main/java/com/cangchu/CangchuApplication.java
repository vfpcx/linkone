package com.cangchu;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 仓储云 SaaS 平台启动入口
 */
@SpringBootApplication
@MapperScan("com.cangchu.**.mapper")
public class CangchuApplication {
    public static void main(String[] args) {
        SpringApplication.run(CangchuApplication.class, args);
    }
}
