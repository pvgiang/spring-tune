package io.github.winkytek.springtune.cli;

import io.github.winkytek.springtune.check.CheckEngine;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * {@code springtune} entry point.
 *
 * <p>Exit codes: {@code 0} no blocking findings, {@code 1} findings at or above {@code --fail-on},
 * {@code 2} invalid usage or configuration.
 */
@Command(name = "springtune", mixinStandardHelpOptions = true, versionProvider = SpringTuneCli.Version.class,
        description = "Catch the Spring Boot configuration that will take you down, before production does.",
        subcommands = {CheckCommand.class, RulesCommand.class})
public final class SpringTuneCli implements Runnable {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FINDINGS = 1;
    public static final int EXIT_USAGE = 2;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    public static CommandLine commandLine() {
        return new CommandLine(new SpringTuneCli())
                .setExitCodeExceptionMapper(ex -> EXIT_USAGE)
                .setParameterExceptionHandler((ex, args) -> {
                    ex.getCommandLine().getErr().println(ex.getMessage());
                    ex.getCommandLine().usage(ex.getCommandLine().getErr());
                    return EXIT_USAGE;
                });
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {CheckEngine.TOOL + " " + CheckEngine.version()};
        }
    }
}
