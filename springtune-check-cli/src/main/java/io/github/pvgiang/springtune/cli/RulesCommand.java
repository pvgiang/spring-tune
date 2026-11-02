package io.github.pvgiang.springtune.cli;

import io.github.pvgiang.springtune.check.CheckEngine;
import io.github.pvgiang.springtune.model.RuleDescriptor;
import java.io.PrintWriter;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "rules", mixinStandardHelpOptions = true, description = "List the available rules.")
final class RulesCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        PrintWriter out = spec.commandLine().getOut();
        for (RuleDescriptor rule : new CheckEngine().descriptors()) {
            out.printf("%-14s %-8s %s%n               %s%n", rule.id(), rule.defaultSeverity(), rule.title(),
                    rule.docsUrl());
        }
        out.flush();
    }
}
