package io.github.some_example_name.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;

import io.github.some_example_name.old.core.log.CrashReport;
import io.github.some_example_name.old.game.MyGame;
import kotlin.Unit;

import java.awt.Dimension;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.awt.Toolkit;

/**
 * Launches the desktop (LWJGL3) application.
 */
public class Lwjgl3Launcher {
//    public static void main(String[] args) {
//        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
//            Logger.logCrash(throwable);
//            System.exit(1);
//        });
//        if (StartupHelper.startNewJvmIfRequired()) return;
//        createApplication();
//    }
    public static void main(String[] args) {
        installCrashReporter();
        if (StartupHelper.startNewJvmIfRequired())
            return; // This handles macOS support and helps on Windows.
        createApplication();
    }

    /**
     * Необработанное исключение в scene2d роняет приложение — и вместе с ним всё, что
     * игрок успел сделать. Здесь мы дописываем к стектрейсу маршрут по экранам и хвост
     * журнала действий, то есть сценарий, по которому баг воспроизводится.
     */
    private static void installCrashReporter() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String report = CrashReport.INSTANCE.build(throwable);
                System.err.println(report);
                Files.write(
                    Paths.get(CRASH_REPORT_FILE),
                    report.getBytes(StandardCharsets.UTF_8)
                );
                System.err.println("[Genomeia] Отчёт сохранён: "
                    + Paths.get(CRASH_REPORT_FILE).toAbsolutePath());
            } catch (Throwable reportingFailure) {
                // Сборщик отчёта не имеет права стать причиной второй ошибки:
                // исходный стектрейс важнее любых наших украшений.
                throwable.printStackTrace();
            }
            System.exit(1);
        });
    }

    private static final String CRASH_REPORT_FILE = "crash-report.txt";

    private static Lwjgl3Application createApplication() {
        return new Lwjgl3Application(new MyGame(new DesktopFileProvider(), null), getDefaultConfiguration());
    }

    private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("Genomeia");

        // === НАДЁЖНЫЙ способ получить реальное разрешение экрана ===
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int screenWidth = screenSize.width;
        int screenHeight = screenSize.height;

        System.out.println("[Lwjgl3Launcher] Detected screen resolution: " + screenWidth + " × " + screenHeight);

        // По умолчанию — 1300×1300
        int windowSize = 1300;

        // Если хотя бы одна сторона экрана меньше 1300 — делаем окно квадратным
        if (screenWidth < 1300 || screenHeight < 1300) {
            int minDimension = Math.min(screenWidth, screenHeight);
            windowSize = (int) (minDimension * 0.8f);

            // Минимальная защита — не меньше 640 пикселей
            if (windowSize < 640) {
                windowSize = 640;
            }
        }

        // === САМАЯ ВАЖНАЯ ЗАЩИТА ===
        // Убеждаемся, что окно точно помещается с учётом заголовка окна, рамок и панели задач
        int maxSafeWidth = screenWidth - 40;   // небольшой отступ по ширине
        int maxSafeHeight = screenHeight - 120; // отступ сверху (заголовок + панель задач)
        windowSize = Math.min(windowSize, Math.min(maxSafeWidth, maxSafeHeight));

        System.out.println("[Lwjgl3Launcher] Final window size: " + windowSize + " × " + windowSize);

        configuration.setWindowedMode(windowSize, windowSize);

        configuration.useVsync(true);
        //// Limits FPS to the refresh rate of the currently active monitor, plus 1 to try to match fractional
        //// refresh rates. The Vsync setting above should limit the actual FPS to match the monitor.
        configuration.setForegroundFPS(Lwjgl3ApplicationConfiguration.getDisplayMode().refreshRate + 1);
        //// If you remove the above line and set Vsync to false, you can get unlimited FPS, which can be
        //// useful for testing performance, but can also be very stressful to some hardware.
        //// You may also need to configure GPU drivers to fully disable Vsync; this can cause screen tearing.
//        configuration.useVsync(false);
//        configuration.setForegroundFPS(60);
        // Размер окна можно задать снаружи: -Dgenomeia.width=1280 -Dgenomeia.height=720.
        // Нужно для проверки вёрстки на разных разрешениях без правки кода.
        int windowWidth = Integer.getInteger("genomeia.width", 1300);
        int windowHeight = Integer.getInteger("genomeia.height", 1300);
        configuration.setWindowedMode(windowWidth, windowHeight);
//        configuration.setFullscreenMode(Lwjgl3ApplicationConfiguration.getDisplayMode());
        // GLES 3.0 feature set (no SSBO). Desktop GL 3.3 for uintBitsToFloat / core profile.
        configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 0);
//        configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL32, 3, 2);

        //// You can change these files; they are in lwjgl3/src/main/resources/ .
        configuration.setWindowIcon("libgdx128.png", "libgdx64.png", "libgdx32.png", "libgdx16.png");
        return configuration;
    }
}
