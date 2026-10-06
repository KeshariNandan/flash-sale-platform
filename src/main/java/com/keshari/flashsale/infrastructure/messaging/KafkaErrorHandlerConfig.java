package com.keshari.flashsale.infrastructure.messaging;

import com.keshari.flashsale.config.FlashSaleProperties;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
public class KafkaErrorHandlerConfig {

	@Bean
	CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, FlashSaleProperties properties) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
				(record, ex) -> new TopicPartition(properties.kafka().dltTopic(), record.partition()));
		return new DefaultErrorHandler(recoverer, new FixedBackOff(200L, 2));
	}
}
