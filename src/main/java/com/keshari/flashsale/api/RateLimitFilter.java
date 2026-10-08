package com.keshari.flashsale.api;

import com.keshari.flashsale.config.FlashSaleProperties;
import com.keshari.flashsale.infrastructure.cache.RedisRateLimiter;
import com.keshari.flashsale.infrastructure.cache.RedisRateLimiter.Decision;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps claim attempts per buyer and globally. Runs after the JWT filter so the
 * subject is already in the security context when security is on.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class RateLimitFilter extends OncePerRequestFilter {

	private final RedisRateLimiter limiter;
	private final FlashSaleProperties properties;

	public RateLimitFilter(RedisRateLimiter limiter, FlashSaleProperties properties) {
		this.limiter = limiter;
		this.properties = properties;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		if (!properties.rateLimit().enabled()) {
			return true;
		}
		return !("POST".equals(request.getMethod()) && request.getRequestURI() != null
				&& request.getRequestURI().matches("/api/v1/sales/[^/]+/claims"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String user = CurrentBuyerArgumentResolver.subjectFromSecurityContext();
		if (user == null) {
			String header = request.getHeader(CurrentBuyerArgumentResolver.HEADER);
			if (header != null && !header.isBlank()) {
				user = header.trim();
			}
		}
		if (user == null) {
			filterChain.doFilter(request, response);
			return;
		}
		Decision decision = limiter.allow(user);
		if (decision == Decision.ALLOWED) {
			filterChain.doFilter(request, response);
			return;
		}
		String detail = decision == Decision.GLOBAL ? "global admission rate exceeded" : "buyer attempt rate exceeded";
		response.setStatus(429);
		response.setHeader("Retry-After", "1");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getWriter().write("{\"type\":\"https://flashsale.example/problems/rate-limited\","
				+ "\"title\":\"rate-limited\",\"status\":429,\"detail\":\"" + detail + "\"}");
	}
}
