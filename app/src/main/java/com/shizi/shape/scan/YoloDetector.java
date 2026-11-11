package com.shizi.shape.scan;

import android.content.res.AssetManager;
import android.graphics.Bitmap;

/**
 * ncnn 推理入口。模型随 assets 打包，首次调用 init 时加载。
 */
public final class YoloDetector {

    public class Obj {
        public float x;
        public float y;
        public float w;
        public float h;
        public String label;
        public float prob;
    }

    public native boolean initShape(AssetManager assets);

    public native Obj[] detectShape(Bitmap bitmap, boolean useGpu);

    public native boolean initColor(AssetManager assets);

    public native Obj[] detectColor(Bitmap bitmap, boolean useGpu);

    static {
        System.loadLibrary("yolov5ncnn");
    }
}
