package com.keshari.flashsale.api;

import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Buyer identity from the verified JWT subject. When the resource server is
 * disabled (tests), the interim {@code X-Buyer-Id} header is accepted.
 */
@Component
public class CurrentBuyerArgumentResolver implements HandlerMethodArgumentResolver, WebMvcConfigurer {

	public static final String HEADER = "X-Buyer-Id";

	@Override
	public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
		resolvers.add(this);
	}

	@Override
	public boolean supportsParameter(MethodParameter parameter) {
		return parameter.hasParameterAnnotation(CurrentBuyer.class) && parameter.getParameterType() == String.class;
	}

	@Override
	public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
			NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
		String fromJwt = subjectFromSecurityContext();
		if (fromJwt != null) {
			return fromJwt;
		}
		String buyer = webRequest.getHeader(HEADER);
		if (buyer == null || buyer.isBlank()) {
			throw new UnauthenticatedException();
		}
		return buyer.trim();
	}

	static String subjectFromSecurityContext() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
			String sub = jwt.getSubject();
			if (sub != null && !sub.isBlank()) {
				return sub.trim();
			}
		}
		return null;
	}

	/** No buyer identity on the request; mapped to 401. */
	public static class UnauthenticatedException extends RuntimeException {
		UnauthenticatedException() {
			super("buyer identity is required");
		}
	}
}
