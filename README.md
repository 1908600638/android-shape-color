# 图形形状和颜色识别

形状 5 类：圆形、矩形、菱形、五角星、三角形。
颜色 8 类：红、绿、蓝、黄、品红、青、黑、白。

## 功能

- 摄像头实时预览，点「识别」抓取当前这一帧
- 一次可识别多个图形，每个图形输出「形状（颜色）」和置信度
- 结果在图上画框标注，点一下画面回到实时预览

## 实现原理

### 整体流程

```
摄像头抓帧（JPEG）
   │
   ▼
ImageSource.fromJpeg()  ──▶  Bitmap（最长边 ≤ 2048，已旋正）
   │
   ▼
ShapeColorEngine.scan()
   ├── 首次调用：initShape() + initColor() 从 assets 加载两个模型
   │
   ├── detectShape(bitmap) ──▶ 形状框（圆/矩/菱/星/三角）
   └── detectColor(bitmap) ──▶ 颜色框（8 色）
   │
   ▼
两个结果按检测框重合度（IOU > 0.5）配对
   │
   ▼
List<Detect>：每个形状拼成「圆形（红色）」+ 平均置信度
   │
   ▼
BoxPainter 画框 ──▶ 预览层 + 结果文本
```

### 1. 模型信息

形状和颜色是两个**独立训练**的 YOLOv5 模型：

| 模型文件 | 类别数 | 输出 |
|---|---|---|
| `shape.bin` / `shape.param` | 5 | circle、rectangle、diamond、star、triangle |
| `TFT-B-color-sim-opt.bin` / `.param` | 8 | red、green、blue、yellow、magenta、cyan、black、white |

### 2. 形状与颜色配对

两个模型各自出框，同一个图形上，形状框和颜色框位置会略有偏差。用 **IOU（交并比）** 判断是不是同一个物体：

```
IOU = 交集面积 / (形状框面积 + 颜色框面积 - 交集面积)
```

遍历所有形状，对每个形状找出 IOU 最大且 **> 0.5** 的颜色框作为它的颜色。配不上颜色的形状显示为「颜色未知」。

> 两个模型的坐标原点都是左上角（`x/y` 是左上角，`w/h` 是宽高），所以 IOU 直接按轴对齐矩形算。

### 3. 推理引擎

用的是**腾讯 ncnn**——为手机端优化的 C++ 推理框架，以静态库形式随仓库附带，通过 CMake 编译成 `libyolov5ncnn.so`。

Java 层通过 JNI 调用，暴露四个方法：

```java
boolean initShape(AssetManager)      // 加载形状模型
Obj[]   detectShape(Bitmap, boolean) // 形状推理
boolean initColor(AssetManager)      // 加载颜色模型
Obj[]   detectColor(Bitmap, boolean) // 颜色推理
```

### 4. YOLOv5 后处理

`yolov5ncnn_jni.cpp` 里是完整的 YOLOv5 后处理：

| 步骤 | 说明 |
|---|---|
| letterbox | 等比缩放到 640×640，空白补灰边，避免拉伸变形 |
| 前向 | ncnn 输出 3 个尺度特征图（stride 8 / 16 / 32） |
| 解码 | 每个格子按 3 个 anchor 解出 `cx, cy, w, h` 和类别置信度 |
| NMS | 去掉重叠框，阈值 0.45 |
| 反 letterbox | 把框坐标按同样比例映射回原图尺寸 |

置信度阈值 0.25。

> 这个 JNI 文件是从一个包含 7 个模型的工程里裁出来的，只保留了形状和颜色这两个模型的 Init/Detect，从 2000 多行裁到 631 行，其余 5 个模型的代码全部删除。

## 代码架构

```
app/src/main/
├── jni/
│   ├── CMakeLists.txt                    链接 ncnn 并编译 libyolov5ncnn.so
│   ├── yolov5ncnn_jni.cpp         631 行  推理 + YOLOv5 后处理（形状 + 颜色两个模型）
│   └── ncnn-20230816-android-vulkan/      ncnn 静态库与头文件（31 MB，第三方）
├── assets/
│   ├── shape.bin                  26.8 MB 形状模型权重
│   ├── shape.param                       形状模型结构
│   ├── TFT-B-color-sim-opt.bin    26.8 MB 颜色模型权重
│   └── TFT-B-color-sim-opt.param         颜色模型结构
└── java/com/shizi/shape/
    ├── MainActivity.java          178 行  摄像头预览、抓帧、结果展示
    ├── img/
    │   └── ImageSource.java        74 行  JPEG 解码 + 降采样 + 旋正
    ├── scan/
    │   ├── Scanner.java            13 行  引擎接口
    │   ├── Detect.java             17 行  一条结果
    │   ├── ScanOutcome.java        17 行  结果集合 + 汇总文本
    │   ├── BoxPainter.java         84 行  画框与标签
    │   ├── YoloDetector.java       23 行  native 方法声明
    │   └── ShapeColorEngine.java  180 行  ★ 双模型推理 + 配对逻辑
    └── util/
        └── UiKit.java              11 行  Toast
```

### 各文件职责

| 文件 | 职责 |
|---|---|
| `MainActivity` | 相机权限 → CameraX 预览 → `ImageCapture` 抓帧 → 调引擎 → 显示标注图和文字 |
| `ImageSource` | JPEG 解码、降采样到最长边 2048、按元数据旋转角旋正 |
| `Scanner` | 引擎接口，`scan(bitmap, callback)` + `close()`，让 UI 层不依赖具体实现 |
| `Detect` | 一条结果：`label`（框上短标签）、`note`（详细说明）、`score`（置信度）、`box`（位置） |
| `ScanOutcome` | 结果列表 + 汇总多行文本 |
| `BoxPainter` | 把每个 `Detect` 的框和标签画到图片副本上 |
| `YoloDetector` | 声明 4 个 native 方法，`System.loadLibrary("yolov5ncnn")` |
| `ShapeColorEngine` | **核心**：懒加载两个模型、跑两次推理、IOU 配对、英文标签转中文 |
| `yolov5ncnn_jni.cpp` | **核心**：YOLOv5 完整推理与后处理实现 |

### 核心代码位置

| 位置 | 作用 |
|---|---|
| `ShapeColorEngine.scan()` | 懒加载模型 + 两次推理 + 组装结果 |
| `ShapeColorEngine.combine()` | **配对核心**：为每个形状找对应的颜色框 |
| `ShapeColorEngine.bestColor()` | 取 IOU 最大且超过阈值的颜色框 |
| `ShapeColorEngine.iou()` | 交并比计算 |
| `ShapeColorEngine.shapeName()` / `colorName()` | 模型输出的英文标签转中文 |
| `yolov5ncnn_jni.cpp` → `initShape` / `initColor` | 从 assets 读模型并建 `ncnn::Net` |
| `yolov5ncnn_jni.cpp` → `detectShape` / `detectColor` | letterbox、前向、解码、NMS、坐标还原 |
| `yolov5ncnn_jni.cpp` → `generate_proposals` | 单尺度候选框解码 |
| `yolov5ncnn_jni.cpp` → `nms_sorted_bboxes` | 非极大值抑制 |

## 环境要求

| 项 | 版本 |
|---|---|
| Android Studio | 2021.3.1（Dolphin）及以上 |
| JDK | 11 或 17（Android Studio 自带的 JBR 即可） |
| Android Gradle Plugin | 7.3.1 |
| Gradle | 7.4 |
| compileSdk / targetSdk | 34 |
| minSdk | 24（Android 7.0） |
| **NDK** | **23.1.7779620** |
| **CMake** | **3.10.2** |
| ABI | 只编 **arm64-v8a** |
| 设备 | ARM64 真机 + 摄像头 |

NDK 和 CMake 在 Android Studio 的 SDK Manager → SDK Tools 里勾选安装。版本对不上时改 `app/build.gradle` 里的 `cmake { version }` 和 SDK Manager 里已装的版本即可。

> 只出 arm64-v8a 是因为附带的 ncnn 静态库只有 arm64-v8a 和 armeabi-v7a 两套，为控制体积只留了 64 位。要 32 位就把 `abiFilters` 改成 `'arm64-v8a', 'armeabi-v7a'`。

## 编译运行

1. 用 Android Studio 打开本目录
2. 确认 SDK Manager 里装了 **NDK 23.1.7779620** 和 **CMake 3.10.2**
3. 首次打开会自动生成 `local.properties`（记录本机 SDK 路径），不要提交到版本库
4. 连接 ARM64 真机，点 Run
5. 首次启动请求相机权限，允许后看到实时画面
6. 对准图形，点「识别」

命令行构建：

```bash
./gradlew assembleDebug
```

首次构建会先跑 CMake 编译 C++，比纯 Java 工程慢（约 20 秒）。产物在 `app/build/outputs/apk/debug/app-debug.apk`。

> 工程放在中文路径下时 AGP 会报非 ASCII 路径错误，本工程已在 `gradle.properties` 加了一行 `android.overridePathCheck=true`。

### 常见编译问题

| 现象 | 原因与处理 |
|---|---|
| `CMake Error: could not find ncnn` | NDK/CMake 版本不对，或 `ncnn-20230816-android-vulkan/<abi>/lib/cmake/ncnn` 目录缺失 |
| `error: expected '}'` | `yolov5ncnn_jni.cpp` 末尾的匿名命名空间右括号被误删，文件末尾应有两级 `}` |
| `Duplicate class kotlin.*` | Kotlin stdlib 版本冲突，`app/build.gradle` 末尾的 `configurations.configureEach { exclude ... }` 已经处理 |

## 依赖

| 依赖 | 用途 | 许可 |
|---|---|---|
| [ncnn](https://github.com/Tencent/ncnn) 20230816（含 Vulkan） | 端侧推理框架，静态库与头文件随仓库附带 | BSD-3-Clause，版权所有 Tencent，全文见 `app/src/main/jni/ncnn-20230816-android-vulkan/LICENSE` |
| CameraX 1.3.0（core / camera2 / lifecycle / view） | 摄像头预览与抓帧 | Apache-2.0 |
| AndroidX AppCompat、Activity、Core | 基础 UI | Apache-2.0 |
| `shape.bin` / `TFT-B-color-sim-opt.bin` | YOLOv5 训练得到的形状、颜色模型 | 仅用于学习演示 |
