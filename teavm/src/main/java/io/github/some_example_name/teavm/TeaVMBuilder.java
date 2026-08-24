package io.github.some_example_name.teavm;

import com.github.xpenatan.gdx.teavm.backends.shared.config.AssetFileHandle;
import com.github.xpenatan.gdx.teavm.backends.shared.config.compiler.TeaCompiler;
import com.github.xpenatan.gdx.teavm.backends.web.config.backend.WebBackend;
import java.io.File;
import org.teavm.tooling.TeaVMSourceFilePolicy;
import org.teavm.tooling.sources.DirectorySourceFileProvider;
import org.teavm.vm.TeaVMOptimizationLevel;

/** Builds the TeaVM/HTML application. */
public class TeaVMBuilder {
    public static void main(String[] args) {
        // Typically set by the Gradle task, but can also be set here or with the command-line arg "debug"
        boolean debug = false;
        // Typically set by the Gradle task, but can also be set here or with the command-line arg "run"
        boolean startJetty = false;
        for (String arg : args) {
            if ("debug".equals(arg)) debug = true;
            else if ("run".equals(arg)) startJetty = true;
        }
        new TeaCompiler(
            new WebBackend()
                .setHtmlWidth(800) /* Change this to fit your game's requirements. */
                .setHtmlHeight(600) /* Change this to fit your game's requirements. */
                .setHtmlTitle("Genomeia")
//                .setWebAssembly(true) /* Uncomment this line to use WASM output instead of JavaScript output. */
                .setStartJettyAfterBuild(startJetty)
                .setJettyPort(8080)
        )
            .addAssets(new AssetFileHandle("../assets"))
            .addAssets(new AssetFileHandle("com/kotcrab/vis/ui/skin/x1", com.badlogic.gdx.Files.FileType.Classpath))
            .addAssets(new AssetFileHandle("com/kotcrab/vis/ui/skin/x2", com.badlogic.gdx.Files.FileType.Classpath))
            .addAssets(new AssetFileHandle("com/kotcrab/vis/ui/widget/color/internal", com.badlogic.gdx.Files.FileType.Classpath))
            .addAssets(new AssetFileHandle("com/kotcrab/vis/ui/i18n/ButtonBar.properties", com.badlogic.gdx.Files.FileType.Classpath))
            .addAssets(new AssetFileHandle("com/kotcrab/vis/ui/i18n/ColorPicker.properties", com.badlogic.gdx.Files.FileType.Classpath))
            .addAssets(new AssetFileHandle("com/kotcrab/vis/ui/i18n/Common.properties", com.badlogic.gdx.Files.FileType.Classpath))
            .addAssets(new AssetFileHandle("com/kotcrab/vis/ui/i18n/Dialogs.properties", com.badlogic.gdx.Files.FileType.Classpath))
            .addAssets(new AssetFileHandle("com/kotcrab/vis/ui/i18n/FileChooser.properties", com.badlogic.gdx.Files.FileType.Classpath))
            .addAssets(new AssetFileHandle("com/kotcrab/vis/ui/i18n/TabbedPane.properties", com.badlogic.gdx.Files.FileType.Classpath))
            .setOptimizationLevel(debug ? TeaVMOptimizationLevel.SIMPLE : TeaVMOptimizationLevel.ADVANCED)
            .setMainClass(TeaVMLauncher.class.getName())
            .setObfuscated(!debug)
            .setDebugInformationGenerated(debug)
            .setSourceMapsFileGenerated(debug)
            .setSourceFilePolicy(TeaVMSourceFilePolicy.COPY)
            // Исходники для source maps в отладочной сборке. Путь именно src/main/java:
            // каталога src/main/kotlin в этом проекте нет и не было, так что прежний
            // провайдер молча не давал ничего — в отладке вместо кода была пустота.
            .addSourceFileProvider(new DirectorySourceFileProvider(new File("../core/src/main/java/")))
            .addSourceFileProvider(new DirectorySourceFileProvider(new File("../render/src/main/java/")))
            // You can also register any classes or packages that require reflection here:
            .addReflectionClass("com.kotcrab.vis.ui.widget")
            .addReflectionClass("com.kotcrab.vis.ui.widget.spinner")
            .addReflectionClass("com.kotcrab.vis.ui.widget.dialog")
            .addReflectionClass("com.kotcrab.vis.ui.widget.color")
            .addReflectionClass("com.kotcrab.vis.ui.widget.tabbedpane")
            .addReflectionClass("com.kotcrab.vis.ui.widget.toast")
            .addReflectionClass("com.kotcrab.vis.ui.util.form")
            .addReflectionClass("com.kotcrab.vis.ui.Sizes")
            .addReflectionClass("com.kotcrab.vis.ui.util.adapter.SimpleListAdapter")
            .addReflectionClass("com.kotcrab.vis.ui.**Style")
            .build(new File("build/dist"));
    }
}
