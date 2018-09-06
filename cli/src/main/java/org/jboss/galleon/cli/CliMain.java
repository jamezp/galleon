/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.galleon.cli;

import java.io.File;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import org.jboss.galleon.cli.config.Configuration;
import java.util.logging.LogManager;
import org.aesh.command.AeshCommandRuntimeBuilder;
import org.aesh.command.Command;
import org.aesh.command.CommandException;
import org.aesh.command.CommandRuntime;
import org.aesh.command.impl.registry.AeshCommandRegistryBuilder;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.invocation.CommandInvocationProvider;
import org.aesh.command.parser.CommandLineParserException;
import org.aesh.command.registry.CommandRegistry;
import org.aesh.command.registry.MutableCommandRegistry;
import org.aesh.command.settings.Settings;
import org.aesh.command.settings.SettingsBuilder;
import org.aesh.readline.ReadlineConsole;
import org.aesh.utils.Config;
import org.jboss.galleon.cli.cmd.CliErrors;
import org.jboss.galleon.cli.terminal.CliShellInvocationProvider;
import org.jboss.galleon.cli.terminal.CliTerminalConnection;
import org.jboss.galleon.cli.terminal.InteractiveInvocationProvider;
import org.jboss.galleon.cli.terminal.OutputInvocationProvider;

/**
 *
 * @author Alexey Loubyansky
 */
public class CliMain {


    public static void main(String[] args) {
        Arguments arguments = Arguments.parseArguments(args);
        boolean interactive = arguments.getCommand() == null && arguments.getScriptFile() == null;
        PmSession pmSession = null;
        try {
            pmSession = new PmSession(Configuration.parse(arguments.getOptions()), interactive);
            CliTerminalConnection connection = new CliTerminalConnection();
            if (arguments.isHelp()) {
                try {
                    CommandRuntime runtime = newRuntime(pmSession, connection);
                    connection.getOutput().println(HelpSupport.getToolHelp(pmSession, runtime.getCommandRegistry(), connection.getShell()));
                } finally {
                    connection.close();
                }
                return;
            }
            if (interactive) {
                startInteractive(pmSession, connection);
            } else {
                try {
                    runCommands(pmSession, connection, arguments);
                } finally {
                    connection.close();
                }
            }
        } catch (Throwable ex) {
            try {
                if (pmSession != null) {
                    PmSessionCommand.printException(pmSession, ex);
                } else if (ex instanceof RuntimeException) {
                    ex.printStackTrace(System.err);
                } else {
                    System.err.println(ex);
                }
            } finally {
                System.exit(1);
            }
        }
    }

    private static void runCommands(PmSession pmSession, CliTerminalConnection connection, Arguments arguments) throws Throwable {
        CommandRuntime runtime = newRuntime(pmSession, connection);
        pmSession.getUniverse().disableBackgroundResolution();
        pmSession.throwException();

        try {
            if (arguments.getScriptFile() != null) {
                String file = arguments.getScriptFile();
                if (file.isEmpty()) {
                    throw new Exception(CliErrors.emptyOption(Arguments.SCRIPT_FILE));
                }
                File f = new File(file);
                if (!f.isAbsolute()) {
                    String parent = runtime.getAeshContext().getCurrentWorkingDirectory().getAbsolutePath();
                    f = new File(parent, file);
                }
                if (!f.exists()) {
                    throw new Exception(CliErrors.unknownFile(f.getAbsolutePath()));
                } else if (!f.isFile()) {
                    throw new Exception(CliErrors.notFile(f.getAbsolutePath()));
                }
                List<String> commands = Files.readAllLines(f.toPath());
                for (String cmd : commands) {
                    if (cmd.startsWith("#")) {
                        continue;
                    }
                    connection.getOutput().println(Config.getLineSeparator() + cmd);
                    runtime.executeCommand(cmd);
                }
            } else if (arguments.getCommand() != null) {
                runtime.executeCommand(arguments.getCommand());
            }
        } catch (Throwable ex) {
            // Remove the wrapper used when re-throwing the exception.
            if (ex instanceof CommandException) {
                ex = ex.getCause();
            }
            throw ex;
        }
    }

    private static void startInteractive(PmSession pmSession, CliTerminalConnection connection) throws Throwable {
        pmSession.setOut(connection.getOutput());
        pmSession.setErr(connection.getOutput());
        pmSession.cleanupLayoutCache();
        // Side effect is to resolve plugins.
        pmSession.getUniverse().resolveBuiltinUniverse();

        Settings settings = buildSettings(pmSession, connection, new InteractiveInvocationProvider(pmSession));

        ReadlineConsole console = new ReadlineConsole(settings);

        pmSession.setAeshContext(console.context());
        console.setPrompt(pmSession.buildPrompt());

        // connection is automatically closed when exit command or Ctrl-D
        console.start();
    }

    private static boolean overrideLogging() {
        // If the current log manager is not java.util.logging.LogManager the user has specifically overridden this
        // and we should not override logging
        return LogManager.getLogManager().getClass() == LogManager.class &&
                // The user has specified a class to configure logging, we shouldn't override it
                System.getProperty("java.util.logging.config.class") == null &&
                // The user has specified a specific logging configuration and again we shouldn't override it
                System.getProperty("java.util.logging.config.file") == null;
    }

    private static Settings buildSettings(PmSession pmSession, CliTerminalConnection connection,
            CommandInvocationProvider<PmCommandInvocation> provider) throws CommandLineParserException {
        Settings settings = SettingsBuilder.builder().
                logging(overrideLogging()).
                commandRegistry(buildRegistry(pmSession)).
                enableOperatorParser(true).
                persistHistory(true).
                commandActivatorProvider(pmSession).
                historyFile(pmSession.getPmConfiguration().getHistoryFile()).
                echoCtrl(false).
                enableExport(false).
                enableAlias(false).
                completerInvocationProvider(pmSession).
                optionActivatorProvider(pmSession).
                commandInvocationProvider(provider).
                connection(connection == null ? null : connection.getConnection()).
                build();
        return settings;
    }

    private static CommandRegistry buildRegistry(PmSession pmSession) throws CommandLineParserException {
        MutableCommandRegistry registry = (MutableCommandRegistry) new AeshCommandRegistryBuilder().create();
        ToolModes modes = ToolModes.getModes(pmSession, registry);
        pmSession.setModes(modes);
        return registry;
    }

    // A runtime attached to cli terminal connection to execute a single command.
    private static CommandRuntime newRuntime(PmSession session, CliTerminalConnection connection) throws CommandLineParserException {
        return newRuntime(session, connection, connection.getOutput(), new CliShellInvocationProvider(session, connection));
    }

    // Used by tests. Tests don't rely on advanced output/input.
    public static CommandRuntime<? extends Command, ? extends CommandInvocation> newRuntime(PmSession session,
            PrintStream out) throws CommandLineParserException {
        return newRuntime(session, null, out, new OutputInvocationProvider(session));
    }

    private static CommandRuntime<? extends Command, ? extends CommandInvocation> newRuntime(PmSession session,
            CliTerminalConnection connection, PrintStream out,
            CommandInvocationProvider<PmCommandInvocation> provider) throws CommandLineParserException {
        AeshCommandRuntimeBuilder builder = AeshCommandRuntimeBuilder.builder();
        builder.settings(buildSettings(session, connection, provider));
        session.setOut(out);
        session.setErr(out);
        CommandRuntime runtime = builder.build();
        session.setAeshContext(runtime.getAeshContext());
        return runtime;
    }
}
