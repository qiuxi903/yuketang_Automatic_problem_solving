package com.ykt.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class YktPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(YktPlatformApplication.class, args);
    }
}
