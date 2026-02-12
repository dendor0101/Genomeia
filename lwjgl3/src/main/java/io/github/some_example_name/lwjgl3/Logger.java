package io.github.some_example_name.lwjgl3;

import java.io.*;
import java.time.LocalDateTime;

public class Logger {
    private static final File baseDir = getJarDir();
    private static final File crashFile = new File(baseDir, "crash.log").getAbsoluteFile();

    public static void logCrash(Throwable throwable) {
        try (PrintWriter out = new PrintWriter(new FileWriter(crashFile, true))) {
            out.println("Crash at: " + LocalDateTime.now());
            throwable.printStackTrace(out);
            out.println(); // пустая строка для читаемости
        } catch (Exception e) {
            System.out.println("Failed to write crash log: " + e.getMessage());
        }
    }

    public static void openCrashLogInEditor() {
        try {
            if (!crashFile.exists()) {
                // сразу создать пустой файл, чтобы редактор открыл его
                crashFile.createNewFile();
            }
            String absolutePath = crashFile.getAbsolutePath();
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("notepad.exe", absolutePath).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", absolutePath).start();
            } else {
                new ProcessBuilder("xdg-open", absolutePath).start();
            }
        } catch (Exception e) {
            System.out.println("Failed to open crash log: " + e.getMessage());
        }
    }

    private static File getJarDir() {
        try {
            String path = Logger.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(path);
            return jarFile.isDirectory() ? jarFile : jarFile.getParentFile();
        } catch (Exception e) {
            return new File(System.getProperty("user.dir"));
        }
    }
}
