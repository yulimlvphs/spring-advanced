package org.example.expert.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class JwtFilter implements Filter {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String url = httpRequest.getRequestURI();

        if (url.startsWith("/auth")) {
            chain.doFilter(request, response);
            return;
        }

        String bearerJwt = httpRequest.getHeader("Authorization");

        if (bearerJwt == null || bearerJwt.isBlank()) {
            log.warn("인증 헤더 누락: URI={}", url);
            sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");
            return;
        }

        Claims claims;

        try {
            String jwt = jwtUtil.substringToken(bearerJwt);
            claims = jwtUtil.extractClaims(jwt);
        } catch (ExpiredJwtException e) {
            log.info("JWT 만료: userId={}, URI={}", e.getClaims().getSubject(), url);
            sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "만료된 토큰입니다.");
            return;
        } catch (SignatureException e) {
            log.warn("JWT 서명 불일치: URI={}", url);
            sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다.");
            return;
        } catch (MalformedJwtException | UnsupportedJwtException e) {
            log.warn("JWT 검증 실패 [{}]: URI={}", e.getClass().getSimpleName(), url);
            sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다.");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT 형식 오류 [{}]: URI={}", e.getClass().getSimpleName(), url);
            sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "토큰 형식이 올바르지 않습니다.");
            return;
        }

        try {
            Long userId = Long.valueOf(claims.getSubject());
            String email = claims.get("email", String.class);
            String userRole = claims.get("userRole", String.class);

            if (email == null || email.isBlank() || userRole == null || userRole.isBlank()) {
                log.warn("필수 JWT Claim 누락: userId={}, URI={}", userId, url);
                sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "인증 정보가 올바르지 않습니다.");
                return;
            }

            request.setAttribute("userId", userId);
            request.setAttribute("email", email);
            request.setAttribute("userRole", userRole);
        } catch (NumberFormatException e) {
            log.warn("JWT 사용자 ID 형식 오류: subject={}, URI={}", claims.getSubject(), url);
            sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "인증 정보가 올바르지 않습니다.");
            return;
        }

        /*
         * JWT 검증은 모두 끝났다.
         *
         * Controller나 Service에서 발생하는 예외는 JwtFilter가 잡지 않고
         * GlobalExceptionHandler 등 다음 예외 처리 계층으로 전달한다.
         */
        chain.doFilter(request, response);
    }

    private void sendErrorResponse(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("status", status.name());
        errorResponse.put("code", status.value());
        errorResponse.put("message", message);

        response.getWriter().write(objectMapper.writeValueAsString(errorResponse));
    }
}