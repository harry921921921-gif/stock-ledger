package com.sideproject.stockledger.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 所有「現在時間」都從這個 Clock 取得；測試時可以換成固定時間，例如測試鎖定 15 分鐘。 */
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.system(ZoneId.of("Asia/Taipei"));
	}

}
