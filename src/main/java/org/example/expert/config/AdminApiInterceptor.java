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
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 정적 리소스 요청 등은 HandlerMethod가 아닐 수 있기 때문에 Controller 메서드 요청이 아니라면 그대로 통과
        if(!(handler instanceof HandlerMethod handlerMethod)){
            return true;
        }

        // 현재 Controller 메서드에 @AdminApiLogging이 붙어있는지 확인
        AdminApiLogging annotation = handlerMethod.getMethodAnnotation(AdminApiLogging.class);

        // 관리자 전용 로깅 대상이 아니라면 권한 검사 없이 통과
        if(annotation == null) {
            return true;
        }

        Object userIdAttribute = request.getAttribute("userId");
        Object userRoleAttribute = request.getAttribute("userRole");

        if(userRoleAttribute == null || userIdAttribute == null){
            throw new AuthException("인즌 사용자 정보가 없습니다.");
        }

        Long userId = convertToLong(userIdAttribute);
        UserRole userRole = convertToUserRole(userRoleAttribute);

        //ADMIN 권한이 아니면 Controller에 도달하기 전에 접근을 차단한다.
        if (userRole != UserRole.ADMIN) {
            log.warn(
                    "[ADMIN API 접근 거부] userId={}, role={}, method={}, url={}",
                    userId,
                    userRole,
                    request.getMethod(),
                    request.getRequestURI()
            );

            throw new AuthException("관리자만 접근할 수 있습니다.");

        }

        // 관리자 인증 성공 시 요청 시각과 URL을 기록한다.
        log.info("[ADMIN API 접근 허용] userId={}, requestTime={}, method={}, url={}",
                userId,
                LocalDateTime.now(),
                request.getMethod(),
                request.getRequestURI()
        );

        return true;
    }

    private Long convertToLong(Object value) {
        if(value instanceof Long longValue) {
            return longValue;
        }

        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw new AuthException("인증 사용자 Id 형식이 올바르지 않습니다.");
        }
    }

    private UserRole convertToUserRole(Object value) {
        if(value instanceof UserRole userRole) {
            return userRole;
        }
        return UserRole.of(value.toString());
    }
}
