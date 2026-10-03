package com.keshari.flashsale.api;

import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Interim identity source: the {@code X-Buyer-Id} header. This is replaced by
 * the verified JWT subject when Spring Security is wired in; the controller
 * contract ({@link CurrentBuyer}) does not change.
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
		String buyer = webRequest.getHeader(HEADER);
		if (buyer == null || buyer.isBlank()) {
			throw new UnauthenticatedException();
		}
		return buyer.trim();
	}

	/** No buyer identity on the request; mapped to 401. */
	public static class UnauthenticatedException extends RuntimeException {
		UnauthenticatedException() {
			super("buyer identity is required");
		}
	}
}
