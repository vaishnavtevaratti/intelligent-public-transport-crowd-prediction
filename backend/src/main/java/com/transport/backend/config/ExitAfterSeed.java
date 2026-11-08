package com.transport.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-shot mode for scripts/reset-demo.ps1: with app.seed.exit-after-seed=true the application stops right after
 * the data initializer (ordered before this runner) has committed, instead of starting to serve requests.
 */
@Component
@Order(100)
public class ExitAfterSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExitAfterSeed.class);

    private final ApplicationContext context;
    private final boolean exitAfterSeed;

    public ExitAfterSeed(ApplicationContext context, @Value("${app.seed.exit-after-seed:false}") boolean exitAfterSeed) {
        this.context = context;
        this.exitAfterSeed = exitAfterSeed;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (exitAfterSeed) {
            log.info("Demo data step finished; exiting (app.seed.exit-after-seed=true)");
            System.exit(SpringApplication.exit(context, () -> 0));
        }
    }
}
