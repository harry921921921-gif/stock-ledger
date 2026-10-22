package com.sideproject.stockledger.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 開啟 @Scheduled：固定收支每天 00:10 自動記帳（RecurringPostingService）。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
