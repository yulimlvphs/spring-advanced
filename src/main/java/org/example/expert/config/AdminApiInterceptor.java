package org.example.expert.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.example.expert.domain.auth.exception.AuthException;
import org.example.expert.domain.common.annotation.AdminApiLogging;
import org.example.expert.domain.user.enums.UserRole;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;
@Slf4j
@Component
public class AdminApiInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        AdminApiLogging annotation =
                handlerMethod.getMethodAnnotation(AdminApiLogging.class);

        if (annotation == null) {
            return true;
        }

        Object userIdAttribute = request.getAttribute("userId");
        Object userRoleAttribute = request.getAttribute("userRole");

        if (userIdAttribute == null || userRoleAttribute == null) {
            throw new AuthException("인증 사용자 정보가 없습니다.");
        }

        UserRole userRole = convertToUserRole(userRoleAttribute);

        if (userRole != UserRole.ADMIN) {
            log.warn(
                    "[ADMIN API 접근 거부] userId={}, role={}, method={}, url={}",
                    userIdAttribute,
                    userRole,
                    request.getMethod(),
                    request.getRequestURI()
            );

            throw new AuthException("관리자만 접근할 수 있습니다.");
        }

        log.info(
                "[ADMIN API 접근 허용] userId={}, method={}, url={}",
                userIdAttribute,
                request.getMethod(),
                request.getRequestURI()
        );

        return true;
    }

    private UserRole convertToUserRole(Object value) {
        if (value instanceof UserRole userRole) {
            return userRole;
        }

        return UserRole.of(value.toString());
    }
}