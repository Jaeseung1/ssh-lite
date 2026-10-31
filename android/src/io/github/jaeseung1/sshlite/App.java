package io.github.jaeseung1.sshlite;

import android.app.Application;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** 앱이 갑자기 멈추면 이유를 파일에 남겨 둠. 다음에 켤 때 MainActivity 가 보여줌 (복사해서 보낼 수 있게) */
public class App extends Application {
    static File crashFile(android.content.Context c) {
        return new File(c.getFilesDir(), "crash.txt");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringWriter w = new StringWriter();
                    e.printStackTrace(new PrintWriter(w));
                    String text = "SSH Lite " + Core.APP_VERSION + " / Android " + android.os.Build.VERSION.RELEASE
                            + " / " + android.os.Build.MODEL + "\n스레드: " + t.getName() + "\n\n" + w;
                    try (FileOutputStream f = new FileOutputStream(crashFile(App.this))) {
                        f.write(text.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Throwable ignored) {
                }
                if (original != null) original.uncaughtException(t, e);
            }
        });
    }
}
