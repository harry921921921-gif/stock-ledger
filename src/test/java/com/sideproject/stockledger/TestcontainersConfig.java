package com.sideproject.stockledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 整合測試共用的 MySQL 8.4 容器。
 * {@code @ServiceConnection} 讓 Spring Boot 自動把容器的連線資訊設成 datasource，不用手寫 URL 和帳密。
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

	@Bean
	@ServiceConnection
	MySQLContainer mysql() {
		return new MySQLContainer("mysql:8.4");
	}

}
