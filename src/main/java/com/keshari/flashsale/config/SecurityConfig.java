package com.keshari.flashsale.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT resource server when {@code flashsale.security.enabled=true}. The open
 * chain keeps Testcontainers suites on the existing {@code X-Buyer-Id} header.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	@ConditionalOnProperty(name = "flashsale.security.enabled", havingValue = "true", matchIfMissing = true)
	SecurityFilterChain jwtChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
		return http.csrf(csrf -> csrf.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health", "/actuator/health/**",
						"/actuator/info").permitAll().requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
						.requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll())
				.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(jwtDecoder)
						.jwtAuthenticationConverter(roleConverter())))
				.build();
	}

	@Bean
	@ConditionalOnProperty(name = "flashsale.security.enabled", havingValue = "false")
	SecurityFilterChain openChain(HttpSecurity http) throws Exception {
		return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
	}

	@Bean
	@ConditionalOnProperty(name = "flashsale.security.enabled", havingValue = "true", matchIfMissing = true)
	JwtDecoder jwtDecoder(FlashSaleProperties properties) {
		byte[] secret = properties.security().jwtSecret().getBytes(StandardCharsets.UTF_8);
		SecretKeySpec key = new SecretKeySpec(secret, "HmacSHA256");
		return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
	}

	static JwtAuthenticationConverter roleConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(jwt -> {
			String role = jwt.getClaimAsString("role");
			if (role == null || role.isBlank()) {
				return List.of();
			}
			return List.of(new SimpleGrantedAuthority("ROLE_" + role));
		});
		return converter;
	}
}
