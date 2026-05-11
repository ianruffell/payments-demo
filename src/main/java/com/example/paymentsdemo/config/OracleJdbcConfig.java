package com.example.paymentsdemo.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!merchant-simulator & !payment-initiator & !oracle-cache-sink")
public class OracleJdbcConfig {

    @Bean
    public DataSource oracleDataSource(
            @Value("${demo.oracle.jdbc-url}") String jdbcUrl,
            @Value("${demo.oracle.username}") String username,
            @Value("${demo.oracle.password}") String password
    ) {
        return DataSourceBuilder.create()
                .driverClassName("oracle.jdbc.OracleDriver")
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .build();
    }
}
