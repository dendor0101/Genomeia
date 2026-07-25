package io.github.some_example_name.teavm;

import com.github.xpenatan.gdx.teavm.backends.web.WebApplicationConfiguration;
import com.github.xpenatan.gdx.teavm.backends.web.WebApplication;

import io.github.some_example_name.old.core.concurrent.Platform;
import io.github.some_example_name.old.game.MyGame;

/**
 * Launches the TeaVM/HTML application.
 */
public class TeaVMLauncher {
    public static void main(String[] args) {
        WebApplicationConfiguration config = new WebApplicationConfiguration("canvas");
        //// If width and height are each greater than 0, then the app will use a fixed size.
        //config.width = 640;
        //config.height = 480;
        //// If width and height are both 0, then the app will use all available space.
        System.out.println("=== MY REAL GAME IS STARTING ===");
        config.width = 0;
        config.height = 0;
        config.useGL30 = true;
        Platform.INSTANCE.setConcurrent(new WebConcurrentFactory());
        new WebApplication(new MyGame(new WebFileProvider(), null), config);
    }
}
