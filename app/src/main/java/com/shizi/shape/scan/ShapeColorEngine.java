package com.shizi.shape.scan;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 形状与颜色识别：两个模型分别出结果，再按检测框的重合度把「形状」和「它里面的颜色」配对。
 */
public final class ShapeColorEngine implements Scanner {

    private static final float IOU_THRESHOLD = 0.5f;

    private final AssetManager assets;
    private final YoloDetector detector = new YoloDetector();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean closed;
    private boolean ready;

    public ShapeColorEngine(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public void scan(final Bitmap bitmap, final Callback callback) {
        if (closed) {
            return;
        }
        worker.execute(() -> {
            try {
                if (!ready) {
                    if (!detector.initShape(assets) || !detector.initColor(assets)) {
                        fail(callback, new IllegalStateException("model load failed"));
                        return;
                    }
                    ready = true;
                }
                YoloDetector.Obj[] shapes = detector.detectShape(bitmap, false);
                YoloDetector.Obj[] colors = detector.detectColor(bitmap, false);

                final List<Detect> detects = combine(shapes, colors);
                final ScanOutcome outcome = new ScanOutcome(detects, describe(detects));
                final Bitmap overlay = BoxPainter.paint(bitmap, detects);
                main.post(() -> {
                    if (!closed) {
                        callback.onResult(outcome, overlay);
                    }
                });
            } catch (RuntimeException | OutOfMemoryError error) {
                fail(callback, error);
            }
        });
    }

    private void fail(final Callback callback, Throwable error) {
        main.post(() -> {
            if (!closed) {
                callback.onError(error);
            }
        });
    }

    /**
     * 每个形状找重合度最高的颜色框配对。两个模型是分开训练的，只能靠位置对齐。
     */
    private static List<Detect> combine(YoloDetector.Obj[] shapes, YoloDetector.Obj[] colors) {
        List<Detect> detects = new ArrayList<>();
        for (YoloDetector.Obj shape : shapes) {
            YoloDetector.Obj matched = bestColor(shape, colors);
            String name = shapeName(shape.label) + "（"
                    + (matched != null ? colorName(matched.label) : "颜色未知") + "）";
            float score = matched != null ? (shape.prob + matched.prob) / 2f : shape.prob;
            detects.add(new Detect(name, null, score, toRect(shape)));
        }
        if (shapes.length == 0) {
            for (YoloDetector.Obj color : colors) {
                detects.add(new Detect("未知形状（" + colorName(color.label) + "）",
                        null, color.prob, toRect(color)));
            }
        }
        return detects;
    }

    private static YoloDetector.Obj bestColor(YoloDetector.Obj shape, YoloDetector.Obj[] colors) {
        YoloDetector.Obj best = null;
        float maxIou = 0f;
        for (YoloDetector.Obj color : colors) {
            float iou = iou(shape, color);
            if (iou > IOU_THRESHOLD && iou > maxIou) {
                maxIou = iou;
                best = color;
            }
        }
        return best;
    }

    private static float iou(YoloDetector.Obj a, YoloDetector.Obj b) {
        float left = Math.max(a.x, b.x);
        float top = Math.max(a.y, b.y);
        float right = Math.min(a.x + a.w, b.x + b.w);
        float bottom = Math.min(a.y + a.h, b.y + b.h);
        if (right <= left || bottom <= top) {
            return 0f;
        }
        float overlap = (right - left) * (bottom - top);
        float union = a.w * a.h + b.w * b.h - overlap;
        return union <= 0f ? 0f : overlap / union;
    }

    private static Rect toRect(YoloDetector.Obj obj) {
        return new Rect((int) obj.x, (int) obj.y,
                (int) (obj.x + obj.w), (int) (obj.y + obj.h));
    }

    private static String shapeName(String label) {
        if (label == null) {
            return "未知形状";
        }
        switch (label) {
            case "circle":
                return "圆形";
            case "rectangle":
                return "矩形";
            case "diamond":
                return "菱形";
            case "star":
                return "五角星";
            case "triangle":
                return "三角形";
            default:
                return label;
        }
    }

    private static String colorName(String label) {
        if (label == null) {
            return "未知颜色";
        }
        switch (label) {
            case "red":
                return "红色";
            case "green":
                return "绿色";
            case "blue":
                return "蓝色";
            case "yellow":
                return "黄色";
            case "magenta":
                return "品红";
            case "cyan":
                return "青色";
            case "black":
                return "黑色";
            case "white":
                return "白色";
            default:
                return label;
        }
    }

    private static String describe(List<Detect> detects) {
        if (detects.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < detects.size(); i++) {
            Detect detect = detects.get(i);
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(i + 1).append(". ").append(detect.label);
            if (detect.score > 0f) {
                builder.append("   置信度 ")
                        .append(String.format(Locale.US, "%.1f%%", detect.score * 100f));
            }
        }
        return builder.toString();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        worker.shutdownNow();
    }
}
