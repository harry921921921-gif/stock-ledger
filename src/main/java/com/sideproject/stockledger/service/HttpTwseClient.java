package com.sideproject.stockledger.service;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** 用 Spring 的 RestClient 連證交所 OpenAPI（不新增套件）。逾時 30 秒。 */
@Component
public class HttpTwseClient implements TwseClient {

	private final RestClient client;

	public HttpTwseClient(@Value("${app.twse.base-url:https://openapi.twse.com.tw/v1}") String baseUrl) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(30));
		factory.setReadTimeout(Duration.ofSeconds(30));
		this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
	}

	@Override
	public String dailyQuotesJson() {
		return get("/exchangeReport/STOCK_DAY_ALL");
	}

	@Override
	public String dividendNoticesJson() {
		return get("/exchangeReport/TWT48U_ALL");
	}

	private String get(String path) {
		return client.get().uri(path).retrieve().body(String.class);
	}

}
