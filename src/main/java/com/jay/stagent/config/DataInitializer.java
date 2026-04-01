package com.jay.stagent.config;

import com.jay.stagent.entity.AppUser;
import com.jay.stagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${ADMIN_PASSWORD:Cerebro@123}")
    private String adminPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (!userRepository.existsByUsername("admin")) {
            AppUser admin = AppUser.builder()
                    .username("admin")
                    .email("admin@cerebro.local")
                    .passwordHash(passwordEncoder.encode(adminPassword))
                    .role("ADMIN")
                    .active(true)
                    .build();
            userRepository.save(admin);
            log.info("Cerebro: default admin user created (set ADMIN_PASSWORD in .env to change)");
        } else {
            log.info("Cerebro: admin user already exists");
        }
    }
}
