# 🔐 JWT 인증 구조 개선 기록

> Spring Boot 프로젝트의 JWT 인증·인가 구조를 점검하면서 발견한 세 가지 문제와 개선 과정을 정리한 문서이다.

---

## 📌 개선 항목

| 번호 | 개선 대상 | 핵심 변경 |
|---:|---|---|
| 1 | Filter와 Interceptor의 권한 검사 중복 | 인증과 인가의 책임 분리 |
| 2 | JWT 예외 처리 및 로그 | 예외 종류와 로그 레벨 세분화 |
| 3 | JwtFilter의 예외 처리 범위 | 비즈니스 예외가 Filter에 잡히지 않도록 수정 |

---

# 1️⃣ Filter와 Interceptor의 권한 검사 책임 분리

## 1. 문제 인식 및 정의

기존에는 `JwtFilter`와 `AdminApiInterceptor`가 모두 관리자 권한을 검사하고 있었다.

```text
JwtFilter
 ├─ JWT 인증
 └─ 관리자 권한 검사

AdminApiInterceptor
 └─ 관리자 권한 검사
```

이 구조에는 다음 문제가 있었다.

- 동일한 권한 검사가 두 곳에 중복된다.
- 관리자 권한 정책이 변경되면 여러 클래스를 수정해야 한다.
- 요청이 어느 단계에서 차단됐는지 파악하기 어렵다.
- `JwtFilter`가 인증과 인가라는 두 가지 책임을 동시에 가진다.
- Filter는 URL 문자열을 기준으로 권한을 판단하므로 Controller 메서드 단위의 정책을 표현하기 어렵다.

---

## 2. 해결 방안

### 2-1. 의사결정 과정

인증과 인가의 역할을 다음과 같이 구분하였다.

| 구성 요소 | 책임 |
|---|---|
| `JwtFilter` | JWT 존재 여부, 서명, 만료 검증 및 Claim 추출 |
| `AdminApiInterceptor` | 관리자 API 접근 권한 검사 |
| `AdminApiLoggingAspect` | 관리자 API 요청·응답 로깅 |

`JwtFilter`는 요청이 Controller에 도달하기 전에 실행되며, 실제로 어떤 Controller 메서드가 선택됐는지 알기 어렵다. 반면 Interceptor는 `HandlerMethod`를 통해 실행 대상 Controller 메서드를 확인할 수 있으므로 관리자 API 인가 처리에 더 적절하다고 판단하였다.

### 2-2. 해결 과정

`JwtFilter`에 있던 관리자 권한 검사 코드를 제거하였다.

```java
if (url.startsWith("/admin") && ...) {
    // 관리자 권한 검사 및 접근 차단
}
```

JWT 검증이 성공하면 인증 정보만 현재 요청에 저장하도록 변경하였다.

```java
request.setAttribute("userId", userId);
request.setAttribute("email", email);
request.setAttribute("userRole", userRole);

chain.doFilter(request, response);
```

관리자 권한 검사는 `AdminApiInterceptor`에서만 수행하도록 통합하였다.

```text
Request
   ↓
JwtFilter
   └─ JWT 인증
   ↓
AdminApiInterceptor
   └─ ADMIN 권한 검사
   ↓
Controller
```

---

## 3. 해결 완료

### 3-1. 회고

인증과 인가는 비슷해 보이지만 서로 다른 책임이다.

- **인증(Authentication)**: 요청한 사용자가 누구인지 확인한다.
- **인가(Authorization)**: 인증된 사용자가 해당 기능을 사용할 수 있는지 확인한다.

두 책임을 분리하면서 코드의 역할이 명확해졌고, 관리자 권한 정책도 Interceptor 한 곳에서 관리할 수 있게 되었다. 각 클래스가 하나의 책임에 집중하도록 리팩터링하면서 단일 책임 원칙을 실제 코드에 적용할 수 있었다.

### 3-2. 전후 데이터 비교

| 비교 항목 | 개선 전 | 개선 후 |
|---|---|---|
| JWT 인증 위치 | `JwtFilter` | `JwtFilter` |
| 관리자 권한 검사 위치 | `JwtFilter` + Interceptor | `AdminApiInterceptor` |
| 권한 검사 구현 수 | 2곳 | 1곳 |
| 정책 변경 지점 | 여러 클래스 | Interceptor 한 곳 |
| 관리자 접근 거부 로그 | Filter 또는 Interceptor | Interceptor |
| 책임 분리 | 인증과 인가 혼재 | 인증과 인가 분리 |

---

# 2️⃣ JWT 예외 처리 및 로그 개선

## 1. 문제 인식 및 정의

기존 JWT 인증 과정에서는 예외의 종류와 관계없이 비슷한 방식으로 처리될 가능성이 있었다.

예를 들어 JWT 서명 불일치는 클라이언트가 유효하지 않은 토큰을 보낸 **예상 가능한 인증 실패**이다. 하지만 일반 `Exception`으로 처리하면 실제 서버 장애처럼 `ERROR` 로그와 긴 스택 트레이스가 출력되고, 상황에 따라 `500 Internal Server Error`로 응답할 수 있었다.

또한 다음 문제도 존재하였다.

- 빈 Authorization 헤더를 명확히 검사하지 않았다.
- JWT의 필수 Claim 누락 여부를 검사하지 않았다.
- 인증 실패와 서버 내부 장애의 로그 레벨이 구분되지 않았다.
- JWT 원문이나 Authorization 헤더를 로그에 남길 경우 보안 정보가 노출될 위험이 있었다.

---

## 2. 해결 방안

### 2-1. 의사결정 과정

JWT 인증 과정에서 발생할 수 있는 상황을 다음처럼 분류하였다.

| 상황 | 처리 기준 |
|---|---|
| 토큰 만료 | 예상 가능한 인증 실패 |
| 서명 불일치 | 유효하지 않은 토큰 |
| 손상된 JWT | 유효하지 않은 토큰 |
| 지원하지 않는 JWT | 유효하지 않은 토큰 |
| Bearer 또는 Claim 형식 오류 | 잘못된 인증 데이터 |
| 예상하지 못한 예외 | 서버 내부 오류 |

예상 가능한 인증 실패는 `INFO` 또는 `WARN`으로 기록하고 `401 Unauthorized`를 반환하며, 실제 서버 내부 오류만 `ERROR`와 `500 Internal Server Error`로 처리하도록 결정하였다.

서버 로그에는 원인 분석에 필요한 정보를 남기되, 클라이언트 응답에는 내부 예외 메시지를 직접 노출하지 않도록 하였다.

### 2-2. 해결 과정

JWT 예외를 종류별로 분리하였다.

```java
catch (ExpiredJwtException e) {
    // 만료된 토큰
} catch (SignatureException e) {
    // JWT 서명 불일치
} catch (MalformedJwtException | UnsupportedJwtException e) {
    // 손상되었거나 지원하지 않는 JWT
} catch (JwtException | IllegalArgumentException e) {
    // JWT 또는 Claim 형식 오류
}
```

로그 레벨과 상태 코드를 상황별로 적용하였다.

| 예외 또는 상황 | 로그 레벨 | HTTP 상태 |
|---|---|---:|
| Authorization 누락 | `WARN` | 401 |
| 토큰 만료 | `INFO` | 401 |
| 서명 불일치 | `WARN` | 401 |
| 손상된 JWT | `WARN` | 401 |
| 지원하지 않는 JWT | `WARN` | 401 |
| JWT 형식 오류 | `WARN` | 401 |
| 예상하지 못한 서버 오류 | `ERROR` | 500 |

Authorization 헤더는 `null`뿐 아니라 빈 문자열도 검사하도록 변경하였다.

```java
if (bearerJwt == null || bearerJwt.isBlank()) {
    sendErrorResponse(
            httpResponse,
            HttpStatus.UNAUTHORIZED,
            "인증이 필요합니다."
    );
    return;
}
```

필수 Claim이 누락된 경우에도 인증 실패로 처리하였다.

```java
if (email == null || email.isBlank()
        || userRole == null || userRole.isBlank()) {

    sendErrorResponse(
            httpResponse,
            HttpStatus.UNAUTHORIZED,
            "인증 정보가 올바르지 않습니다."
    );
    return;
}
```

JWT 원문과 Authorization 헤더는 로그에 기록하지 않고 예외 종류와 URI만 기록하였다.

```text
JWT 검증 실패 [SignatureException]: URI=/admin/users/3/role
```

---

## 3. 해결 완료

### 3-1. 회고

로그는 많이 남기는 것보다 **상황을 구분할 수 있도록 의미 있게 남기는 것**이 중요했다.

JWT 인증 실패는 서버 장애가 아니므로 무조건 `ERROR`로 기록할 필요가 없다. 예상 가능한 실패는 `INFO` 또는 `WARN`으로 남기고, 실제로 개발자가 즉시 확인해야 하는 예상 밖의 서버 오류만 `ERROR`로 기록하는 편이 운영 환경에서 더 유용하다.

또한 서버 로그와 클라이언트 응답의 목적이 다르다는 점도 확인하였다.

```text
서버 로그
→ 개발자가 원인을 분석할 수 있도록 구체적으로 기록

클라이언트 응답
→ 내부 구현과 보안 정보를 노출하지 않도록 단순하게 반환
```

### 3-2. 전후 데이터 비교

| 비교 항목 | 개선 전 | 개선 후 |
|---|---|---|
| JWT 예외 처리 | 일부 예외 또는 일괄 처리 | 예외 종류별 처리 |
| 서명 불일치 응답 | 500 가능성 | 401 |
| 로그 레벨 | 구분 부족 | `INFO` / `WARN` / `ERROR` |
| 빈 Authorization 검사 | `null` 위주 | `null` + `isBlank()` |
| 필수 Claim 검사 | 후속 코드에서 오류 가능 | 인증 단계에서 401 처리 |
| JWT 원문 로그 | 노출 위험 가능 | 기록하지 않음 |
| 클라이언트 메시지 | 내부 예외 노출 가능 | 단순한 인증 실패 메시지 |

### 응답 예시

```json
{
  "status": "UNAUTHORIZED",
  "code": 401,
  "message": "유효하지 않은 토큰입니다."
}
```

---

# 3️⃣ JwtFilter의 예외 처리 범위 개선

## 1. 문제 인식 및 정의

기존에는 `chain.doFilter()`가 JWT 예외 처리를 위한 `try-catch` 내부에 존재하였다.

```text
try
 ├─ JWT 검증
 ├─ request.setAttribute(...)
 └─ chain.doFilter()
catch (Exception)
```

`chain.doFilter()`가 실행되면 이후 단계에서 다음 로직이 수행된다.

```text
Interceptor
→ ArgumentResolver
→ Controller
→ Service
→ Repository
```

따라서 Controller나 Service에서 발생한 비즈니스 예외까지 `JwtFilter`의 `catch (Exception e)`가 가로채는 문제가 발생할 수 있었다.

예를 들어 Service에서 `InvalidRequestException`이 발생하더라도 Filter가 이를 잡으면 프로젝트의 `GlobalExceptionHandler`가 처리하지 못하고, 모든 오류가 `500 Internal Server Error`로 변환될 수 있었다.

---

## 2. 해결 방안

### 2-1. 의사결정 과정

`JwtFilter`가 처리해야 하는 예외는 JWT 인증 과정에서 발생한 예외뿐이다.

비즈니스 로직에서 발생한 예외는 Spring MVC의 예외 처리 흐름에 따라 `GlobalExceptionHandler`가 처리해야 한다. 따라서 JWT 검증 구간과 이후 요청 실행 구간을 분리하기로 결정하였다.

```text
JwtFilter
→ JWT 관련 예외만 처리

GlobalExceptionHandler
→ Controller와 Service의 비즈니스 예외 처리
```

### 2-2. 해결 과정

JWT 검증만 `try-catch`에서 수행하도록 변경하였다.

```java
Claims claims;

try {
    String jwt = jwtUtil.substringToken(bearerJwt);
    claims = jwtUtil.extractClaims(jwt);
} catch (ExpiredJwtException e) {
    // JWT 예외 처리
    return;
}
```

Claim을 검증하고 request attribute에 인증 정보를 저장하였다.

```java
request.setAttribute("userId", userId);
request.setAttribute("email", email);
request.setAttribute("userRole", userRole);
```

가장 중요한 변경으로, `chain.doFilter()`를 JWT `try-catch` 바깥으로 이동하였다.

```java
chain.doFilter(request, response);
```

개선된 예외 처리 흐름은 다음과 같다.

```text
JWT 검증
   ↓
JWT 관련 예외 처리
   ↓
인증 정보 저장
   ↓
chain.doFilter()
   ↓
Controller / Service
   ↓
GlobalExceptionHandler
```

---

## 3. 해결 완료

### 3-1. 회고

처음에는 Filter에서 발생 가능한 모든 예외를 처리하는 것이 안전하다고 생각할 수 있다. 하지만 `chain.doFilter()` 이후에는 JWT와 무관한 애플리케이션 로직이 실행된다.

따라서 Filter가 해당 예외까지 처리하면 Spring MVC가 제공하는 예외 처리 흐름을 침범하게 된다. Filter는 인증이라는 자신의 책임만 수행하고, 비즈니스 예외는 `GlobalExceptionHandler`에 맡기는 구조가 더 자연스럽고 유지보수하기 좋았다.

### 3-2. 전후 데이터 비교

| 비교 항목 | 개선 전 | 개선 후 |
|---|---|---|
| `chain.doFilter()` 위치 | JWT `try-catch` 내부 | JWT `try-catch` 외부 |
| JWT 예외 처리 | `JwtFilter` | `JwtFilter` |
| Controller 예외 | Filter가 가로챌 수 있음 | Spring MVC로 전달 |
| Service 예외 | 500으로 변경될 가능성 | `GlobalExceptionHandler` 처리 |
| 예외 처리 책임 | Filter에 혼재 | 계층별 책임 분리 |
| Spring MVC 예외 흐름 | 우회 가능 | 정상 유지 |

---

# ✅ 최종 구조

세 가지 개선을 완료한 뒤의 인증·인가·예외 처리 흐름은 다음과 같다.

```text
Client Request
      ↓
JwtFilter
 ├─ JWT 존재 여부 확인
 ├─ 서명 및 만료 검증
 ├─ Claim 추출
 └─ request attribute 저장
      ↓
AdminApiInterceptor
 ├─ 관리자 API 여부 확인
 └─ ADMIN 권한 검사
      ↓
AuthUserArgumentResolver
 └─ request attribute를 AuthUser로 변환
      ↓
Controller
      ↓
Service
      ↓
Repository
      ↓
GlobalExceptionHandler
 └─ Controller와 Service의 비즈니스 예외 처리
```

## 🎯 개선 결과 요약

- `JwtFilter`는 JWT 인증만 담당하도록 변경하였다.
- 관리자 권한 검사는 `AdminApiInterceptor` 한 곳에서만 수행한다.
- JWT 예외를 종류별로 구분하고 적절한 로그 레벨과 상태 코드를 적용하였다.
- JWT 원문이나 Authorization 헤더는 로그에 남기지 않는다.
- `chain.doFilter()`를 JWT 예외 처리 블록 밖으로 이동하였다.
- 비즈니스 예외는 `GlobalExceptionHandler`가 처리하도록 Spring MVC의 기본 흐름을 유지하였다.
- 인증, 인가, 로깅, 비즈니스 예외 처리의 책임이 명확하게 분리되었다.
