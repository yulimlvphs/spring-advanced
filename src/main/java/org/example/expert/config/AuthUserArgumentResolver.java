package org.example.expert.config;

import jakarta.servlet.http.HttpServletRequest;
import org.example.expert.domain.auth.exception.AuthException;
import org.example.expert.domain.common.annotation.Auth;
import org.example.expert.domain.common.dto.AuthUser;
import org.example.expert.domain.user.enums.UserRole;
import org.springframework.core.MethodParameter;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
public class AuthUserArgumentResolver implements HandlerMethodArgumentResolver {

    /**
     * 현재 Controller 매개변수를 이 Resolver가 처리할지 판단한다.
     *
     * 정상 사용:
     * @Auth AuthUser authUser
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        boolean hasAuthAnnotation =
                parameter.hasParameterAnnotation(Auth.class);

        boolean isAuthUserType =
                AuthUser.class.equals(parameter.getParameterType());

        if (hasAuthAnnotation != isAuthUserType) {
            throw new AuthException(
                    "@Auth와 AuthUser 타입은 함께 사용되어야 합니다."
            );
        }

        return hasAuthAnnotation && isAuthUserType;
    }

    /**
     * JwtFilter가 HttpServletRequest에 저장한 인증 정보를 꺼내
     * Controller 매개변수로 전달할 AuthUser 객체를 생성한다.
     */
    @Override
    public AuthUser resolveArgument(
            @Nullable MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory
    ) {
        HttpServletRequest request =
                webRequest.getNativeRequest(HttpServletRequest.class);

        if (request == null) {
            throw new AuthException(
                    "HTTP 요청 정보를 찾을 수 없습니다."
            );
        }

        Object userIdAttribute = request.getAttribute("userId");
        Object emailAttribute = request.getAttribute("email");
        Object userRoleAttribute = request.getAttribute("userRole");

        if (userIdAttribute == null
                || emailAttribute == null
                || userRoleAttribute == null) {
            throw new AuthException(
                    "인증 사용자 정보가 없습니다."
            );
        }

        Long userId = convertToLong(userIdAttribute);
        String email = emailAttribute.toString();
        UserRole userRole = convertToUserRole(userRoleAttribute);

        return new AuthUser(userId, email, userRole);
    }

    private Long convertToLong(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }

        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw new AuthException(
                    "인증 사용자 ID 형식이 올바르지 않습니다."
            );
        }
    }

    private UserRole convertToUserRole(Object value) {
        if (value instanceof UserRole userRole) {
            return userRole;
        }

        return UserRole.of(value.toString());
    }
}