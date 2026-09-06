package com.nongxin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 农心 Agent 启动入口。
 * 运行：mvn spring-boot:run 或 java -jar target/nongxin-agent.jar
 */
@SpringBootApplication
public class NongxinApplication {
    public static void main(String[] args) {
        // SQLite 数据目录自动创建
        try {
            Files.createDirectories(Path.of("data"));
        } catch (Exception ignored) {
            // 目录已存在或无法创建时由数据库报错兜底
        }
        SpringApplication.run(NongxinApplication.class, args);
    }
}
