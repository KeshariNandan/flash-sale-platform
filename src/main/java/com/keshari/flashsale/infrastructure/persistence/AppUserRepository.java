package com.keshari.flashsale.infrastructure.persistence;

import com.keshari.flashsale.domain.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, String> {
}
