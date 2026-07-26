package org.example.expert.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.example.expert.domain.common.dto.AuthUser;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.time.LocalDateTime;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AdminApiLoggingAspect {

    private final ObjectMapper objectMapper;

    /**
     * @AdminApiLogging이 붙은 메서드의 실행 전후를 감싼다.
     */
    @Around("@annotation(org.example.expert.domain.common.annotation.AdminApiLogging)")
    public Object logAdminApi(ProceedingJoinPoint joinPoint) throws Throwable {

        MethodSignature signature =
                (MethodSignature) joinPoint.getSignature();

        Method method = signature.getMethod();

        HttpServletRequest httpRequest =
                findHttpServletRequest(joinPoint.getArgs());

        Long userId = findUserId(joinPoint.getArgs(), httpRequest);
        Object requestBody = findRequestBody(method, joinPoint.getArgs());

        String requestUrl =
                httpRequest != null
                        ? httpRequest.getRequestURI()
                        : "UNKNOWN";

        String httpMethod =
                httpRequest != null
                        ? httpRequest.getMethod()
                        : "UNKNOWN";

        LocalDateTime requestTime = LocalDateTime.now();

        log.info(
                """
                [ADMIN API 요청]
                userId={}
                requestTime={}
                method={}
                url={}
                requestBody={}
                """,
                userId,
                requestTime,
                httpMethod,
                requestUrl,
                toJson(requestBody)
        );

        long startTime = System.currentTimeMillis();

        try {
            /*
             * 실제 Controller 메서드를 실행한다.
             */
            Object result = joinPoint.proceed();

            long executionTime =
                    System.currentTimeMillis() - startTime;

            Object responseBody = extractResponseBody(result);

            log.info(
                    """
                    [ADMIN API 응답]
                    userId={}
                    method={}
                    url={}
                    executionTime={}ms
                    responseBody={}
                    """,
                    userId,
                    httpMethod,
                    requestUrl,
                    executionTime,
                    toJson(responseBody)
            );

            return result;

        } catch (Exception e) {
            long executionTime =
                    System.currentTimeMillis() - startTime;

            log.error(
                    """
                    [ADMIN API 예외]
                    userId={}
                    method={}
                    url={}
                    executionTime={}ms
                    exceptionType={}
                    message={}
                    """,
                    userId,
                    httpMethod,
                    requestUrl,
                    executionTime,
                    e.getClass().getSimpleName(),
                    e.getMessage()
            );

            throw e;
        }
    }

    /**
     * Controller 매개변수 중 @RequestBody가 붙은 값을 찾는다.
     */
    private Object findRequestBody(
            Method method,
            Object[] args
    ) {
        Annotation[][] parameterAnnotations =
                method.getParameterAnnotations();

        for (int i = 0; i < parameterAnnotations.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof RequestBody) {
                    return args[i];
                }
            }
        }

        return null;
    }

    /**
     * Controller 매개변수에 AuthUser가 있다면 사용자 ID를 꺼낸다.
     * 없다면 JwtFilter가 request에 넣은 userId를 사용한다.
     */
    private Long findUserId(
            Object[] args,
            HttpServletRequest request
    ) {
        for (Object arg : args) {
            if (arg instanceof AuthUser authUser) {
                /*
                 * AuthUser가 record라면 authUser.id()로 변경해야 한다.
                 * 일반 클래스라면 실제 getter 이름에 맞춰 사용한다.
                 */
                return authUser.getId();
            }
        }

        if (request == null) {
            return null;
        }

        Object value = request.getAttribute("userId");

        if (value instanceof Long longValue) {
            return longValue;
        }

        if (value != null) {
            try {
                return Long.valueOf(value.toString());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    /**
     * Controller 인자 중 HttpServletRequest를 찾는다.
     *
     * Controller 매개변수에 없으면 null이 될 수 있으므로,
     * 아래 RequestContextHolder 방식으로 바꾸는 것도 가능하다.
     */
    private HttpServletRequest findHttpServletRequest(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof HttpServletRequest request) {
                return request;
            }
        }

        RequestAttributes attributes =
                RequestContextHolder.getRequestAttributes();

        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }

        return null;
    }

    private HttpServletRequest getCurrentRequest() {

        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();

        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }

        return null;
    }
    /**
     * ResponseEntity이면 실제 body만 꺼낸다.
     */
    private Object extractResponseBody(Object result) {
        if (result instanceof ResponseEntity<?> responseEntity) {
            return responseEntity.getBody();
        }

        return result;
    }

    /**
     * 객체를 JSON 문자열로 변환한다.
     */
    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }

        /*
         * Servlet 객체는 JSON 직렬화 대상에서 제외한다.
         */
        if (value instanceof HttpServletRequest
                || value instanceof HttpServletResponse
                || value instanceof NativeWebRequest
                || value instanceof BindingResult) {
            return "\"SERIALIZATION_SKIPPED\"";
        }

        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn(
                    "로그 데이터 JSON 변환 실패. type={}",
                    value.getClass().getName()
            );

            return "\"JSON_SERIALIZATION_FAILED\"";
        }
    }


}