package com.segi.watchlist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MovieWatchlistWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(MovieWatchlistWebApplication.class, args);
    }
}
