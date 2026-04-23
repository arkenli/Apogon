package com.company.aiops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiopsAlertAnalysisApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiopsAlertAnalysisApplication.class, args);
    }
}
