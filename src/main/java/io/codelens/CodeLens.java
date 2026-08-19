package io.codelens;

import io.codelens.cli.CliApp;
import io.codelens.web.WebApp;

/**
 * CodeLens entry point.
 *
 * <pre>
 *   codelens &lt;github-repo-url&gt;   CLI mode: analyze once, write Markdown reports
 *   codelens --serve [--port N]   Web mode: local UI with live agent streaming
 * </pre>
 */
public class CodeLens {

    public static void main(String[] args) {
        if (args.length > 0 && "radar".equals(args[0])) {
            System.exit(io.codelens.radar.RadarApp.run(
                    java.util.Arrays.copyOfRange(args, 1, args.length)));
            return;
        }
        for (String arg : args) {
            if ("--serve".equals(arg)) {
                WebApp.run(args);
                return;
            }
        }
        System.exit(CliApp.run(args));
    }
}
