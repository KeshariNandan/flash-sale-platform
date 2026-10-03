package com.keshari.flashsale.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Programmatic transactions for the claim path, where the boundary must be explicit and short. */
@Configuration
public class TransactionConfig {

	@Bean
	public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
		return new TransactionTemplate(txManager);
	}
}
