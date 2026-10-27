package com.hook.capture;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "DeepSeekHook";
    private static final String SAVE_PATH = "/storage/emulated/0/Download/DeepSeek_Hooked.txt";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        // 只 Hook 官方 APP
        if (!lpparam.packageName.equals("com.deepseek.chat")) return;

        Log.i(TAG, "✅ 成功注入官方 DeepSeek APP！");
        writeToFile("=== 成功注入 DeepSeek ===");

        // 1. Hook OkHttp (真实拦截链)
        try {
            Class<?> chainClass = XposedHelpers.findClass("okhttp3.internal.http.RealInterceptorChain", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(chainClass, "proceed", "okhttp3.Request", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object request = param.args[0];
                    if (request == null) return;
                    try {
                        Object urlObj = XposedHelpers.callMethod(request, "url");
                        String url = urlObj.toString();
                        if (!url.contains("/api/v0/")) return;

                        StringBuilder sb = new StringBuilder();
                        sb.append("\n\n[").append(getTime()).append("] OkHttp 请求拦截:\n");
                        sb.append("URL: ").append(url).append("\n");

                        // Headers
                        Object headers = XposedHelpers.callMethod(request, "headers");
                        int size = (int) XposedHelpers.callMethod(headers, "size");
                        for (int i = 0; i < size; i++) {
                            String name = (String) XposedHelpers.callMethod(headers, "name", i);
                            String value = (String) XposedHelpers.callMethod(headers, "value", i);
                            sb.append(name).append(": ").append(value).append("\n");
                        }

                        // Body
                        Object body = XposedHelpers.callMethod(request, "body");
                        if (body != null) {
                            Class<?> bufferClass = XposedHelpers.findClass("okio.Buffer", lpparam.classLoader);
                            Object buffer = XposedHelpers.newInstance(bufferClass);
                            XposedHelpers.callMethod(body, "writeTo", buffer);
                            String bodyStr = (String) XposedHelpers.callMethod(buffer, "readUtf8");
                            sb.append("Body: ").append(bodyStr).append("\n");
                        }

                        Log.i(TAG, sb.toString());
                        writeToFile(sb.toString());
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Hook OkHttp 失败 (可能未使用)", t);
        }

        // 2. Hook 底层 HttpURLConnection (备用)
        try {
            XposedHelpers.findAndHookMethod("java.net.URL", lpparam.classLoader, "openConnection", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object conn = param.getResult();
                    if (conn != null && conn.getClass().getName().contains("HttpURLConnection")) {
                        XposedHelpers.findAndHookMethod(conn.getClass(), "connect", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param2) throws Throwable {
                                try {
                                    Object urlObj = XposedHelpers.callMethod(conn, "getURL");
                                    String url = urlObj.toString();
                                    if (!url.contains("/api/v0/")) return;

                                    String msg = "\n\n[" + getTime() + "] HttpURLConnection 请求拦截:\nURL: " + url + "\n";
                                    Log.i(TAG, msg);
                                    writeToFile(msg);
                                } catch (Throwable ignored) {}
                            }
                        });
                    }
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Hook HttpURLConnection 失败", t);
        }
    }

    private void writeToFile(String text) {
        try {
            File f = new File(SAVE_PATH);
            FileOutputStream fos = new FileOutputStream(f, true);
            OutputStreamWriter osw = new OutputStreamWriter(fos);
            osw.write(text);
            osw.close();
            fos.close();
        } catch (Throwable t) {
            Log.e(TAG, "写文件失败: " + t.getMessage());
        }
    }

    private String getTime() {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
    }
}
