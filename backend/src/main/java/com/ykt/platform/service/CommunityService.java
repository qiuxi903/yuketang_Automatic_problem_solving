package com.ykt.platform.service;

import com.ykt.platform.dto.ContributionView;
import com.ykt.platform.entity.Contribution;
import com.ykt.platform.entity.User;
import com.ykt.platform.repository.ContributionRepository;
import com.ykt.platform.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 答案社区：采集到的完整正确答案自动发布的贡献流。
 * 发布本身（写入 answer-lib 落盘、供 matcher 匹配复用）由 collector 完成；
 * 这里只维护「谁贡献了什么」的可展示记录，按 任务+试卷 去重。
 */
@Service
public class CommunityService {

    private static final Logger log = LoggerFactory.getLogger(CommunityService.class);

    private final ContributionRepository contributionRepository;
    private final UserRepository userRepository;

    public CommunityService(ContributionRepository contributionRepository,
                            UserRepository userRepository) {
        this.contributionRepository = contributionRepository;
        this.userRepository = userRepository;
    }

    /**
     * Agent 回传答案时调用：新采集与复用都记录（复用也是一次真实的答案获取，
     * 用户口径「获取完答案自动上传/记录到社区」），同一任务同一试卷只记一条。
     */
    @Transactional
    public void record(Long taskId, Long userId, String batch, String paper, int questions, String source) {
        if (userId == null || paper == null || paper.isBlank() || questions <= 0) {
            return;
        }
        if (contributionRepository.findFirstByTaskIdAndPaper(taskId, paper).isPresent()) {
            return;
        }
        Contribution contribution = new Contribution();
        contribution.setUserId(userId);
        contribution.setBatch(batch);
        contribution.setPaper(paper);
        contribution.setQuestions(questions);
        contribution.setSource(source == null || source.isBlank() ? "collect" : source);
        contribution.setTaskId(taskId);
        contributionRepository.save(contribution);
        log.info("答案社区新增记录：任务 {} 用户 {} {} {}（{} 题，{}）",
                taskId, userId, "reuse".equalsIgnoreCase(source) ? "复用" : "采集", paper,
                questions, source);
    }

    @Transactional(readOnly = true)
    public List<ContributionView> list(int limit) {
        List<Contribution> rows = contributionRepository.findTop50ByOrderByCreatedAtDesc();
        if (limit > 0 && rows.size() > limit) {
            rows = rows.subList(0, limit);
        }
        List<ContributionView> views = new ArrayList<>();
        for (Contribution row : rows) {
            String username = userRepository.findById(row.getUserId())
                    .map(User::getUsername)
                    .orElse("user#" + row.getUserId());
            views.add(new ContributionView(username, row.getBatch(), row.getPaper(),
                    row.getQuestions(), row.getSource(), row.getTaskId(), row.getCreatedAt()));
        }
        return views;
    }
}
