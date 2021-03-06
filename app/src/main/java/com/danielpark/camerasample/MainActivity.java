package com.danielpark.camerasample;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import com.danielpark.camera.CameraApiChecker;
import com.danielpark.camera.listeners.OnTakePictureListener;
import com.danielpark.camera.util.AutoFitTextureView;
import com.danielpark.camera.util.CameraLogger;
import com.danielpark.camerasample.databinding.ActivityMainBinding;

import net.danielpark.library.dialog.DialogInput;
import net.danielpark.library.dialog.DialogUtil;
import net.danielpark.library.log.Logger;
import net.danielpark.library.util.PermissionChecker;

import java.io.File;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements View.OnClickListener, OnTakePictureListener, PermissionChecker.OnPermissionCheckerListener {

    private AutoFitTextureView cameraPreview;
    private PermissionChecker permissionChecker;

    private ActivityMainBinding binding;

    private Timer timer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main);

        permissionChecker = new PermissionChecker(this);
        permissionChecker
                .withPermissions(
                    new String[]{
                            Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    })
                .withListener(this)
                .check();
    }

    private void onProceedStep() {

        binding.autoFocusBtn.setOnClickListener(this);
        binding.takePictureBtn.setOnClickListener(this);
        binding.flashBtn.setOnClickListener(this);
        binding.settingBtn.setOnClickListener(this);

        // Daniel (2016-08-23 10:45:00): Turn on CameraLogger Log switch
        CameraLogger.enable();

        try {
            cameraPreview =  CameraApiChecker.getInstance()
                    .setOrientation(CameraApiChecker.CameraOrientation.Landscape)
                    .setCameraType(CameraApiChecker.CameraType.CAMERA_FACING_FRONT)
                    .build(this);
            binding.container.addView(cameraPreview);

            /**
             * Daniel (2016-11-05 18:42:58): It is required to listen taking a picture event, and auto-focus event
             */
            cameraPreview.setOnTakePictureListener(this);

            cameraPreview.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {

                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            cameraPreview.autoFocus();
                            return true;
                    }
                    return false;
                }
            });

        } catch (UnsupportedOperationException | IOException e){
            Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_SHORT).show();

            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        try {
            if (cameraPreview != null)
                cameraPreview.openCamera(cameraPreview.getSurfaceTexture(), cameraPreview.getWidth(), cameraPreview.getHeight());
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (timer == null) {
            timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    if (cameraPreview == null) return;

                    Bitmap bitmap = cameraPreview.getThumbnail(0.7f);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (bitmap != null)
                                binding.thumbnail.setImageBitmap(bitmap);
                        }
                    });

                }
            }, 5000, 10);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (cameraPreview != null)
            cameraPreview.releaseCamera();

        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        permissionChecker.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    public void onPermissionCheckerResult(@NonNull PermissionChecker.PermissionState permissionState) {
        switch (permissionState) {
            case Granted:
                onProceedStep();
                break;
            case Denied:
                finish();
                break;
            case Farewell:
                DialogUtil.showDefaultDialog(this,
                        new DialogInput.Builder()
                                .setCancelable(false)
                                .setMessage("We need those permissions!")
                                .setMiddleButtonText(getString(android.R.string.ok))
                                .setClickListener((dialog, which) -> {
                                    // Direct user to setting page
                                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.fromParts("package", getPackageName(), null));
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                                    startActivity(intent);

                                    dialog.dismiss();

                                    finish();
                                })
                                .build());
                break;
        }
    }

    @Override
    public void onClick(View view) {
        final int id = view.getId();

        switch (id){
            case R.id.autoFocusBtn:
                cameraPreview.autoFocus();
                break;
            case R.id.takePictureBtn:
                cameraPreview.takePicture();
                break;
            case R.id.flashBtn:
                cameraPreview.flashToggle();
                break;
            case R.id.settingBtn:
                break;
        }
    }

    @Override
    protected void onDestroy() {

        if (cameraPreview != null)
            cameraPreview.finishCamera();

        if (timer != null) {
            timer.cancel();
            timer = null;
        }

        super.onDestroy();
    }

    @Override
    public void onTakePicture(@NonNull File file) {
        Toast.makeText(this, file.getAbsolutePath(), Toast.LENGTH_SHORT).show();

        try {
            if (binding.imageView != null)
                binding.imageView.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onLensFocused(boolean isFocused) {
        Toast.makeText(this, "Lens focused : " + isFocused, Toast.LENGTH_SHORT).show();
    }


}
