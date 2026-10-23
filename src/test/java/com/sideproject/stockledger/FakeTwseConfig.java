package com.sideproject.stockledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.sideproject.stockledger.service.TwseClient;

/** 測試用的假證交所：回傳測試設定好的 JSON，不會真的連網路。 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeTwseConfig {

	@Bean
	@Primary
	FakeTwseClient fakeTwseClient() {
		return new FakeTwseClient();
	}

	public static class FakeTwseClient implements TwseClient {

		public String daily = "[]";
		public String dividends = "[]";
		public RuntimeException failure;

		@Override
		public String dailyQuotesJson() {
			if (failure != null) {
				throw failure;
			}
			return daily;
		}

		@Override
		public String dividendNoticesJson() {
			if (failure != null) {
				throw failure;
			}
			return dividends;
		}

	}

}
