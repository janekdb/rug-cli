package com.atomist.rug.cli.command.shell;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;

public class OperationCompleter implements Completer {

    private static final List<String> COMMANDS = Arrays
            .asList(new String[] { "edit", "generate", "execute", "executo-remote", "describe" });

    private long timestamp = -1;

    private ReadContext ctx = null;

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line.words().size() >= 1) {

            String command = line.words().get(0);

            if (COMMANDS.contains(command)) {

                init();

                switch (command) {
                case "edit":
                    completeBasedOnJsonpathMatches("editors", line.words(), candidates);
                    break;
                case "generate":
                    completeBasedOnJsonpathMatches("generators", line.words(), candidates);
                    break;
                case "execute":
                    completeBasedOnJsonpathMatches("executors", line.words(), candidates);
                    break;
                case "execute-remote":
                    completeBasedOnJsonpathMatches("executors", line.words(), candidates);
                    break;
                case "describe":
                    if (line.words().size() >= 2) {
                        String subCommand = line.words().get(1);
                        switch (subCommand) {
                        case "editor":
                            completeBasedOnJsonpathMatches("editors", line.words(), candidates);
                            break;
                        case "generator":
                            completeBasedOnJsonpathMatches("generators", line.words(), candidates);
                            break;
                        case "executor":
                            completeBasedOnJsonpathMatches("executors", line.words(), candidates);
                            break;
                        case "reviewer":
                            completeBasedOnJsonpathMatches("reviewers", line.words(), candidates);
                            break;
                        }
                    }
                    break;
                }
            }
        }
    }

    private void completeBasedOnJsonpathMatches(String kind, List<String> words,
            List<Candidate> candidates) {
        if (ctx != null) {
            List<String> names = ctx.read(String.format("$.%s[*].name", kind));
            Optional<String> name = names.stream().filter(v -> words.contains(v.toString()))
                    .map(v -> v.toString()).findFirst();
            if (name.isPresent()) {
                List<String> parameterNames = ctx.read(String
                        .format("$.%s[?(@.name=='%s')].parameters[*].name", kind, name.get()));
                parameterNames.stream()
                        .filter(p -> !kind.equals("generators")
                                || (kind.equals("generators") && !p.equals("project_name")))
                        .filter(p -> !words.stream().filter(w -> w.startsWith(p + "=")).findAny()
                                .isPresent())
                        .forEach(n -> candidates.add(
                                new Candidate(n + "=", n, "Parameters", null, null, null, false)));
            }
            else {
                names.forEach(n -> candidates.add(
                        new Candidate(n, n, StringUtils.capitalize(kind), null, null, null, true)));
            }
        }
    }

    private void init() {
        if (ShellUtils.SHELL_OPERATIONS.exists()
                && ShellUtils.SHELL_OPERATIONS.lastModified() > timestamp) {
            this.timestamp = ShellUtils.SHELL_OPERATIONS.lastModified();
            try {
                this.ctx = JsonPath.parse(FileUtils.readFileToString(ShellUtils.SHELL_OPERATIONS,
                        StandardCharsets.ISO_8859_1));

            }
            catch (IOException e) {
            }
        }
    }
}
