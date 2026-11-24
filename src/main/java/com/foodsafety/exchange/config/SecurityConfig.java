package com.foodsafety.exchange.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * API 安全：/api/** 需要 HTTP Basic 认证；/target/**（模拟目标端）与 Swagger 文档放行。
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/target/**").permitAll()
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
            .requestMatchers("/api/**").authenticated()
            .anyRequest().permitAll())
        .httpBasic(basic -> {});
    return http.build();
  }

  @Bean
  UserDetailsService userDetailsService(ExchangeProperties properties) {
    List<UserDetails> users = new ArrayList<>();
    properties.security().users().forEach((name, spec) -> {
      String[] parts = spec.split(":", 2);
      users.add(User.withUsername(name)
          .password("{noop}" + parts[0])
          .roles(parts[1])
          .build());
    });
    return new InMemoryUserDetailsManager(users);
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
}
