package com.avr.examples;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 使用 AVR 自动配置的 Spring Boot Web 示例。 */
@SpringBootApplication
public class StockReportSpringApplication {
    public static void main(String[] args) {
        SpringApplication.run(StockReportSpringApplication.class, args);
    }
}
