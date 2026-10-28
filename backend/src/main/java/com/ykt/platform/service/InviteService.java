package com.ykt.platform.service;

import com.ykt.platform.dto.InviteCreateRequest;
import com.ykt.platform.dto.InviteCreateResponse;
import com.ykt.platform.dto.InviteInfoResponse;
import com.ykt.platform.dto.InviteUseView;
import com.ykt.platform.dto.InviteView;
import com.ykt.platform.entity.InviteCode;
import com.ykt.platform.entity.InviteUse;
import com.ykt.platform.repository.InviteCodeRepository;
import com.ykt.platform.repository.InviteUseRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 注册邀请码：生成（可多次使用）、列表、启停、校验消费、公开查询。 */
@Service
public class InviteService {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"; // 去掉易混淆字符
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteCodeRepository inviteCodeRepository;
    private final InviteUseRepository inviteUseRepository;

    public InviteService(InviteCodeRepository inviteCodeRepository, InviteUseRepository inviteUseRepository) {
        this.inviteCodeRepository = inviteCodeRepository;
        this.inviteUseRepository = inviteUseRepository;
    }

    /** 批量生成邀请码。maxUses=0 不限次数；null 一码一用；>1 一个码可邀请多人。 */
    @Transactional
    public InviteCreateResponse generate(InviteCreateRequest request, Long adminUserId) {
        int n = Math.max(1, Math.min(request.count(), 50));
        int maxUses = request.maxUses() == null ? 1 : Math.max(0, Math.min(request.maxUses(), 9999));
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            InviteCode invite = new InviteCode();
            invite.setCode(randomCode());
            invite.setMaxUses(maxUses);
            invite.setUsedCount(0);
            invite.setUsed(false);
            invite.setCreatedByUserId(adminUserId);
            inviteCodeRepository.save(invite);
            codes.add(invite.getCode());
        }
        return new InviteCreateResponse(codes);
    }

    @Transactional(readOnly = true)
    public List<InviteView> list() {
        List<InviteCode> all = inviteCodeRepository.findAllByOrderByCreatedAtDesc();
        Map<String, List<InviteUse>> usesByCode = inviteUseRepository
                .findByCodeInOrderByUsedAtDesc(all.stream().map(InviteCode::getCode).toList())
                .stream()
                .collect(Collectors.groupingBy(InviteUse::getCode));
        return all.stream()
                .map(c -> new InviteView(
                        c.getCode(), c.isUsed(), c.getUsedBy(), c.getUsedAt(),
                        c.isDisabled(), c.getCreatedAt(),
                        c.maxUsesOrDefault(), c.usedCountOrDefault(),
                        usesByCode.getOrDefault(c.getCode(), List.of()).stream()
                                .map(u -> new InviteUseView(u.getUsername(), u.getUsedAt()))
                                .toList()))
                .toList();
    }

    @Transactional
    public void disable(String code) {
        InviteCode invite = inviteCodeRepository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "邀请码不存在"));
        invite.setDisabled(true);
        inviteCodeRepository.save(invite);
    }

    @Transactional
    public void enable(String code) {
        InviteCode invite = inviteCodeRepository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "邀请码不存在"));
        invite.setDisabled(false);
        inviteCodeRepository.save(invite);
    }

    /** 删除单个邀请码（任何状态；使用记录一并清走，未使用的删除等于作废）。 */
    @Transactional
    public void delete(String code) {
        InviteCode invite = inviteCodeRepository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "邀请码不存在"));
        inviteUseRepository.deleteByCodeIn(List.of(invite.getCode()));
        inviteCodeRepository.delete(invite);
    }

    /** 批量操作：disable / enable / delete，返回实际影响的条数。 */
    @Transactional
    public long batch(String action, List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return 0;
        }
        List<InviteCode> found = inviteCodeRepository.findByCodeIn(codes);
        if (found.isEmpty()) {
            return 0;
        }
        switch (action == null ? "" : action) {
            case "disable" -> found.forEach(i -> i.setDisabled(true));
            case "enable" -> found.forEach(i -> i.setDisabled(false));
            case "delete" -> {
                inviteUseRepository.deleteByCodeIn(found.stream().map(InviteCode::getCode).toList());
                inviteCodeRepository.deleteAll(found);
                return found.size();
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "不支持的操作：" + action + "（可用 disable / enable / delete）");
        }
        inviteCodeRepository.saveAll(found);
        return found.size();
    }

    /** 一键清理全部已用尽的邀请码（使用记录一并清理），返回清理数量。 */
    @Transactional
    public long deleteUsed() {
        List<InviteCode> used = inviteCodeRepository.findByUsedTrue();
        if (used.isEmpty()) return 0;
        inviteUseRepository.deleteByCodeIn(used.stream().map(InviteCode::getCode).toList());
        inviteCodeRepository.deleteAll(used);
        return used.size();
    }

    /** 注册时校验并消费邀请码：必须存在、未禁用、剩余次数 > 0；通过即记一次使用。 */
    @Transactional
    public void consume(String code, String username) {
        // 行锁串行化同码并发注册，防止把仅剩一次的码用超
        InviteCode invite = inviteCodeRepository.lockByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "邀请码无效"));
        if (invite.isDisabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邀请码已被禁用");
        }
        if (invite.exhausted()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    invite.maxUsesOrDefault() == 1 ? "邀请码已被使用" : "邀请码使用次数已达上限");
        }
        invite.setUsedCount(invite.usedCountOrDefault() + 1);
        invite.setUsed(invite.exhausted());
        invite.setUsedBy(username);
        invite.setUsedAt(Instant.now());
        inviteCodeRepository.save(invite);

        InviteUse use = new InviteUse();
        use.setCode(invite.getCode());
        use.setUsername(username);
        use.setUsedAt(Instant.now());
        inviteUseRepository.save(use);
    }

    /** 邀请码公开查询：注册页带码进入时展示有效性，不暴露创建者等管理信息。 */
    @Transactional(readOnly = true)
    public InviteInfoResponse inspect(String code) {
        return inviteCodeRepository.findByCode(code)
                .map(invite -> new InviteInfoResponse(
                        !invite.isDisabled() && !invite.exhausted(),
                        invite.isDisabled(),
                        invite.exhausted(),
                        invite.remainingUses(),
                        invite.maxUsesOrDefault()))
                .orElse(new InviteInfoResponse(false, false, false, 0, 0));
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder("YKT-");
        for (int g = 0; g < 2; g++) {
            for (int i = 0; i < 4; i++) {
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            sb.append('-');
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
