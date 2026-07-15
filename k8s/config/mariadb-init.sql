-- Debezium replication user for CDC (kind deployment, spec 009).
-- Runs on first MariaDB init via /docker-entrypoint-initdb.d.
CREATE USER IF NOT EXISTS 'dbzuser'@'%' IDENTIFIED BY 'DbzPassword123!';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'dbzuser'@'%';
-- The application user owns the payments_app schema (created by MARIADB_DATABASE/USER env).
GRANT ALL PRIVILEGES ON payments_app.* TO 'payments_app'@'%';
FLUSH PRIVILEGES;
