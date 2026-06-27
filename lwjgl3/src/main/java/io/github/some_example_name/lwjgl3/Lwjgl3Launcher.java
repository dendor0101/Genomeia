package io.github.some_example_name.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.video.SilentVideoPlayer;

import io.github.some_example_name.old.ui.screens.MyGame;

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
        return new Lwjgl3Application(new MyGame(new DesktopFileProvider(), null, null, null, SilentVideoPlayer::new), getDefaultConfiguration());
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
//        configuration.setFullscreenMode(Lwjgl3ApplicationConfiguration.getDisplayMode());

        // OpenGL context selection — must stay cross-platform (Windows / Linux / macOS / Android).
        //
        // GL32 emulation gives a desktop core-profile context and a non-null Gdx.gl31, which the GPU
        // particle/pheromone renderers (ShaderManagerLibgdxApi) need for OpenGL 4.3 SSBOs. That works on
        // Windows/Linux, where drivers are lenient about libGDX's GLES2-style default shaders.
        //
        // macOS is different: every 3.2+ context is a strict core-profile context, and macOS caps OpenGL
        // at 4.1 (no SSBOs at all). Under GL32 emulation libGDX's default SpriteBatch/Scene2D shader
        // (attribute/varying, no #version) fails to compile there, crashing the app at the menu screen.
        // So on macOS we fall back to GL20 emulation: the default shaders compile and the menu/editor run.
        // See ShaderManager selection in DIGameGlobalContainer for the macOS render-path caveat.
        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        if (isMac) {
            configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL30, 3, 2);
        } else {
            configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.GL32, 3, 2);
        }
        //// You can change these files; they are in lwjgl3/src/main/resources/ .
        configuration.setWindowIcon("libgdx128.png", "libgdx64.png", "libgdx32.png", "libgdx16.png");
        return configuration;
    }
}
