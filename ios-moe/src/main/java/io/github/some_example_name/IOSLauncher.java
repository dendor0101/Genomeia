package io.github.some_example_name;

import apple.uikit.c.UIKit;
import com.badlogic.gdx.backends.iosmoe.IOSApplication;
import com.badlogic.gdx.backends.iosmoe.IOSApplicationConfiguration;
import com.example.concurrent.DefaultSimulationSystemFactory;
import com.example.concurrent.JvmConcurrentFactory;

import org.moe.natj.general.Pointer;
import io.github.some_example_name.old.core.concurrent.Platform;
import io.github.some_example_name.old.game.MyGame;

/** Launches the iOS (Multi-Os Engine) application. */
public class IOSLauncher extends IOSApplication.Delegate {
    protected IOSLauncher(Pointer peer) {
        super(peer);
    }

    @Override
    protected IOSApplication createApplication() {
        IOSApplicationConfiguration configuration = new IOSApplicationConfiguration();
        Platform.INSTANCE.setConcurrent(new JvmConcurrentFactory());
        Platform.INSTANCE.setSimulationFactory(new DefaultSimulationSystemFactory());
        return new IOSApplication(new MyGame(new IosFileProvider(), null), configuration);
    }

    public static void main(String[] argv) {
        UIKit.UIApplicationMain(0, null, null, IOSLauncher.class.getName());
    }
}
