package com.knowcli.cli;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;

final class PlanDecisionMenu {
    private static final int ESC = 27;
    private static final int CTRL_C = 3;
    private static final int CTRL_D = 4;
    private static final int WINDOWS_EXTENDED_KEY = 224;
    private static final int WINDOWS_NULL_KEY = 0;
    private static final int WINDOWS_UP = 72;
    private static final int WINDOWS_DOWN = 80;

    private static final Option[] OPTIONS = {
            new Option(PlanDecision.EXECUTE, "执行计划"),
            new Option(PlanDecision.SUPPLEMENT, "补充需求"),
            new Option(PlanDecision.CANCEL, "取消计划")
    };

    private final Terminal terminal;
    private final KeyReader keyReader;
    private final PrintWriter writer;

    PlanDecisionMenu(Terminal terminal) {
        this(
                Objects.requireNonNull(terminal, "terminal").reader()::read,
                terminal.writer(),
                terminal
        );
    }

    PlanDecisionMenu(KeyReader keyReader, PrintWriter writer) {
        this(keyReader, writer, null);
    }

    private PlanDecisionMenu(KeyReader keyReader, PrintWriter writer, Terminal terminal) {
        this.keyReader = Objects.requireNonNull(keyReader, "keyReader");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.terminal = terminal;
    }

    PlanDecision choose() {
        Attributes originalAttributes = null;
        try {
            if (terminal != null) {
                originalAttributes = terminal.enterRawMode();
            }

            int selectedIndex = 0;
            render(selectedIndex, false);
            while (true) {
                InputKey key = readKey();
                switch (key) {
                    case UP -> {
                        selectedIndex = (selectedIndex + OPTIONS.length - 1) % OPTIONS.length;
                        render(selectedIndex, true);
                    }
                    case DOWN -> {
                        selectedIndex = (selectedIndex + 1) % OPTIONS.length;
                        render(selectedIndex, true);
                    }
                    case ENTER -> {
                        return OPTIONS[selectedIndex].decision();
                    }
                    case CANCEL -> {
                        return PlanDecision.CANCEL;
                    }
                    case OTHER -> {
                        // Ignore unrelated keys so accidental typing does not change the decision.
                    }
                }
            }
        } catch (IOException | RuntimeException exception) {
            return PlanDecision.CANCEL;
        } finally {
            if (terminal != null && originalAttributes != null) {
                try {
                    terminal.setAttributes(originalAttributes);
                } catch (RuntimeException ignored) {
                    // Best-effort terminal restoration; the caller should still get a safe cancel result.
                }
            }
            writer.println();
            writer.flush();
        }
    }

    private void render(int selectedIndex, boolean repaint) {
        if (repaint) {
            clearMenu();
        }
        writer.println("计划已生成。使用 ↑/↓ 选择，按 Enter 确认。");
        for (int index = 0; index < OPTIONS.length; index++) {
            String marker = index == selectedIndex ? ">" : " ";
            writer.println(marker + " " + OPTIONS[index].label());
        }
        writer.flush();
    }

    private void clearMenu() {
        for (int index = 0; index < OPTIONS.length + 1; index++) {
            writer.print("\033[F");
            writer.print("\033[2K");
        }
    }

    private InputKey readKey() throws IOException {
        int key = keyReader.read();
        if (key == -1 || key == CTRL_C || key == CTRL_D) {
            return InputKey.CANCEL;
        }
        if (key == '\r' || key == '\n') {
            return InputKey.ENTER;
        }
        if (key == ESC) {
            return readAnsiEscapeKey();
        }
        if (key == WINDOWS_EXTENDED_KEY || key == WINDOWS_NULL_KEY) {
            return readWindowsExtendedKey();
        }
        return InputKey.OTHER;
    }

    private InputKey readAnsiEscapeKey() throws IOException {
        int second = keyReader.read();
        if (second != '[' && second != 'O') {
            return InputKey.OTHER;
        }
        int third = keyReader.read();
        if (third == 'A') {
            return InputKey.UP;
        }
        if (third == 'B') {
            return InputKey.DOWN;
        }
        return InputKey.OTHER;
    }

    private InputKey readWindowsExtendedKey() throws IOException {
        int second = keyReader.read();
        if (second == WINDOWS_UP) {
            return InputKey.UP;
        }
        if (second == WINDOWS_DOWN) {
            return InputKey.DOWN;
        }
        return InputKey.OTHER;
    }

    @FunctionalInterface
    interface KeyReader {
        int read() throws IOException;
    }

    private enum InputKey {
        UP,
        DOWN,
        ENTER,
        CANCEL,
        OTHER
    }

    private record Option(PlanDecision decision, String label) {
    }
}