CREATE USER IF NOT EXISTS 'maxscale_monitor'@'%' IDENTIFIED BY 'MaxScaleMonitor123!';
GRANT REPLICA MONITOR ON *.* TO 'maxscale_monitor'@'%';

CREATE USER IF NOT EXISTS 'maxscale_service'@'%' IDENTIFIED BY 'MaxScaleService123!';
GRANT SELECT ON mysql.user TO 'maxscale_service'@'%';
GRANT SELECT ON mysql.db TO 'maxscale_service'@'%';
GRANT SELECT ON mysql.tables_priv TO 'maxscale_service'@'%';
GRANT SELECT ON mysql.columns_priv TO 'maxscale_service'@'%';
GRANT SELECT ON mysql.procs_priv TO 'maxscale_service'@'%';
GRANT SELECT ON mysql.proxies_priv TO 'maxscale_service'@'%';
GRANT SELECT ON mysql.global_priv TO 'maxscale_service'@'%';
GRANT SELECT ON mysql.roles_mapping TO 'maxscale_service'@'%';
GRANT SHOW DATABASES ON *.* TO 'maxscale_service'@'%';
GRANT SET USER ON *.* TO 'maxscale_service'@'%';

FLUSH PRIVILEGES;
