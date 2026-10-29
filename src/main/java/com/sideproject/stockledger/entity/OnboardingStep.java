package com.sideproject.stockledger.entity;

/**
 * 第一次使用引導的進度（spec DR-050）。宣告順序就是前進順序，isAfter 靠 ordinal 比較，不要隨意調換。
 */
public enum OnboardingStep {

	WELCOME("/welcome"), ACCOUNTS("/welcome/accounts"), LOANS("/welcome/loans"), STOCKS("/welcome/stocks"),
	COLLATERAL("/welcome/collateral"), DONE("/");

	private final String url;

	OnboardingStep(String url) {
		this.url = url;
	}

	/** 是否比 other 更後面。 */
	public boolean isAfter(OnboardingStep other) {
		return ordinal() > other.ordinal();
	}

	/**
	 * 這一步的網址（登入轉址、頁面提醒的連結都回到這裡，設計文件 3.3）。擔保那一步由 controller 再轉到第一筆需要的質押；
	 * 完成就是總覽。放在 enum 上，NoticeService 不用依賴 OnboardingService 也拿得到。
	 */
	public String url() {
		return url;
	}

}
