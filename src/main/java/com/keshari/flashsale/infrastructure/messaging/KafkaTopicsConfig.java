package com.keshari.flashsale.infrastructure.messaging;

import com.keshari.flashsale.config.FlashSaleProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

	@Bean
	NewTopic reservationsTopic(FlashSaleProperties properties) {
		return TopicBuilder.name(properties.kafka().reservationsTopic())
				.partitions(properties.kafka().partitions())
				.replicas(1)
				.build();
	}

	@Bean
	NewTopic reservationsDltTopic(FlashSaleProperties properties) {
		return TopicBuilder.name(properties.kafka().dltTopic()).partitions(properties.kafka().partitions()).replicas(1)
				.build();
	}
}
