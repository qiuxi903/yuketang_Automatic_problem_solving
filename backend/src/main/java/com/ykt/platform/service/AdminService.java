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
    private final VipAdminService vipAdminService;
    private final com.ykt.platform.repository.VipOrderRepository orderRepository;
    private final com.ykt.platform.repository.LoginLogRepository loginLogRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public AdminService(UserRepository userRepository,
                        TaskRepository taskRepository,
                        YktSessionRepository sessionRepository,
                        PaperRepository paperRepository,
                        QuestionRepository questionRepository,
                        AnswerLibService answerLibService,
                        VipAdminService vipAdminService,
                        com.ykt.platform.repository.VipOrderRepository orderRepository,
                        com.ykt.platform.repository.LoginLogRepository loginLogRepository,
                        org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.taskRepository = taskRepository;
        this.sessionRepository = sessionRepository;
        this.paperRepository = paperRepository;
        this.questionRepository = questionRepository;
        this.answerLibService = answerLibService;
        this.vipAdminService = vipAdminService;
        this.orderRepository = orderRepository;
        this.loginLogRepository = loginLogRepository;
        this.passwordEncoder = passwordEncoder;
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

        com.ykt.platform.dto.VipStatsResponse vip = vipAdminService.stats();
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
                batches,
                vip.totalRevenueFen(),
                vip.pendingOrders(),
                vip.monthActiveUsers());
    }

    public List<AdminUserView> users() {
        Map<Long, List<Task>> tasksByUser = taskRepository.findAll().stream()
                .collect(Collectors.groupingBy(Task::getUserId));
        // 充值合计：按用户聚合已支付订单
        Map<Long, long[]> paidByUser = new java.util.HashMap<>(); // userId -> [totalFen, orders]
        orderRepository.findAll().stream()
                .filter(o -> "PAID".equals(o.getStatus()))
                .forEach(o -> {
                    long[] agg = paidByUser.computeIfAbsent(o.getUserId(), k -> new long[2]);
                    agg[0] += o.getPayableFen();
                    agg[1] += 1;
                });
        return userRepository.findAll().stream()
                .sorted(Comparator.comparing(User::getId))
                .map(user -> {
                    List<Task> own = tasksByUser.getOrDefault(user.getId(), List.of());
                    long running = own.stream().filter(t -> t.getStatus() == TaskStatus.RUNNING).count();
                    long[] paid = paidByUser.getOrDefault(user.getId(), new long[2]);
                    return new AdminUserView(user.getId(), user.getUsername(),
                            user.getRole() == null ? "USER" : user.getRole(),
                            own.size(), running, user.getCreatedAt(),
                            user.isDisabled(), user.getLastLoginIp(), user.getLastLoginLocation(),
                            user.getLastLoginAt(), paid[0], paid[1]);
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

    /** 按用户过滤做题记录（用户详情页）。 */
    public List<AdminTaskView> tasksByUser(Long userId) {
        Map<Long, String> nameById = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, User::getUsername, (a, b) -> a));
        return taskRepository.findByUserIdOrderByIdDesc(userId).stream()
                .map(task -> toView(task, nameById.get(task.getUserId())))
                .toList();
    }

    /** 管理员重置用户密码：规则与注册一致，重置后旧 token 全部失效（强制重新登录）。 */
    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (newPassword == null || newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 8 位");
        }
        if (!newPassword.matches("(?=.*[A-Za-z])(?=.*\\d).+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码需同时包含字母和数字");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordUpdatedAt(java.time.Instant.now());
        userRepository.save(user);
    }

    /** 封禁/解封用户：封禁后无法登录，已有登录态在下一次请求时失效。 */
    @Transactional
    public void setDisabled(Long userId, boolean disabled) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if ("ADMIN".equalsIgnoreCase(user.getRole()) && disabled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能封禁管理员账号");
        }
        user.setDisabled(disabled);
        if (disabled) {
            // 封禁同时踢下线
            user.setPasswordUpdatedAt(java.time.Instant.now());
        }
        userRepository.save(user);
    }

    /** 用户登录记录（最近 50 条）。 */
    @Transactional(readOnly = true)
    public List<com.ykt.platform.entity.LoginLog> loginLogs(Long userId) {
        return loginLogRepository.findByUserIdOrderByCreatedAtDesc(userId,
                org.springframework.data.domain.PageRequest.of(0, 50));
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
        long[] paid = paidByUserSnapshot(userId);
        return new AdminUserView(user.getId(), user.getUsername(), user.getRole(),
                taskCount, running, user.getCreatedAt(),
                user.isDisabled(), user.getLastLoginIp(), user.getLastLoginLocation(),
                user.getLastLoginAt(), paid[0], paid[1]);
    }

    /** 单用户的已支付合计快照（[totalFen, orders]）。 */
    private long[] paidByUserSnapshot(Long userId) {
        long[] agg = {0, 0};
        for (var o : orderRepository.findByUserIdOrderByCreatedAtDesc(userId)) {
            if ("PAID".equals(o.getStatus())) {
                agg[0] += o.getPayableFen();
                agg[1] += 1;
            }
        }
        return agg;
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
