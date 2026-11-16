package edu.jlu.facegeometry;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.facemesh.FaceMesh;
import com.google.mlkit.vision.facemesh.FaceMeshDetection;
import com.google.mlkit.vision.facemesh.FaceMeshDetector;
import com.google.mlkit.vision.facemesh.FaceMeshPoint;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private static final int PAPER = 0xfff2f5ee;
    private static final int INK = 0xff172b25;
    private static final int MUTED = 0xff4d6258;
    private static final int GREEN = 0xff176456;
    private static final int DARK = 0xff142b24;
    private static final int MINT = 0xffd5f5df;
    private static final int STONE = 0xffe5ede3;
    private static final int WHITE = Color.WHITE;
    private static final int FILL = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MAX_IMAGE_SIDE = 1440;

    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor();
    private final Executor direct = Runnable::run;
    private Executor main;
    private FaceMeshDetector detector;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture capture;
    private ImageAnalysis analysis;
    private ActivityResultLauncher<String[]> picker;
    private ActivityResultLauncher<String> permission;

    private enum Mode { EMPTY, PHOTO, LIVE }

    private Mode mode = Mode.EMPTY;
    private int operation;
    private volatile boolean destroyed;
    private long nextLiveFrame;
    private boolean takingPhoto;
    private boolean showMesh = true;
    private Geometry.Profile selected = Geometry.Profile.GENERAL;
    private Geometry.Features features;
    private PreviewView preview;
    private PhotoView photoView;
    private LinearLayout emptyView;
    private LinearLayout result;
    private LinearLayout metrics;
    private TextView hint;
    private TextView source;
    private TextView score;
    private TextView scoreNote;
    private TextView cameraAction;
    private TextView photoAction;
    private TextView meshAction;
    private TextView profileNote;
    private final TextView[] profiles = new TextView[Geometry.Profile.values().length];

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        main = ContextCompat.getMainExecutor(this);
        detector = FaceMeshDetection.getClient();
        picker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPicked);
        permission = registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                allowed -> {
                    if (allowed) {
                        startCamera();
                    } else {
                        hint.setText("相机权限未开启。可以选取相册图片，或在系统设置中授权相机。");
                    }
                });
        if (state != null) {
            int profile = state.getInt("profile");
            selected = Geometry.Profile.values()[profile];
        }
        createScreen();
    }

    private void createScreen() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(PAPER);
        LinearLayout body = column();
        body.setPadding(dp(20), dp(17), dp(20), dp(24));
        page.addView(body, new ScrollView.LayoutParams(FILL, WRAP));

        TextView title = label("面部几何实验室", 29, INK, true);
        body.addView(title);
        place(body, label("颜值打分课程设计 · 图像仅在本机处理", 13, MUTED, false), 5);

        LinearLayout imagePanel = column();
        imagePanel.setPadding(dp(12), dp(11), dp(12), dp(12));
        imagePanel.setBackground(round(DARK, 22));
        place(body, imagePanel, 17);
        LinearLayout imageHeader = row();
        source = label("图像输入", 13, MINT, true);
        imageHeader.addView(source, new LinearLayout.LayoutParams(0, WRAP, 1));
        meshAction = action("隐藏关键点", DARK, MINT);
        meshAction.setMinimumHeight(dp(34));
        meshAction.setVisibility(View.GONE);
        meshAction.setOnClickListener(v -> {
            showMesh = !showMesh;
            photoView.setShowPoints(showMesh);
            meshAction.setText(showMesh ? "隐藏关键点" : "显示关键点");
        });
        imageHeader.addView(meshAction);
        imagePanel.addView(imageHeader);

        FrameLayout display = new FrameLayout(this);
        display.setBackground(round(0xff0d1d18, 14));
        display.setClipToOutline(true);
        LinearLayout.LayoutParams displayParams = new LinearLayout.LayoutParams(FILL, dp(260));
        displayParams.topMargin = dp(8);
        imagePanel.addView(display, displayParams);
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        display.addView(preview, new FrameLayout.LayoutParams(FILL, FILL));
        photoView = new PhotoView(this);
        display.addView(photoView, new FrameLayout.LayoutParams(FILL, FILL));
        emptyView = column();
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(16), 0, dp(16), 0);
        TextView emptyTitle = label("选择一张正面人像", 20, WHITE, true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyView.addView(emptyTitle);
        TextView emptyHint = label("单人、光线均匀、脸部完整", 13, MINT, false);
        emptyHint.setGravity(Gravity.CENTER);
        place(emptyView, emptyHint, 8);
        display.addView(emptyView, new FrameLayout.LayoutParams(FILL, FILL));
        setMode(Mode.EMPTY);

        LinearLayout actions = row();
        TextView album = action("相册选图", WHITE, GREEN);
        album.setOnClickListener(v -> picker.launch(new String[]{"image/*"}));
        addButton(actions, album, 0);
        cameraAction = action("打开相机", GREEN, WHITE);
        cameraAction.setOnClickListener(v -> onCameraAction());
        addButton(actions, cameraAction, 8);
        photoAction = action("拍照", GREEN, WHITE);
        photoAction.setEnabled(false);
        photoAction.setVisibility(View.GONE);
        photoAction.setOnClickListener(v -> takePhoto());
        addButton(actions, photoAction, 8);
        place(body, actions, 12);
        hint = label("打开相机后可实时检测，也可以拍照查看关键点。", 13, MUTED, false);
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        place(body, hint, 9);

        TextView profileLabel = label("评分依据", 17, INK, true);
        place(body, profileLabel, 19);
        LinearLayout profileRow = row();
        for (Geometry.Profile profile : Geometry.Profile.values()) {
            TextView choice = action(profile.title, STONE, INK);
            choice.setOnClickListener(v -> {
                selected = profile;
                renderProfile();
                renderResult(features);
            });
            profiles[profile.ordinal()] = choice;
            addButton(profileRow, choice, profile.ordinal() == 0 ? 0 : 8);
        }
        place(body, profileRow, 8);
        profileNote = label("", 12, MUTED, false);
        place(body, profileNote, 8);

        result = column();
        result.setPadding(dp(15), dp(14), dp(15), dp(14));
        result.setBackground(round(WHITE, 20));
        place(body, result, 14);
        result.addView(label("几何规则评分", 14, GREEN, true));
        score = label("—", 39, INK, true);
        place(result, score, 2);
        scoreNote = label("", 13, MUTED, false);
        place(result, scoreNote, 2);
        metrics = column();
        place(result, metrics, 5);
        place(body, label("分值只反映所选几何规则的匹配程度。颜值偏好带有主观性，"
                + "结果与个人价值无关。", 12, MUTED, false), 12);
        setContentView(page);
        renderProfile();
        renderResult(null);
    }

    private void renderProfile() {
        for (Geometry.Profile profile : Geometry.Profile.values()) {
            TextView view = profiles[profile.ordinal()];
            boolean active = selected == profile;
            view.setSelected(active);
            view.setTextColor(active ? WHITE : INK);
            view.setBackground(buttonBackground(active ? GREEN : STONE));
        }
        String note = switch (selected) {
            case GENERAL -> "按通用几何权重计算。模板由你选择。";
            case MASCULINE -> "男性模板提高下颌轮廓和眉眼间距的权重。";
            case FEMININE -> "女性模板提高眼型、唇形和面部外形的权重。";
        };
        profileNote.setText(note + " 权重来自课程演示设定。");
    }

    private void renderResult(Geometry.Features newFeatures) {
        if (mode == Mode.LIVE && newFeatures == null && features == null
                && scoreNote.length() > 0) {
            return;
        }
        features = newFeatures;
        metrics.removeAllViews();
        if (features == null) {
            score.setText("—");
            scoreNote.setText("检测到一张清晰的正面人脸后显示结果。");
            return;
        }
        score.setText(String.format(Locale.CHINA, "%.1f / 10", features.score(selected)));
        scoreNote.setText("各指标显示实测值和对应权重。条形越长，越接近该规则的参考值。");
        for (Geometry.Metric metric : features.metrics(selected)) {
            if (mode == Mode.LIVE) {
                continue;
            }
            addMetric(metric);
        }
        if (mode == Mode.LIVE) {
            scoreNote.setText("实时评分会随镜头画面更新。拍照后可查看九项分值依据。");
        }
    }

    private void addMetric(Geometry.Metric metric) {
        LinearLayout item = column();
        LinearLayout headings = row();
        TextView name = label(metric.name(), 14, INK, true);
        headings.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView value = label(metric.value(), 12, MUTED, false);
        value.setGravity(Gravity.END);
        headings.addView(value, new LinearLayout.LayoutParams(0, WRAP, 1));
        item.addView(headings);
        LinearLayout barRow = row();
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress((int) Math.round(metric.score() * 100));
        bar.setProgressTintList(ColorStateList.valueOf(GREEN));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(STONE));
        bar.setContentDescription(metric.name() + "匹配度 " + bar.getProgress() + "%");
        barRow.addView(bar, new LinearLayout.LayoutParams(0, dp(7), 1));
        TextView weight = label("权重 " + metric.weight(), 11, MUTED, false);
        LinearLayout.LayoutParams weightParams = new LinearLayout.LayoutParams(WRAP, WRAP);
        weightParams.leftMargin = dp(8);
        barRow.addView(weight, weightParams);
        place(item, barRow, 4);
        place(metrics, item, 11);
    }

    private void setMode(Mode newMode) {
        mode = newMode;
        preview.setVisibility(newMode == Mode.LIVE ? View.VISIBLE : View.GONE);
        photoView.setVisibility(newMode == Mode.PHOTO ? View.VISIBLE : View.GONE);
        emptyView.setVisibility(newMode == Mode.EMPTY ? View.VISIBLE : View.GONE);
        meshAction.setVisibility(newMode == Mode.PHOTO ? View.VISIBLE : View.GONE);
        source.setText(switch (newMode) {
            case EMPTY -> "图像输入";
            case PHOTO -> "静态图像";
            case LIVE -> "实时检测";
        });
        if (cameraAction != null) {
            cameraAction.setText(newMode == Mode.LIVE ? "关闭相机" : "打开相机");
            photoAction.setVisibility(newMode == Mode.LIVE ? View.VISIBLE : View.GONE);
            photoAction.setEnabled(false);
        }
    }

    private void onPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        stopCamera();
        int current = ++operation;
        setMode(Mode.EMPTY);
        photoView.setPhoto(null, null, null);
        renderResult(null);
        hint.setText("正在读取图片…");
        imageWorker.execute(() -> {
            try {
                Bitmap bitmap = ImageDecoder.decodeBitmap(
                        ImageDecoder.createSource(getContentResolver(), uri), (decoder, info, src) -> {
                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                            int width = info.getSize().getWidth();
                            int height = info.getSize().getHeight();
                            float scale = MAX_IMAGE_SIDE / (float) Math.max(width, height);
                            if (scale < 1) {
                                decoder.setTargetSize(Math.max(1, Math.round(width * scale)),
                                        Math.max(1, Math.round(height * scale)));
                            }
                        });
                main.execute(() -> {
                    if (isCurrent(current)) {
                        analyzePhoto(bitmap, current);
                    }
                });
            } catch (Exception error) {
                main.execute(() -> {
                    if (isCurrent(current)) {
                        hint.setText("无法读取图片。请选择设备可打开的 JPG、PNG 或其他相册图片。");
                    }
                });
            }
        });
    }

    private void onCameraAction() {
        if (mode == Mode.LIVE) {
            stopCamera();
            setMode(Mode.EMPTY);
            renderResult(null);
            hint.setText("相机已关闭。");
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permission.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        int current = ++operation;
        setMode(Mode.LIVE);
        photoView.setPhoto(null, null, null);
        renderResult(null);
        hint.setText("正在打开相机…");
        nextLiveFrame = 0;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (!isCurrent(current) || mode != Mode.LIVE) {
                return;
            }
            try {
                cameraProvider = future.get();
                CameraSelector facing = CameraSelector.DEFAULT_FRONT_CAMERA;
                if (!cameraProvider.hasCamera(facing)) {
                    facing = CameraSelector.DEFAULT_BACK_CAMERA;
                }
                Preview cameraPreview = new Preview.Builder().build();
                cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                capture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build();
                analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                cameraProvider.bindToLifecycle(this, facing, cameraPreview, capture, analysis);
                analysis.setAnalyzer(main, proxy -> analyzeFrame(proxy, current));
                photoAction.setEnabled(true);
                hint.setText("请面向镜头，将脸部放入画面。");
            } catch (Exception error) {
                stopCamera();
                setMode(Mode.EMPTY);
                renderResult(null);
                hint.setText("相机打开失败。请检查设备相机，或使用相册选图。");
            }
        }, main);
    }

    @ExperimentalGetImage
    private void analyzeFrame(ImageProxy proxy, int current) {
        long now = SystemClock.elapsedRealtime();
        Image image = proxy.getImage();
        if (!isCurrent(current) || mode != Mode.LIVE || takingPhoto
                || image == null || now < nextLiveFrame) {
            proxy.close();
            return;
        }
        nextLiveFrame = now + 250;
        try {
            InputImage input = InputImage.fromMediaImage(image, proxy.getImageInfo().getRotationDegrees());
            detector.process(input).addOnCompleteListener(direct, task -> {
                proxy.close();
                main.execute(() -> {
                    if (isCurrent(current) && mode == Mode.LIVE && !takingPhoto) {
                        if (task.isSuccessful()) {
                            showDetection(task.getResult(), null);
                        } else {
                            renderResult(null);
                            hint.setText("实时检测暂时不可用。可以拍照或选图分析。");
                        }
                    }
                });
            });
        } catch (RuntimeException error) {
            proxy.close();
            renderResult(null);
            hint.setText("实时画面无法分析。可以拍照或选图分析。");
        }
    }

    private void takePhoto() {
        if (capture == null || takingPhoto || mode != Mode.LIVE) {
            return;
        }
        int current = operation;
        takingPhoto = true;
        photoAction.setEnabled(false);
        hint.setText("正在拍照…");
        capture.takePicture(imageWorker, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                Bitmap bitmap;
                try {
                    Bitmap raw = image.toBitmap();
                    Matrix rotation = new Matrix();
                    rotation.postRotate(image.getImageInfo().getRotationDegrees());
                    float scale = Math.min(1,
                            MAX_IMAGE_SIDE / (float) Math.max(raw.getWidth(), raw.getHeight()));
                    rotation.postScale(scale, scale);
                    bitmap = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(),
                            rotation, true);
                } catch (RuntimeException error) {
                    main.execute(() -> captureFailed(current));
                    return;
                } finally {
                    image.close();
                }
                main.execute(() -> {
                    if (isCurrent(current)) {
                        stopCamera();
                        analyzePhoto(bitmap, operation);
                    }
                });
            }

            @Override
            public void onError(@NonNull ImageCaptureException error) {
                main.execute(() -> captureFailed(current));
            }
        });
    }

    private void captureFailed(int current) {
        if (isCurrent(current)) {
            takingPhoto = false;
            photoAction.setEnabled(true);
            hint.setText("拍照未成功，请重试。");
        }
    }

    private void analyzePhoto(Bitmap bitmap, int current) {
        setMode(Mode.PHOTO);
        meshAction.setVisibility(View.GONE);
        photoView.setPhoto(bitmap, null, null);
        renderResult(null);
        hint.setText("正在计算面部关键点…");
        detector.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(main, task -> {
            if (!isCurrent(current)) {
                return;
            }
            if (task.isSuccessful()) {
                showDetection(task.getResult(), bitmap);
            } else {
                hint.setText("人脸分析未完成，请尝试另一张照片。");
            }
        });
    }

    private void showDetection(List<FaceMesh> faces, Bitmap bitmap) {
        if (faces.size() != 1) {
            renderResult(null);
            hint.setText(faces.isEmpty() ? "未检测到人脸，请使用清晰的正面图像。"
                    : "检测到多张人脸，请让画面只保留一张脸。");
            return;
        }
        FaceMesh face = faces.get(0);
        Geometry.Point[] points = new Geometry.Point[Geometry.LANDMARK_COUNT];
        for (FaceMeshPoint landmark : face.getAllPoints()) {
            int index = landmark.getIndex();
            if (index >= 0 && index < points.length) {
                points[index] = new Geometry.Point(landmark.getPosition().getX(),
                        landmark.getPosition().getY());
            }
        }
        Rect bounds = face.getBoundingBox();
        try {
            Geometry.Features measured = Geometry.measure(points);
            renderResult(measured);
            if (bitmap != null) {
                photoView.setPhoto(bitmap, points, bounds);
                meshAction.setVisibility(View.VISIBLE);
            }
            hint.setText("检测到 1 张人脸 · 468 个关键点");
        } catch (IllegalArgumentException error) {
            renderResult(null);
            hint.setText(error.getMessage());
        }
    }

    private boolean isCurrent(int current) {
        return !destroyed && current == operation;
    }

    private void stopCamera() {
        operation++;
        takingPhoto = false;
        if (analysis != null) {
            analysis.clearAnalyzer();
            analysis = null;
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        capture = null;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putInt("profile", selected.ordinal());
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopCamera();
        detector.close();
        imageWorker.shutdown();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private void place(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(FILL, WRAP);
        params.topMargin = dp(margin);
        parent.addView(view, params);
    }

    private void addButton(LinearLayout parent, View view, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, WRAP, 1);
        params.leftMargin = dp(margin);
        parent.addView(view, params);
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private TextView action(String text, int fill, int ink) {
        TextView view = label(text, 13, ink, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8), dp(3), dp(8), dp(3));
        view.setMinHeight(dp(47));
        view.setFocusable(true);
        view.setBackground(buttonBackground(fill));
        return view;
    }

    private RippleDrawable buttonBackground(int color) {
        return new RippleDrawable(ColorStateList.valueOf(0x307d9e8d), round(color, 13), null);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
}
