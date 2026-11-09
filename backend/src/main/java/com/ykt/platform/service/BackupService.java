package com.ykt.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * H2 在线备份：用 H2 的 BACKUP TO 语句（事务一致，运行中也能备），
 * 每天凌晨 3 点 + 支持管理员手动触发；保留最近 N 份，超出自动清理。
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JdbcTemplate jdbc;
    private final Path dir;
    private final int keep;

    public BackupService(JdbcTemplate jdbc,
                         @Value("${ykt.backup.dir:./backup}") String dir,
                         @Value("${ykt.backup.keep:7}") int keep) {
        this.jdbc = jdbc;
        this.dir = Paths.get(dir);
        this.keep = Math.max(1, keep);
    }

    /** 每日凌晨 3 点自动备份。 */
    @Scheduled(cron = "0 0 3 * * *")
    public void scheduledBackup() {
        try {
            String path = backupNow();
            log.info("每日备份完成：{}", path);
        } catch (Exception e) {
            log.error("每日备份失败：{}", e.getMessage(), e);
        }
    }

    /** 在线备份到 <dir>/ykt-vip-<时间戳>.zip，返回文件路径（调用方自行通知/记录）。 */
    public String backupNow() throws IOException {
        Files.createDirectories(dir);
        String file = dir.resolve("ykt-vip-" + LocalDateTime.now().format(STAMP) + ".zip")
                .toString().replace('\\', '/');
        // H2 BACKUP TO 输出 zip；路径里的单引号不可能出现（受控目录名），直接拼接安全
        jdbc.execute("BACKUP TO '" + file + "'");
        retain();
        return file;
    }

    /** 只保留最近 keep 份，删掉更旧的。 */
    private void retain() {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> zips = files
                    .filter(f -> f.getFileName().toString().startsWith("ykt-vip-") && f.toString().endsWith(".zip"))
                    .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                    .toList();
            for (int i = keep; i < zips.size(); i++) {
                Files.deleteIfExists(zips.get(i));
                log.info("备份超出保留份数，已删除 {}", zips.get(i).getFileName());
            }
        } catch (IOException e) {
            log.warn("清理旧备份失败：{}", e.getMessage());
        }
    }

    /** 备份目录（管理端展示用）。 */
    public Path dir() {
        return dir;
    }

    /** 今天是否已有备份（避免重复刷屏；手动触发不受限）。 */
    public boolean hasTodayBackup() {
        String prefix = "ykt-vip-" + LocalDate.now();
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(f -> f.getFileName().toString().startsWith(prefix));
        } catch (IOException e) {
            return false;
        }
    }
}
