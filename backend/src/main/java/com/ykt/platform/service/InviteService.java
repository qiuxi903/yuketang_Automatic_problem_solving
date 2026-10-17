package com.ykt.platform.service;

import com.ykt.platform.dto.InviteCreateRequest;
import com.ykt.platform.dto.InviteCreateResponse;
import com.ykt.platform.dto.InviteView;
import com.ykt.platform.entity.InviteCode;
import com.ykt.platform.repository.InviteCodeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 注册邀请码：生成、列表、禁用、校验消费。 */
@Service
public class InviteService {

    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"; // 去掉易混淆字符
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteCodeRepository inviteCodeRepository;

    public InviteService(InviteCodeRepository inviteCodeRepository) {
        this.inviteCodeRepository = inviteCodeRepository;
    }

    /** 批量生成邀请码（一码一用）。 */
    @Transactional
    public InviteCreateResponse generate(int count, Long adminUserId) {
        int n = Math.max(1, Math.min(count, 50));
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            InviteCode invite = new InviteCode();
            invite.setCode(randomCode());
            invite.setCreatedByUserId(adminUserId);
            inviteCodeRepository.save(invite);
            codes.add(invite.getCode());
        }
        return new InviteCreateResponse(codes);
    }

    @Transactional(readOnly = true)
    public List<InviteView> list() {
        return inviteCodeRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(c -> new InviteView(c.getCode(), c.isUsed(), c.getUsedBy(),
                        c.getUsedAt(), c.isDisabled(), c.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void disable(String code) {
        InviteCode invite = inviteCodeRepository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "邀请码不存在"));
        invite.setDisabled(true);
        inviteCodeRepository.save(invite);
    }

    /** 删除单个邀请码（任何状态；已使用的删除即从列表清走，未使用的删除等于作废）。 */
    @Transactional
    public void delete(String code) {
        InviteCode invite = inviteCodeRepository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "邀请码不存在"));
        inviteCodeRepository.delete(invite);
    }

    /** 一键清理全部已使用的邀请码，返回清理数量。 */
    @Transactional
    public long deleteUsed() {
        return inviteCodeRepository.deleteByUsedTrue();
    }

    /** 注册时校验邀请码：必须存在、未使用、未禁用；通过即标记为已使用。 */
    @Transactional
    public void consume(String code, String username) {
        InviteCode invite = inviteCodeRepository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "邀请码无效"));
        if (invite.isDisabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邀请码已被禁用");
        }
        if (invite.isUsed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邀请码已被使用");
        }
        invite.setUsed(true);
        invite.setUsedBy(username);
        invite.setUsedAt(Instant.now());
        inviteCodeRepository.save(invite);
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
