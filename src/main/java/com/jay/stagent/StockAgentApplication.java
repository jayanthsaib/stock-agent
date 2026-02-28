package com.jay.stagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StockAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(StockAgentApplication.class, args);
    }
}
