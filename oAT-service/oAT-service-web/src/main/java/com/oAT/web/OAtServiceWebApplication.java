package com.oAT.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@ComponentScan(basePackages = {"com.oAT.web", "com.ovanth.client"})
public class OAtServiceWebApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        System.setProperty("io.netty.noUnsafe", System.getProperty("io.netty.noUnsafe", "true"));
        SpringApplication.run(OAtServiceWebApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(OAtServiceWebApplication.class);
    }

}
