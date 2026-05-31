package com.virtual.card.infrastructure.scheduler;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * ShedLock configuration — prevents the card expiration scheduler (and any other
 * {@code @SchedulerLock}-annotated jobs) from running concurrently across multiple
 * application instances.
 *
 * <p>Uses JDBC-backed distributed locking via the {@code shedlock} table created
 * in {@code V4__add_shedlock.sql}. Each instance races to insert/update the lock row;
 * only the winner executes the job.
 *
 * <p>{@code defaultLockAtMostFor} is a safety cap — if the JVM crashes mid-job, the
 * lock is automatically released after this duration so other instances can proceed.
 * Set it slightly above the expected maximum job runtime.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()   // use DB clock — avoids clock-skew issues across JVM instances
                        .build()
        );
    }
}
