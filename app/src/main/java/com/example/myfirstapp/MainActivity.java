package com.example.myfirstapp; // 确保这是你的包名

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.Image;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 101;
    private final String[] REQUIRED_PERMISSIONS = new String[]{Manifest.permission.CAMERA};

    private PreviewView previewView;
    private TextView scanningInfoText;
    private ExecutorService cameraExecutor;
    private BarcodeScanner barcodeScanner;
    private boolean isScanningPaused = false; // 标志位，防止重复处理同一个码

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        scanningInfoText = findViewById(R.id.scanningInfoText);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // 配置条码扫描器，只识别二维码
        BarcodeScannerOptions options =
                new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build();
        barcodeScanner = BarcodeScanning.getClient(options);

        // 检查相机权限
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS);
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "相机权限被拒绝，无法使用扫码功能。", Toast.LENGTH_LONG).show();
                finish(); // 如果没有权限，可以关闭应用或提示用户去设置
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreviewAndAnalysis(cameraProvider);
                // 相机启动后显示提示文本
                runOnUiThread(() -> scanningInfoText.setVisibility(View.VISIBLE));
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "获取 CameraProvider 失败: ", e);
                Toast.makeText(this, "无法启动相机", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreviewAndAnalysis(@NonNull ProcessCameraProvider cameraProvider) {
        // 预览用例
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // 相机选择器，选择后置摄像头
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        // 图像分析用例
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(1280, 720)) // 根据需要设置分辨率
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 只处理最新的图像帧
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
            @SuppressLint("UnsafeOptInUsageError")
            @Override
            public void analyze(@NonNull ImageProxy imageProxy) {
                if (isScanningPaused) { // 如果已暂停扫描（例如已显示结果对话框）
                    imageProxy.close();
                    return;
                }

                Image mediaImage = imageProxy.getImage();
                if (mediaImage != null) {
                    InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                    barcodeScanner.process(image)
                            .addOnSuccessListener(barcodes -> {
                                if (!isScanningPaused && !barcodes.isEmpty()) {
                                    isScanningPaused = true; // 标记为已扫描到，暂停进一步扫描
                                    Barcode barcode = barcodes.get(0); // 获取第一个码
                                    String rawValue = barcode.getRawValue();
                                    Log.d(TAG, "二维码内容: " + rawValue);

                                    // 在主线程显示结果对话框
                                    runOnUiThread(() -> showResultDialog(rawValue));
                                }
                            })
                            .addOnFailureListener(e -> Log.e(TAG, "条码扫描失败", e))
                            .addOnCompleteListener(task -> imageProxy.close()); // 确保ImageProxy被关闭
                } else {
                    imageProxy.close(); // 如果mediaImage为null，也关闭
                }
            }
        });

        // 在绑定用例前，先解绑所有用例
        cameraProvider.unbindAll();

        try {
            // 绑定用例到相机
            cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageAnalysis);
        } catch (Exception e) {
            Log.e(TAG, "用例绑定失败", e);
            Toast.makeText(this, "无法绑定相机用例", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isValidUrl(String url) {
        try {
            new java.net.URL(url);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showResultDialog(String qrCodeContent) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("扫描结果");

        // 创建自定义样式的TextView
        TextView messageView = new TextView(this);
        messageView.setText("二维码内容:\n" + qrCodeContent);
        messageView.setTextSize(20); // 设置文字大小为20sp
        messageView.setPadding(50, 30, 50, 30); // 设置内边距
        builder.setView(messageView);

        // 设置按钮文字大小
        builder.setPositiveButton("复制", (dialog, which) -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("QR Code Content", qrCodeContent);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            resumeScanningAfterDialog(dialog);
        });

        // 检查是否是有效的URL
        if (isValidUrl(qrCodeContent)) {
            builder.setNegativeButton("直达", (dialog, which) -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(qrCodeContent));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show();
                }
                resumeScanningAfterDialog(dialog);
            });
        } else {
            builder.setNegativeButton("关闭", (dialog, which) -> {
                resumeScanningAfterDialog(dialog);
            });
        }

        builder.setCancelable(false);

        AlertDialog dialog = builder.create();
        dialog.show();

        // 设置按钮样式
        Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);

        if (positiveButton != null) {
            positiveButton.setTextSize(18);
            positiveButton.setTextColor(getResources().getColor(R.color.button_text));
            positiveButton.setBackgroundColor(getResources().getColor(R.color.primary_blue_light));
            positiveButton.setPadding(40, 20, 40, 20);
            positiveButton.setAllCaps(false);
        }

        if (negativeButton != null) {
            negativeButton.setTextSize(18);
            negativeButton.setTextColor(getResources().getColor(R.color.button_text));
            negativeButton.setBackgroundColor(getResources().getColor(R.color.primary_green_light));
            negativeButton.setPadding(40, 20, 40, 20);
            negativeButton.setAllCaps(false);
        }
    }

    // 在对话框关闭后恢复扫描
    private void resumeScanningAfterDialog(DialogInterface dialog) {
        dialog.dismiss();
        // 延迟一小段时间再恢复扫描，给用户反应时间，避免立即又扫到同一个码
        previewView.postDelayed(() -> isScanningPaused = false, 500);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (barcodeScanner != null) {
            barcodeScanner.close(); // 释放ML Kit扫描器资源
        }
    }
}