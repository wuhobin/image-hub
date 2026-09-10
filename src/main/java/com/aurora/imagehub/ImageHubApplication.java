package com.aurora.imagehub;

import org.dromara.x.file.storage.spring.EnableFileStorage;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableFileStorage
public class ImageHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImageHubApplication.class, args);
    }
}
