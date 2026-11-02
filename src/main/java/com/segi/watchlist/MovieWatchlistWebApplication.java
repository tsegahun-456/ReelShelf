package com.segi.watchlist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@SpringBootApplication
@EnableScheduling
public class MovieWatchlistWebApplication {
    /**
     * Three scheduler threads so a long TMDB match pass, a folder walk and the poster cache
     * run side by side instead of queueing behind each other on Spring's default single thread.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("job-");
        return scheduler;
    }

    public static void main(String[] args) {
        SpringApplication.run(MovieWatchlistWebApplication.class, args);
    }
}
