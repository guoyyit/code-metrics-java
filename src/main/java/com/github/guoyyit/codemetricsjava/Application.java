package com.github.guoyyit.codemetricsjava;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.github.guoyyit.codemetricsjava.config.MetricsProperties;

@SpringBootApplication
@EnableConfigurationProperties(MetricsProperties.class)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
