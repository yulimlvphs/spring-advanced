# Spring Advanced

JWT 인증과 권한 기반 접근 제어를 적용한 일정 관리 백엔드 프로젝트이다. 사용자, 일정, 댓글, 담당자 기능을 구현했으며, Filter·Interceptor·ArgumentResolver·AOP를 활용해 인증, 인가, 사용자 정보 전달, 관리자 API 로깅의 책임을 분리했다.

## 기술 스택

- Java 17
- Spring Boot 3.3.3
- Spring MVC
- Spring Data JPA
- MySQL
- JWT
- Gradle
- Lombok
- Jackson
- AOP

## 주요 기능

- 회원가입 및 로그인
- JWT 발급과 인증
- 일정 생성·조회·수정·삭제
- 댓글 관리
- 일정 담당자 관리
- 일반 사용자와 관리자 권한 구분
- 관리자 API 접근 제어
- 관리자 API 요청·응답 로깅

## 인증 및 요청 처리 흐름

```text
클라이언트 요청
→ JwtFilter에서 JWT 검증
→ 인증 정보를 request attribute에 저장
→ AdminApiInterceptor에서 관리자 권한 검사
→ AuthUserArgumentResolver에서 AuthUser 생성
→ Controller
→ Service
→ Repository
```

`JwtFilter`는 인증만 담당하고, 관리자 API의 권한 검사는 `AdminApiInterceptor`가 담당한다. Controller에서는 `@Auth AuthUser`를 통해 현재 로그인한 사용자 정보를 전달받는다.

## 프로젝트 구조

```text
org.example.expert
├── config
│   ├── JwtFilter
│   ├── JwtUtil
│   ├── FilterConfig
│   ├── WebConfig
│   ├── AuthUserArgumentResolver
│   ├── AdminApiInterceptor
│   └── AdminApiLoggingAspect
└── domain
    ├── auth
    ├── user
    ├── todo
    ├── comment
    └── manager
```

# 개선 1. Filter와 Interceptor의 권한 검사 중복 제거

## 1. [문제 인식 및 정의]

기존 `JwtFilter`는 JWT의 유효성을 검증하는 동시에 `/admin` 경로의 관리자 권한까지 검사했다. 이후 추가한 `AdminApiInterceptor`에서도 같은 관리자 권한을 검사하면서 인가 로직이 두 곳에 중복되었다.

```text
기존 흐름
JwtFilter: JWT 인증 + ADMIN 권한 검사
AdminApiInterceptor: ADMIN 권한 검사
```

이 구조는 권한 정책이 변경될 때 두 클래스를 모두 수정해야 하고, 어떤 단계에서 접근이 차단됐는지 파악하기 어렵다는 문제가 있었다. 또한 Filter는 URL 문자열을 기준으로 판단하므로 실제 Controller 메서드 단위의 정책을 표현하기 어려웠다.

## 2. [해결 방안]

### 2-1. [의사결정 과정]

Filter와 Interceptor의 특성을 기준으로 책임을 나눴다.

- `JwtFilter`: 토큰 존재 여부, 서명, 만료 검증과 Claim 추출
- `AdminApiInterceptor`: 관리자 API 여부 확인과 `ADMIN` 권한 검사
- `AdminApiLoggingAspect`: 관리자 API 요청·응답 로깅

Filter는 Controller 실행 전에 동작하지만 어떤 Controller 메서드가 선택됐는지 알기 어렵다. 반면 Interceptor는 `HandlerMethod`를 통해 실제 대상 메서드를 확인할 수 있으므로 관리자 API 인가에 더 적합하다고 판단했다.

### 2-2. [해결 과정]

`JwtFilter`에서 아래 관리자 권한 검사 로직을 제거했다.

```java
if (url.startsWith("/admin") && ...) {
    // 관리자 권한 검사 및 접근 차단
}
```

JWT 검증 성공 후에는 인증 정보만 request에 저장하고 다음 단계로 전달하도록 변경했다.

```java
request.setAttribute("userId", userId);
request.setAttribute("email", email);
request.setAttribute("userRole", userRole);
chain.doFilter(request, response);
```

관리자 권한 검사는 `AdminApiInterceptor`에서만 수행하도록 통합했다.

```text
개선 흐름
JwtFilter: JWT 인증
→ AdminApiInterceptor: ADMIN 권한 검사
→ Controller
```

## 3. [해결 완료]

### 3-1. [회고]

인증과 인가는 비슷해 보이지만 책임이 다르다는 점을 코드 구조를 통해 이해했다. JWT가 유효한 사용자인지 확인하는 것은 인증이고, 해당 사용자가 관리자 API를 실행할 수 있는지 판단하는 것은 인가이다. 하나의 클래스에 두 책임을 넣기보다 각 계층의 특성에 맞게 분리하는 것이 유지보수에 유리했다.

### 3-2. [전후 데이터 비교]

| 구분 | 개선 전 | 개선 후 |
|---|---|---|
| JWT 인증 위치 | JwtFilter | JwtFilter |
| 관리자 권한 검사 위치 | JwtFilter + Interceptor | AdminApiInterceptor |
| 관리자 권한 검사 횟수 | 최대 2회 | 1회 |
| 정책 수정 지점 | 여러 클래스 | Interceptor 한 곳 |
| 관리자 접근 거부 로그 | JwtFilter 또는 Interceptor | AdminApiInterceptor |

---

# 개선 2. JWT 예외 처리와 로그 개선

## 1. [문제 인식 및 정의]

기존 `JwtFilter`는 일부 JWT 오류를 구분했지만, 서명 불일치와 같은 예상 가능한 인증 실패가 최종 `catch (Exception e)`로 처리될 가능성이 있었다. 이 경우 잘못된 토큰 요청이 서버 내부 오류인 것처럼 `ERROR` 로그와 긴 스택 트레이스를 남기고, 상황에 따라 500 응답으로 처리될 수 있었다.

또한 인증 헤더가 빈 문자열인 경우를 검사하지 않았고, JWT에 필수 Claim이 누락된 상황도 명확하게 구분하지 않았다.

## 2. [해결 방안]

### 2-1. [의사결정 과정]

JWT 오류를 다음 기준으로 분류했다.

- 토큰 만료: 예상 가능한 인증 실패
- 서명 불일치·손상된 토큰·지원하지 않는 형식: 유효하지 않은 인증 정보
- Bearer 형식 또는 Claim 값 오류: 잘못된 요청 데이터
- 그 외 예측하지 못한 오류: 서버 내부 오류

예상 가능한 인증 실패는 `WARN` 또는 `INFO`로 간결하게 기록하고 401을 반환하며, 실제 서버 내부 오류만 `ERROR`와 500으로 처리하도록 결정했다. JWT 원문과 Authorization 헤더는 보안상 로그에 남기지 않았다.

### 2-2. [해결 과정]

JWT 예외를 종류별로 분리했다.

```java
catch (ExpiredJwtException e) {
    // 401: 만료된 토큰
} catch (SignatureException | MalformedJwtException | UnsupportedJwtException e) {
    // 401: 유효하지 않은 토큰
} catch (IllegalArgumentException e) {
    // 401: 잘못된 토큰 형식
} catch (Exception e) {
    // 500: 예상하지 못한 서버 오류
}
```

Authorization 헤더는 `null`뿐 아니라 빈 문자열도 검사하도록 변경했다.

```java
if (bearerJwt == null || bearerJwt.isBlank()) {
    // 401 응답
}
```

`email`, `userRole` 등 필수 Claim이 누락된 경우에도 인증 실패로 처리했다. 로그에는 예외 종류, 요청 URI, 원인 메시지만 기록하고 JWT 문자열 자체는 기록하지 않았다.

## 3. [해결 완료]

### 3-1. [회고]

모든 예외를 `ERROR`로 기록한다고 장애 분석이 쉬워지는 것은 아니었다. 오히려 예상 가능한 인증 실패와 실제 서버 장애를 구분해야 운영 로그의 의미가 명확해진다. 서버 로그는 원인을 확인할 수 있을 만큼 구체적으로 남기되, 클라이언트에는 내부 구현이 노출되지 않는 단순한 메시지를 반환하는 것이 중요했다.

### 3-2. [전후 데이터 비교]

| 상황 | 개선 전 | 개선 후 |
|---|---|---|
| 서명 불일치 | 일반 예외 또는 과도한 스택 트레이스 가능 | `WARN`, 401 |
| 만료 토큰 | 401 | `INFO`, 401 및 전용 메시지 |
| 빈 Authorization 헤더 | 명확한 처리 부족 | 401 |
| 필수 Claim 누락 | 후속 코드에서 오류 가능 | 401 |
| 예상하지 못한 내부 오류 | 500 | `ERROR`, 500 |
| JWT 원문 로그 | 노출 위험 존재 | 기록하지 않음 |

실제 서명 불일치 요청은 다음과 같이 구분되어 기록된다.

```text
JWT 검증 실패 [SignatureException]: URI=/admin/users/3/role
```

클라이언트에는 내부 예외 메시지 대신 다음과 같은 응답을 반환한다.

```json
{
  "status": "UNAUTHORIZED",
  "code": 401,
  "message": "유효하지 않은 토큰입니다."
}
```

## 향후 개선

- JWT 인증 오류 응답 DTO 통일
- 관리자 권한 전용 예외 분리
- Interceptor와 AOP 단위 테스트 추가
- 관리자 감사 로그의 민감정보 마스킹
- Refresh Token 도입 검토
