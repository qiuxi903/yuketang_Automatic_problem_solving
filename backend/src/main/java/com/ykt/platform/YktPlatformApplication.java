package com.ykt.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class YktPlatformApplication {

    /** 服务端口固定：不漂移换口（内网穿透隧道、收藏夹、Agent 地址全部依赖它不变）。 */
    private static final int SERVER_PORT = 28080;

    public static void main(String[] args) {
        killPortSquatters(SERVER_PORT);
        SpringApplication.run(YktPlatformApplication.class, args);
    }

    /** 端口强占：被平台残留进程占用时直接结束占用者（含子进程树），宁可强杀也不换端口。 */
    private static void killPortSquatters(int port) {
        try {
            Process probe = new ProcessBuilder("cmd", "/c",
                    "netstat -ano | findstr \":" + port + " \" | findstr \"LISTENING\"").start();
            String out;
            try (var in = new java.util.Scanner(probe.getInputStream()).useDelimiter("\\A")) {
                out = in.hasNext() ? in.next() : "";
            }
            java.util.Set<String> pids = new java.util.HashSet<>();
            for (String line : out.split("\r?\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length > 0 && parts[parts.length - 1].matches("\\d+")) {
                    pids.add(parts[parts.length - 1]);
                }
            }
            for (String pid : pids) {
                try {
                    new ProcessBuilder("cmd", "/c", "taskkill /PID " + pid + " /F /T").start();
                    System.out.println("[port] 已结束占用 " + port + " 端口的残留进程 PID " + pid);
                } catch (Exception ex) {
                    // 进程可能恰好自行退出
                }
            }
            if (!pids.isEmpty()) {
                Thread.sleep(1500); // 等内核真正释放端口
            }
        } catch (Exception ex) {
            // 端口空闲（findstr 无匹配非零退出）或命令不可用：按无占用处理
        }
    }
}
