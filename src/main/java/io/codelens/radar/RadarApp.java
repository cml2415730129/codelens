package io.codelens.radar;

import io.codelens.config.AppConfig;
import java.nio.file.Paths;
import java.util.List;

/** Radar CLI: codelens radar [check|add|remove|list] [--lang X] */
public class RadarApp {

    public static int run(String[] args) {
        String cmd = args.length > 0 ? args[0] : "check";
        String lang = "Chinese";
        String operand = null;
        for (int i = 0; i < args.length; i++) {
            if ("--lang".equals(args[i]) && i + 1 < args.length) {
                lang = args[++i];
            } else if (!args[i].startsWith("--") && !args[i].equals(cmd)) {
                operand = args[i];
            }
        }

        AppConfig config = AppConfig.load();
        RadarEngine engine = new RadarEngine(config, Paths.get("."));
        try {
            switch (cmd) {
                case "add" -> {
                    if (operand == null) {
                        return usage();
                    }
                    String slug = engine.addRepo(operand);
                    System.out.println("✔ Added to watchlist: " + slug);
                    return 0;
                }
                case "remove" -> {
                    if (operand == null) {
                        return usage();
                    }
                    System.out.println("✔ Removed: " + engine.removeRepo(operand));
                    return 0;
                }
                case "list" -> {
                    List<String> list = engine.listWatchlist();
                    System.out.println("RepoRadar watchlist (" + list.size() + "):");
                    list.forEach(u -> System.out.println("  • " + u));
                    return 0;
                }
                case "check" -> {
                    System.out.println("📡 RepoRadar sweep started (model: "
                            + config.modelId() + ")");
                    var report = engine.check(lang, System.out::println);
                    System.out.println();
                    System.out.println("── Sweep done @ " + report.checkedAt());
                    if (report.alerts().isEmpty()) {
                        System.out.println("   没有值得注意的变化。");
                    } else {
                        System.out.println("   🔔 " + report.alerts().size() + " 个仓库有动静：");
                        report.alerts().forEach(a ->
                                System.out.println("   " + a.severity() + "  " + a.slug()));
                    }
                    if (report.errorCount() > 0) {
                        System.out.println("   ⚠ " + report.errorCount() + " 个仓库检查失败");
                    }
                    return 0;
                }
                default -> {
                    return usage();
                }
            }
        } catch (Exception e) {
            System.out.println("✘ " + e.getMessage());
            return 2;
        }
    }

    private static int usage() {
        System.out.println("""
                RepoRadar — 持续盯住你关心的开源仓库。

                Usage:
                  codelens radar                      巡检一遍（默认）
                  codelens radar add <repo-url>       加入监控清单
                  codelens radar remove <owner/repo>  移出清单
                  codelens radar list                 查看清单
                  codelens --serve                    Web 仪表盘 (http://localhost:8321)
                """);
        return 1;
    }
}
