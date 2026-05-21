package com.example.paymentsdemo.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!merchant-simulator & !payment-initiator & !reference-cache-sink & !oracle-cache-sink")
public class ExternalDatabaseJdbcConfig {

    @Bean
    public DataSource externalDatabaseDataSource(
            @Value("${demo.external-db.type:oracle}") String type,
            @Value("${demo.external-db.jdbc-url}") String jdbcUrl,
            @Value("${demo.external-db.username}") String username,
            @Value("${demo.external-db.password}") String password
    ) {
        ExternalDatabaseType databaseType = ExternalDatabaseType.from(type);
        return DataSourceBuilder.create()
                .driverClassName(databaseType.driverClassName())
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .build();
    }
}
