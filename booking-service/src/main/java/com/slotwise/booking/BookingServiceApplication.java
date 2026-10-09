package com.slotwise.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BookingServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
