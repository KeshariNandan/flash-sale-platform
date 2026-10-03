package com.keshari.flashsale.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * A buyer with a saved payment method. The id matches the JWT subject so that
 * identity on the claim path never comes from the request body.
 */
@Entity
@Table(name = "app_user")
public class AppUser implements Persistable<String> {

	@Id
	@Column(name = "user_id", length = 64)
	private String userId;

	@Column(nullable = false, length = 320, unique = true)
	private String email;

	@Column(name = "saved_payment_token", nullable = false, length = 128)
	private String savedPaymentToken;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Transient
	private boolean isNew = false;

	protected AppUser() {
		// JPA
	}

	public AppUser(String userId, String email, String savedPaymentToken, Instant now) {
		this.userId = userId;
		this.email = email;
		this.savedPaymentToken = savedPaymentToken;
		this.createdAt = now;
		this.isNew = true;
	}

	@Override
	public String getId() {
		return userId;
	}

	@Override
	public boolean isNew() {
		return isNew;
	}

	@PostLoad
	@PostPersist
	void markNotNew() {
		this.isNew = false;
	}

	public String email() {
		return email;
	}

	public String savedPaymentToken() {
		return savedPaymentToken;
	}

	public Instant createdAt() {
		return createdAt;
	}
}
