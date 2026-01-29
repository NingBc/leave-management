package com.leave.system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
@MapperScan("com.leave.system.mapper")
public class LeaveSystemApplication {
    public static void main(String[] args) {
        SpringApplication.run(LeaveSystemApplication.class, args);
    }
}
