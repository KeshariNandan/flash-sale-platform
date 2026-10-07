package com.keshari.flashsale.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flashsale")
public record FlashSaleProperties(String gate, Kafka kafka, Outbox outbox, Payment payment, Reconciler reconciler) {

	public FlashSaleProperties {
		if (gate == null || gate.isBlank()) {
			gate = "sql";
		}
		if (kafka == null) {
			kafka = new Kafka("sale.reservations", "sale.reservations.DLT", 6);
		}
		if (outbox == null) {
			outbox = new Outbox(50, 200, true);
		}
		if (payment == null) {
			payment = new Payment(20, 0);
		}
		if (reconciler == null) {
			reconciler = new Reconciler(true, 1000, 100);
		}
	}

	public record Kafka(String reservationsTopic, String dltTopic, int partitions) {
		public Kafka {
			if (reservationsTopic == null || reservationsTopic.isBlank()) {
				reservationsTopic = "sale.reservations";
			}
			if (dltTopic == null || dltTopic.isBlank()) {
				dltTopic = "sale.reservations.DLT";
			}
			if (partitions <= 0) {
				partitions = 6;
			}
		}
	}

	public record Outbox(int batchSize, long pollMs, boolean enabled) {
		public Outbox {
			if (batchSize <= 0) {
				batchSize = 50;
			}
			if (pollMs <= 0) {
				pollMs = 200;
			}
		}
	}

	public record Reconciler(boolean enabled, long pollMs, int batchSize) {
		public Reconciler {
			if (pollMs <= 0) {
				pollMs = 1000;
			}
			if (batchSize <= 0) {
				batchSize = 100;
			}
		}
	}

	public record Payment(long latencyMs, double failureRate) {
		public Payment {
			if (latencyMs < 0) {
				latencyMs = 0;
			}
			if (failureRate < 0) {
				failureRate = 0;
			}
			if (failureRate > 1) {
				failureRate = 1;
			}
		}
	}
}
