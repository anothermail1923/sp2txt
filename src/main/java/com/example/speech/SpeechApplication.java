package com.example.speech;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SpeechApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpeechApplication.class, args);
    }
}
