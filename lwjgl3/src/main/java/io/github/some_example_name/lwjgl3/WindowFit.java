package io.github.some_example_name.lwjgl3;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Graphics;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * Окно целиком в рабочей области монитора: заголовок с кнопками «свернуть / развернуть /
 * закрыть» и нижний край всегда на экране.
 *
 * ЗАЧЕМ
 * -----
 * Окно 900x900 на ноутбуке 1366x768 уходило вниз и вверх за экран, и игрок не видел кнопок
 * заголовка. Прежняя защита в лаунчере считала по {@code Toolkit.getScreenSize()}: он не
 * знает про панель задач и рамку окна, а при масштабе Windows 125-150% отдаёт другие числа,
 * чем GLFW. Здесь всё берётся у самого GLFW: рабочая область монитора (без панели задач) и
 * реальные размеры рамки уже созданного окна.
 *
 * Вызывается при старте и после выхода из полного экрана ({@code PlatformWindow.fitWindow}).
 * Окно, которое и так помещается, не двигается: поправляется только то, что вылезает.
 */
final class WindowFit {

    private WindowFit() {
    }

    /** Окно текущего приложения. Звать из потока рендера, когда окно уже создано. */
    static void fit() {
        if (!(Gdx.graphics instanceof Lwjgl3Graphics)) return;
        long window = ((Lwjgl3Graphics) Gdx.graphics).getWindow().getWindowHandle();
        if (GLFW.glfwGetWindowMonitor(window) != 0L) return; // полный экран — вмещать нечего
        if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer x = stack.mallocInt(1), y = stack.mallocInt(1);
            IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
            GLFW.glfwGetWindowPos(window, x, y);
            GLFW.glfwGetWindowSize(window, w, h);

            int[] area = workArea(stack, x.get(0), y.get(0), w.get(0), h.get(0));
            if (area == null) return;

            IntBuffer l = stack.mallocInt(1), t = stack.mallocInt(1);
            IntBuffer r = stack.mallocInt(1), b = stack.mallocInt(1);
            GLFW.glfwGetWindowFrameSize(window, l, t, r, b);

            int width = Math.min(w.get(0), Math.max(MIN_SIZE, area[2] - l.get(0) - r.get(0)));
            int height = Math.min(h.get(0), Math.max(MIN_SIZE, area[3] - t.get(0) - b.get(0)));

            // Левый/верхний край — не левее/выше рабочей области (там заголовок), правый/нижний — не дальше.
            int left = area[0] + l.get(0);
            int top = area[1] + t.get(0);
            int px = clamp(x.get(0), left, Math.max(left, area[0] + area[2] - r.get(0) - width));
            int py = clamp(y.get(0), top, Math.max(top, area[1] + area[3] - b.get(0) - height));

            if (width != w.get(0) || height != h.get(0)) GLFW.glfwSetWindowSize(window, width, height);
            if (px != x.get(0) || py != y.get(0)) GLFW.glfwSetWindowPos(window, px, py);
            if (width != w.get(0) || height != h.get(0) || px != x.get(0) || py != y.get(0)) {
                System.out.println("[WindowFit] окно " + w.get(0) + "x" + h.get(0) + " @" + x.get(0) + "," + y.get(0)
                    + " -> " + width + "x" + height + " @" + px + "," + py
                    + " (рабочая область " + area[2] + "x" + area[3] + " @" + area[0] + "," + area[1] + ")");
            }
        }
    }

    /** Рабочая область монитора, на котором больше всего окна; если окно ни на одном, то основного. */
    private static int[] workArea(MemoryStack stack, int wx, int wy, int ww, int wh) {
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        int[] best = null;
        long bestOverlap = -1;
        IntBuffer ax = stack.mallocInt(1), ay = stack.mallocInt(1), aw = stack.mallocInt(1), ah = stack.mallocInt(1);
        if (monitors != null) {
            for (int i = 0; i < monitors.remaining(); i++) {
                GLFW.glfwGetMonitorWorkarea(monitors.get(i), ax, ay, aw, ah);
                long overlapW = Math.min(wx + ww, ax.get(0) + aw.get(0)) - Math.max(wx, ax.get(0));
                long overlapH = Math.min(wy + wh, ay.get(0) + ah.get(0)) - Math.max(wy, ay.get(0));
                long overlap = overlapW > 0 && overlapH > 0 ? overlapW * overlapH : 0;
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = new int[]{ax.get(0), ay.get(0), aw.get(0), ah.get(0)};
                }
            }
        }
        if (best != null && bestOverlap > 0) return best;
        long primary = GLFW.glfwGetPrimaryMonitor();
        if (primary == 0L) return best;
        GLFW.glfwGetMonitorWorkarea(primary, ax, ay, aw, ah);
        return new int[]{ax.get(0), ay.get(0), aw.get(0), ah.get(0)};
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Меньше этого окно не сжимаем, даже если рабочая область меньше (вёрстка ломается). */
    private static final int MIN_SIZE = 480;
}
