package com.oAT.relay;

import com.oAT.relay.config.RelayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RelayProperties.class)
public class OAtRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(OAtRelayApplication.class, args);
    }
}
