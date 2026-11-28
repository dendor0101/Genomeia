package io.github.some_example_name.teavm;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;

/** Системный выбор файла в браузере: скрытый input + FileReader, текст читается как UTF-8. */
public final class WebFilePicker {

    public interface Callback extends JSObject {
        void onPicked(String name, String text);
    }

    @JSBody(params = {"accept", "callback"}, script =
        "var input = document.createElement('input');" +
        "input.type = 'file';" +
        "input.accept = accept;" +
        "input.onchange = function() {" +
        "  var f = input.files && input.files[0];" +
        "  if (!f) { callback.onPicked(null, null); return; }" +
        "  var r = new FileReader();" +
        "  r.onload = function() { callback.onPicked(f.name, r.result); };" +
        "  r.onerror = function() { callback.onPicked(f.name, null); };" +
        "  r.readAsText(f, 'UTF-8');" +
        "};" +
        "input.click();")
    public static native void pick(String accept, Callback callback);

    @JSBody(params = {"fileName", "text"}, script =
        "var blob = new Blob([text], {type: 'text/plain;charset=utf-8'});" +
        "var url = URL.createObjectURL(blob);" +
        "var a = document.createElement('a');" +
        "a.href = url;" +
        "a.download = fileName;" +
        "document.body.appendChild(a);" +
        "a.click();" +
        "document.body.removeChild(a);" +
        "setTimeout(function() { URL.revokeObjectURL(url); }, 0);")
    public static native void save(String fileName, String text);

    private WebFilePicker() {}
}
