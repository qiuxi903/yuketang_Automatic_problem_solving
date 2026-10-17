package com.ykt.platform.controller;

import com.ykt.platform.dto.ContributionView;
import com.ykt.platform.dto.SearchHit;
import com.ykt.platform.service.AnswerLibService;
import com.ykt.platform.service.CommunityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 答案社区：全体登录用户可浏览答案库贡献流与搜索答案。 */
@RestController
@RequestMapping("/api/community")
public class CommunityController {

    private final CommunityService communityService;
    private final AnswerLibService answerLibService;

    public CommunityController(CommunityService communityService, AnswerLibService answerLibService) {
        this.communityService = communityService;
        this.answerLibService = answerLibService;
    }

    @GetMapping("/contributions")
    public List<ContributionView> contributions(@RequestParam(defaultValue = "20") int limit) {
        return communityService.list(Math.min(Math.max(limit, 1), 50));
    }

    @GetMapping("/search")
    public List<SearchHit> search(@RequestParam("q") String query,
                                  @RequestParam(defaultValue = "50") int limit) {
        return answerLibService.search(query, Math.min(Math.max(limit, 1), 100));
    }
}
