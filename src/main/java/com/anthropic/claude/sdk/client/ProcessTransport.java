package com.anthropic.claude.sdk.client;

import com.anthropic.claude.sdk.errors.CLINotFoundException;
import com.anthropic.claude.sdk.errors.ClaudeSDKException;
import com.anthropic.claude.sdk.errors.ProcessException;
import com.anthropic.claude.sdk.types.ClaudeAgentOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Transport implementation using a subprocess to communicate with Claude Code CLI.
 */
public class ProcessTransport implements Transport {

    private static final Logger logger = LoggerFactory.getLogger(ProcessTransport.class);

    private final ClaudeAgentOptions options;
    @Nullable
    private Process process;
    @Nullable
    private BufferedReader reader;

    public ProcessTransport(@Nullable ClaudeAgentOptions options) {
        this.options = options != null ? options : ClaudeAgentOptions.builder().build();
    }

    @Override
    public void start() throws ClaudeSDKException {
        String cliPath = determineCliPath();
        List<String> command = buildCommand(cliPath);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            if (options.getCwd() != null) {
                pb.directory(options.getCwd().toFile());
            }

            // Set environment variables
            pb.environment().put("CLAUDE_CODE_ENTRYPOINT", "sdk-java");

            // Add custom environment variables from options
            if (options.getEnv() != null) {
                pb.environment().putAll(options.getEnv());
            }

            logger.debug("Starting Claude Code CLI: {}", String.join(" ", command));
            process = pb.start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        } catch (IOException e) {
            throw new ClaudeSDKException("Failed to start Claude Code CLI", e);
        }
    }

    /**
     * Determines the CLI path to use.
     * First checks if cliPath was explicitly provided in options,
     * otherwise searches for the CLI in common locations.
     *
     * @return the path to the Claude CLI executable
     * @throws CLINotFoundException if CLI cannot be found
     */
    private String determineCliPath() throws CLINotFoundException {
        // If user provided explicit CLI path, use it
        if (options.getCliPath() != null) {
            String explicitPath = options.getCliPath().toString();
            if (isExecutable(explicitPath)) {
                logger.debug("Using explicitly configured CLI path: {}", explicitPath);
                return explicitPath;
            } else {
                throw new CLINotFoundException(explicitPath);
            }
        }

        // Otherwise search for CLI
        return findClaudeCLI();
    }

    private String findClaudeCLI() throws CLINotFoundException {
        // First, try to find in PATH using 'which' command (like shutil.which in Python)
        try {
            Process which = Runtime.getRuntime().exec(new String[]{"which", "claude"});
            BufferedReader whichReader = new BufferedReader(new InputStreamReader(which.getInputStream()));
            String line = whichReader.readLine();
            whichReader.close();
            which.waitFor();

            if (line != null && !line.isEmpty()) {
                Path p = Paths.get(line.trim());
                if (Files.exists(p) && Files.isRegularFile(p)) {
                    return line.trim();
                }
            }
        } catch (IOException | InterruptedException e) {
            // Continue to fallback locations
        }

        // Fallback to checking common installation locations (matching Python SDK exactly)
        String homeDir = System.getProperty("user.home");
        String[] possiblePaths = {
                homeDir + "/.npm-global/bin/claude",
                "/usr/local/bin/claude",
                homeDir + "/.local/bin/claude",
                homeDir + "/node_modules/.bin/claude",
                homeDir + "/.yarn/bin/claude",
                homeDir + "/.claude/local/claude"
        };

        for (String path : possiblePaths) {
            if (isExecutable(path)) {
                return path;
            }
        }

        throw new CLINotFoundException(null);
    }

    private boolean isExecutable(String path) {
        try {
            Path p = Paths.get(path);
            return Files.exists(p) && Files.isRegularFile(p) && Files.isExecutable(p);
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> buildCommand(String cliPath) {
        List<String> command = new ArrayList<>();
        command.add(cliPath);
        command.add("sdk");
        command.add("--streaming");

        return command;
    }

    @Override
    public OutputStream getOutputStream() {
        if (process == null) {
            throw new IllegalStateException("Transport not started");
        }
        return process.getOutputStream();
    }

    @Override
    public BufferedReader getReader() {
        if (reader == null) {
            throw new IllegalStateException("Transport not started");
        }
        return reader;
    }

    @Override
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    @Override
    @Nullable
    public Integer getExitCode() {
        if (process == null || process.isAlive()) {
            return null;
        }
        return process.exitValue();
    }

    @Override
    public void close() {
        if (process != null) {
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                logger.warn("Error closing reader", e);
            }
        }
    }
}
