package com.interview.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class InterviewAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(InterviewAgentApplication.class, args);
    }
}