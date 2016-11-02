package com.danielpark.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import com.danielpark.camera.listeners.OnTakePictureListener;
import com.danielpark.camera.util.AutoFitTextureView;
import com.danielpark.camera.util.DeviceUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Camera2 API preview
 * <br><br>
 * Copyright (C) 2014-2016 daniel@bapul.net
 * Created by Daniel on 2016-08-26.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Preview extends AutoFitTextureView {

    private CameraDevice mCameraDevice;
    private CameraManager mCameraManager;
    private String mCameraId; // ID of the current CameraDevice
    private Size mPreviewSize;  // Size of camera preview
    private int mSensorOrientation;

    /** A {@link CameraCaptureSession } for camera preview */
    private CameraCaptureSession mCameraCaptureSession;

    private Size[] mSupportedPreviewSize;

    /** A {@link Handler} for running tasks in the background */
    private Handler mBackgroundHandler;
    /** An additional thread for running tasks that shouldn't block the UI */
    private HandlerThread mBackgroundThread;
    /** {@link CaptureRequest.Builder} for the camera preview */
    private CaptureRequest.Builder mPreviewRequestBuilder;
    /** An {@link ImageReader} that handles still image capture */
    private ImageReader mImageReader;
    /** An output file for our picture */
    private File mTakePictureFile = getOutputMediaFile();
    /** A {@link Semaphore} to prevent the app from exiting before closing the camera. */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    private OnTakePictureListener onTakePictureListener;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    public Camera2Preview(Context context) {
        super(context);

        setSurfaceTextureListener(mSurfaceTextureListener);
    }

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            try {
                openCamera(surfaceTexture, width, height);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            configureTransform(surfaceTexture, width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            releaseCamera();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice camera) {
            // This method is called when the camera is opened. We start camera preview here
            mCameraOpenCloseLock.release();
            mCameraDevice = camera;
            // TODO: startPreview
            try {
                startPreview();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
        }
    };

    /**
     * To tell if {@link ImageSaver} finished storing Image byte[] to file
     */
    Handler mOnImageFinishedHandler = new Handler(){

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

            if (msg != null)
                LOG.d("handleMessage : " + msg.toString());

            if (onTakePictureListener != null && mTakePictureFile != null)
                onTakePictureListener.onTakePicture(mTakePictureFile);
        }
    };

    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), mTakePictureFile, mOnImageFinishedHandler));
        }
    };

    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {
//            process(partialResult, false);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
//            process(result, true);
        }

    };

    @Override
    public void openCamera(SurfaceTexture surfaceTexture, int width, int height) throws CameraAccessException, SecurityException {
//        super.openCamera(surfaceTexture, width, height);
        LOG.d("openCamera() : " + width + " , " + height);

        // Daniel (2016-10-25 23:32:40): Start handler thread
        startBackgroundThread();

        if (mCameraManager == null)
            mCameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);

        setUpCameraOutput(width, height, mCameraManager);
        configureTransform(surfaceTexture, width, height);

        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            mCameraManager.openCamera(mCameraId, mStateCallback, mBackgroundHandler);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Check if current device gets correct orientation compares to preview size
     * @return
     */
    private boolean isCorrectOrientation() {
        // Daniel (2016-08-26 12:17:06): Get the largest supported preview size
        Size largestPreviewSize = Collections.max(
                Arrays.asList(mSupportedPreviewSize),
                new CompareSizesByArea());

        // Daniel (2016-08-26 12:17:33): Get current device configuration
        int orientation = getResources().getConfiguration().orientation;

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            int screenWidth = DeviceUtil.getResolutionWidth(getContext());
            int screenHeight = DeviceUtil.getResolutionHeight(getContext());

            if ((screenWidth > screenHeight && largestPreviewSize.getWidth() < largestPreviewSize.getHeight())
                    || (screenWidth < screenHeight && largestPreviewSize.getWidth() > largestPreviewSize.getHeight()))
                return false;
            else
                return true;


        } else if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            int screenWidth = DeviceUtil.getResolutionWidth(getContext());
            int screenHeight = DeviceUtil.getResolutionHeight(getContext());

            if ((screenWidth > screenHeight && largestPreviewSize.getWidth() < largestPreviewSize.getHeight())
                    || (screenWidth < screenHeight && largestPreviewSize.getWidth() > largestPreviewSize.getHeight()))
                return false;
            else
                return true;
        }
        return true;
    }

    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private void setUpCameraOutput(int width, int height, CameraManager cameraManager) throws CameraAccessException, NullPointerException {
        LOG.d("setupCameraOutput() : " + width + " , " + height);

        // TODO: For now, only rear Camera is supported
        for (String cameraId : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

            // We don't use a front camera for now
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT)
                continue;

            // We don't use an external camera in this class
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL)
                continue;

            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                continue;
            }

            // TODO: We do use a back camera for now
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {

                mSupportedPreviewSize = map.getOutputSizes(ImageFormat.JPEG);

                // 1. Get the largest supported preview size
                Size largestPreviewSize = Collections.max(
                        Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)),
                        new CompareSizesByArea());

                LOG.d("1. Largest preview size : " + largestPreviewSize.getWidth() + " , " + largestPreviewSize.getHeight());

                // 2. Get the largest supported picture size
                mImageReader = ImageReader.newInstance(largestPreviewSize.getWidth(), largestPreviewSize.getHeight(),
                        ImageFormat.JPEG, /*maxImages*/2);
                mImageReader.setOnImageAvailableListener(
                        mOnImageAvailableListener, mBackgroundHandler);

                // 3. Get Camera orientation to fix rotation problem
                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                // https://developer.android.com/reference/android/hardware/Camera.CameraInfo.html
                /**
                 * The orientation of the camera image. The value is the angle that the camera image needs to be rotated clockwise so it shows correctly on the display in its natural orientation.
                 * It should be 0, 90, 180, or 270.
                 */
                mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                LOG.d("3. Camera Lens orientation : " + mSensorOrientation);

                // 4. Get current display rotation
                WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
                int displayRotation = windowManager.getDefaultDisplay().getRotation();
                LOG.d("4. Current device rotation : " + ORIENTATIONS.get(displayRotation));

                // 5. Check if dimensions should be swapped
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                }
                LOG.d("5. is Dimension swapped? : " + swappedDimensions);

                // 6. Get device resolution max size
                Point resolutionSize = new Point();
                windowManager.getDefaultDisplay().getSize(resolutionSize);
                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = resolutionSize.x;
                int maxPreviewHeight = resolutionSize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewWidth = width;
                    maxPreviewWidth = resolutionSize.y;
                    maxPreviewHeight = resolutionSize.x;
                }
                LOG.d("6. Resolution Size : " + resolutionSize.x + " , " + resolutionSize.y);

                // 7. choose Optimal preview size!
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth, maxPreviewHeight,
                        largestPreviewSize);
                LOG.d("7. Optimal Preview size : " + mPreviewSize.getWidth() + " , " + mPreviewSize.getHeight());

                // 8. choose Optimal Picture size!

                // 9. According to orientation, change SurfaceView size
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                    LOG.d("9. Orientation PORTRAIT");
//            setAspectRatio(
//                    mPreviewSize.height, mPreviewSize.width);
                } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    LOG.d("9. Orientation LANDSCAPE");
//            setAspectRatio(
//                    mPreviewSize.width, mPreviewSize.height);
                }

                // Save camera id
                mCameraId = cameraId;
            }
        }
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(SurfaceTexture surfaceTexture, int viewWidth, int viewHeight) {
        if (null == mPreviewSize)
            return;

        LOG.d("configureTransform () : " + viewWidth + " , " + viewHeight);

        // If your preview can change or rotate, take care of those events here.
        // Make sure to stop the preview before resizing or reformatting it.
        if (surfaceTexture == null) {
            // preview surface does not exist
            return;
        }

        // Set preview size and make any resize, rotate or
        // reformatting changes here
        // and start preview with new settings
        try {
            if (null == mPreviewSize ) {
                return;
            }
            WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            int rotation = windowManager.getDefaultDisplay().getRotation();
            Matrix matrix = new Matrix();
            RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
            RectF bufferRect = new RectF(0, 0, mPreviewSize.getWidth(), mPreviewSize.getHeight());
            float centerX = viewRect.centerX();
            float centerY = viewRect.centerY();
            LOG.d("rotation : " + ORIENTATIONS.get(rotation));
            LOG.d("Correct Orientation : " + isCorrectOrientation());
            LOG.d("Sensor orientation : " + mSensorOrientation);

            if (isCorrectOrientation()) {
                if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
                    // Daniel (2016-08-26 17:34:05): Reverse dst size
                    bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
                    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                    float scale = Math.max(
                            (float) viewHeight / mPreviewSize.getHeight(),
                            (float) viewWidth / mPreviewSize.getWidth());
                    matrix.postScale(scale, scale, centerX, centerY);
                    matrix.postRotate(90 * (rotation - 2), centerX, centerY);
                } else if (Surface.ROTATION_0 == rotation || Surface.ROTATION_180 == rotation) {
                    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                    float scale = Math.max(
                            (float) viewHeight / mPreviewSize.getHeight(),
                            (float) viewWidth / mPreviewSize.getWidth());
                    matrix.postScale(scale, scale, centerX, centerY);

                    if (Surface.ROTATION_180 == rotation)
                        matrix.postRotate(180, centerX, centerY);
                }

            } else {
                if (Surface.ROTATION_0 == rotation || Surface.ROTATION_180 == rotation) {
                    // Daniel (2016-08-26 17:34:05): Reverse dst size
                    bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
                    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                    float scale = Math.max(
                            (float) viewHeight / mPreviewSize.getHeight(),
                            (float) viewWidth / mPreviewSize.getWidth());
                    matrix.postScale(scale, scale, centerX, centerY);

                    // Sensor orientation is 90 for most devices, or 270 for some devices (eg. Nexus 5X)
                    // We have to take that into account and rotate JPEG properly.
                    // For devices with orientation of 90, we simply return our mapping from ORIENTATIONS.
                    // (In this case, add 90)
                    // For devices with orientation of 270, we need to rotate the JPEG 180 degrees.
                    // (In this case, add -90)
                    if (Surface.ROTATION_0 == rotation)
                        matrix.postRotate(-mSensorOrientation + (mSensorOrientation == 90 ? 90 : -90), centerX, centerY);
                    else
                        matrix.postRotate(-mSensorOrientation, centerX, centerY);
                } else if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
                    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
                    float scale = Math.max(
                            (float) viewHeight / mPreviewSize.getHeight(),
                            (float) viewWidth / mPreviewSize.getWidth());
                    matrix.postScale(scale, scale, centerX, centerY);

                    if (Surface.ROTATION_90 == rotation)
                        matrix.postRotate(270, centerX, centerY);
                    if (Surface.ROTATION_270 == rotation)
                        matrix.postRotate(90, centerX, centerY);
                }
            }
            setTransform(matrix);

            // 10. Set preview size
//            Camera.Parameters mParameters = mCamera.getParameters();
//            mParameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
//            LOG.d("10. Set preview size : " + mPreviewSize.width + " , " + mPreviewSize.height);
//
//            // 11. Set Picture size & format
//            mParameters.setPictureSize(mPictureSize.width, mPictureSize.height);
////            mParameters.setPictureFormat(PixelFormat.JPEG);
//            LOG.d("11. Set Picture size : " + mPictureSize.width + " , " + mPictureSize.height);
//
//            mCamera.setParameters(mParameters);
//
//            mCamera.setPreviewTexture(surfaceTexture);
//            mCamera.startPreview();
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    private void startPreview() throws CameraAccessException {
        SurfaceTexture texture = getSurfaceTexture();
        assert texture != null;

        texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

        Surface surface = new Surface(texture);

        mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        mPreviewRequestBuilder.addTarget(surface);

        try {
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback(){

                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            try {
                                // The camera is already closed
                                if (mCameraDevice == null) return;

                                // When the session is ready, we start displaying the preivew
                                mCameraCaptureSession = session;

//                                session.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
                                mCameraCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {

                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            return choices[0];
        }
    }

    @Override
    public void autoFocus() {
        super.autoFocus();
    }

    @Override
    public void takePicture() {
        super.takePicture();
        try {
            if (mCameraDevice != null) {
                // This is the CaptureRequest.Builder that we use to take a picture.
                final CaptureRequest.Builder captureBuilder =
                        mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                captureBuilder.addTarget(mImageReader.getSurface());
                captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

                // Daniel (2016-08-26 14:01:20): Current Device rotation
                WindowManager windowManager = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
                int displayRotation = windowManager.getDefaultDisplay().getRotation();
                LOG.d("Current device rotation : " + ORIENTATIONS.get(displayRotation));

                int result = (mSensorOrientation - ORIENTATIONS.get(displayRotation) + 360) % 360;
                captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, result);

                final CameraCaptureSession.CaptureCallback CaptureCallback
                        = new CameraCaptureSession.CaptureCallback() {

                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                   @NonNull CaptureRequest request,
                                                   @NonNull TotalCaptureResult result) {

                        LOG.d("File path : " + mTakePictureFile.getAbsolutePath());
                    }
                };

//                mCameraCaptureSession.stopRepeating();
                mCameraCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
            }
        } catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    @Override
    public void flashTorch() {
        super.flashTorch();
    }

    @Override
    public void setOnTakePictureListener(OnTakePictureListener onTakePictureListener) {
        this.onTakePictureListener = onTakePictureListener;
    }

    /**
     * You must call this method to release Camera
     */
    @Override
    public void releaseCamera() {
//        super.releaseCamera();
        LOG.d("release camera");

        try {
            closeCamera();
            stopBackgroundThread();
        } catch (Exception e){
            e.printStackTrace();
        }

    }

    @Override
    public void finishCamera() {
        super.finishCamera();

        try {
            mOnImageFinishedHandler = null;
            closeCamera();
            stopBackgroundThread();
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Close camera
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCameraCaptureSession) {
                mCameraCaptureSession.close();
                mCameraCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            if (mCameraOpenCloseLock != null)
                mCameraOpenCloseLock.release();
        }
    }

    /**
     * Saves a JPEG {@link Image} into the specified {@link File}.
     */
    private static class ImageSaver implements Runnable {

        /**
         * The JPEG image
         */
        private final Image mImage;
        /**
         * The file we save the image into.
         */
        private final File mFile;

        private final Handler mHandler;

        public ImageSaver(Image image, File file, Handler handler) {
            mImage = image;
            mFile = file;
            mHandler = handler;
        }

        // Daniel (2016-04-29 18:15:01): Try to save image byte to file
        @Override
        public void run() {
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try {
                output = new FileOutputStream(mFile);
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();
                if (null != output) {
                    try {
                        output.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                // Daniel (2016-11-02 12:09:01): When FileOutputStream process has finished, report result to handler
                mHandler.sendEmptyMessage(101020);
            }
        }
    }

    private static File getOutputMediaFile() {
        File mediaStorageDir = new File(
                Environment
                        .getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "CameraLibrary");
        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                Log.e("CameraLogger", "failed to create directory");
                return null;
            }
        }
        // Create a media file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss")
                .format(new Date());
        File mediaFile;
        mediaFile = new File(mediaStorageDir.getPath() + File.separator
                + "CameraLibrary.jpg");

        return mediaFile;
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    private static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }
}
