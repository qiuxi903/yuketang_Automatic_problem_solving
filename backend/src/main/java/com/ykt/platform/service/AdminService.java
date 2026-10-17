package com.ykt.platform.service;

import com.ykt.platform.dto.AdminOverviewResponse;
import com.ykt.platform.dto.AdminTaskView;
import com.ykt.platform.dto.AdminUserView;
import com.ykt.platform.dto.PaperManifestResponse;
import com.ykt.platform.dto.UpdateUserRoleRequest;
import com.ykt.platform.entity.Task;
import com.ykt.platform.entity.TaskStatus;
import com.ykt.platform.entity.User;
import com.ykt.platform.entity.YktSession;
import com.ykt.platform.repository.PaperRepository;
import com.ykt.platform.repository.QuestionRepository;
import com.ykt.platform.repository.TaskRepository;
import com.ykt.platform.repository.UserRepository;
import com.ykt.platform.repository.YktSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 管理后台只读统计与用户管理，全部接口受 /api/admin/** 的 ADMIN 鉴权保护。 */
@Service
public class AdminService {

    private static final List<String> TASK_STATUSES =
            List.of("PENDING", "RUNNING", "PAUSED", "SUCCESS", "FAILED");
    private static final List<String> SESSION_STATUSES =
            List.of("WAITING_SCAN", "SCANNED", "EXPIRED", "CLOSED");

    private final UserRepository userRepository;
    private final TaskRepository taskRepository;
    private final YktSessionRepository sessionRepository;
    private final PaperRepository paperRepository;
    private final QuestionRepository questionRepository;
    private final AnswerLibService answerLibService;

    public AdminService(UserRepository userRepository,
                        TaskRepository taskRepository,
                        YktSessionRepository sessionRepository,
                        PaperRepository paperRepository,
                        QuestionRepository questionRepository,
                        AnswerLibService answerLibService) {
        this.userRepository = userRepository;
        this.taskRepository = taskRepository;
        this.sessionRepository = sessionRepository;
        this.paperRepository = paperRepository;
        this.questionRepository = questionRepository;
        this.answerLibService = answerLibService;
    }

    public AdminOverviewResponse overview() {
        List<User> users = userRepository.findAll();
        long adminCount = users.stream().filter(u -> "ADMIN".equalsIgnoreCase(u.getRole())).count();

        List<Task> tasks = taskRepository.findAll();
        Map<String, Long> taskStatus = new LinkedHashMap<>();
        for (String status : TASK_STATUSES) {
            taskStatus.put(status, tasks.stream().filter(t -> status.equals(t.getStatus().name())).count());
        }

        List<YktSession> sessions = sessionRepository.findAll();
        Map<String, Long> sessionStatus = new LinkedHashMap<>();
        for (String status : SESSION_STATUSES) {
            sessionStatus.put(status, sessions.stream().filter(s -> status.equals(s.getStatus().name())).count());
        }

        PaperManifestResponse manifest = answerLibService.getManifest();
        List<AdminOverviewResponse.BatchBrief> batches = manifest.batches().stream()
                .map(b -> new AdminOverviewResponse.BatchBrief(
                        b.batch(), b.papersCount(), b.questions()))
                .toList();

        return new AdminOverviewResponse(
                users.size(),
                adminCount,
                tasks.size(),
                taskStatus,
                paperRepository.count(),
                questionRepository.count(),
                batches.size(),
                sessions.size(),
                sessionStatus,
                batches);
    }

    public List<AdminUserView> users() {
        Map<Long, List<Task>> tasksByUser = taskRepository.findAll().stream()
                .collect(Collectors.groupingBy(Task::getUserId));
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getId))
                .map(user -> {
                    List<Task> own = tasksByUser.getOrDefault(user.getId(), List.of());
                    long running = own.stream().filter(t -> t.getStatus() == TaskStatus.RUNNING).count();
                    return new AdminUserView(user.getId(), user.getUsername(),
                            user.getRole() == null ? "USER" : user.getRole(),
                            own.size(), running, user.getCreatedAt());
                })
                .toList();
    }

    public List<AdminTaskView> tasks() {
        Map<Long, String> nameById = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        return taskRepository.findAll().stream()
                .sorted(Comparator.comparing(Task::getId).reversed())
                .map(task -> toView(task, nameById.get(task.getUserId())))
                .toList();
    }

    @Transactional
    public AdminUserView updateRole(Long userId, UpdateUserRoleRequest request) {
        String role = request == null || request.role() == null ? "" : request.role().strip().toUpperCase();
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色仅支持 USER 或 ADMIN");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        long adminTotal = userRepository.findAll().stream()
                .filter(u -> "ADMIN".equalsIgnoreCase(u.getRole()))
                .count();
        if ("USER".equals(role) && "ADMIN".equalsIgnoreCase(user.getRole()) && adminTotal <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "至少保留一名管理员");
        }
        user.setRole(role);
        userRepository.save(user);
        long taskCount = taskRepository.findByUserIdOrderByIdDesc(userId).size();
        long running = taskRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(t -> t.getStatus() == TaskStatus.RUNNING).count();
        return new AdminUserView(user.getId(), user.getUsername(), user.getRole(),
                taskCount, running, user.getCreatedAt());
    }

    private AdminTaskView toView(Task task, String username) {
        return new AdminTaskView(
                task.getId(),
                task.getUserId(),
                username,
                task.getBatch(),
                task.getMode(),
                task.getStatus().name(),
                task.getTotalPaper(),
                task.getDonePaper(),
                task.getCurrentPaper(),
                task.getCurrentSeq(),
                task.getCurrentTotal(),
                task.getScore(),
                task.getMessage(),
                task.getCreatedAt());
    }
}
