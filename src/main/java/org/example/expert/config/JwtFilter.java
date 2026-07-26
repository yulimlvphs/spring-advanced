package org.example.expert.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
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

        try {
            String jwt = jwtUtil.substringToken(bearerJwt);
            Claims claims = jwtUtil.extractClaims(jwt);

            Long userId = Long.valueOf(claims.getSubject());
            String email = claims.get("email", String.class);
            String userRole = claims.get("userRole", String.class);

            if (email == null || userRole == null) {
                log.warn("필수 JWT Claim 누락: userId={}, URI={}", userId, url);
                sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "인증 정보가 올바르지 않습니다.");
                return;
            }

            request.setAttribute("userId", userId);
            request.setAttribute("email", email);
            request.setAttribute("userRole", userRole);

            chain.doFilter(request, response);
        } catch (ExpiredJwtException e) {
            log.info("JWT 만료: userId={}, URI={}", e.getClaims().getSubject(), url);
            sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "만료된 토큰입니다.");
        } catch (SignatureException | MalformedJwtException | UnsupportedJwtException e) {
            log.warn("JWT 검증 실패 [{}]: URI={}, message={}", e.getClass().getSimpleName(), url, e.getMessage());
            sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다.");
        } catch (IllegalArgumentException e) {
            log.warn("JWT 형식 오류: URI={}, message={}", url, e.getMessage());
            sendErrorResponse(httpResponse, HttpStatus.UNAUTHORIZED, "토큰 형식이 올바르지 않습니다.");
        } catch (Exception e) {
            log.error("예상치 못한 오류: URI={}", url, e);
            sendErrorResponse(httpResponse, HttpStatus.INTERNAL_SERVER_ERROR, "요청 처리 중 오류가 발생했습니다.");
        }
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