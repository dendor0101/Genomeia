package io.github.some_example_name.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.example.concurrent.DefaultSimulationSystemFactory;
import com.example.concurrent.JvmConcurrentFactory;

import io.github.some_example_name.old.core.concurrent.Platform;
import io.github.some_example_name.old.game.MyGame;
import kotlin.Unit;

import java.awt.Dimension;
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
        if (StartupHelper.startNewJvmIfRequired())
            return; // This handles macOS support and helps on Windows.
        createApplication();
    }

    private static Lwjgl3Application createApplication() {
        Platform.INSTANCE.setConcurrent(new JvmConcurrentFactory());
        Platform.INSTANCE.setSimulationFactory(new DefaultSimulationSystemFactory());
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
        configuration.setWindowedMode(1300, 1300);
//        configuration.setFullscreenMode(Lwjgl3ApplicationConfiguration.getDisplayMode());
        // GLES 3.0 feature set (no SSBO). Desktop GL 3.3 for uintBitsToFloat / core profile.
        configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 0);
//        configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL32, 3, 2);

        //// You can change these files; they are in lwjgl3/src/main/resources/ .
        configuration.setWindowIcon("libgdx128.png", "libgdx64.png", "libgdx32.png", "libgdx16.png");
        return configuration;
    }
}
