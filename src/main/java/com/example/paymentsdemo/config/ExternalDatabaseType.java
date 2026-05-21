package com.example.paymentsdemo.config;

import java.util.Locale;

public enum ExternalDatabaseType {
    ORACLE("oracle.jdbc.OracleDriver"),
    MARIADB("org.mariadb.jdbc.Driver");

    private final String driverClassName;

    ExternalDatabaseType(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public String driverClassName() {
        return driverClassName;
    }

    public static ExternalDatabaseType from(String value) {
        if (value == null || value.isBlank()) {
            return ORACLE;
        }

        return ExternalDatabaseType.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}
