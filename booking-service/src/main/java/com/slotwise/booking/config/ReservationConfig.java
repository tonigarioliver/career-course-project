package com.slotwise.booking.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReservationProperties.class)
public class ReservationConfig {
}
