package org.example.expert.domain.todo.service;

import org.example.expert.client.WeatherClient;

import org.example.expert.domain.common.dto.AuthUser;
import org.example.expert.domain.common.exception.InvalidRequestException;
import org.example.expert.domain.todo.dto.request.TodoSaveRequest;
import org.example.expert.domain.todo.dto.response.TodoResponse;
import org.example.expert.domain.todo.dto.response.TodoSaveResponse;
import org.example.expert.domain.todo.entity.Todo;
import org.example.expert.domain.todo.repository.TodoRepository;
import org.example.expert.domain.user.entity.User;
import org.example.expert.domain.user.enums.UserRole;
import org.example.expert.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WeatherClient weatherClient;

    @InjectMocks
    private TodoService todoService;

    private User user;
    private AuthUser authUser;

    @BeforeEach
    void setUp() {
        user = new User(
                "test@test.com",
                "password",
                UserRole.USER
        );

        ReflectionTestUtils.setField(user, "id", 1L);

        authUser = new AuthUser(
                1L,
                "test@test.com",
                UserRole.USER
        );
    }

    @Test
    @DisplayName("일정 생성에 성공한다")
    void saveTodo_success() {
        // given
        TodoSaveRequest request = new TodoSaveRequest(
                "테스트 제목",
                "테스트 내용"
        );

        String weather = "맑음";

        Todo savedTodo = new Todo(
                request.getTitle(),
                request.getContents(),
                weather,
                user
        );

        ReflectionTestUtils.setField(savedTodo, "id", 10L);

        when(userRepository.findById(authUser.getId()))
                .thenReturn(Optional.of(user));

        when(weatherClient.getTodayWeather())
                .thenReturn(weather);

        when(todoRepository.save(any(Todo.class)))
                .thenReturn(savedTodo);

        // when
        TodoSaveResponse response =
                todoService.saveTodo(authUser, request);

        // then
        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getTitle()).isEqualTo("테스트 제목");
        assertThat(response.getContents()).isEqualTo("테스트 내용");
        assertThat(response.getWeather()).isEqualTo("맑음");
        assertThat(response.getUser().getId()).isEqualTo(1L);
        assertThat(response.getUser().getEmail())
                .isEqualTo("test@test.com");

        verify(userRepository).findById(1L);
        verify(weatherClient).getTodayWeather();
        verify(todoRepository).save(any(Todo.class));
    }

    @Test
    @DisplayName("존재하지 않는 사용자가 일정을 생성하면 예외가 발생한다")
    void saveTodo_userNotFound() {
        // given
        TodoSaveRequest request = new TodoSaveRequest(
                "테스트 제목",
                "테스트 내용"
        );

        when(userRepository.findById(authUser.getId()))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(
                () -> todoService.saveTodo(authUser, request)
        )
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("존재하지 않는 사용자입니다.");

        verify(userRepository).findById(1L);
        verifyNoInteractions(weatherClient);
        verifyNoInteractions(todoRepository);
    }

    @Test
    @DisplayName("일정 목록을 수정일 내림차순으로 조회한다")
    void getTodos_success() {
        // given
        Todo todo1 = new Todo(
                "제목1",
                "내용1",
                "맑음",
                user
        );

        Todo todo2 = new Todo(
                "제목2",
                "내용2",
                "흐림",
                user
        );

        ReflectionTestUtils.setField(todo1, "id", 1L);
        ReflectionTestUtils.setField(todo2, "id", 2L);

        ReflectionTestUtils.setField(
                todo1,
                "createdAt",
                LocalDateTime.of(2026, 7, 1, 10, 0)
        );

        ReflectionTestUtils.setField(
                todo1,
                "modifiedAt",
                LocalDateTime.of(2026, 7, 2, 10, 0)
        );

        ReflectionTestUtils.setField(
                todo2,
                "createdAt",
                LocalDateTime.of(2026, 7, 3, 10, 0)
        );

        ReflectionTestUtils.setField(
                todo2,
                "modifiedAt",
                LocalDateTime.of(2026, 7, 4, 10, 0)
        );

        Page<Todo> todoPage =
                new PageImpl<>(List.of(todo2, todo1));

        when(todoRepository.findAllByOrderByModifiedAtDesc(
                any(Pageable.class)
        )).thenReturn(todoPage);

        // when
        Page<TodoResponse> result =
                todoService.getTodos(1, 10);

        // then
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).getId())
                .isEqualTo(2L);
        assertThat(result.getContent().get(0).getTitle())
                .isEqualTo("제목2");
        assertThat(result.getContent().get(1).getId())
                .isEqualTo(1L);

        verify(todoRepository)
                .findAllByOrderByModifiedAtDesc(
                        argThat(pageable ->
                                pageable.getPageNumber() == 0
                                        && pageable.getPageSize() == 10
                        )
                );
    }

    @Test
    @DisplayName("일정 단건 조회에 성공한다")
    void getTodo_success() {
        // given
        Todo todo = new Todo(
                "테스트 제목",
                "테스트 내용",
                "맑음",
                user
        );

        ReflectionTestUtils.setField(todo, "id", 10L);

        LocalDateTime createdAt =
                LocalDateTime.of(2026, 7, 1, 10, 0);

        LocalDateTime modifiedAt =
                LocalDateTime.of(2026, 7, 2, 10, 0);

        ReflectionTestUtils.setField(
                todo,
                "createdAt",
                createdAt
        );

        ReflectionTestUtils.setField(
                todo,
                "modifiedAt",
                modifiedAt
        );

        when(todoRepository.findByIdWithUser(10L))
                .thenReturn(Optional.of(todo));

        // when
        TodoResponse response =
                todoService.getTodo(10L);

        // then
        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getTitle())
                .isEqualTo("테스트 제목");
        assertThat(response.getContents())
                .isEqualTo("테스트 내용");
        assertThat(response.getWeather())
                .isEqualTo("맑음");
        assertThat(response.getUser().getId())
                .isEqualTo(1L);
        assertThat(response.getUser().getEmail())
                .isEqualTo("test@test.com");
        assertThat(response.getCreatedAt())
                .isEqualTo(createdAt);
        assertThat(response.getModifiedAt())
                .isEqualTo(modifiedAt);

        verify(todoRepository).findByIdWithUser(10L);
    }

    @Test
    @DisplayName("존재하지 않는 일정을 조회하면 예외가 발생한다")
    void getTodo_notFound() {
        // given
        when(todoRepository.findByIdWithUser(999L))
                .thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(
                () -> todoService.getTodo(999L)
        )
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("Todo not found");

        verify(todoRepository).findByIdWithUser(999L);
    }
}